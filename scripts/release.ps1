# Выпуск HHPhone: сборка, проверка подписи, HHPhone.apk + latest.json (постоянные имена — их ищет автообновление).
# usage: powershell -File scripts\release.ps1 -Notes "что нового" [-Prerelease]
param([Parameter(Mandatory = $true)][string]$Notes, [switch]$Prerelease)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$gradle = Get-Content (Join-Path $root 'app\build.gradle.kts') -Raw
$m = [regex]::Match($gradle, 'create\("hh"\)[\s\S]*?versionCode = (\d+)[\s\S]*?versionName = "([^"]+)"')
if (-not $m.Success) { throw 'не найдены versionCode/versionName flavor hh' }
$code = [int]$m.Groups[1].Value; $name = $m.Groups[2].Value
if (git status --porcelain --untracked-files=no) { throw 'есть незакоммиченные изменения — выпуск только из коммита' }
& .\gradlew.bat testHhDebugUnitTest assembleHhRelease
if ($LASTEXITCODE -ne 0) { throw "сборка завершилась с кодом $LASTEXITCODE" }
$apk = Join-Path $root 'app\build\outputs\apk\hh\release\app-hh-release.apk'
$bt = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools') | Sort-Object Name | Select-Object -Last 1
$cert = & (Join-Path $bt.FullName 'apksigner.bat') verify --print-certs $apk
if ($LASTEXITCODE -ne 0 -or -not ($cert -match 'CN=HHPhone')) { throw 'APK не подписан ключом HHPhone' }
$out = Join-Path $env:TEMP "hhphone-release-$name"
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item $apk (Join-Path $out 'HHPhone.apk') -Force
$latest = [ordered]@{ versionCode = $code; versionName = $name; notes = $Notes } | ConvertTo-Json -Compress
[IO.File]::WriteAllText((Join-Path $out 'latest.json'), $latest, (New-Object Text.UTF8Encoding $false))
# PS 5.1: код возврата git не прерывает скрипт сам — проверяем; --verify-tag не даёт gh создать тег на другом коммите
git tag "v$name"
if ($LASTEXITCODE -ne 0) { throw "git tag v$name не создан (уже есть?)" }
git push origin "v$name"
if ($LASTEXITCODE -ne 0) { throw "git push тега v$name завершился с кодом $LASTEXITCODE" }
$ghArgs = @('release', 'create', "v$name", (Join-Path $out 'HHPhone.apk'), (Join-Path $out 'latest.json'), '--title', "HHPhone $name", '--notes', $Notes, '--verify-tag')
if ($Prerelease) { $ghArgs += '--prerelease' }
& gh @ghArgs
if ($LASTEXITCODE -ne 0) { throw "gh release завершился с кодом $LASTEXITCODE" }
Write-Host "Выпущено: HHPhone $name (versionCode $code)$(if ($Prerelease) { ' — пре-релиз, телефоны его не видят' })"
