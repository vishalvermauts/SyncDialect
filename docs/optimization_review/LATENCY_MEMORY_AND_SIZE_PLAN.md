# SyncDialect Latency, Memory, and Model-Size Optimization Plan

**Audit Date:** April 19, 2026  
**Inspected Git Commit:** `f4ffa9a07bca671b822b83d71fef35d87b20dd32` (`v2` branch)

---

## Section 3: Finding and Eliminating the Actual Latency Bottlenecks

### 3.1 Why Existing Latency Claims Are Inaccurate (`[CODE-VERIFIED]`)

`README.md` (`lines 5, 40`) claims an *"average end-to-end delay/latency of 600–800 ms"*, while `techstack.md` (`line 143`) reports *"Typical TTFT (Time to First Token): 700ms – 1300ms"* on a Samsung SM-S928B. Inspection of `TranslationEngine.kt` (`lines 191, 206–209`) reveals what the code actually measures:

```kotlin
val startTime = System.currentTimeMillis()
var isFirstToken = true
...
val ttft = if (isFirstToken) {
    isFirstToken = false
    System.currentTimeMillis() - startTime
} else null
```

`startTime` is recorded **after**:
1. The user has finished speaking and waited `400 ms` to `800 ms` of silence (`AudioRecorderHelper.kt:138–145`).
2. `translationMutex` is acquired and `currentEngine.createConversation(convConfig)` has completed (`TranslationEngine.kt:152–175`).
3. `pcmToWav(audioData)` has copied the audio array (`line 185`).

And `ttft` stops **at the very first generated token** (not when a speakable 3-word phrase is buffered in `StreamingTTSManager`, and not when `com.google.android.tts` starts playing audio from the speaker)—and worse, `MainActivity.kt:375` (`onTokenGenerated = { token, latencyMs ->`) **never even logs or records `latencyMs`**!

---

### 3.2 Formal 10-Stage Latency Definitions (`[CODE-VERIFIED]`)

To measure and optimize real user-perceived performance without ambiguity, we define **10 distinct, non-overlapping latency metrics** using `SystemClock.elapsedRealtimeNanos()`:

| Stage # | Metric Name | Start Event ($T_{\text{start}}$) | End Event ($T_{\text{end}}$) | Current Code Path & Status |
|---:|---|---|---|---|
| **1** | **Cold Startup & Model Loading ($L_{\text{cold\_init}}$)** | `MainActivity.onCreate()` entry (`T_app_create`) | `TranslationEngine.initialize()` returns `true` AND `StreamingTTSManager.onInit(SUCCESS)` completes (`T_engines_ready`) | `MainActivity.kt:92, 148–170`. Unmeasured. `cacheDir` is currently `null`, so LiteRT re-initializes delegate state on every cold launch. |
| **2** | **Speech-End Detection Delay / Endpointing ($L_{\text{endpoint}}$)** | Timestamp of the last 20 ms frame where `vad.isSpeech(buffer) == true` (`T_last_speech_frame`) | Timestamp when `AudioRecorderHelper` flushes `chunkData` to `onChunkReady` (`T_chunk_flushed`) | `AudioRecorderHelper.kt:107–154`. Currently fixed by construction at **`360–400 ms`** (18–20 frames $\times$ 20 ms) when `speechFrames >= 75` ($\ge 1.5\text{ s}$), or **`800 ms`** (40 frames $\times$ 20 ms) when `speechFrames < 75`. (`0 ms` on forced 3.0 s flush). |
| **3** | **Speech Recognition / Audio Encoding ($L_{\text{audio\_enc}}$)** | Native entry into `sendMessageAsync` (`T_infer_call`) | Completion of USM/Conformer audio encoder (`tf_lite_audio_encoder_hw` + `tf_lite_audio_adapter`) before LLM prefill (`T_audio_enc_done`) | Embedded inside `litertlm-android` native `sendMessageAsync`. Currently runs strictly on **CPU** (`89.70 MiB` + `9.02 MiB` submodels) and includes `400–800 ms` of un-trimmed trailing silence! |
| **4** | **Translation Prompt & Audio Prefill ($L_{\text{prefill}}$)** | Start of `tf_lite_prefill_decode` prefill signature (`T_prefill_start`) | Completion of KV-cache prefill for system instruction + user prompt + audio soft tokens (`T_prefill_done`) | Exposed via `conversation.getBenchmarkInfo()` (`lastPrefillTokenCount / lastPrefillTokensPerSecond`). Currently runs on **CPU** because `EngineConfig.backend` is omitted! |
| **5** | **Time to First Generated Token ($L_{\text{TTFT}}$)** | `T_chunk_flushed` (or `T_infer_call` for pure engine TTFT) | First non-empty text chunk received in `sendMessageAsync` (`T_first_token`) | `TranslationEngine.kt:191–208`. Includes $L_{\text{setup}}$ (`createConversation` + `pcmToWav` + Base64/JSON serialization) + $L_{\text{audio\_enc}}$ + $L_{\text{prefill}}$ + 1 decode step. Historical range on S28 CPU: `700–1,300 ms` (`techstack.md:143`). |
| **6** | **Translation Generation / Decoding ($L_{\text{decode}}$)** | First generated token (`T_first_token`) | Stream completion (`T_decode_done` at `onComplete()`) | `TranslationEngine.kt:197–215`. Exposed via `conversation.getBenchmarkInfo()` (`lastDecodeTokenCount`, `lastDecodeTokensPerSecond`). Runs on CPU (~40–47 tok/s on flagship). |
| **7** | **TTS Phrase Buffering & Synthesis ($L_{\text{tts\_synth}}$)** | First generated token (`T_first_token`) -> First phrase boundary dispatched via `tts.speak()` (`T_tts_speak_call`) | `UtteranceProgressListener.onStart(firstUtteranceId)` callback from `com.google.android.tts` (`T_tts_on_start`) | `StreamingTTSManager.kt:256–346`. Consists of **phrase accumulation wait** ($T_{\text{tts\_speak\_call}} - T_{\text{first\_token}}$: waiting for punctuation, 3 words, or 20 chars) + **TTS engine synthesis latency** ($T_{\text{tts\_on\_start}} - T_{\text{tts\_speak\_call}}$). |
| **8** | **Audio Playback Startup ($L_{\text{audio\_out}}$)** | `UtteranceProgressListener.onStart(firstUtteranceId)` (`T_tts_on_start`) | First acoustic waveform emitted from phone speaker/headset jack (`T_first_acoustic_sound`) | OS AudioFlinger / HAL buffer latency (`FLAG_LOW_LATENCY` enabled on API $\ge 34$ in `StreamingTTSManager.kt:36–43`). Requires external loopback / dual-mic acoustic capture to measure exactly. |
| **9** | **End of User Speech to First Translated Audio ($L_{\text{E2E\_first\_audio}}$)** | Last spoken audio frame (`T_last_speech_frame`) | First audible translated speech (`T_tts_on_start` software proxy, or `T_first_acoustic_sound` hardware truth) | **The primary conversational responsiveness metric:**<br/>$$L_{\text{E2E\_first\_audio}} = L_{\text{endpoint}} + L_{\text{setup}} + L_{\text{TTFT}} + L_{\text{first\_phrase\_buf}} + L_{\text{tts\_synth}} + L_{\text{audio\_out}}$$<br/>Currently estimated at **`1,350 ms – 2,400 ms`** (`[HYPOTHESIS]` based on `400–800 ms` VAD + `700–1300 ms` TTFT + `100–200 ms` 3-word accumulation + `150–250 ms` TTS synthesis). |
| **10** | **Complete Conversational Turn ($L_{\text{turn\_total}}$)** | First spoken audio frame of user utterance (`T_first_speech_frame`) | Final TTS utterance completion (`UtteranceProgressListener.onDone(lastUtteranceId)`) and mic unmute (`T_mic_unmuted`) | Total wall-clock time from when Speaker A starts talking until Speaker B finishes hearing the translation and the mic is ready for Speaker B's reply. |

---

### 3.3 Latency Behavior Across Operating Regimes (`[CODE-VERIFIED]`, `[DOC-VERIFIED]`)

1. **Cold vs. Warm Execution:**
   - **Cold:** `TranslationEngine.initialize()` must mmap `2.58 GB` from flash storage, initialize XNNPACK threadpools (`libLiteRt.so`), and (if GPU is enabled) compile OpenCL kernels (`libLiteRtClGlAccelerator.so`). Without `cacheDir` set (`TranslationEngine.kt:98`), delegate initialization repeats on every process start. Furthermore, on the first inference turn after initialization, the OS page cache faults in the `89.70 MiB` audio encoder (`Submodel 2`), `9.02 MiB` audio adapter (`Submodel 3`), and `780.38 MiB` decoder (`Submodel 8`).
   - **Warm:** Subsequent turns hit warm OS page caches and pre-allocated XNNPACK/OpenCL workspaces, but because `TranslationEngine` creates and destroys a fresh `Conversation` on every turn (`TranslationEngine.kt:175, 223`), the system prompt (`"You are a translation assistant..."`, ~25 tokens) is **re-tokenized and re-prefilled from scratch on every single turn**.
2. **Short (<1.5 s) vs. Long (1.5 s–3.0 s) vs. Continuous (>3.0 s) Utterances:**
   - **Short utterances (<1.5 s / <75 frames):** Ironically suffer a **longer VAD wait (`800 ms`)** than 1.5–3.0 s utterances (`400 ms`) due to `AudioRecorderHelper.kt:138–142` (`if (speechFrames >= 75) 20 else 40`)! Thus, saying *"Yes, thank you"* (0.9 s) incurs a full `800 ms` silence penalty before inference even starts.
   - **Medium utterances (1.5 s–3.0 s):** Trigger the `400 ms` adaptive VAD threshold and run as a single inference chunk.
   - **Continuous speech (>3.0 s / 96,000 bytes):** Hits `shouldForceFlush` (`AudioRecorderHelper.kt:134`) mid-sentence! This causes **two severe problems**:
     1. **Mid-word / mid-clause acoustic slicing:** Slicing at exactly 3.0 seconds splits words in half and deprives Gemma of syntactic context (especially for SOV languages like Japanese, Korean, Hindi, or German where the verb appears at the end of the clause).
     2. **Immediate drop of the second half of the sentence:** While Chunk 1 (`0.0–3.0 s`) is translating (taking ~1.0–1.5 s), the user continues speaking (`3.0–5.0 s`). If Chunk 2 finalizes while Chunk 1 is still in inference or while Chunk 1's TTS starts playing (`isMuted = true`), **Chunk 2 is completely discarded** (Finding F-06)!
3. **First Turn vs. Sustained Conversation (Thermal & Memory Pressure):**
   - Running both the 300M audio encoder and the 2.3B effective decoder on CPU (`Backend.CPU()`) saturates big/prime ARM cores on every turn. Over a 10–20 minute conversation, sustained prime-core utilization causes SoC thermal throttling, reducing CPU clock frequencies by 25–40% and degrading both prefill and decode throughput.
4. **CPU vs. GPU vs. NPU Behavior (`[DOC-VERIFIED]` from [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)):**
   - **Audio Encoder (`Submodels 2, 3, 4`):** Strictly constrained to `CPU` in the `.litertlm` flatbuffer metadata (`backend_constraint = "cpu"`).
   - **Main Decoder (`Submodel 8`, `tf_lite_prefill_decode`):**
     - On **CPU** (S26 Ultra, 4 threads): Prefill = **557 tok/s**, Decode = **46.9 tok/s**, CPU RAM = **1,733 MB**.
     - On **GPU** (S26 Ultra, ML Drift): Prefill = **3,808 tok/s** (**6.8× faster prefill**), Decode = **52.1 tok/s** (**1.11× faster decode**), CPU RAM = **676 MB** (**1,057 MB less CPU RAM**).
     - On **NPU** (Qualcomm Dragonwing IQ8, requires separate 2,967 MB NPU-compiled `.litertlm` artifact): Prefill = **3,747 tok/s**, Decode = **31.7 tok/s**.

---

### 3.4 Ready-to-Use Instrumentation Plan (`[CODE-VERIFIED]`, `NOT EXECUTED`)

Because the current codebase lacks monotonic per-stage telemetry, add a lightweight `TurnTelemetry` data structure using `SystemClock.elapsedRealtimeNanos()` and LiteRT-LM's built-in `conversation.getBenchmarkInfo()` API (`BenchmarkInfo.class` verified in `litertlm-android-0.13.1.aar`):

```kotlin
// Proposed TurnTelemetry.kt (Instrumentation Plan — NOT EXECUTED)
package com.syncdialect.app

import android.os.SystemClock

data class TurnTelemetry(
    val turnId: String,
    val sourceLang: String,
    val targetLang: String,
    val pcmBytesSent: Int,
    val speechFrames: Int,
    val trailingSilenceFramesTrimmed: Int,
    val forcedFlush: Boolean,
    val tFirstSpeechNanos: Long,
    val tLastSpeechNanos: Long,
    val tChunkFlushedNanos: Long,
    var tEngineAdmittedNanos: Long = 0L,
    var tConvCreatedNanos: Long = 0L,
    var tInferStartNanos: Long = 0L,
    var tFirstTokenNanos: Long = 0L,
    var tFirstTtsSpeakCallNanos: Long = 0L,
    var tFirstTtsOnStartNanos: Long = 0L,
    var tDecodeCompleteNanos: Long = 0L,
    var tLastTtsOnDoneNanos: Long = 0L,
    var prefillTokens: Int = 0,
    var decodeTokens: Int = 0,
    var nativePrefillTokPerSec: Double = 0.0,
    var nativeDecodeTokPerSec: Double = 0.0,
    var nativeTtftSec: Double = 0.0
) {
    private fun ms(start: Long, end: Long): Long =
        if (start > 0 && end >= start) (end - start) / 1_000_000L else -1L

    fun toStructuredLog(): String = buildString {
        append("TURN_METRICS id=$turnId pair=$sourceLang->$targetLang ")
        append("audio_ms=${pcmBytesSent / 32} forced=$forcedFlush ")
        append("L2_endpoint_ms=${ms(tLastSpeechNanos, tChunkFlushedNanos)} ")
        append("L_queue_ms=${ms(tChunkFlushedNanos, tEngineAdmittedNanos)} ")
        append("L_conv_ms=${ms(tEngineAdmittedNanos, tConvCreatedNanos)} ")
        append("L5_engine_ttft_ms=${ms(tInferStartNanos, tFirstTokenNanos)} ")
        append("L6_decode_ms=${ms(tFirstTokenNanos, tDecodeCompleteNanos)} ")
        append("L7a_phrase_buf_ms=${ms(tFirstTokenNanos, tFirstTtsSpeakCallNanos)} ")
        append("L7b_tts_synth_ms=${ms(tFirstTtsSpeakCallNanos, tFirstTtsOnStartNanos)} ")
        append("L9_speech_end_to_first_audio_ms=${ms(tLastSpeechNanos, tFirstTtsOnStartNanos)} ")
        append("L10_total_turn_ms=${ms(tFirstSpeechNanos, tLastTtsOnDoneNanos)} ")
        append("prefill_tok=$prefillTokens prefill_tps=${"%.1f".format(nativePrefillTokPerSec)} ")
        append("decode_tok=$decodeTokens decode_tps=${"%.1f".format(nativeDecodeTokPerSec)}")
    }
}
```

**Important Note on `ExperimentalFlags.enableBenchmark` (`[CODE-VERIFIED]`):**  
Disassembly of `Engine.initialize()` (`Engine.class:1202–1203`) shows that `LiteRtLmJni.nativeCreateEngine` reads `ExperimentalFlags.INSTANCE.getEnableBenchmark()`. To populate `conversation.getBenchmarkInfo()` (`lastPrefillTokenCount`, `lastDecodeTokenCount`, `lastPrefillTokensPerSecond`, `lastDecodeTokensPerSecond`, `timeToFirstTokenInSecond`), set `ExperimentalFlags.enableBenchmark = true` before calling `engine.initialize()`.

---

### 3.5 Concrete Latency Optimization Techniques, Applicability, and Trade-Offs

| Optimization Technique | Target Stage(s) | Mechanism & Code Change | Expected Benefit (`[HYPOTHESIS]` / `[DOC-VERIFIED]`) | Risks & When It Harms Correctness or Battery |
|---|---|---|---|---|
| **1. Hybrid Backend (`backend = Backend.GPU()`, `audioBackend = Backend.CPU(4)`) + `cacheDir`** | Stage 1 ($L_{\text{cold\_init}}$), Stage 4 ($L_{\text{prefill}}$), Stage 5 ($L_{\text{TTFT}}$), Stage 6 ($L_{\text{decode}}$) | Declare `libOpenCL.so` in `AndroidManifest.xml`; set `backend = Backend.GPU()`, `audioBackend = Backend.CPU(4)`, `cacheDir = context.cacheDir.path` in `EngineConfig`. | **`[DOC-VERIFIED]` on S26 Ultra:** Prefill speed jumps **6.8×** (`557 -> 3,808 tok/s`), Decode speed **+11%** (`46.9 -> 52.1 tok/s`), CPU RAM drops by **1.05 GB**. **`[HYPOTHESIS]`:** Saves **`200–450 ms`** per turn on SyncDialect prefill + decode. | Some low-end Mali/PowerVR GPUs have buggy OpenCL drivers; must wrap `Backend.GPU()` in `try/catch` and fall back cleanly to `Backend.CPU(4)`. |
| **2. Trim Trailing Silence Frames Before Inference** | Stage 3 ($L_{\text{audio\_enc}}$), Stage 4 ($L_{\text{prefill}}$) | During endpoint wait (`20–40` silence frames = `400–800 ms`), `AudioRecorderHelper` writes all silence frames into `chunkStream`. Keep only 6–8 trailing frames (`120–160 ms`) and strip the remaining `12–32` silence frames (`240–640 ms`) before `pcmToWav`. | **`[HYPOTHESIS]`:** Reduces audio duration fed into USM/Conformer encoder by **15–40%** on short turns, saving **`80–200 ms`** of CPU audio encoding and LLM prefill time. | Zero accuracy risk if `120–160 ms` tail pad is preserved after the last speech frame. |
| **3. Two-Tier Adaptive Endpointing + Manual "Translate Now" Release** | Stage 2 ($L_{\text{endpoint}}$), Stage 9 ($L_{\text{E2E\_first\_audio}}$) | Current code waits `800 ms` for `<1.5 s` speech and `400 ms` for `>=1.5 s` speech. Add a mid-tier: `500 ms` (25 frames) for `0.6 s–1.5 s` speech (`30–74` frames) when energy returns to noise floor for 20 consecutive frames, plus a hold-to-talk release / "Translate Now" button (`0 ms` endpoint wait). | **By construction:** Saves **`300 ms`** on `0.6–1.5 s` conversational replies (`800 ms -> 500 ms`), and **`400–800 ms`** when using push-to-talk release (`0 ms` wait). | Dropping silence threshold below `400 ms` in hands-free mode splits mid-sentence hesitations (`"uh"`, clause pauses), degrading translation quality for verb-final (SOV) languages. |
| **4. Raise Max Chunk Duration from `3.0 s` (`96,000 B`) to `6.0–8.0 s` with Soft Clause Endpointing** | Stage 2, Translation Quality | Current `maxChunkBytes = 96000` (`3.0 s`) force-flushes mid-word during normal sentences (`AudioRecorderHelper.kt:73, 134`). Instead, look for a short pause (`>= 200 ms` = 10 frames of silence) once speech exceeds `3.5 s`, and only hard-flush at `8.0 s` (`256,000 B`). | Prevents mid-word acoustic slicing and avoids dropping the second half of a 4-second sentence while the first 3 seconds are translating. | Longer chunks (`5–8 s`) take ~`200–400 ms` longer to encode in `tf_lite_audio_encoder_hw` than `3 s` chunks, trading raw chunk latency for complete, accurate sentences. |
| **5. Bypass Base64 + JSON Serialization via `Session` + `InputData.Audio` (or In-Place WAV Header)** | Stage 4/5 ($L_{\text{setup}}$) | Replace `Conversation.sendMessageAsync(Content.AudioBytes)` (which Base64-encodes `wavData` into a 128 KB JSON string) with `Engine.createSession()` + `InputData.Audio(wavData)` and in-place 44-byte WAV header in `AudioRecorderHelper`. | **`[HYPOTHESIS]`:** Eliminates 3 large byte/string copies + Gson serialization/parsing per turn (~`10–30 ms` saved, plus significantly reduced GC pressure on low-RAM devices). | Requires formatting the Gemma 4 prompt string (`<|turn>system\n...<turn|>\n<|turn>user\n...`) directly in `TranslationEngine.kt`. |
| **6. Disable Gemma 4 Thinking Mode Explicitly + Concise System Prompt** | Stage 4 ($L_{\text{prefill}}$), Stage 6 ($L_{\text{decode}}$) | Pass `extraContext = mapOf("enable_thinking" to false)` and shorten prompt from 34 tokens to 16 tokens: `"Translate this $sourceLang speech to $targetLang. Output only the translation."` | Prevents accidental `<|think|>` reasoning token generation and reduces system prompt prefill tokens by ~50%. | None. |
| **7. Direct Background TTS Token Dispatch & Smarter First-Phrase Boundary** | Stage 7 ($L_{\text{tts\_synth}}$) | Call `ttsEngine.processToken(clean)` directly on the `Dispatchers.IO` inference thread before `runOnUiThread`, and trigger first-phrase synthesis on punctuation OR `>= 3 words` (Latin/spaced scripts) OR `>= 8 chars` (CJK scripts: `zh`, `ja`, `ko`). | **`[HYPOTHESIS]`:** Saves **`15–40 ms`** of Main Looper contention and fixes CJK first-phrase buffering (currently CJK waits for `20` full characters before speaking because CJK has no spaces!). | Speaking after only 1–2 words sounds robotic due to broken prosody; 3 words (spaced) or 6–8 characters (CJK) or punctuation is the minimum safe prosodic unit. |
| **8. Warm TTS Engine & Pre-Synthesize to Memory/File if Needed** | Stage 7 ($L_{\text{tts\_synth}}$), Stage 8 ($L_{\text{audio\_out}}$) | Keep `com.google.android.tts` audio session warm or use `AudioAttributes.FLAG_LOW_LATENCY` across all API levels (currently gated on `SDK_INT >= 34` in `StreamingTTSManager.kt:36`, even though `FLAG_LOW_LATENCY = 0x100` was added in API 21!). | **`[CODE-VERIFIED]`:** `AudioAttributes.FLAG_LOW_LATENCY` exists since Android 5.0 (API 21). Removing the `SDK_INT >= 34` gate enables low-latency audio track routing on Android 7–13 (API 24–33) devices! | None (`FLAG_LOW_LATENCY` is a standard public constant since API 21). |

---

## Section 6: Quantization, Storage, and Runtime Memory Analysis

### 6.1 Complete Storage & Memory Footprint Breakdown (`[CODE-VERIFIED]`, `[DOC-VERIFIED]`)

We report each storage and memory metric separately, distinguishing code/binary-verified numbers from documented benchmarks and analytical estimates:

| Metric | Current SyncDialect (`v2`, `f4ffa9a`) | Verification Source & Breakdown |
|---|---|---|
| **1. Model Download Size** | **`2,588,147,712 bytes`** (`2.588 GB` / `2,468.25 MiB`) + **`~30 MB per language`** (unused ML Kit packs) | **`[CODE-VERIFIED]`** (`ModelDownloadManager.kt:26`, `./gemma-4-E2B-it.litertlm` on disk). |
| **2. APK / AAB Size** | - **Release AAB:** `62,332,140 bytes` (`62.3 MB` / `59.4 MiB`)<br/>- **Release APK:** `62,439,449 bytes` (`62.4 MB` / `59.5 MiB`)<br/>- **Debug APK:** `95,024,494 bytes` (`95.0 MB` / `90.6 MiB`) | **`[CODE-VERIFIED]`** (`app/build/outputs/`). Inflated because `isMinifyEnabled = false` (`app/build.gradle.kts:29`) and `com.google.mlkit:translate:17.0.3` bundles `libtranslate_jni.so` across 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`). |
| **3. Installed Storage Footprint** | **`~2.72 GB – 2.80 GB`** (steady state)<br/>**`~5.31 GB`** (peak during model update/re-download!) | **`[CODE-VERIFIED]`:** Installed APK + extracted `.so` libs (`useLegacyPackaging = true` extracts ~23 MB of `.so` files on disk) + `2.588 GB` `.litertlm` in `filesDir` + ~60 MB for 2 ML Kit language packs. During model re-download, `ModelDownloadService.kt:96, 208` writes `gemma-4-E2B-it.litertlm.tmp` (`2.588 GB`) alongside any existing `gemma-4-E2B-it.litertlm` (`2.588 GB`) before deleting the old file! |
| **4. Peak Runtime RAM (PSS / RSS)** | - **CPU Backend (Current):** **`~1,733 MB – 1,950 MB`** native RSS + ~120 MB JVM/Compose heap<br/>- **GPU Backend (Proposed):** **`~676 MB – 850 MB`** CPU RSS + **`~850 MB`** GPU/OpenCL buffer + ~99 MB audio encoder | **`[DOC-VERIFIED]`** (`litert-community/gemma-4-E2B-it-litert-lm` S26 Ultra benchmark reports `1,733 MB` CPU RSS on CPU backend and `676 MB` CPU RSS on GPU backend for text; add `98.72 MiB` for `tf_lite_audio_encoder_hw` + `tf_lite_audio_adapter` when audio is active). |
| **5. KV-Cache Memory** | **`~28 MB – 70 MB`** at `maxNumTokens = 2048` (FP16) | **`[CODE-VERIFIED]` + Analytical Math:** Submodel 8 (`tf_lite_prefill_decode`) contains exactly **30 KV-cache tensors** (`kv_cache_k_0..14` and `kv_cache_v_0..14` across the 35 layers due to Gemma 4's shared-KV global attention + 512-token sliding window local attention). Even if all 15 KV groups stored a full 2,048 tokens at `head_dim = 256`, $15 \times 2 \times 2048 \times 256 \times 2\text{ bytes (FP16)} \approx 31.5\text{ MB}$ (and even less for layers capped at the 512-token sliding window). |
| **6. Model Load Time ($L_{\text{cold\_init}}$)** | **`~1.5 s – 4.5 s`** on CPU (mmap); up to **`6–10 s`** on first uncached GPU compilation | **`[DOC-VERIFIED]`** (LiteRT-LM docs note up to 10s without `cacheDir`, dropping significantly on 2nd load when `cacheDir` is set). **`[MISSING-DATA]`:** Exact load time on target phones must be logged via `TurnTelemetry`. |

---

### 6.2 Why Weight Storage Does Not Equal Runtime RAM in Gemma 4 E2B (`[CODE-VERIFIED]`, `[DOC-VERIFIED]`)

A naive parameter calculation for a 5.1B parameter model at Int4/Int8 would suggest `2.5–5.0 GB` of active runtime RAM. However, binary inspection of `gemma-4-E2B-it.litertlm` (`Section 1.2` of `CURRENT_ARCHITECTURE_AND_AUDIT.md`) explains why active working memory is much smaller:

1. **Per-Layer Embeddings (`1,324.04 MiB` combined across `Submodel 0` + `Submodel 1`) Are Memory-Mapped (`mmap`), Not Resident in Working RAM:**
   - `Submodel 0` (`tf_lite_embedder`, `99.02 MiB`) and `Submodel 1` (`tf_lite_per_layer_embedder`, `1,225.02 MiB`) account for **53.6% of the entire 2.58 GB file**.
   - During inference on a turn with ~100 input tokens and ~30 output tokens, only ~130 rows out of the 262,144-row vocabulary table are looked up. LiteRT-LM leaves `tf_lite_per_layer_embedder` memory-mapped (`mmap`) on flash storage and faults in only the specific 4 KB OS memory pages containing the active token IDs!
2. **Unused Multimodal Encoders (`260.60 MiB`) Are Loaded On-Demand:**
   - `Submodel 5` (`tf_lite_vision_encoder`, `213.77 MiB`), `Submodel 6` (`tf_lite_vision_adapter`, `4.53 MiB`), and `Submodel 7` (`tf_lite_end_of_vision`, `0.02 MiB`) are **never initialized** because SyncDialect never passes `Content.ImageBytes` / `InputData.Image`.
   - `Submodel 9` (`tf_lite_mtp_drafter`, `42.28 MiB`) is only executed if speculative decoding is enabled.
3. **Active Weights During SyncDialect Speech Translation (`~879.1 MiB`):**
   - `Submodel 2` (`tf_lite_audio_encoder_hw`): `89.70 MiB` (CPU)
   - `Submodel 3` (`tf_lite_audio_adapter`): `9.02 MiB` (CPU)
   - `Submodel 8` (`tf_lite_prefill_decode` 35-layer decoder, mixed 2/4/8-bit quantized): `780.38 MiB` (CPU or GPU)
   - Total active neural network weights = **`879.10 MiB`** (~0.92 GB), plus XNNPACK/ML Drift activation buffers and KV cache (`~28–70 MB`).

---

### 6.3 Supported Quantization & Model-Size Reduction Options

#### Option Q1: Strip Unused Vision & MTP Submodels from `gemma-4-E2B-it.litertlm` (`[CODE-VERIFIED]`)
- **Evidence:** As proven by our `TFL3` flatbuffer scan of `gemma-4-E2B-it.litertlm`:
  - `tf_lite_vision_encoder` (`Submodel 5`) = `224,149,504 bytes` (`213.77 MiB`)
  - `tf_lite_vision_adapter` (`Submodel 6`) = `4,751,360 bytes` (`4.53 MiB`)
  - `tf_lite_end_of_vision` (`Submodel 7`) = `16,384 bytes` (`0.02 MiB`)
  - `tf_lite_mtp_drafter` (`Submodel 9`) = `44,335,104 bytes` (`42.28 MiB`)
- **Savings:** Re-exporting or repackaging `gemma-4-E2B-it.litertlm` without the vision submodels (and optionally without the MTP drafter if speculative decoding is not used) reduces the download and on-disk file size by **`228.9 MB` to `273.2 MB`** (from `2,588 MB` down to **`~2,315 MB`**) with **zero change to audio or text weights** and **zero translation quality impact**.

#### Option Q2: Enable R8 Minification, Resource Shrinking, ABI Splits, and Remove Unused `com.google.mlkit:translate` (`[CODE-VERIFIED]`)
- **Evidence:** `app/build.gradle.kts:29` sets `isMinifyEnabled = false` on `release` builds, and line `103` includes `com.google.mlkit:translate:17.0.3` (which is never called during translation).
- **Savings:**
  1. Removing `com.google.mlkit:translate:17.0.3` eliminates `libtranslate_jni.so` and saves users **`~30 MB per language`** in wasted background downloads.
  2. Enabling `isMinifyEnabled = true` and `isShrinkResources = true` in `app/build.gradle.kts` (with keep rules for `com.google.ai.edge.litertlm.**` and `com.google.gson.**`) and publishing via Android App Bundle (AAB, which delivers only `arm64-v8a` to ARM64 phones rather than both `arm64-v8a` and `x86_64`) will reduce user Play Store app download size from **`~62.4 MB` to `~22–26 MB`**.

#### Option Q3: Evaluate Speculative Decoding (`tf_lite_mtp_drafter`) already inside `gemma-4-E2B-it.litertlm` (`[CODE-VERIFIED]`, `[DOC-VERIFIED]`)
- **Evidence:** Our binary scan of `gemma-4-E2B-it.litertlm` (`Submodel 9` at offset `2,543,812,608`) confirmed that the local model file **already contains the `42.28 MiB` `tf_lite_mtp_drafter` submodel**, and `ExperimentalFlags.class` in `litertlm-android-0.13.1.aar` exposes `ExperimentalFlags.enableSpeculativeDecoding = true`!
- **Documented Benchmark (`[DOC-VERIFIED]`, [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)):**
  - On S26 Ultra **GPU backend**, enabling speculative decoding increases decode speed from **51.5 tok/s to 66.5–91.7 tok/s** (up to **1.78× faster decoding**) with only +13 to +26 MB of additional memory (`791 MB -> 804–817 MB`).
  - On **CPU backend**, speculative decoding yields a modest gain on summarization/rewriting (`40.7 -> 47.5 tok/s`) but slightly degrades free-form generation (`40.7 -> 38.1 tok/s`) and increases CPU RAM by ~100–220 MB (`1,362 -> 1,582 MB`).
- **Recommendation:** Test `ExperimentalFlags.enableSpeculativeDecoding = true` **specifically when `backend = Backend.GPU()` is active**. Do not enable it on the CPU backend.

#### Option Q4: Why Further Quantization of `gemma-4-E2B-it.litertlm` Is Not Recommended (`[CODE-VERIFIED]`)
- **Why smaller weight files do not guarantee faster execution:** `gemma-4-E2B-it.litertlm` already uses Google's specialized mixed **2-bit, 4-bit, and 8-bit** quantization (`Submodel 8`, the entire 35-layer decoder, is already compressed to `780.38 MiB`—roughly ~3.3 bits per parameter on average!).
- Attempting to quantize the `1,225 MiB` `tf_lite_per_layer_embedder` table further or force uniform 2-bit quantization across attention projections would:
  1. Severely degrade multilingual vocabulary recall (especially on non-Latin scripts and named entities where PLE embeddings carry token-specific lexical knowledge).
  2. Provide **zero runtime RAM reduction** for the PLE table because it is already memory-mapped (`mmap`) and not resident in RAM.
  3. Potentially **slow down execution** if custom sub-4-bit dequantization kernels are not hardware-accelerated by XNNPACK / ML Drift on the target SoC.

#### Option Q5: Dynamic Model Downloads vs. Language-Specific Packs (`[HYPOTHESIS]`)
- **Initial App Footprint:** SyncDialect already keeps the 2.58 GB `.litertlm` file out of the APK/AAB via on-demand download (`ModelDownloadService`). However, migrating to **Google Play Asset Delivery (`install-time` or `fast-follow` / `on-demand` asset packs)** or keeping the Hugging Face CDN download with a **Two-Tier Model Selector** in Onboarding/Settings would allow users on low-storage phones to choose:
  - **Standard Mode (High Quality, 35+ Languages, Direct Audio-to-Text):** Gemma 4 E2B (`~2.3–2.58 GB` download, requires $\ge 6\text{ GB}$ device RAM).
  - **Lite Mode (Low Storage / Budget Phones):** Cascaded Streaming ASR (`sherpa-onnx` Whisper-tiny/base or Moonshine, `~40–75 MB`) + On-Demand Bilingual Translation Pack (ML Kit Translate or Opus-MT, `~30–50 MB` per language pair) = **`~100–125 MB` total download** (`20×` smaller download and `10×` lower RAM footprint, at the cost of cascaded ASR $\to$ MT error propagation).
