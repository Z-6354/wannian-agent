# Reads TYPESAFE_API_KEY from the environment. Never prints the key.
param(
    [string]$Case
)

$ErrorActionPreference = 'Stop'

$key = [System.Environment]::GetEnvironmentVariable('TYPESAFE_API_KEY', 'Process')
if ([string]::IsNullOrWhiteSpace($key)) {
    $key = [System.Environment]::GetEnvironmentVariable('TYPESAFE_API_KEY', 'User')
}
if ([string]::IsNullOrWhiteSpace($key)) {
    Write-Error 'TYPESAFE_API_KEY is not set. Set the user environment variable, then open a new terminal.'
    exit 1
}

$curl = Join-Path $env:SystemRoot 'System32\curl.exe'
if (-not (Test-Path -LiteralPath $curl)) {
    $curl = 'curl.exe'
}

$caseDir = Join-Path $PSScriptRoot 'cases'
$files = Get-ChildItem -LiteralPath $caseDir -Filter '*.json' | Sort-Object Name
if (-not [string]::IsNullOrWhiteSpace($Case)) {
    $files = @($files | Where-Object { $_.BaseName -like "*$Case*" })
}
if ($files.Count -eq 0) {
    Write-Error "No case matched '$Case' under $caseDir"
    exit 1
}

function Format-Answer($name, $answer) {
    switch ($answer.type) {
        'choice' {
            $probs = ($answer.probabilities.PSObject.Properties | ForEach-Object {
                '{0}={1:N2}' -f $_.Name, [double]$_.Value
            }) -join ', '
            return ('{0}: choice={1} confidence={2:N2} [{3}]' -f $name, $answer.choice, [double]$answer.confidence, $probs)
        }
        'score' {
            $legend = $answer.legend.PSObject.Properties | Sort-Object { [int]$_.Name }
            $nearest = $legend | Sort-Object { [math]::Abs([int]$_.Name - [double]$answer.score) } | Select-Object -First 1
            return ('{0}: score={1:N2} nearest="{2}" confidence={3:N2}' -f $name, [double]$answer.score, $nearest.Value, [double]$answer.confidence)
        }
        'noul' {
            return ('{0}: noul={1:N2}' -f $name, [double]$answer.noul)
        }
        default {
            return ('{0}: type={1}' -f $name, $answer.type)
        }
    }
}

$failed = $false
foreach ($file in $files) {
    $tmp = New-TemporaryFile
    try {
        $code = & $curl -sS -o $tmp.FullName -w '%{http_code}' -X POST 'https://api.typesafe.ai/v1/systemone' `
            -H "Authorization: Bearer $key" `
            -H 'Content-Type: application/json' `
            --data-binary "@$($file.FullName)"
        Write-Output ('=== ' + $file.Name + ' status=' + $code + ' ===')
        $raw = Get-Content -LiteralPath $tmp.FullName -Raw -Encoding UTF8
        if ($code -ne '200') {
            Write-Output $raw
            $failed = $true
            continue
        }
        $parsed = $raw | ConvertFrom-Json
        Write-Output ('model=' + $parsed.model)
        foreach ($prop in $parsed.answers.PSObject.Properties) {
            Write-Output (Format-Answer $prop.Name $prop.Value)
        }
    }
    finally {
        Remove-Item -LiteralPath $tmp.FullName -Force -ErrorAction SilentlyContinue
    }
}

if ($failed) {
    exit 1
}
