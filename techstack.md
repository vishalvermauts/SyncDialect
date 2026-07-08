# SyncDialect (Formerly AuraVoice Live)
## Complete Tech Stack and Configuration

### Core Technology Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Local LLM Engine**: Google LiteRT (formerly MediaPipe LLM Inference API) `com.google.ai.edge.litertlm`
- **Model**: Gemma 2B IT (Instruction Tuned), Int8 Quantization
- **Text-to-Speech (TTS)**: Android Native `TextToSpeech` (`android.speech.tts.TextToSpeech`)
- **Audio Capture**: Android `AudioRecord` (16kHz, Mono, 16-bit PCM)
- **OCR / Vision**: Google ML Kit (`com.google.mlkit:text-recognition`)

### Critical "Known Good" Settings (DO NOT CHANGE)
These settings were meticulously tuned to eliminate hallucinations, acoustic feedback loops, and premature audio cutoff.

#### 1. Audio Recording, WebRTC VAD, & Security Hardening
Located in [AudioRecorderHelper.kt](file:///C:/Users/mcmur/Desktop/AuraTranslate/app/src/main/java/com/syncdialect/app/AudioRecorderHelper.kt).
- `sampleRate` = 16000
- **Voice Activity Detection (VAD)**: Utilizes Google's WebRTC VAD (`com.konovalov.vad.webrtc.Vad`) set to `Mode.VERY_AGGRESSIVE` for ultra-low latency silence detection.
- **Audio Framing**: Processes audio in exact 20ms frames (320 samples / 640 bytes).
- **Memory Hardening**: Raw audio buffers are explicitly zeroed out (`Arrays.fill(..., 0)`) immediately after being yielded to prevent sensitive voice data from lingering in memory.
- `maxSilenceFrames` = `prefs.vadWaitFrames` (Configurable wait before finalizing a sentence to prevent cutting off the user mid-thought)
- `speechFrames` requirement = Must have >= 5 frames (100ms) of active speech to process the chunk.
- **Acoustic Feedback Protection**: The `isMuted` flag is dynamically toggled by the TTS Engine to ignore microphone input while the app is speaking its translation. This entirely prevents infinite translation loops.

#### 2. LLM Translation Engine Configuration
Located in [TranslationEngine.kt](file:///C:/Users/mcmur/Desktop/AuraTranslate/app/src/main/java/com/syncdialect/app/TranslationEngine.kt).
- `topK` = 1
- `topP` = 1.0
- `temperature` = 0.1
- `seed` = 0
- `maxNumTokens` = 2048 (Needed because audio input tokens easily exceed standard 512 limits)
- **Memory/State**: STATELSSS. *Conversation memory is explicitly disabled.* A new `Conversation` object is created and subsequently `close()`'d for *every single audio chunk*. This is mandatory to prevent audio-modality hallucinations where Gemma repeats previous history outputs over and over again.

#### 3. Core Features & Flows
- **Live Voice Translation**: Streams real-time audio from microphone, chunks it via RMS-based VAD, converts PCM to WAV on the fly, and feeds it directly into the LiteRT audio-multimodal input for Gemma.
- **Background Processes**: Model weights (2.2GB) are handled by a custom [ModelDownloadManager.kt](file:///C:/Users/mcmur/Desktop/AuraTranslate/app/src/main/java/com/syncdialect/app/ModelDownloadManager.kt) leveraging Android's `DownloadManager` API.
- **Text-to-Speech Mitigation**: The [StreamingTTSManager.kt](file:///C:/Users/mcmur/Desktop/AuraTranslate/app/src/main/java/com/syncdialect/app/StreamingTTSManager.kt) dynamically sets flags to mute the mic while playing translation output.

### UI Theme Configuration
Located in [MainActivity.kt](file:///C:/Users/mcmur/Desktop/AuraTranslate/app/src/main/java/com/syncdialect/app/MainActivity.kt).
- App Name: `SyncDialect`
- Top Background: `#050507`
- Bottom Background: `#14141C`
- Accents: Coral (`#E8593C`), Deep Coral (`#C2402A`)
- Pill Backgrounds: `White.copy(alpha = 0.08f)`

---

## 📋 Troubleshooting Log — 2026-07-03

### Symptoms Reported
- Latency spiked from historical ~600–700ms → **5042ms–7075ms**
- Model producing empty/silent translations (`Chars: 0`)
- UI showing many blank `"..."` chat bubbles
- Occasional hallucinations (model repeating past translations)

---

### Root Cause Investigation (via Logcat Analysis)

#### Bug 1 — Shadowed `var conv` variable in `TranslationEngine.kt` [CRITICAL]
The `translateAudioStreaming` function declared `var conv: Conversation? = null` **twice** (lines 165 and 169). The inner declaration shadowed the outer one. As a result, the `finally { conv?.close() }` block always closed `null`, **leaking every Conversation object** into the engine. This caused progressive resource exhaustion and increasing latency with every translation call.
**Fix**: Refactored to `val conv: Conversation` (non-nullable), set once via `if/else` block, always closed in `finally`.

#### Bug 2 — Deadlock between `translationMutex` and `prewarmMutex` [CRITICAL]
The previous prewarming implementation held `translationMutex.withLock` while also trying to acquire `prewarmMutex.withLock`. The background `prewarmConversation()` coroutine held `prewarmMutex` while calling `engine!!.createConversation()` which uses the same engine internal lock. This caused the prewarm to be effectively serialized with translations rather than running ahead of them.
**Fix**: Replaced both mutexes with a single **lock-free `AtomicReference<PrewarmedSlot?>`**. Claims and swaps are done via `getAndSet(null)` — no blocking, no deadlock possible.

#### Bug 3 — Parallel translation race condition [CRITICAL]
`MainActivity` launched a new `lifecycleScope.launch(Dispatchers.IO)` for **every VAD chunk**, without checking if a translation was already in progress. Multiple coroutines queued on the same `translationMutex`, each waiting for the engine's internal lock sequentially. 4 queued translations × 1.5s each = **6s+ latency**.
**Fix**: Added `tryStartTranslation(): Boolean` using `AtomicBoolean.compareAndSet(false, true)`. Chunks that arrive while a translation is running are **dropped immediately** before a coroutine is even launched. Only one translation runs at a time.

#### Bug 4 — GPU backend attempted on CPU-only model [MINOR]
Every cold start tried `Backend.GPU()` first, received `INVALID_ARGUMENT: Audio backend constraint mismatch. Model requires one of [cpu]`, then fell back to CPU. This wasted ~130ms on startup and polluted logcat.
**Fix**: Removed GPU attempt entirely. Now initializes directly with `Backend.CPU()`.

#### Bug 5 — Unbounded VAD chunk size (48s monster chunks)
With no upper bound on audio chunk size, continuous speech for 30+ seconds produced a single 1.5MB chunk. Processing 48s of audio takes 15s+ of inference time.
**Fix**: Added `maxChunkBytes = 96000` (3 seconds). Chunks auto-flush at 3s even if VAD hasn't detected silence.

#### Bug 6 — Empty `"..."` UI rows for silent chunks
The UI pre-added a `ChatMessage("", ...)` placeholder for every VAD chunk before inference started. When `Chars: 0` (silence), the model produced no tokens, leaving the empty placeholder permanently visible as `"..."`.
**Fix**: Removed pre-adding. The message row is now created **lazily on the first token**. Silent chunks leave zero UI trace.

---

### Final Configuration — Post Fix (2026-07-03)

#### Model
| Setting | Value | Notes |
|---|---|---|
| Model file | `gemma-4-E2B-it.litertlm` | Gemma 4 E2B Instruction-Tuned, audio-multimodal |
| Backend | **CPU only** | GPU not supported by this model (will throw `INVALID_ARGUMENT`) |
| `maxNumTokens` | `2048` | Audio tokens exceed default 512 limit |
| `topK` | `1` | Greedy decoding — deterministic, no hallucination drift |
| `topP` | `1.0` | |
| `temperature` | `0.1` | Near-zero temperature = faithful translation, minimal creativity |
| `seed` | `0` | |

#### VAD / Audio Chunking
| Setting | Value | Notes |
|---|---|---|
| VAD Mode | `VERY_AGGRESSIVE` | Aggressive silence detection |
| Frame size | 320 samples (20ms) | WebRTC standard |
| Sample rate | 16,000 Hz | |
| `vadWaitFrames` | `40` (default) = 800ms | How long to wait after silence before finalizing chunk |
| `minSpeechFrames` | `10` = 200ms | Minimum confirmed speech required to pass chunk to model |
| `minChunkBytes` | `16,000` = 1 second | Pad/discard chunks shorter than this |
| `maxChunkBytes` | `96,000` = 3 seconds | Auto-flush if speech is continuous beyond this point |
| RMS energy gate | `> 500,000` (~707 RMS) | Skip near-silence chunks even if VAD claims "speech" |

#### Translation Engine Architecture
| Property | Value |
|---|---|
| Stateless? | **YES** — new `Conversation` per chunk, no history retained |
| Concurrency model | `AtomicBoolean` tryLock — at most 1 translation at a time |
| Prewarming | `AtomicReference<PrewarmedSlot>` — next `Conversation` pre-created in background while user speaks |
| Prewarm triggered | On engine init, after each translation, on language change |
| UI placeholder | Lazy — message row only added on first real token |

#### System Prompt
```
You are a translation assistant. Strictly translate the audio to {targetLang}.
Output ONLY the translation. Do not answer questions.
```

---

### Measured Latency Results (Post-Fix)

Testing device: **Samsung SM-S928B (Galaxy S28, Android 16)**

| Metric | Before Fix | After Fix |
|---|---|---|
| Reported latency | 5042ms – 7075ms | **700ms – 1300ms** |
| Empty `Chars: 0` calls | ~50% of all calls | Reduced significantly via RMS gate |
| Parallel translations | Up to 4 simultaneous | Always exactly 1 |
| GPU error on startup | Yes (every run) | Eliminated |
| Chunk size | Up to 1,557,760 bytes (48s) | Capped at 96,000 bytes (3s) |
| Empty UI rows | Many | Zero |

**Typical TTFT (Time to First Token):** `700ms – 1300ms` ✅

---

### Detailed Configuration Comparison (Old vs. New)

#### 1. Audio Processing & VAD (Voice Activity Detection)
| Setting | Previous Config | Today's Config | Impact |
| :--- | :--- | :--- | :--- |
| **Minimum Speech** | `5 frames (100ms)` | `10 frames (200ms)` | Ignores tiny noises/coughs so the model doesn't waste time on non-speech. |
| **Max Chunk Size** | `None (Infinite)` | `96,000 bytes (3s)` | **Critical:** Prevents massive 48-second audio chunks from building up. Forces the model to translate in 3-second snappy blocks. |
| **Energy/Silence Gate** | `None` | `RMS > 500,000` | **Critical:** Drops near-silent chunks *before* they reach the model. Eliminated the frequent `Chars: 0` model calls that wasted 5s each. |

#### 2. Concurrency & Pre-warming (The Deadlocks & Queues)
| Setting | Previous Config | Today's Config | Impact |
| :--- | :--- | :--- | :--- |
| **Parallel Translations** | Allowed. Queued up `lifecycleScope` launches. | `tryStartTranslation()` (Drops chunk if busy) | **Critical:** Stopped 4+ translations from piling up on the engine. If the model is busy, new audio is dropped to keep the CPU 100% focused. |
| **Pre-warming Locks** | `prewarmMutex` + `translationMutex` | Lock-free `AtomicReference` | Eliminated the deadlock where pre-warming was actually blocking the main translation thread. |
| **Engine State** | Shadowed `conv` variable (Leaked memory) | Strict `finally { conv.close() }` | Fixed the memory leak that caused the model to get slower and slower after every sentence. |

#### 3. Model Engine & UI
| Setting | Previous Config | Today's Config | Impact |
| :--- | :--- | :--- | :--- |
| **Model Backend** | Attempted `GPU`, fell back to `CPU` | Strict `CPU` only | Saved ~130ms on every cold start and removed the `INVALID_ARGUMENT` crash log. `gemma-4-E2B` does not support GPU. |
| **UI Updates** | Pre-added `"..."` placeholder immediately | Lazy load on first real token | Prevented the UI from filling up with empty chat bubbles when silent audio was processed. |

---

### Key Learnings
1. `Conversation` in `litertlm` does NOT have a `reset()` method. It must be `close()`'d and recreated to prevent history hallucination.
2. `createConversation()` completes in **5–10ms**. The actual latency cost is inside `sendMessageAsync()` during KV-cache prefill of the system prompt.
3. `gemma-4-E2B-it.litertlm` is **CPU-only** for audio input. The GPU backend will always fail with `INVALID_ARGUMENT`.
4. The `translationMutex` alone is insufficient to prevent parallel translations if `lifecycleScope.launch` is called before the mutex is acquired. The lock must be checked **before** the coroutine is launched.

