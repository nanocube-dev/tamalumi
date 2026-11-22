# Baut Lumi und installiert es auf dem verbundenen Handy (Daten und Modelle bleiben erhalten: install -r).
#   .\tools\install.ps1                 bauen + installieren
#   .\tools\install.ps1 -Serial XYZ     bestimmtes Gerät
param([string]$Serial = "")

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not $env:JAVA_HOME -and (Test-Path "J:\Android\jdk-17")) { $env:JAVA_HOME = "J:\Android\jdk-17" }

Push-Location $root
try {
    & .\gradlew.bat :app:assembleDebug --no-daemon -q
    if ($LASTEXITCODE -ne 0) { throw "Build fehlgeschlagen" }
} finally { Pop-Location }

$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "J:\Android\Sdk")) {
        if ($sdk -and (Test-Path "$sdk\platform-tools\adb.exe")) { $adb = "$sdk\platform-tools\adb.exe"; break }
    }
}
$a = @()
if ($Serial) { $a = @("-s", $Serial) }
& $adb @a install -r -g $apk
& $adb @a shell am start -n de.tamalumi.app/com.lou.lumi.ui.MainActivity
