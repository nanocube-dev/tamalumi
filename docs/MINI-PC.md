# Lumi auf einem anderen PC weiterbauen

Dieses Projekt kam als ZIP vom Entwicklungs-PC. Ohne `build`-Ordner, ohne APKs, ohne Modelle
(die lädt Lumi selbst) und ohne Signierschlüssel.

## 1. Auspacken

Zum Beispiel nach `C:\AI\Claude\Lumi`. Der Ort ist egal, nur keine Leerzeichen im Pfad, damit
es mit Gradle keinen Ärger gibt.

## 2. Werkzeuge (falls noch nicht da, z. B. von Triong)

* **JDK 17** (z. B. Microsoft Build of OpenJDK 17)
* **Android SDK** mit Platform 35 und Build-Tools (Android Studio installiert beides)
* Dem Projekt sagen, wo das SDK liegt – eine Datei `local.properties` im Projektordner, z. B.:

  ```
  sdk.dir=C:/Users/<dein Name>/AppData/Local/Android/Sdk
  ```

  (Android Studio legt sie beim Öffnen selbst an.)

## 3. Bauen und testen

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17…"   # Pfad zu deinem JDK 17
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Der erste Build lädt Gradle und alle Bibliotheken (ein paar Minuten). Die Test-Bilder von Lumi
landen in `app\build\renders\`.

## 4. Für den Play Store

* **Eigenen Upload-Schlüssel für Lumi anlegen** (nicht den von Triong): einmal

  ```powershell
  .\tools\new-upload-key.ps1
  ```

  keytool fragt Passwort und Namen selbst ab. Der Schlüssel landet in
  `%USERPROFILE%\Schluessel\Lumi\lumi-upload.jks` (außerhalb des Projekts), das Skript trägt Pfad und
  Alias in `keystore.properties` ein (git-ignoriert, Vorlage `keystore.properties.example`).
  Passwörter stehen nirgends in einer Datei. **Die .jks-Datei sichern** (zweiter Ort), das Passwort im
  Passwortmanager – ohne beide keine Updates mehr (außer über ein Zurücksetzen bei Google).
* **Bundle für den Store bauen**: `.\tools\build-release.ps1` fragt das Passwort ab und baut
  `app\build\outputs\bundle\release\app-release.aab` (`-Apk` baut zusätzlich eine signierte Release-APK).
  Ohne `keystore.properties` signiert `assembleRelease` mit dem lokalen Debug-Schlüssel (zum Testen),
  `bundleRelease` bricht dann bewusst ab.
* Was sonst noch fehlt, steht in `docs/STORE-PLAN.md` (Ziel-API 36, AAB, Melden-Funktion,
  Datenschutzerklärung, Name, 12 Tester × 14 Tage).

## 5. Mit Claude Code weitermachen

Den Ordner in Claude Code öffnen – `CLAUDE.md` erklärt einer neuen Sitzung das Projekt.
Der Verweis auf das Gemma-Voice-Projekt dort ist nur Hintergrund; gebraucht wird es nicht,
der Sprachteil liegt schon in `app/src/main/java/com/lou/lumi/voice/`.
