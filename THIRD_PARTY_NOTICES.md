# Bausteine anderer Projekte in Lumi

Lumi selbst steht unter der **GNU General Public License v3.0** (siehe `LICENSE`). Diese Datei listet
alles, was Lumi von anderen enthält oder beim ersten Start herunterlädt – mit Lizenz und Urheber.
Dieselbe Liste zeigt die App unter ⋯ → *Lizenzen & Quellcode*
(`app/src/main/assets/third_party_notices.txt`).

## In der App enthalten

| Baustein | Lizenz | Urheber / Quelle |
| --- | --- | --- |
| sherpa-onnx 1.13.2 (VAD, Spracherkennung, Sprachausgabe) | Apache-2.0 | Xiaomi Corporation / k2-fsa, github.com/k2-fsa/sherpa-onnx |
| eSpeak NG (Phonemisierer, in sherpa-onnx einkompiliert) | **GPL-3.0-or-later** | Reece H. Dunn, Jonathan Duddington u. a., github.com/espeak-ng/espeak-ng |
| ONNX Runtime (in sherpa-onnx) | MIT | Microsoft Corporation, github.com/microsoft/onnxruntime |
| LiteRT-LM (Laufzeit für Gemma) | Apache-2.0 | Google LLC, github.com/google-ai-edge/LiteRT-LM |
| AndroidX (core, appcompat, documentfile) | Apache-2.0 | The Android Open Source Project |
| Kotlin-Standardbibliothek, kotlinx.coroutines | Apache-2.0 | JetBrains s.r.o. und Mitwirkende |

## Beim ersten Start heruntergeladen („Gehirn“)

| Modell | Lizenz | Urheber / Quelle |
| --- | --- | --- |
| Gemma 4 E2B (LiteRT-LM-Format) | Apache-2.0 | Google LLC, huggingface.co/litert-community/gemma-4-E2B-it-litert-lm |
| Whisper small (int8, sherpa-onnx-Format) | MIT | OpenAI; Umwandlung huggingface.co/csukuangfj/sherpa-onnx-whisper-small |
| Silero VAD v5 | MIT | Silero Team, github.com/snakers4/silero-vad |
| Piper-Stimme „Kerstin“ (de_DE, low) | Stimme CC0; Piper MIT | Datensatz github.com/rhasspy/dataset-voice-kerstin; huggingface.co/csukuangfj/vits-piper-de_DE-kerstin-low |
| eSpeak-NG-Sprachdaten (`espeak-ng-data`) | GPL-3.0-or-later | eSpeak NG |

Die vollständigen Lizenztexte: GPL-3.0 in `LICENSE`; Apache-2.0 und MIT in
`app/src/main/assets/third_party_notices.txt`.
