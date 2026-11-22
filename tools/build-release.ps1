# Baut das Play-Store-Bundle (.aab), signiert mit Lumis Upload-Schlüssel.
# Fragt das Keystore-Passwort ab und gibt es nur diesem einen Gradle-Lauf mit (nie in eine Datei).
#   .\tools\build-release.ps1          -> app\build\outputs\bundle\release\app-release.aab
#   .\tools\build-release.ps1 -Apk     zusätzlich eine signierte Release-APK zum Selbst-Installieren
param([switch]$Apk)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not $env:JAVA_HOME -and (Test-Path "J:\Android\jdk-17")) { $env:JAVA_HOME = "J:\Android\jdk-17" }
if (-not (Test-Path (Join-Path $root "keystore.properties"))) {
    throw "keystore.properties fehlt – zuerst .\tools\new-upload-key.ps1 ausführen."
}

$secure = Read-Host "Passwort des Upload-Schlüssels" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$tasks = @(":app:bundleRelease")
if ($Apk) { $tasks += ":app:assembleRelease" }
Push-Location $root
try {
    $env:LUMI_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    & .\gradlew.bat @tasks --no-daemon -q
    if ($LASTEXITCODE -ne 0) { throw "Build fehlgeschlagen" }
} finally {
    Remove-Item Env:\LUMI_STORE_PASSWORD -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    Pop-Location
}
Write-Host "Bundle: $(Join-Path $root 'app\build\outputs\bundle\release\app-release.aab')"
if ($Apk) { Write-Host "APK:    $(Join-Path $root 'app\build\outputs\apk\release\app-release.apk')" }
