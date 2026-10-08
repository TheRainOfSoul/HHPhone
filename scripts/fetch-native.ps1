# Скачивает нативные библиотеки baresip (ассет релиза native-<ver>) в distribution.video\.
# usage: powershell -File scripts\fetch-native.ps1 [-Version 82.1.0]
param([string]$Version = '82.1.0')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dest = Join-Path $root 'distribution.video'
if (Test-Path (Join-Path $dest 'baresip\lib\arm64-v8a\libbaresip.a')) { Write-Host "distribution.video уже есть"; exit 0 }
$tmp = Join-Path $env:TEMP "hhphone-native-$Version"
New-Item -ItemType Directory -Force $tmp | Out-Null
gh release download "native-$Version" --repo TheRainOfSoul/HHPhone --pattern "distribution.video-$Version.tar.gz" --dir $tmp --clobber
if ($LASTEXITCODE -ne 0) { throw "gh release download завершился с кодом $LASTEXITCODE" }
& "$env:SystemRoot\System32\tar.exe" -xzf (Join-Path $tmp "distribution.video-$Version.tar.gz") -C $root
if ($LASTEXITCODE -ne 0) { throw "tar завершился с кодом $LASTEXITCODE" }
Write-Host "Готово: $dest"
