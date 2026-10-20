# SyncDialect Optimization & Architecture Audit — Executive Summary & Reading Guide

**Audit Date:** April 19, 2026  
**Inspected Git Commit:** `f4ffa9a07bca671b822b83d71fef35d87b20dd32` (`v2` branch)  
**Working Tree Status:** Dirty (7 modified files: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `AudioRecorderHelper.kt`, `MainActivity.kt`, `ModelDownloadManager.kt`, `SettingsScreen.kt`, `StreamingTTSManager.kt`; 4 untracked files: `"SyncDialect V2 .md"`, `push_chunks.py`, `stream.py`, `test.txt`)  
**Scope:** Static source/binary/log audit and evidence-backed engineering plan only. No application source code was modified, no builds or benchmarks were executed, and no paid APIs or cloud resources were invoked.

---

## 1. Plain-English Executive Summary

SyncDialect is an offline Android voice-to-voice translation app built with Kotlin and Jetpack Compose. Instead of chaining a separate Speech-to-Text (ASR) model into a text translation model, SyncDialect captures 16 kHz microphone audio, slices it using WebRTC Voice Activity Detection (VAD), wraps the audio into an in-memory WAV file, and feeds it directly into an on-device multimodal LLM (**Google Gemma 4 E2B IT**, packaged as `gemma-4-E2B-it.litertlm`, 2.58 GB on disk) running on the **Google LiteRT-LM** runtime (`com.google.ai.edge.litertlm:litertlm-android:0.13.1`). Generated text tokens stream into Android's native `TextToSpeech` (TTS) engine (`com.google.android.tts`) while muting the microphone to prevent acoustic feedback loops.

### Key Conclusions of This Audit

1. **Gemma is NOT the only bottleneck — pipeline orchestration, backend selection, and prompt/template mismatches account for most avoidable delay and instability.**
   - **Accidental CPU-only LLM execution:** `TranslationEngine.kt` (`lines 98–102`) passes `Backend.CPU()` to `audioBackend` in `EngineConfig`, but **omits the `backend` parameter**. In `litertlm-android:0.13.1`, omitting `backend` defaults the main 35-layer language model decoder (`tf_lite_prefill_decode`) to `Backend.CPU()` (`EngineConfig.class` bytecode line 100). While the `.litertlm` binary header strictly constrains the audio encoder (`tf_lite_audio_encoder_hw`), audio adapter (`tf_lite_audio_adapter`), and end-of-audio (`tf_lite_end_of_audio`) submodels to `"cpu"`, the 780 MB `tf_lite_prefill_decode` LLM submodel has **no CPU-only constraint** and supports `Backend.GPU()` (ML Drift OpenCL) once `<uses-native-library android:name="libOpenCL.so" .../>` is declared in `AndroidManifest.xml`. According to the official `litert-community/gemma-4-E2B-it-litert-lm` model card benchmarks on Galaxy S26 Ultra, moving `backend` from CPU to GPU increases prefill throughput from **557 tok/s to 3,808 tok/s** (6.8× faster) and reduces 1,024-token prefill Time-to-First-Token (TTFT) from **1.8 s to 0.3 s** while lowering CPU RAM usage from **1,733 MB to 676 MB**.
   - **Missing `cacheDir` in `EngineConfig`:** `TranslationEngine.kt` (`lines 98–102`) leaves `cacheDir = null`, preventing LiteRT/XNNPACK/ML Drift from caching compiled graph delegates across app launches and inflating cold-start initialization time.
   - **Per-turn WAV Base64 + JSON serialization overhead on the critical path:** `TranslationEngine.kt` (`line 188`) passes `Content.AudioBytes(wavData)` into `Conversation.sendMessageAsync(contents)`. Disassembly of `Content$AudioBytes.class` and `Conversation.class` proves that `litertlm-android` Base64-encodes the entire WAV byte array (`up to 96 KB PCM -> 128 KB Base64 String`), wraps it into a `com.google.gson.JsonObject`, serializes it to a `java.lang.String`, and parses/decodes it again in C++ JNI on every turn.
   - **Dead `sourceLang` parameter & prompt-template mismatch:** `TranslationEngine.translateAudioStreaming()` (`line 139`) accepts `sourceLang: String` from the UI, **never uses it**, and immediately invokes `onLanguageDetected(targetLang)` (`line 195`). Furthermore, the embedded Jinja template in `gemma-4-E2B-it.litertlm` uses `<|turn>model\n...<turn|>` and `<|channel>thought...<channel|>` tokens (Gemma 4 format), whereas `MainActivity.kt` (`line 376`) and `StreamingTTSManager.kt` (`line 323`) check for Gemma 2/3 `<end_of_turn>` tokens.
   - **Unresolved Coroutines ABI crash masked by global exception swallowing:** `app/build.gradle.kts` (`lines 112–114`) forces `kotlinx-coroutines-*:1.6.4!!` while `litertlm-android:0.13.1` was compiled against a newer `SendChannel$DefaultImpls.close$default` ABI (`Conversation$sendMessageAsync$1$1.class` line 10). When `onDone()` fires at the end of every translation stream, it throws `java.lang.NoSuchMethodError: close$default`. `MainActivity.kt` (`lines 98–104`) catches and ignores this error on the uncaught exception handler, leaving the `callbackFlow` in `sendMessageAsync` unclosed and hanging!

2. **Over 218 MB of dead vision weights sit inside the 2.58 GB model bundle, and 30 MB ML Kit translation models are downloaded during onboarding but never used.**
   - Inspecting the binary structure of `gemma-4-E2B-it.litertlm` (2,588,147,712 bytes) reveals 10 embedded TFLite (`TFL3`) submodels, including `tf_lite_vision_encoder` (**213.77 MB**), `tf_lite_vision_adapter` (**4.53 MB**), and `tf_lite_mtp_drafter` (**42.28 MB**). SyncDialect has no camera or image translation feature, yet users download ~218.3 MB of unused vision weights.
   - During onboarding (`MainActivity.kt:260–261`) and in `SettingsScreen.kt` (`LanguagePacksCard`), the app downloads Google ML Kit text translation models (~30 MB per language) via `RemoteModelManager`, and uses the list of downloaded ML Kit models to restrict which languages appear in the home screen language picker (`MainActivity.kt:228–237`)! Yet `MLKitTranslator.kt` is **completely dead code**—never instantiated anywhere in the app—and Gemma 4 E2B natively translates 35+ languages (140+ pre-trained) without ML Kit packs.

3. **Replacing or fine-tuning Gemma is NOT recommended as the first step.**
   - Fixing the runtime bugs (upgrading coroutines or calling callback-based `sendMessageAsync`, enabling `backend = Backend.GPU()` with `audioBackend = Backend.CPU()`, setting `cacheDir`, passing `sourceLang` in the prompt, and removing dead ML Kit downloads) is high-confidence, low-effort engineering work that requires **zero model training**.
   - Only after establishing an instrumented baseline should model conversion (stripping vision submodels or testing `google/gemma-3n-E2B-it-litert-lm` Int4) or LoRA fine-tuning be executed.

---

## 2. Classification of Evidence Used in This Audit

Throughout this report series, every statement is tagged or categorized into one of four strict epistemic tiers:

| Tier | Tag | Definition |
|---|---|---|
| **1. Code-Verified Fact** | `[CODE-VERIFIED]` | Directly verified by inspecting the repository's `.kt` source files, `build.gradle.kts`, disassembled `.class` bytecode inside `litertlm-android-0.13.1.aar`, binary headers of `gemma-4-E2B-it.litertlm`, or logcat captures (`log.txt`–`log4.txt`). |
| **2. Documented Capability** | `[DOC-VERIFIED]` | Verified from official primary documentation, papers, or model cards (`google/gemma-4-E2B-it`, `litert-community/gemma-4-E2B-it-litert-lm`, `google/gemma-3n-E2B-it-litert-lm`, `google-ai-edge/LiteRT-LM`, Android Developer docs) with URLs and access dates. |
| **3. Testable Hypothesis** | `[HYPOTHESIS]` | Mechanistically grounded engineering inference (e.g., expected latency reduction from GPU prefill or adaptive VAD) that must be validated on physical target phones before claiming success. |
| **4. Unavailable Evidence** | `[MISSING-DATA]` | Measurements, hardware traces, or dataset artifacts not present in the workspace that must be collected using the provided instrumentation plan. |

---

## 3. Reading Guide to the Documentation Suite

This review is organized into six self-contained documents inside `docs/optimization_review/`:

1. **[`README.md`](README.md) (This Document)**  
   Plain-English executive summary, evidence classification, top findings table, and reading guide.
2. **[`CURRENT_ARCHITECTURE_AND_AUDIT.md`](CURRENT_ARCHITECTURE_AND_AUDIT.md)**  
   *Covers Sections 1 & 2:* Exact hardware/software stack, binary dissection of `gemma-4-E2B-it.litertlm` (all 10 internal TFLite submodels and Jinja template), end-to-end Mermaid architecture and threading diagram, discrepancy analysis between docs and code, and a 16-item code audit with severity, file/line evidence, proposed fixes, trade-offs, and verification metrics.
3. **[`LATENCY_MEMORY_AND_SIZE_PLAN.md`](LATENCY_MEMORY_AND_SIZE_PLAN.md)**  
   *Covers Sections 3 & 6:* Formal 10-stage latency definitions, analysis of cold vs. warm / short vs. long / CPU vs. GPU vs. NPU behavior, ready-to-drop-in `SystemClock.elapsedRealtimeNanos()` instrumentation plan, pipeline optimization techniques, and deep memory/storage/quantization analysis (APK/AAB size, 2.58 GB `.litertlm` submodel breakdown, mmap PLE embeddings, KV-cache math, and submodel stripping).
4. **[`MODEL_OPTIONS_AND_TRAINING_PLAN.md`](MODEL_OPTIONS_AND_TRAINING_PLAN.md)**  
   *Covers Sections 4, 5 & 7:* Technical comparison of Prompt/Template fixing vs. SFT vs. LoRA vs. QLoRA vs. Full FT for `google/gemma-4-E2B-it`; LiteRT-LM adapter support (`LoraConfig` vs. `litert-torch` merge/export); complete reproducible LoRA training plan (`NOT EXECUTED`); knowledge distillation vs. quantization vs. pruning analysis; and a primary-source comparison of 6 alternative offline model architectures (`gemma-3n-E2B-it-litert-lm`, `gemma-3-1b-it` / `270m`, `translategemma-4b-it`, Moonshine/Whisper + ML Kit / Opus-MT / NLLB-200 cascades, and SeamlessM4T).
5. **[`UI_AND_CONVERSATION_EXPERIENCE.md`](UI_AND_CONVERSATION_EXPERIENCE.md)**  
   *Covers Section 8:* Line-by-line UI/UX and state-management audit of `MainActivity.kt`, `OnboardingScreen.kt`, `TutorialScreen.kt`, and `SettingsScreen.kt`. Covers conversation turn state machine, dual-speaker UX, source transcript visibility, streaming revision hazards, stop/cancel/replay controls, offline voice readiness, accessibility (TalkBack, touch targets, font scaling), and `LazyColumn` scroll performance.
6. **[`PROPOSED_EVALUATION_AND_ROADMAP.md`](PROPOSED_EVALUATION_AND_ROADMAP.md)**  
   *Covers Sections 9 & 10:* Bounded experimental evaluation protocol (4 candidate configurations, 3 device tiers, 100-utterance stratified test corpus per language direction, MQM human rubric + COMET/chrF++/BLEU metrics, acoustic loopback latency rig, thermal/battery profiling), prioritized 4-tier engineering roadmap, pass/rollback criteria, and the final executive decision table.

---

## 4. Summary of Top High-Impact Findings

| ID | Severity | Component | Core Finding | Primary Fix |
|---|---|---|---|---|
| **F-01** | **CRITICAL** | `app/build.gradle.kts:112–114`, `MainActivity.kt:98–104` | `kotlinx-coroutines` forced to `1.6.4!!` causes `NoSuchMethodError: SendChannel$DefaultImpls.close$default` inside `litertlm-android:0.13.1` when `onDone()` fires; global crash handler swallows the exception, leaving `callbackFlow` hanging until hit by `takeWhile` or GC. | Upgrade `kotlinx-coroutines-*` to `1.10.2` (matching `libs.versions.toml`) OR use callback-based `Conversation.sendMessageAsync(contents, MessageCallback)` directly; remove global `UncaughtExceptionHandler` hack. |
| **F-02** | **HIGH** | `TranslationEngine.kt:98–102`, `AndroidManifest.xml` | `EngineConfig` sets `audioBackend = Backend.CPU()` but omits `backend`, defaulting the 35-layer LLM decoder (`tf_lite_prefill_decode`) to CPU instead of GPU and omitting `cacheDir`. | Set `backend = Backend.GPU()` (with fallback to `Backend.CPU(numOfThreads = 4)`), keep `audioBackend = Backend.CPU()`, set `cacheDir = context.cacheDir.absolutePath`, and add `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` to `AndroidManifest.xml`. |
| **F-03** | **HIGH** | `TranslationEngine.kt:139, 169, 187, 195` | `sourceLang` is ignored in the prompt (`"Translate this audio to $targetLang"`), and `onLanguageDetected(targetLang)` falsely reports `targetLang`. | Include `sourceLang` in the system/user prompt (`"Translate this $sourceLang speech into $targetLang"`) or implement explicit bidirectional translation between `sourceLang` and `targetLang`. |
| **F-04** | **HIGH** | `MainActivity.kt:376`, `StreamingTTSManager.kt:323` | Token stream parser checks for `<end_of_turn>` (Gemma 2/3), but `gemma-4-E2B-it.litertlm` uses `<|turn>`, `<turn|>`, `<|think|>`, and `<|channel>thought...<channel|>` tokens. Also passes `extraContext` without explicitly disabling thinking mode (`"enable_thinking" to false`). | Pass `extraContext = mapOf("enable_thinking" to false)` in `ConversationConfig` / `sendMessageAsync`, and update control-token regex to strip `<\|turn>|<turn\|>|<\|think\|>|<\|channel>thought[\s\S]*?<channel\|>`. |
| **F-05** | **HIGH** | `MainActivity.kt:228–237, 258–261`, `SettingsScreen.kt:393`, `MLKitTranslator.kt` | App downloads ~30 MB ML Kit Translate models during onboarding/settings and restricts the home screen language selector to downloaded ML Kit models, even though `MLKitTranslator` is never used and Gemma 4 E2B translates natively. | Decouple Gemma 4 language selection from ML Kit `RemoteModelManager`, or wire `MLKitTranslator` as an explicit lightweight fallback mode. |
| **F-06** | **HIGH** | `MainActivity.kt:356–359`, `AudioRecorderHelper.kt:97–103` | Silent speech loss: chunks arriving while `isTranslating == true` are silently dropped (`tryStartTranslation()`), and any user speech during TTS playback (`isMuted == true`) is discarded with zero UI feedback. | Add a 1-slot bounded segment queue (`Channel<AudioSegment>(capacity = 1)`) for speech during inference, and expose explicit visual turn states (`Listening` / `Translating` / `Speaking`) with a "Stop Speaking & Listen" button. |
| **F-07** | **MEDIUM** | `TranslationEngine.kt:185–189`, `Content$AudioBytes.class` | `Content.AudioBytes(wavData)` Base64-encodes up to 96 KB of WAV bytes into a ~128 KB JSON string via Gson on every turn. | Use `Session` (`Engine.createSession`) with `InputData.Audio(wavData)` (which passes raw `byte[]` directly over JNI without Base64/JSON serialization) or `Content.AudioFile`. |
| **F-08** | **MEDIUM** | `ModelDownloadService.kt:91, 204–213`, `ModelDownloadManager.kt:26` | Model download uses mutable `/resolve/main/` URL without SHA-256 verification; `ModelDownloadManager` checks exact byte size (`2588147712L`), which will delete the model if Hugging Face updates `main`. | Pin the immutable Hugging Face commit revision hash in the URL and verify SHA-256 digest before promoting `.tmp` to `.litertlm`. |

---

## 5. Summary of Missing Information (`[MISSING-DATA]`)

Because this task is a static audit without running code or device benchmarks, the following data points are unavailable in the workspace and must be gathered during Phase 1 of the roadmap:
1. **Per-stage nanosecond latency distributions (`p50`, `p90`, `p95`)** on physical devices for the current `v2` commit (`f4ffa9a`), as `techstack.md` only records coarse `700ms–1300ms` TTFT ranges on a single device (`SM-S928B`) from an earlier build.
2. **Audio encoder token rate for `gemma-4-E2B-it.litertlm`**: While `gemma-3n-E2B-it` documents 6.25 audio tokens/sec, the exact audio token count emitted per second of 16 kHz WAV input by `tf_lite_audio_encoder_hw` in `gemma-4-E2B-it.litertlm` must be read at runtime via `conversation.getBenchmarkInfo().lastPrefillTokenCount`.
3. **Device thermal throttling curves and mAh battery drain** over a sustained 20-minute bilingual conversation across flagship, mid-range, and budget Android phones.
4. **Native-speaker MQM (Multidimensional Quality Metrics) translation accuracy scores** across SyncDialect's target language pairs on conversational speech.
