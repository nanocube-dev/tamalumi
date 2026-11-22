# Briefing for Claude Code – „Lumi“ (offline talking virtual pet, Samsung S23)

A modern virtual pet for Android that you can talk to. The voice cascade (Silero VAD → Whisper →
Gemma 4 E2B via LiteRT-LM → Piper) is taken over from the sibling project
`C:\AI\Claude\gemma-voice\gemma-voice-avatar` (read its CLAUDE.md for the hard-won device lessons);
`voice/*` is a near-verbatim copy of its engine files.

## Ground rules

1. **Offline in use.** Everything is drawn (Canvas) or synthesised (SoundFx); models live in
   `files/models` (user data, never in the repo). **One exception, approved by the owner on 2026-11-22
   for the store release:** the one-time brain download in `voice/ModelDownloader.kt` – started only
   by a tap, HTTPS to Hugging Face hosts only (`isAllowedHost`, checked after redirects), every file
   pinned to a commit in `assets/brain_manifest.tsv` (regenerate with `tools/make_brain_manifest.py`),
   SHA-256 for large files, resumable `.part` files. No other network code, no HTTP library, no analytics.
2. **Classic Views, not Compose.** One activity, XML layout, StateFlows collected in a Main scope.
3. Same toolchain as Gemma Voice: minSdk 26, arm64-v8a only, JDK 17, Kotlin 2.4.20, AGP 8.7.3,
   Gradle 8.10.2, LiteRT-LM 0.19.0, vendored sherpa-onnx 1.13.2 (newer drops the Piper phonemiser).
4. UI language is German. Pet rules (`pet/`) and text logic (`brain/Persona`, `EmotionTag`,
   `Understanding`) stay pure Kotlin so they run as JVM unit tests.
5. Keep the Gemma Voice workarounds: thinking off, engine recycling (LiteRT-LM#2062) only when idle,
   plain bundle on the GPU delegate (`-gpu` bundles decode gibberish on the S23), `generate` instead of
   `generateWithCallback`, barge-in gate 260 ms / RMS 0.045.

## Licence

Lumi is **GPL-3.0** (decided 2026-11-22 – eSpeak NG is compiled into sherpa-onnx). Any new dependency or
downloaded model must be GPL-compatible and be added to `THIRD_PARTY_NOTICES.md` **and**
`app/src/main/assets/third_party_notices.txt` (shown in the app under „Lizenzen & Quellcode“).
`MainActivity.SOURCE_URL` must point at the public source of the shipped version.

## Build, test, look

```powershell
$env:JAVA_HOME="J:\Android\jdk-17"
.\gradlew.bat :app:assembleDebug --no-daemon -q
.\gradlew.bat :app:testDebugUnitTest --no-daemon -q      # includes PetRenderTest
```

Release signing: Lumi's own upload key lives outside the project (`tools/new-upload-key.ps1`), git-ignored
`keystore.properties` holds only path + alias, passwords reach Gradle only via env for one run
(`tools/build-release.ps1` → `.aab`). Without the key, release falls back to the debug key and
`bundleRelease` refuses to run.

`BrainDownloadNetTest` downloads the small manifest files for real – only with `-Plumi.net=true`.
Store plan, licences and open decisions: `docs/STORE-PLAN.md` (eSpeak-ng inside sherpa-onnx is GPL-3.0).

`PetRenderTest` renders every mood plus the whole screen to `app/build/renders/*.png` with
Robolectric native graphics (works on this Windows PC) – look at `contact_sheet.png` after any
visual change. Gotcha found that way: a Paint's alpha multiplies its shader, so PetView resets the
colour before every gradient (`fill.color = Color.BLACK; fill.shader = …`).

Device: `.\tools\install.ps1`, models via `.\tools\copy-models-from-gemmavoice.ps1` (run-as tar pipe
between the two debuggable apps). Probe extras: see README (`hatch`, `age_hours`, `needs`, `say`,
`speak`, `feed`, `play`, `talk`). Logcat tags `Lumi`, `Lumi.LLM`, `Lumi.STT`, `Lumi.TTS`, `Lumi.PET`.

## Learned on the S23 (2026-11-21)

* Gemma 4 E2B ignored „[froh]“-style tags completely (and once copied the `<Zustand: froh>` format of
  the state line). A leading feeling **emoji** („😊 Juhu!“) is followed reliably – that is the
  contract now; `EmotionTagFilter` still understands brackets and echoed state lines, strips emojis
  from caption and speech, and `guess()` reads a feeling off the first sentence as a last resort.
* `adb shell am start … --es say "a b"` delivers only „a“: quote twice (`adb shell "am start … --es say 'a b'"`).
* The `.ps1` tools must stay UTF-8 **with BOM** – Windows PowerShell 5.1 reads BOM-less files as ANSI and
  a „–“ turns into a quote character that breaks parsing.
* Spoken commands are matched loosely (Whisper heard „Umi“/„Lomi“ for Lumi, users say „lass uns
  *zusammen* spielen“); see `Understanding.PLAY`.

## How the pieces talk

* `PetKeeper` owns `PetState` (StateFlow, SharedPreferences). Every change goes through
  `apply { PetEngine.rule(it) }`, which ticks time first. Reactions (`ATE`, `PURR`, `HATCHED` …) go to
  the UI as a SharedFlow; spoken care actions („Hier, iss was“) as `spokenActions`.
* `PetBrain` = Gemma Voice's `Assistant` minus wake word / home PC, plus: conversation session
  (mic button, closes after 30 s silence), `<Zustand …>` line before each message, `[gefühl]` tag
  filter → `emotion` flow → `PetView.flash()`, stage-dependent pitch (Piper slower × AudioTrack faster),
  `remark()` for spontaneous comments, `ensureOut()` reopening playback on pitch / mic changes.
* „⚑ Antwort melden“ (Play AI-content policy): `AnswerReport`/`ReportGuard` (pure Kotlin) – reports stay in
  SharedPreferences `reports`, reported sentences are dropped in `PetBrain.startTurn`, a report resets the context.
* Persona (system prompt) is only rebuilt between conversations or on rename/forget, because it
  resets the multi-turn context.
