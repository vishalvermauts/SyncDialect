# SyncDialect UI, Accessibility, and Conversation Experience Audit

**Audit Date:** April 19, 2026  
**Inspected Git Commit:** `f4ffa9a07bca671b822b83d71fef35d87b20dd32` (`v2` branch)  
**Inspected UI Source Files:** `MainActivity.kt`, `SettingsScreen.kt`, `OnboardingScreen.kt`, `TutorialScreen.kt`, `ui/main/MainScreen.kt`, `ui/main/MainScreenViewModel.kt`.

---

## Section 8: Comprehensive UI & Conversation Experience Review

Every finding below is backed by direct code evidence in the repository (`[CODE-VERIFIED]`) without inventing unverified UI issues.

---

### 8.1 Perceived Responsiveness vs. Actual Processing Speed

In a real-time voice translator, **perceived latency** and **measured acoustic latency ($L_{\text{E2E\_first\_audio}}$)** diverge whenever the user lacks visual confirmation that:
1. Their speech is actively being picked up by the microphone (`Listening` with real audio-reactive waveform),
2. The endpoint has triggered and the AI is translating their turn (`Translating...`), and
3. The app is speaking the translation (`Speaking...`, microphone muted).

#### Code-Verified Flaws in Current Visual Feedback (`[CODE-VERIFIED]`)
1. **Static Random Waveforms Instead of Real Audio-Reactive Feedback:**
   - In `MainActivity.kt:683–706` (`MiniWaveform`) and `lines 708–729` (`BackgroundWaveform`), the waveform heights are generated using `Random.nextFloat()` (`List(barCount) { Random.nextFloat() * 0.6f + 0.4f }`) and **never respond to actual microphone amplitude or VAD speech detection**!
   - When the user speaks into the microphone, the background waveform is completely static, and the mic button merely pulses on a fixed 800 ms timer (`MainActivity.kt:757–761`) whether the user is speaking, silent, or **even when the microphone is muted (`audioRecorderHelper.isMuted == true`) during TTS playback**!
2. **Zero Visual Distinction Between `Listening`, `Translating`, and `Speaking (Mic Muted)`:**
   - `MainActivity.kt:529` displays a static subtitle `"Live Translation Active"` at all times (even when `isRecording.value == false` or when the model is not downloaded!).
   - `BottomControls` (`MainActivity.kt:744`) only checks `if (isLoading) "INITIALIZING AI..." else if (isRecording) "TAP TO STOP" else "TAP TO SPEAK"`.
   - Because the message row is added lazily only after the **first token** is generated (`MainActivity.kt:381–383`, fixed earlier to avoid permanent `"..."` bubbles on silent chunks), there is a **1.1 s to 2.1 s dead zone** (`400–800 ms` VAD wait + `700–1,300 ms` TTFT) after the user stops speaking where **nothing happens on screen at all**. The user cannot tell whether the app heard them, is still waiting for silence, is running inference, or dropped their chunk!
3. **Artificial / Fixed Delays in Onboarding & Tutorial (`[CODE-VERIFIED]`):**
   - While `MainActivity.kt:108` now dismisses the system splash screen immediately (`splashScreen.setKeepOnScreenCondition { false }`), `OnboardingScreen.kt:57–60` still enforces a `delay(2500)` custom splash page (`page 0`), and `TutorialScreen.kt:27–36` forces the user to wait **`3,700 ms`** (`300 + 1200 + 1200 + 1000 ms`) before the `"Continue"` button even becomes visible (`buttonVisible = true`)!

---

### 8.2 State Machine & Turn-Taking Controls (`Listening` $\to$ `Translating` $\to$ `Speaking` $\to$ `Error`)

#### Proposed Explicit Turn State Machine
Replace the single boolean `isRecording: MutableState<Boolean>` (`MainActivity.kt:76`) with an explicit sealed/enum `ConversationState`:

```kotlin
enum class ConversationState {
    MODEL_NOT_DOWNLOADED,
    INITIALIZING_MODEL,
    IDLE_READY,
    LISTENING_SILENCE,      // Mic open, waiting for voice
    LISTENING_SPEECH,       // VAD confirmed active speech (show live RMS level + "Translate Now" button)
    TRANSLATING,            // Engine prefill/decode in flight (show transient shimmer status bar)
    SPEAKING_TRANSLATION,   // TTS playing, mic muted (show "Muted while speaking — Tap to Interrupt")
    ERROR_RECOVERABLE
}
```

#### Concrete UI Improvements for Turn Control
1. **Transient "Translating..." Activity Pill (Without Leaving Empty Chat Bubbles):**
   - Instead of inserting a permanent `ChatMessage("", ...)` into `messages` (which caused Bug 6 in `techstack.md:82`), display a transient footer item inside the `LazyColumn` whenever `state == TRANSLATING`. If the model emits `[SILENCE]` or `0` chars, the transient footer simply disappears; as soon as the first real token arrives, it transitions seamlessly into the new `ChatMessage` row.
2. **"Translate Now" (Manual Endpoint) Action During Speech:**
   - While `state == LISTENING_SPEECH`, show a secondary action chip (`"Done Speaking / Translate Now"`) or support **Push-to-Talk (Hold to Speak, Release to Translate)** alongside Continuous Hands-Free Mode. Releasing the button flushes `AudioRecorderHelper` immediately (`0 ms` VAD silence delay, saving `400–800 ms`).
3. **"Stop Speaking & Listen" (Barge-In) Action During TTS Playback:**
   - Currently, while TTS is speaking, `audioRecorderHelper.isMuted = true` (`MainActivity.kt:123`), but the UI still shows the pulsing coral Mic button with `"TAP TO STOP"`. If a user speaks while TTS is playing, their voice is silently discarded (`AudioRecorderHelper.kt:97–103`).
   - When `state == SPEAKING_TRANSLATION`, change the bottom status indicator to `"SPEAKING (MIC PAUSED) — TAP TO INTERRUPT"`, and provide a one-tap interrupt that calls `ttsEngine.stop()`, clears `audioRecorderHelper.isMuted = false`, and immediately resumes listening.
4. **Replay & Retry Controls on Transcript Rows:**
   - Currently, `TranscriptRow` (`MainActivity.kt:607–650`) only shows a `"Report"` flag button (`lines 627–644`) that launches an external email client (`mailto:edumapstu@gmail.com`). There is **no button to replay the TTS audio** of a translated message!
   - Add a `VolumeUp` (`Replay Audio`) icon button on every non-system `TranscriptRow` that invokes `ttsEngine.playText(msg.text)` so users in noisy environments (airports, streets, clinics) can replay a translation without speaking again.

---

### 8.3 Visible Source Transcripts vs. Target-Only Transcripts (And Streaming Translation Risks)

#### Current Code Reality (`[CODE-VERIFIED]`)
Look closely at `MainActivity.kt:326–332` and `lines 513–515`:
```kotlin
TranscriptRow(
    speakerLabel = if (msg.language == sourceLanguage.value || msg.isUser) "You (${msg.language}):" else if (msg.language == "System") "System:" else "Speaker (${msg.language}):",
    message = if (msg.text.isEmpty()) "..." else msg.text,
    waveformOnStart = msg.language == sourceLanguage.value || msg.isUser,
    isSystem = msg.language == "System"
)
```
Look at how `ChatMessage` is created during translation (`MainActivity.kt:383`):
```kotlin
messages.add(ChatMessage(clean, currentTargetLang, false, msgId))
```
- **Code-Verified Fact:** `isUser` is **never set to `true` anywhere in the codebase**! Every translated message is added with `language = currentTargetLang` and `isUser = false`.
- Consequently:
  1. **No source-language transcript is ever shown to the user.** The speaker never sees what words the model heard in `sourceLanguage`; they only see `"Speaker (Spanish): <translated text>"`.
  2. Even worse, if the user taps the **Swap Languages** button (`MainActivity.kt:284`) to reply in the other direction, `sourceLanguage.value` becomes `"Spanish"`, which suddenly causes all previous `"Spanish"` translation rows in `messages` to match `msg.language == sourceLanguage.value` (`line 328`) and **flip their label from `"Speaker (Spanish):"` to `"You (Spanish):"` and move their waveform to the opposite side of the screen**!

#### Fix for Transcript Attribution & Source Visibility
1. **Store Immutable Speaker Role & Language Pair on `ChatMessage`:**
   - Replace `val language: String, val isUser: Boolean` on `ChatMessage` (`MainActivity.kt:61–66`) with immutable fields captured at turn creation:
     ```kotlin
     data class ChatMessage(
         val id: String = UUID.randomUUID().toString(),
         val sourceLanguage: String,
         val targetLanguage: String,
         val targetLanguageTag: String,
         val sourceTranscript: String? = null,
         val translatedText: String,
         val isSystem: Boolean = false,
         val isTruncated: Boolean = false
     )
     ```
   - Never recompute `speakerLabel` or layout alignment by comparing `msg.language == sourceLanguage.value` against the *currently selected* mutable `sourceLanguage`!
2. **Should SyncDialect Show Partial Source Transcripts or Streaming Speculative Translation?**
   - **Why Streaming Speculative Translation While the User Is Still Speaking Is Dangerous:**
     In translation between languages with different word orders (e.g., English SVO $\leftrightarrow$ Japanese/Korean/Hindi SOV, or German subordinate clauses where negation *nicht* or the main verb appears at the very end of the sentence), translating partial 1-second fragments while the speaker is still talking forces the model to **guess the verb or negation before hearing it**.
     - *Text UI vs. Spoken TTS Asymmetry:* In a visual text box, a partial translation can be overwritten when the sentence finishes (though flickering text is distracting). In **spoken TTS (`StreamingTTSManager`)**, once `tts.speak()` speaks an early speculative guess out loud, **you cannot unspeak audio that has already been played through the loudspeaker**! If the speaker ends the sentence with *"not"* or *"cancelled"*, early speculative speech can communicate the exact opposite meaning.
   - **Recommended Design:**
     - **Do NOT speak speculative translations before clause/turn endpointing.** Wait for the adaptive VAD clause endpoint (`360–500 ms` silence or manual Push-to-Talk release), then stream the translated tokens to UI and TTS (`3 words` or first punctuation boundary).
     - **Optional Bilingual Output (Source + Target):** If users want to verify what was heard (e.g., for numbers/names), configure an optional `"Show Source & Translation"` toggle where Gemma outputs:
       ```text
       [SRC] Where is gate fourteen B?
       [TGT] ¿Dónde está la puerta catorce B?
       ```
       Wait to route only the `[TGT]` span into `StreamingTTSManager` while displaying `[SRC]` in muted secondary text above `[TGT]` in the `TranscriptRow`. (Note: generating `[SRC]` first adds ~5–12 decode tokens = `~100–250 ms` before `[TGT]` starts; therefore, make `[SRC]` display an optional "Careful / Verification Mode" toggle while keeping direct `[TGT]`-only output as the default "Fast Conversation Mode".)

---

### 8.4 Language Selection, Switching, and Dual-Speaker Ergonomics

1. **Inconsistent Tutorial vs. Actual Mic Interaction (`[CODE-VERIFIED]`):**
   - `TutorialScreen.kt:90` instructs the user: *"Hold the microphone button to talk. The app will translate automatically."*
   - However, `MicButton` in `MainActivity.kt:763` uses `.clickable { onClick() }` (a **toggle** tap-to-start / tap-to-stop continuous recorder), **not** a press-and-hold gesture! A user who follows the tutorial and holds their finger on the mic button will find that nothing starts until they lift their finger (`onClick`), and when they lift their finger, recording *starts* instead of stopping!
   - **Fix:** Either support both gestures on `MicButton` (tap to toggle continuous hands-free mode; press-and-hold for push-to-talk mode that flushes on release) or update `TutorialScreen.kt:90` to accurately describe the tap behaviour.
2. **Dual-Speaker (Bilingual) Turn-Taking Ergonomics:**
   - Currently, in a face-to-face conversation between an English speaker and a Spanish speaker, the users must manually tap the small `40.dp` Swap arrow (`MainActivity.kt:559–576`) after **every single turn** so that `targetLanguage` switches between `"Spanish"` and `"English"`.
   - **Fix:** Offer a **Dual-Mic / Split-Button Bar** at the bottom of the Home screen:
     - Left Button: `🇺🇸 Speak English (→ ES)`
     - Right Button: `🇪🇸 Hablar Español (→ EN)`
     Tapping either button immediately sets `(sourceLang, targetLang, targetTag)` for that turn without requiring a separate Swap tap!
3. **Decouple Home Language Picker from Unused ML Kit Downloads (`[CODE-VERIFIED]`):**
   - As documented in Finding F-05 (`CURRENT_ARCHITECTURE_AND_AUDIT.md`), `MainActivity.kt:228–237` and `lines 460–464` restrict the Home screen language picker to ML Kit's `getDownloadedModels()`, showing *"Only downloaded languages are shown. Manage them in Settings."*
   - Because Gemma 4 E2B natively translates 35+ languages out of the box without ML Kit packs, populate the Home screen picker with Gemma's supported languages, and display a clear badge (`"Offline Voice Ready"` vs. `"Text Only / Download Voice"`) based on `ttsEngine.getAvailableVoices(tag)`.

---

### 8.5 Offline Readiness & Model Download Experience

1. **Misleading Model Size & Model Name Strings Across Screens (`[CODE-VERIFIED]`):**
   - `ModelDownloadManager.kt:40`: `"Gemma 4 E2B (2.58 GB)"` (never called by the UI!).
   - `MainActivity.kt:347`: `"Gemma Model is missing. Please go to Settings to download the 2.2GB offline engine."`
   - `SettingsScreen.kt:104`: `"Manage the 2.2GB offline LLM powering translation."`
   - `SettingsScreen.kt:111`: `"2.2 GB"`
   - `SettingsScreen.kt:326`: `"Google Gemma 2B IT (Instruction Tuned)"`
   - `SettingsScreen.kt:339`: `"Are you sure you want to delete the 2.2GB Gemma model?"`
   - **Fix:** Bind all UI labels to `downloadManager.getModelDisplayName()` (`"Gemma 4 E2B (2.58 GB)"`) and check `StatFs(context.filesDir.path).availableBytes` before starting the download so users with `< 3.0 GB` free storage receive a clear warning instead of an mid-download `IOException`.
2. **Pause Button Actually Cancels & Deletes Status Without Resumable UI State (`[CODE-VERIFIED]`):**
   - In `SettingsScreen.kt:137–144`, the button next to the download progress bar shows a **Pause icon (`Icons.Default.Pause`, `contentDescription = "Pause"`)**, which calls `downloadManager.cancelDownload(); isDownloading.value = false`.
   - When `isDownloading.value` becomes `false`, the UI reverts to `"Available for Download"` / `"Download Gemma"` (`SettingsScreen.kt:109, 171`), even though a partial `.tmp` file (`e.g., 1.4 GB of 2.58 GB`) remains on disk in `context.filesDir`!
   - **Fix:** Have `ModelDownloadManager` expose `getPartialDownloadBytes(): Long`. When a `.tmp` file exists (`> 0 bytes`), display `"Resume Download (1.42 / 2.58 GB)"` on the primary button and provide a secondary `"Discard Partial Download"` action.

---

### 8.6 Accessibility, Text Scaling, and Touch Target Audit (`[CODE-VERIFIED]`)

| Issue | File & Line Evidence | Accessibility Impact | Concrete Fix |
|---|---|---|---|
| **1. Undersized Touch Targets (< 48x48 dp minimum)** | - `MainActivity.kt:561`: Language Swap button is `.size(40.dp)`.<br/>- `MainActivity.kt:633`: `"Report"` icon is `.size(16.dp)` and text is `12.sp` with no minimum touch padding.<br/>- `SettingsScreen.kt:469`: Language Pack Delete icon is `.size(20.dp).clickable { ... }` with no `IconButton` wrapper! | Violates Android Accessibility / WCAG 2.1 AAA `48.dp × 48.dp` minimum touch target size; difficult to tap accurately, especially while walking or holding a phone out to another speaker. | Wrap icons in `IconButton` (which enforces `48.dp × 48.dp` minimum touch target) or add `.sizeIn(minWidth = 48.dp, minHeight = 48.dp)`. |
| **2. Missing TalkBack `contentDescription` & State Announcements** | - `MainActivity.kt:789`: `MicButton` has static `contentDescription = "Mic"` regardless of whether recording is active or stopped.<br/>- `MainActivity.kt:557, 578`: `LanguagePill` has no semantic role (`Role.Button`) or action label (`"Change source language, currently English"`). | Screen-reader users cannot tell whether the microphone is recording, initializing, or stopped, nor which pill is source vs. target. | Set dynamic `contentDescription` on `MicButton` (`"Stop recording"`, `"Start voice translation"`, `"Initializing AI model"`) and add `semantics(mergeDescendants = true)` on `LanguagePill`. |
| **3. Flag Emojis Used as Primary Visual Signifiers with Country Bias** | - `MainActivity.kt:182–202` and `SettingsScreen.kt:362–389`: Maps `"en" -> "🇺🇸"`, `"es" -> "🇪🇸"`, `"pt" -> "🇵🇹"` (on Home) vs. `"pt" -> "🇧🇷"` (in Settings!). | Notice `"pt"` shows Portugal's flag (`🇵🇹`) in `MainActivity.kt:193` and Brazil's flag (`🇧🇷`) in `SettingsScreen.kt:379`! Also, only 16 languages have flag mappings in `MainActivity.kt:182`, so the other 43 languages all show the generic globe `"🌐"`. | Display the ISO language code badge (`EN`, `ES`, `PT`, `HI`, `ZH`) alongside the localized language name consistently across all screens. |
| **4. Fixed Height Buttons Clipping Under 200% System Font Scaling** | - `OnboardingScreen.kt:157, 222, 301`: `.height(56.dp)`<br/>- `SettingsScreen.kt:121, 169, 483, 721`: `.height(48.dp)` | When a user enables Android 200% font size in system Accessibility settings, fixed `.height(48.dp)` buttons with `16.sp–18.sp` text clip vertically. | Replace `.height(48.dp)` with `.heightIn(min = 48.dp)` so buttons expand gracefully with large system font scales. |

---

### 8.7 Transcript List (`LazyColumn`) Performance & Stale-Result Prevention

1. **Missing `key` Parameter in `LazyColumn` `items(messages)` (`[CODE-VERIFIED]`):**
   - In `MainActivity.kt:326`:
     ```kotlin
     items(messages) { msg ->
         TranscriptRow(...)
     }
     ```
   - Even though `ChatMessage` has a unique `val id: String = UUID.randomUUID().toString()` (`MainActivity.kt:65`), `items(messages)` **does not pass `key = { it.id }`**! Without stable keys, Compose uses positional indices and cannot efficiently skip recomposition or preserve item state (`showReportDialog`) when items are inserted or cleared.
   - **Fix:** Change `MainActivity.kt:326` to `items(items = messages, key = { it.id }) { msg ->`.
2. **Preventing Confusing Stale Results After Stop or Language Swap (`[CODE-VERIFIED]`):**
   - Currently, if the user taps **Stop** (`isRecording.value = false; audioRecorderHelper.stopRecording()` in `MainActivity.kt:343–344`) or taps **Clear Screen** (`messages.clear()` in `MainActivity.kt:521`) while an inference coroutine is already running in `lifecycleScope.launch(Dispatchers.IO)` (`line 369`), **the running inference coroutine is NOT cancelled**!
   - Moments after the user stops recording or clears the screen, tokens from the old turn continue arriving in `onTokenGenerated`, re-populating `messages` on the cleared screen and speaking out loud via `ttsEngine.processToken(clean)`!
   - **Fix:**
     1. Maintain an explicit `activeTranslationJob: Job?` and `turnGenerationId: AtomicLong`.
     2. When the user taps **Stop**, **Clear Screen**, or navigates away, call `activeTranslationJob?.cancel()`, `translationEngine.cancelActiveTurn()` (`conv.cancelProcess()`), and `ttsEngine.stop()`.
     3. Inside `onTokenGenerated`, verify that `turnId == currentActiveTurnId` before mutating `messages` or dispatching to `ttsEngine`.
