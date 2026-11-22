# Kopiert Lumis "Gehirn" (Whisper, Piper-Stimmen, Gemma 4) direkt auf dem Handy aus der
# installierten Gemma-Voice-App in die Lumi-App. Nichts geht über den PC, nichts ins Internet.
#
# Voraussetzungen: Handy per USB/WLAN-adb verbunden, beide Apps installiert und "debuggable"
# (das sind die selbst gebauten APKs von Gemma Voice und Lumi).
#
# Aufruf:   .\tools\copy-models-from-gemmavoice.ps1            (ein Gerät verbunden)
#           .\tools\copy-models-from-gemmavoice.ps1 -Serial XYZ
param([string]$Serial = "")

$ErrorActionPreference = "Stop"
$from = "com.lou.gemnavoice"
$to = "de.tamalumi.app"

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "J:\Android\Sdk", "$env:LOCALAPPDATA\Android\Sdk")) {
        if ($sdk -and (Test-Path "$sdk\platform-tools\adb.exe")) { $adb = "$sdk\platform-tools\adb.exe"; break }
    }
}
if (-not $adb) { throw "adb nicht gefunden (Android SDK platform-tools)" }
$a = @()
if ($Serial) { $a = @("-s", $Serial) }

function Invoke-Adb([string]$cmd) { & $adb @a shell $cmd }

Write-Host "Gerät:" (& $adb @a get-serialno)
foreach ($p in @($from, $to)) {
    if (-not (Invoke-Adb "pm path $p")) { throw "$p ist nicht installiert" }
}
$probe = Invoke-Adb "run-as $from ls files/models 2>&1"
if ($probe -match "not debuggable|unknown package") { throw "Gemma Voice ist nicht debuggable – dann in Lumi über ⋯ → Gehirn importieren (Ordner/ZIP)." }

Write-Host "Gemma Voice hat:"; Invoke-Adb "run-as $from du -sh files/models"
Write-Host "Kopiere … (3–4 GB, dauert etwa eine Minute)"
Invoke-Adb "am force-stop $to"
Invoke-Adb "run-as $to mkdir -p files"
# tar aus der einen Sandbox direkt in die andere; der XNNPACK-/GPU-Cache und Selbsttest-Dateien bleiben weg
Invoke-Adb "run-as $from tar -cf - -C files --exclude=models/cache --exclude=models/selftest models | run-as $to tar -xf - -C files"

Write-Host "Lumi hat jetzt:"
Invoke-Adb "run-as $to du -sh files/models"
Invoke-Adb "run-as $to ls files/models files/models/gemma files/models/tts/de"
Write-Host "Fertig. Lumi starten – oben steht dann 'wacht auf …' und kurz darauf 'wach · alles offline'."
