# Lumi in den Play Store – Prüfung und Plan

Stand: 22.11.2026. Geprüft an den Quellen (Hugging-Face-API, Modellkarten, die eingebauten Bibliotheken,
Google-Play-Hilfe). Keine Rechtsberatung – bei der GPL-Frage im Zweifel jemanden mit Fachwissen fragen.

## 1. Was in Lumi steckt und unter welcher Lizenz

| Baustein | Herkunft | Lizenz | Folge |
| --- | --- | --- | --- |
| **Gemma 4 E2B** (`gemma-4-E2B-it.litertlm`, 2,59 GB) | `litert-community/gemma-4-E2B-it-litert-lm`, Commit `2e12bfd8`, **nicht zugangsbeschränkt** | **Apache 2.0** (seit Gemma 4, vorher eigene Gemma-Bedingungen) | kommerziell frei nutzbar; Lizenztext + Hinweis mitliefern |
| LiteRT-LM (Laufzeit) | Google, Maven | Apache 2.0 | Hinweis mitliefern |
| **sherpa-onnx 1.13.2** (VAD, Whisper, Piper) | k2-fsa, in `app/libs` | Apache 2.0 | Hinweis mitliefern |
| **eSpeak-ng** (Phonemisierer für Piper) | **fest einkompiliert** in `libsherpa-onnx-jni.so` (nachgeprüft: Zeichenketten „espeak-ng“, „phontab“) | **GPL-3.0** | **die ausgelieferte App fällt unter die GPL-3.0** → siehe Entscheidung A |
| espeak-ng-data (Sprachdaten, 355 Dateien, 18 MB) | mit der Stimme heruntergeladen | GPL-3.0 | wie oben |
| Whisper small (int8) | OpenAI, Umwandlung `csukuangfj/sherpa-onnx-whisper-small`, Commit `8f3c18b3` | MIT | Hinweis mitliefern |
| Silero VAD v5 | `csukuangfj/vad`, Commit `fba88cd2` | MIT | Hinweis mitliefern |
| Stimme **Kerstin** (low) | `csukuangfj/vits-piper-de_DE-kerstin-low`, Commit `7bc6590b` | Datensatz **CC0** | frei, ideal als Standardstimme |
| Stimme Thorsten (medium) | `csukuangfj/vits-piper-de_DE-thorsten-medium` | Datensatz **CC0** | frei |
| Stimme Eva K | M-AILABS-Datensatz | Modellkarte sagt nur „siehe URL“ – nicht geklärt | **nicht ausliefern**, bis geklärt |
| AndroidX, Kotlin, Coroutines | Google/JetBrains | Apache 2.0 | Hinweis mitliefern |

## 2. Entscheidungen, die bei dir liegen

**A. GPL: Lumi als Open Source veröffentlichen – oder die Sprachausgabe tauschen?** → **entschieden am
22.11.2026: Open Source unter GPL-3.0.** `LICENSE`, `THIRD_PARTY_NOTICES.md` und die Seite
„Lizenzen & Quellcode“ in der App sind angelegt. Öffentliches Repo: https://github.com/nanocube-dev/tamalumi
(ist als `SOURCE_URL` in `MainActivity` eingetragen).
* *Open Source (GPL-3.0)* – empfohlen. Der Quellcode kommt öffentlich auf GitHub, die App darf trotzdem im Store
  stehen und sogar Geld kosten. Passt zur Botschaft „offline, privat, nachprüfbar“. Aufwand: klein.
* *Geschlossener Code*: Piper/eSpeak müssten raus, z. B. zugunsten der **Android-Systemstimme** (Google/Samsung
  TTS, offline, Tonhöhe einstellbar). Folge: Klang hängt vom Handy ab, Lippensync muss umgebaut werden. Aufwand: mittel.

**B. Name.** „Lumi“ ist mehrfach belegt, u. a. „Lumi AI: Character AI Chat“ (iOS), „Lumi – AI Assistant“, „Lumi – AI Car
Companion“, „Lumi: AI Bedtime Stories“. **lumi.com, lumi.de, lumi.app, getlumi.app, lumipet.de, lumipet.com sind
registriert** (DNS-Nameserver aktiv – eine „frei“-Anzeige ist dann ein Weiterverkaufs-/Premium-Angebot). Empfehlung:
einen eigenständigen Namen suchen, vor dem Domainkauf in **EUIPO/DPMA** (Marken) und im Play Store prüfen. Das
Haustier selbst kann im Spiel weiter „Lumi“ heißen. Fremde Marken für virtuelle Haustiere dürfen weder im
Namen noch in Store-Texten vorkommen („virtuelles Haustier“ geht).
→ **entschieden am 22.11.2026: „TamaLumi“** (Domains tamalumi.de/.com reserviert, keine Marke „Tamalumi“ bei
WIPO/DPMA gefunden; Umbenennung, falls es Beschwerden gibt). Store-Titel „TamaLumi – Offline-Haustier“, Name unter
dem App-Symbol „TamaLumi“, das Haustier heißt weiter „Lumi“. Paket-ID `de.tamalumi.app` (nach dem ersten Upload
unveränderlich).

**C. Zielgruppe.** „Ab 13“ (oder ab 16) ist deutlich einfacher als „für Kinder“: Für Kinder gelten das
Familien-Programm und strengere Regeln (Mikrofon zählt dort zu den sensiblen Daten), und ein frei redendes
Sprachmodell müsste kindgerecht abgesichert werden. → **entschieden am 22.11.2026: ab 16** für den Start;
nach dem geschlossenen Test lässt sich das in der Play Console auf 13 senken.

## 3. Was Google Play verlangt

* **Ziel-API 36** (Android 16) für neue Apps und Updates seit 31.08.2026 – ✓ umgestellt am 22.11.2026 (compileSdk 36 mit AGP 8.7.3, Warnung in `gradle.properties` unterdrückt).
* **AAB statt APK**, eigener **Upload-Schlüssel** + Play App Signing – ✓ vorbereitet: `tools/new-upload-key.ps1`, `tools/build-release.ps1` (baut das .aab, ~27 MB).
* **Neues Privat-Entwicklerkonto**: geschlossener Test mit **mindestens 12 Testern, 14 Tage am Stück**, erst dann
  Antrag auf Produktion. (Organisationskonten sind ausgenommen.) Dazu die Identitätsprüfung für Entwickler.
* **Richtlinie zu KI-Inhalten**: Chatbot-Apps brauchen eine **Melden-Funktion in der App** für anstößige Antworten
  (ohne die App zu verlassen); die Meldungen sollen in Filter/Moderation einfließen. ✓ eingebaut: „⚑ Antwort melden“
  unter jedem Untertitel (auch langes Drücken), Grund + Anmerkung; Lumi verstummt, beginnt das Gespräch neu und
  spricht gemeldete Sätze nie wieder (`brain/AnswerReport.kt`). Liste unter ⋯ → Gemeldete Antworten. Weitergabe an
  den Entwickler nur per eigener Mail-App, sobald `MainActivity.REPORT_EMAIL` gesetzt ist. Dazu Schutzregeln in der Persona.
* **Datensicherheit-Formular + Datenschutzerklärung** (URL nötig, auch wenn nichts erhoben wird).
* **Größe**: AAB-Download bis 4 GB inkl. Asset-Packs (einzelnes Pack max. 1,5 GB) – die 2,59-GB-Gemma-Datei
  passt nicht in ein Pack. Darum lädt Lumi das Gehirn beim ersten Start selbst (siehe 4).
* **Gerätefilter** in der Play Console: arm64, sinnvoll ≥ 8 GB RAM.

## 4. Gehirn-Download beim ersten Start (eingebaut, am PC gegen die echten Server getestet – auf dem Handy noch offen)

Die App lädt ~3,05 GB einmalig von Hugging Face: Gemma 4 E2B, Whisper small int8, Silero VAD, Stimme Kerstin
mit Phonemisierer. Regeln dafür:
* nur nach ausdrücklichem Tippen, nie automatisch; Hinweis, wenn das Netz getaktet ist (Mobilfunk);
* nur HTTPS zu Hugging Face (Host-Allowlist), feste Commits, SHA-256-Prüfung der großen Dateien, Größe der kleinen;
* fortsetzbar nach Abbruch; danach keine Netzverbindung mehr – Lumi bleibt offline;
* Englisch ist nicht mehr Pflicht (Lumi spricht Deutsch).

## 5. Fahrplan

1. Gehirn-Download ✓ (Gerätetest offen) · GPL-3.0 + Lizenzseite ✓ · öffentliches Repo · Melden-Funktion · Lizenz-Seite in der App · Kerstin als Standard
2. Entscheidungen A–C · Ziel-API 36 · Upload-Schlüssel · AAB
3. Tests auf 3–4 weiteren Handys (Speicher, GPU, Tempo) · Datenschutzerklärung + Website
4. Geschlossener Test (12 Tester, 14 Tage) → Produktion
5. Danach: iOS als eigenes Projekt

## Quellen

* Gemma 4 unter Apache 2.0: [Google-Blog](https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/), [The Decoder](https://the-decoder.com/googles-gemma-4-is-now-available-with-apache-2-0-licensing-for-the-first-time/); Hugging-Face-API: `gated: false`, `license: apache-2.0`
* eSpeak-ng / GPL bei sherpa-onnx-Apps: [SherpaTTS (F-Droid, GPL-3.0)](https://f-droid.org/en/packages/org.woheller69.ttsengine/), [Marmalade TTS](https://f-droid.org/packages/app.marmalade.tts/)
* Stimmen: [Thorsten-Modellkarte](https://huggingface.co/rhasspy/piper-voices/blob/main/de/de_DE/thorsten/medium/MODEL_CARD), [Kerstin-Modellkarte](https://huggingface.co/rhasspy/piper-voices/blob/v1.0.0/de/de_DE/kerstin/low/MODEL_CARD)
* [Play: KI-generierte Inhalte](https://support.google.com/googleplay/android-developer/answer/14094294) · [Testanforderungen neue Konten](https://support.google.com/googleplay/android-developer/answer/14151465) · [Ziel-API](https://support.google.com/googleplay/android-developer/answer/11926878) · [Größenlimits](https://support.google.com/googleplay/android-developer/answer/9859372) · [Familien-Richtlinie](https://support.google.com/googleplay/android-developer/answer/9893335)
* Andere „Lumi“-Apps: [Lumi AI: Character AI Chat](https://apps.apple.com/us/app/lumi-ai-character-ai-chat/id6475080382), [Lumi – AI Assistant](https://apps.apple.com/gr/app/lumi-ai-assistant/id6736787802), [Lumi – AI Car Companion](https://apps.apple.com/us/app/-/id6754324797)
