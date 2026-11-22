# Legt Lumis EIGENEN Upload-Schlüssel an (nicht den von Triong) und trägt ihn in keystore.properties ein.
# keytool fragt Passwort und Namen selbst ab – nichts davon landet in einer Datei oder im Chat.
#   .\tools\new-upload-key.ps1
#   .\tools\new-upload-key.ps1 -Dir D:\Schluessel\Lumi
param([string]$Dir = (Join-Path $env:USERPROFILE "Schluessel\Lumi"),
      [string]$Alias = "lumi-upload")

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not $env:JAVA_HOME -and (Test-Path "J:\Android\jdk-17")) { $env:JAVA_HOME = "J:\Android\jdk-17" }
$keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
$store = Join-Path $Dir "lumi-upload.jks"

if ($store.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw "Der Schlüssel darf nicht im Projektordner liegen." }
if (Test-Path $store) { throw "Es gibt schon einen Schlüssel: $store – nicht überschrieben." }
New-Item -ItemType Directory -Force $Dir | Out-Null

Write-Host "Gleich fragt keytool nach einem Passwort (zweimal) und nach Name/Organisation/Ort."
Write-Host "Das Passwort gut merken bzw. im Passwortmanager ablegen – ohne es kein Update mehr hochladen."
& $keytool -genkeypair -v -keystore $store -storetype PKCS12 -alias $Alias `
    -keyalg RSA -keysize 4096 -validity 10000
if ($LASTEXITCODE -ne 0) { throw "keytool ist fehlgeschlagen." }

$props = Join-Path $root "keystore.properties"
@(
    "# Lumis Upload-Schlüssel (nicht einchecken). Passwörter fragt tools\build-release.ps1 ab.",
    "storeFile=$($store -replace '\\','/')",
    "keyAlias=$Alias"
) | Set-Content -Encoding ASCII $props

Write-Host ""
Write-Host "Fertig: $store"
Write-Host "Eingetragen in: $props"
Write-Host "Jetzt bitte SICHERN: die .jks-Datei an einen zweiten Ort (USB-Stick, Passwortmanager) – getrennt vom Passwort."
& $keytool -list -v -keystore $store -alias $Alias -storetype PKCS12 | Select-String "SHA256:"
