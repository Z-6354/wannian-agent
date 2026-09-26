#Requires -Version 5.1
<#
.SYNOPSIS
  本地快速启动 wn-server（真人测 / live）。

.DESCRIPTION
  默认：有 boot jar 则直接 java -jar（约数秒～二十秒进 live）。
  -Rebuild：先 package -DskipTests 再起（改了 Java 代码时用）。
  人物导入 / prompt-review / PersonaPromptReviewService 等 app 代码改动后，必须 -Rebuild；
  复用旧 jar 会导致 restage/approve 仍跑旧合并逻辑（见 persona-dialogue-wiring-fix O4）。
  /ui/ 样式直接从 wannian-ui 源目录读取；改 CSS 无需重建或重启，刷新页面即可。
  /chat/ /shell/ /manage/ 静态脚本同样优先读 wn-server 源码目录；改 JS 刷新即可，不必 -Rebuild。
  仅本脚本的本地启动启用源码直读，普通 jar 启动仍使用包内资源。
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
$settings = "D:\0HAN\HANAGENT\.mvn\settings.xml"
$mvnCandidates = @(
    $(if ($env:MAVEN_HOME) { Join-Path $env:MAVEN_HOME "bin\mvn.cmd" }),
    "C:\Apache\apache-maven-3.9.9\bin\mvn.cmd"
)
$mvn = $null
foreach ($c in $mvnCandidates) {
    if ($c -and (Test-Path $c)) { $mvn = $c; break }
}
if (-not $mvn) {
    $cmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($cmd) { $mvn = $cmd.Source }
}
if (-not $mvn) { throw "找不到 mvn。请先运行 HANAGENT\\scripts\\install-maven-c.ps1 或把 Maven 装到 C:\\Apache\\apache-maven-3.9.9" }
# scripts/ → wannian-agent/
$wnRoot = Split-Path $PSScriptRoot -Parent
$serverRoot = Join-Path $wnRoot "wn-server"
$jar = Join-Path $serverRoot "app\target\wn-server-app-0.1.0-SNAPSHOT.jar"
$dataDir = Join-Path $serverRoot "data"
$uiResourceRoot = Join-Path $wnRoot "wannian-ui\src\main\resources\META-INF\resources"
$appResourceRoot = Join-Path $serverRoot "app\src\main\resources\META-INF\resources"
$uiLocation = "file:///$(($uiResourceRoot -replace '\\', '/'))/"
$appLocation = "file:///$(($appResourceRoot -replace '\\', '/'))/"
# 源码优先于 jar，本地改 chat/shell JS 立即生效
$staticLocations = "$uiLocation,$appLocation,classpath:/META-INF/resources/,classpath:/resources/,classpath:/static/,classpath:/public/"

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

    $secretsDir = Join-Path $dataDir "secrets"
    $hasSecretFile = (Test-Path $secretsDir) -and @(Get-ChildItem $secretsDir -Filter "*.key" -ErrorAction SilentlyContinue).Count -gt 0
    if (-not $env:DEEPSEEK_API_KEY -and -not $env:WANNIAN_AI_API_KEY -and -not $env:ZHIPU_API_KEY -and -not $hasSecretFile) {
        Write-Host "警告: 未找到密钥文件（data/secrets/*.key）且未设置供应商环境变量；live 对话可能失败。"
    }

    Write-Host "==> java -jar  (data=$dataDir, port=$Port, mode=live)"
    Write-Host "    样式源码直读: $uiResourceRoot（保存 CSS 后刷新页面）"
    Write-Host "    页面/脚本源码直读: $appResourceRoot（保存 JS 后刷新页面）"
    Write-Host "    工作台: http://127.0.0.1:$Port/#chat"
    Write-Host "    管理入口: http://127.0.0.1:$Port/manage/  （会转到 /#vendors）"
    Write-Host "    探活: http://127.0.0.1:$Port/internal/live"
    $env:WANNIAN_MODEL_MODE = "live"
    & java "-Dserver.port=$Port" "-Dwannian.data-dir=$dataDir" `
        "-Dspring.web.resources.static-locations=$staticLocations" `
        "-Dspring.web.resources.cache.period=0" `
        "-Dspring.web.resources.chain.cache=false" -jar $jar
} finally {
    Pop-Location
}
