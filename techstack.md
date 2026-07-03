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

#### 1. Audio Recording & Voice Activity Detection (VAD)
Located in [AudioRecorderHelper.kt](file:///C:/Users/mcmur/Desktop/AuraTranslate/app/src/main/java/com/syncdialect/app/AudioRecorderHelper.kt).
- `sampleRate` = 16000
- `silenceThresholdRMS` = 2000.0 (Tolerance for background noise)
- `speechThresholdRMS` = 3000.0 (Minimum volume to be considered active speech)
- `maxSilenceFrames` = 25 (Waits exactly 1.0 second of silence before finalizing a sentence to prevent cutting off the user mid-thought)
- `speechFrames` requirement = Must have >= 3 frames of active speech to process the chunk.
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
