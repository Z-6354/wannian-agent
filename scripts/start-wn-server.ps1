#Requires -Version 5.1
<#
.SYNOPSIS
  本地快速启动 wn-server（真人测 / live）。

.DESCRIPTION
  默认：有 boot jar 则直接 java -jar（约数秒～二十秒进 live）。
  -Rebuild：先 package -DskipTests 再起（改了代码时用）。
  避免 spring-boot:run（二次编译 + 远端 SNAPSHOT metadata，慢且易 classpath 不一致）。

.EXAMPLE
  .\scripts\start-wn-server.ps1
  .\scripts\start-wn-server.ps1 -Rebuild
#>
param(
    [switch]$Rebuild,
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$mvn = "D:\0HAN\HANAGENT\.tools\apache-maven-3.9.9\bin\mvn.cmd"
$settings = "D:\0HAN\HANAGENT\.mvn\settings.xml"
# scripts/ → wannian-agent/
$wnRoot = Split-Path $PSScriptRoot -Parent
$serverRoot = Join-Path $wnRoot "wn-server"
$jar = Join-Path $serverRoot "app\target\wn-server-app-0.1.0-SNAPSHOT.jar"
$dataDir = Join-Path $serverRoot "data"

if (-not (Test-Path (Join-Path $serverRoot "pom.xml"))) {
    throw "找不到 wn-server: $serverRoot"
}

$busy = netstat -ano | Select-String ":$Port\s+.*LISTENING"
if ($busy) {
    Write-Host "端口 $Port 已被占用。先停掉旧进程再启，或改 -Port。"
    Write-Host $busy
    exit 1
}

Push-Location $serverRoot
try {
    if ($Rebuild -or -not (Test-Path $jar)) {
        Write-Host "==> package -DskipTests（仅此时编译）"
        & $mvn -pl app -am package "-DskipTests" -o -s $settings
        if ($LASTEXITCODE -ne 0) {
            Write-Host "离线 package 失败，改在线重试…"
            & $mvn -pl app -am package "-DskipTests" -s $settings
        }
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } else {
        Write-Host "==> 复用已有 jar（改代码请加 -Rebuild）: $jar"
    }

    if (-not $env:DEEPSEEK_API_KEY) {
        Write-Host "警告: DEEPSEEK_API_KEY 未设置；live 对话会失败。"
    }

    Write-Host "==> java -jar  (data=$dataDir, port=$Port, mode=live)"
    Write-Host "    聊天: http://127.0.0.1:$Port/chat/"
    Write-Host "    管理: http://127.0.0.1:$Port/manage/"
    Write-Host "    探活: http://127.0.0.1:$Port/internal/live"
    $env:WANNIAN_MODEL_MODE = "live"
    & java "-Dserver.port=$Port" "-Dwannian.data-dir=$dataDir" -jar $jar
} finally {
    Pop-Location
}
