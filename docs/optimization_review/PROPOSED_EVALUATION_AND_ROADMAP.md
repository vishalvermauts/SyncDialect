# SyncDialect Proposed Evaluation Plan & Prioritized Engineering Roadmap

**Audit Date:** April 19, 2026  
**Inspected Git Commit:** `f4ffa9a07bca671b822b83d71fef35d87b20dd32` (`v2` branch)  
**Status:** Bounded Experimental Design & Implementation Plan Only (`NOT EXECUTED`).

---

## Section 9: Bounded Experimental Evaluation Plan

To prevent regression and avoid chasing unverified speed claims, every candidate optimization must be evaluated against the current baseline under identical, controlled conditions.

### 9.1 Candidate Configurations to Compare

| Candidate ID | Configuration Name | Exact Stack & Settings | Purpose |
|---|---|---|---|
| **C0 (Baseline)** | **Current Shipped `v2` (`f4ffa9a`)** | `gemma-4-E2B-it.litertlm` (`2.58 GB`), `litertlm-android:0.13.1`, `backend = Backend.CPU()` (implicit), `audioBackend = Backend.CPU()`, `cacheDir = null`, `sourceLang` ignored in prompt, `Conversation.sendMessageAsync(Content.AudioBytes)`. (With only `TurnTelemetry` added so timings can be recorded). | Establish ground-truth p50/p90/p95 latency, memory, drop rate, and translation quality. |
| **C1 (Runtime & Prompt Opt)** | **Zero-Training Runtime & Pipeline Fix** | Same `gemma-4-E2B-it.litertlm` model weights + coroutines `1.10.2` fix (F-01) + **`backend = Backend.GPU()`** with `audioBackend = Backend.CPU(4)` and `cacheDir` set (F-02) + `sourceLang` constraint & `enable_thinking = false` (F-03, F-04) + trailing-silence trimming (F-08) + 1-slot audio queue (F-06) + TTS watchdog fix (F-11). | Measure how much improvement comes purely from fixing runtime/pipeline flaws without touching model weights. |
| **C2 (Stripped / Speculative / Lite Cascade)** | **C2a: Vision-Stripped Gemma 4 + GPU Speculative Decoding**<br/>**C2b: Whisper-Base Int8 + ML Kit Cascade (Budget Tier)** | - **C2a:** `gemma-4-E2B-it.litertlm` repackaged without `tf_lite_vision_encoder/adapter` (`~2.36 GB`) + `ExperimentalFlags.enableSpeculativeDecoding = true` on GPU.<br/>- **C2b:** `sherpa-onnx` `whisper-base` Int8 (`~75 MB`) + `MLKitTranslator` (`~30 MB/lang`) for $<6\text{ GB}$ RAM phones. | Test storage reduction + MTP speculative decoding speedup on flagship/midrange (C2a), and low-RAM viability on budget devices (C2b). |
| **C3 (LoRA Fine-Tuned)** | **LoRA-Adapted `gemma-4-E2B-it` (Conditional)** | Executed **ONLY IF** C1 fails quality acceptance criteria on spoken register, code-switching, or noise `[SILENCE]` rejection. Merged BF16 LoRA (`r=16`) exported via `litert-torch export_hf --export_vision_encoder=False`. | Improve translation fidelity, conciseness, and noise robustness without increasing inference cost. |

---

### 9.2 Target Hardware Matrix

Evaluate all candidates across three representative physical Android device tiers:

| Device Tier | Representative Phone | SoC / GPU / NPU | RAM | Android Version | Primary Role in Evaluation |
|---|---|---|---|---|---|
| **Tier 1: Flagship** | Samsung Galaxy S25 / S26 / S28 Ultra (`SM-S928B` or Pixel 10 Pro) | Snapdragon 8 Gen 3 / Elite or Tensor G4/G5 (Adreno / Mali GPU with OpenCL) | 12–16 GB | Android 15 / 16 | Validate peak GPU prefill/decode speed, speculative decoding (C2a), and sub-1.2s p50 $L_{\text{E2E\_first\_audio}}$. |
| **Tier 2: Mid-Range** | Google Pixel 8a / Samsung Galaxy A55 | Tensor G3 / Exynos 1480 / Snapdragon 7 Gen 3 | 8 GB | Android 14 / 15 | Validate sustained 20-minute thermal stability, memory pressure (`PSS`), and GPU vs. 4-thread CPU fallback. |
| **Tier 3: Entry / Budget** | Samsung Galaxy A15 / Moto G Power | MediaTek Helio G99 / Dimensity 6100+ | 4–6 GB | Android 14 | Test low-memory behavior (`onTrimMemory` / `lmkd`) of Gemma 4 E2B (C1) vs. Cascaded Lite Mode (C2b). |

---

### 9.3 Standardized Test Corpus & Experimental Protocol

1. **Language Pairs (6 Core Bidirectional Pairs = 12 Directions):**
   - `English <-> Spanish` (`en <-> es`) — High-resource Latin script
   - `English <-> Mandarin Chinese` (`en <-> zh`) — Non-spaced CJK script, tonal
   - `English <-> Japanese` (`en <-> ja`) — SOV word order, CJK script (tests clause segmentation and verb-final latency)
   - `English <-> Hindi` (`en <-> hi`) — SOV word order, Devanagari script, frequent English code-switching
   - `English <-> Arabic` (`en <-> ar`) — Right-to-Left (RTL) UI layout, rich morphology
   - `Spanish <-> French` (`es <-> fr`) — Non-English pair (tests direct translation vs. ML Kit English-pivoting)
2. **Stratified Utterance Corpus (`100 utterances per direction = 1,200 total test utterances`):**
   - **Never overlap with any training or validation data.**
   - **25% Short Conversational Turns (`0.5 s – 1.5 s`):** Greetings, confirmations, short questions (`"How much does this cost?"`, `"Gate fourteen B, please"`).
   - **30% Medium Single-Clause Sentences (`1.5 s – 3.0 s`):** Travel, medical, dining, and directions scenarios.
   - **20% Multi-Sentence / Long Utterances (`3.5 s – 7.0 s` with natural `300–600 ms` internal pauses):** Specifically tests forced chunking (`maxChunkBytes`) and whether the second clause is silently dropped (Finding F-06).
   - **15% High-Risk Entity / Negation / Number Utterances:** Dates, currency amounts, medication dosages, allergies (`"I am allergic to penicillin, do NOT give me penicillin"`), person/street names, and decimals (`"3.5 mg"`).
   - **10% Code-Switched & Noisy / Non-Speech Audio:** Spanglish/Hinglish utterances + pure noise clips (coughs, door slams, café chatter at 5 dB SNR) where expected output is `0` spoken words (`[SILENCE]`).
3. **Deterministic Audio Injection + Live Acoustic Loopback:**
   - **Track A (Deterministic PCM Injection Hook):** Feed the exact 16 kHz PCM `.wav` files directly into `AudioRecorderHelper`'s frame loop (bypassing room acoustics) so C0, C1, C2, and C3 process bit-identical audio frames.
   - **Track B (Live Acoustic Loopback Rig):** Play the test corpus through a calibrated artificial mouth / studio monitor at 65 dBA SPL into the phone's microphone at 30 cm distance, and record both the source speaker and the phone's loudspeaker output on a 2-channel audio interface to measure true hardware **Speech-End-to-First-Acoustic-Sound ($L_{\text{E2E\_first\_audio}}$)**.
4. **Trial Ordering, Cold vs. Warm Separation, and Thermal Controls:**
   - **Cold Trials:** Force-stop app, clear OS page cache if rooted (or wait 60s), launch app, and record $L_{\text{cold\_init}}$ and Turn 1 latency across 10 repetitions per candidate.
   - **Warm Trials:** Randomize candidate execution order (Latin square design across C0, C1, C2, C3). Before starting each 100-utterance block, verify battery level $\ge 50\%$, charger unplugged (to avoid charging heat), and starting battery/SoC skin temperature $\le 32^\circ\text{C}$ via `adb shell dumpsys battery` and `PowerManager.getCurrentThermalStatus()`.
   - **Sustained 20-Minute Stress Test:** Run 120 consecutive turns over 20 minutes; log $L_{\text{E2E\_first\_audio}}$, `PowerManager` thermal status (`THERMAL_STATUS_NONE` through `THERMAL_STATUS_SEVERE`), PSS memory (`dumpsys meminfo`), and battery current (`BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`).

---

### 9.4 Quality, Latency, Resource, and Offline Acceptance Criteria

| Dimension | Evaluation Metric & Tooling | Limitations of Metric | Target Acceptance Gate |
|---|---|---|---|
| **1. Automated Translation Quality** | **COMET-22** (`Unbabel/wmt22-comet-da`), **MetricX-24**, and **chrF++** (`sacrebleu`) against professional human references. | Automated neural metrics can miss subtle negation flips or hallucinated politeness if embeddings remain similar; chrF++ penalizes valid synonyms. | C1/C2a/C3 must achieve **$\ge$ baseline C0 COMET-22** (and **+2.0 COMET** on previously unconstrained short utterances via `sourceLang` fix). |
| **2. Critical Entity, Number & Negation Accuracy** | Automated regex/AST extractor + human verification on the 15% Entity/Number/Negation subset (`numbers`, `dates`, `negation polarity`). | Requires exact reference entity annotations per utterance. | **$\ge 98\%$ exact number/negation polarity preservation**; **0 critical medical/safety inversions**. |
| **3. Human Native-Speaker Evaluation (When Required)** | **MQM (Multidimensional Quality Metrics)** + 1–5 Adequacy/Fluency scale by **bilingual native speakers** (minimum 2 native raters per language pair on a 50-utterance stratified sample). | **Required before shipping any fine-tuned (C3), distilled, or cascaded (C2b) model**, and for tonal/SOV languages (`zh`, `ja`, `hi`, `ar`) where automated metrics under-detect register and prosodic chunking errors. | Mean Adequacy **$\ge 4.2 / 5.0$**, critical mistranslation rate **$< 1.0\%$**, natural TTS prosody rating **$\ge 4.0 / 5.0$**. |
| **4. Turn Coverage (Silent Drop Rate)** | Percentage of spoken source words/clauses in the 100-utterance corpus that produce a translated output (including multi-clause turns). | Latency percentiles are meaningless if achieved by dropping 30% of user clauses! | **100% clause coverage on 2-clause turns** (down from 50% drop rate when `isTranslating` drops busy chunks). **0 unreported drops.** |
| **5. End-to-End First-Audio Latency ($L_{\text{E2E\_first\_audio}}$)** | `TurnTelemetry` (`T_last_speech_frame` to `T_tts_on_start`) + 2-channel acoustic loopback (`p50`, `p90`, `p95`, including failed/timed-out turns). | Software `onStart` fires ~20–60 ms before acoustic speaker onset; report both. | **Tier 1 Flagship (Warm, short/medium turns):**<br/>- `p50 <= 1,100 ms`<br/>- `p90 <= 1,600 ms`<br/>- `p95 <= 1,900 ms` |
| **6. Non-Speech Hallucination Rate** | False-positive spoken translation rate on the 10% non-speech noise clips (coughs, taps, ambient noise). | Depends on VAD + RMS gate + model `[SILENCE]` adherence. | **0% spoken hallucinations** on non-speech noise clips. |
| **7. Memory, Battery & Thermal Stability** | Peak `TOTAL PSS` (`dumpsys meminfo`), memory growth $\Delta\text{PSS}$ between Turn 5 and Turn 120, and thermal throttling degradation over 20 minutes. | `mmap` pages fluctuate with OS page cache pressure; track `Native Heap` + `Graphics/GL` + `AnonPages`. | $\Delta\text{PSS} \le 15\text{ MB}$ over 120 turns (zero leak); p95 latency in minute 20 degrades by **$\le 15\%$** vs. minute 1. |
| **8. Strict Airplane-Mode Offline Verification** | Cold start + 10 turns per language pair with **Wi-Fi and Cellular Data completely disabled (Airplane Mode ON)**. | Requires pre-installed offline TTS voice pack for target language. | **100% functional** in Airplane Mode; if an offline TTS voice is missing for a newly selected language, text translation still succeeds 100% and UI prompts user gracefully without silent failure. |

---

## Section 10: Final Prioritized Recommendations & Executive Decision Table

### 10.1 Ranked Recommendations (Ordered by Risk-Adjusted ROI)

---

#### Priority Tier 1: Small Code & Runtime Changes Likely to Help Immediately (Zero Model Training or Conversion)

##### Recommendation R1.1 — Fix `kotlinx-coroutines` `1.6.4!!` ABI Mismatch & Remove Global Crash Swallowing (Finding F-01)
- **Evidence & Confidence:** **`[CODE-VERIFIED]` — Very High Confidence (100%).** Bytecode inspection of `litertlm-android-0.13.1.aar` (`Conversation$sendMessageAsync$1$1.onDone`) proves it calls `SendChannel$DefaultImpls.close$default`, which throws `NoSuchMethodError` against `kotlinx-coroutines:1.6.4!!` forced in `app/build.gradle.kts:112–114`.
- **Implementation Effort:** **Trivial (~1 hour).** Remove `1.6.4!!` pins in `app/build.gradle.kts` (use `1.10.2` from `libs.versions.toml`), switch `TranslationEngine.kt` to direct `MessageCallback` or `Session`, and delete the `UncaughtExceptionHandler` hack in `MainActivity.kt:97–105`.
- **Expected Benefit:** Eliminates per-turn thread crashes, prevents unclosed `callbackFlow` hangs, and restores reliable turn completion.
- **Quality & Compatibility Risks:** Minimal; verify all coroutines and Play Services `tasks.await()` compile cleanly against `1.10.2`.
- **Pass Condition:** 100 consecutive turns complete with `0` `NoSuchMethodError` exceptions and `100%` `onComplete()` invocation.
- **Rollback Approach:** `git revert` the dependency change or use `Conversation.sendMessageAsync(contents, MessageCallback)` directly (which does not touch `SendChannel`).

##### Recommendation R1.2 — Enable Hybrid Hardware Acceleration (`backend = Backend.GPU()`, `audioBackend = Backend.CPU(4)`) and Set `cacheDir` (Finding F-02)
- **Evidence & Confidence:** **`[CODE-VERIFIED]` + `[DOC-VERIFIED]` — High Confidence.** `TranslationEngine.kt:98` omits `backend`, defaulting the 35-layer decoder (`Submodel 8`) to CPU. The `.litertlm` flatbuffer header restricts only the audio submodels (`Submodels 2, 3, 4`) to CPU. Official `litert-community/gemma-4-E2B-it-litert-lm` benchmarks on S26 Ultra show GPU backend accelerates prefill **6.8×** (`557 -> 3,808 tok/s`), accelerates decode **+11%** (`46.9 -> 52.1 tok/s`), and reduces CPU RAM by **1,057 MB** (`1,733 MB -> 676 MB`).
- **Implementation Effort:** **Low (~2–4 hours).** Add `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` and `libvndksupport.so` to `AndroidManifest.xml`, set `backend = Backend.GPU()`, `audioBackend = Backend.CPU(4)`, and `cacheDir = context.cacheDir.absolutePath` in `TranslationEngine.kt`, with automatic try/catch fallback to `Backend.CPU(4)`.
- **Expected Benefit (`[HYPOTHESIS]`):** Reduces $L_{\text{TTFT}}$ by **`250–500 ms`** per turn, lowers CPU thermal load, and frees ~1 GB of CPU RAM.
- **Quality & Compatibility Risks:** FP16 GPU execution can have minor numerical differences vs. CPU XNNPACK; broken OpenCL drivers on some budget devices require the automatic CPU fallback.
- **Pass Condition:** `conversation.getBenchmarkInfo().lastPrefillTokensPerSecond` increases by $\ge 2.5\times$ on Tier 1/2 devices with $< 0.5$ COMET score difference vs. CPU.
- **Rollback Approach:** Toggle `backend = Backend.CPU(4)` via `AppPreferences` or remote config flag.

##### Recommendation R1.3 — Pass `sourceLang` in Prompt, Disable `<|think|>` Mode, Fix Gemma 4 `<|turn>` Token Sanitizer, and Trim Trailing VAD Silence (Findings F-03, F-04, F-08)
- **Evidence & Confidence:** **`[CODE-VERIFIED]` — Very High Confidence.** `sourceLang` is unused in `TranslationEngine.kt:139`; `MainActivity.kt:376` checks `token.contains("eos")` (matching words like `"videos"`) and misses Gemma 4's `<turn|>` token; `AudioRecorderHelper.kt:130` sends `400–800 ms` of trailing silence frames to the audio encoder.
- **Implementation Effort:** **Low (~4 hours).**
- **Expected Benefit (`[HYPOTHESIS]`):** Saves **`80–200 ms`** of audio encoding/prefill per turn by trimming trailing silence; prevents `<|think|>` token stalls (`400–1,000 ms` saved when thinking would have triggered); improves short-utterance translation accuracy by constraining `sourceLang`.
- **Quality & Compatibility Risks:** None if `160 ms` (8 frames) of trailing silence is kept as natural acoustic decay.
- **Pass Condition:** Zero `<turn|>` or `<|think|>` tokens in UI/TTS; zero premature flushes on words containing `"eos"`; $\ge 15\%$ reduction in `pcmBytesSent` on pause-terminated turns.
- **Rollback Approach:** Revert prompt string and silence trim offset to `0`.

##### Recommendation R1.4 — Fix 5-Second TTS Watchdog Premature Unmute, Add 1-Slot Audio Turn Queue, and Remove Unused ML Kit Downloads (Findings F-05, F-06, F-11, F-12, F-14, F-15, F-16)
- **Evidence & Confidence:** **`[CODE-VERIFIED]` — Very High Confidence.** `StreamingTTSManager.kt:60` (`if (!stillSpeaking || activeUtterances.isNotEmpty())`) unmutes the mic after 5.0 seconds even while TTS is still speaking; `MainActivity.kt:356` drops speech chunks during inference; `MainActivity.kt:228–261` downloads unused ~30 MB ML Kit packs and restricts the Home language selector to them.
- **Implementation Effort:** **Medium (~2–3 days).**
- **Expected Benefit:** Eliminates acoustic self-translation feedback loops on sentences $>5\text{ s}$, eliminates silent clause drops on 2-sentence turns, saves 30 MB/language of wasted downloads, and preserves engine state across screen rotations via a `ViewModel`.
- **Quality & Compatibility Risks:** Low; ensure the 1-slot queue drops segments older than 8 seconds with a visual toast (`"Speech missed, please repeat"`).
- **Pass Condition:** 0 acoustic feedback loops on 8-second TTS outputs; 100% capture of 2-clause test utterances; 0 model reloads on screen rotation.
- **Rollback Approach:** Revert individual component commits on the `v2` branch.

---

#### Priority Tier 2: Model & Runtime Changes Requiring Conversion or Packaging

##### Recommendation R2.1 — Strip Dead Vision Submodels (`218.3 MiB`) & Benchmark Built-In MTP Speculative Decoding on GPU
- **Evidence & Confidence:** **`[CODE-VERIFIED]` + `[DOC-VERIFIED]` — High Confidence.** Binary inspection of `gemma-4-E2B-it.litertlm` proves `Submodels 5, 6, 7` (`tf_lite_vision_encoder/adapter/end_of_vision`) occupy **`228.92 MB` (`218.32 MiB`)** and are never used. Meanwhile, `Submodel 9` (`tf_lite_mtp_drafter`, `42.28 MiB`) is already present and documented to boost GPU decode speed from **51.5 tok/s to 66.5–91.7 tok/s** on S26 Ultra.
- **Implementation Effort:** **Medium (~2 days).** Enable `ExperimentalFlags.enableSpeculativeDecoding = true` when `activeBackend == "GPU"` and benchmark; re-export `google/gemma-4-E2B-it` via `litert-torch export_hf --export_vision_encoder=False` to remove the 218.3 MiB vision weights.
- **Expected Benefit:** Reduces initial model download by **~229 MB** (or **~273 MB** if MTP is also omitted on CPU-only builds) and increases GPU token generation speed by **25–75%** (`[DOC-VERIFIED]` on S26 Ultra).
- **Quality & Compatibility Risks:** `litert-torch` export must be verified with the 200-utterance parity suite (`chrF++ >= 96.0` vs. upstream `litert-community` artifact).
- **Pass Condition:** Converted `.litertlm` passes SHA-256 verification, loads in `litertlm-android:0.13.1`, saves $>200\text{ MB}$ on disk, and matches C1 COMET-22 within $\pm 0.3$ points.
- **Rollback Approach:** Point the model manifest URL back to the pinned upstream `litert-community/gemma-4-E2B-it-litert-lm` artifact.

##### Recommendation R2.2 — Optional "Lite Mode" Cascaded Pipeline (`sherpa-onnx` Whisper-Base Int8 + `MLKitTranslator`) for $<6\text{ GB}$ RAM Devices
- **Evidence & Confidence:** **`[CODE-VERIFIED]` + `[DOC-VERIFIED]` — Medium-High Confidence.** `MLKitTranslator.kt` is already implemented in the repository (`com.google.mlkit:translate:17.0.3`). Pairing it with an Int8 ONNX ASR model (`whisper-base`, ~75 MB) provides a ~105 MB offline fallback for budget devices that cannot download or hold a 2.58 GB model in RAM.
- **Implementation Effort:** **Medium (~1 week).**
- **Expected Benefit:** Reduces download size by **~95%** (`2.58 GB -> ~105 MB`) and peak RAM by **~80%** (`~1.7 GB -> ~250 MB`) for budget device users, while also providing a live source-language transcript.
- **Quality & Compatibility Risks:** Lower translation quality than Gemma 4 E2B on idioms, noisy speech, and non-English pairs (due to ML Kit English pivoting and ASR $\to$ MT error compounding).
- **Pass Condition:** Runs without OOM on Tier 3 (4 GB RAM) phone with $L_{\text{E2E\_first\_audio}}$ `p50 <= 1.5 s` and acceptable human adequacy ($\ge 3.6 / 5.0$).
- **Rollback Approach:** Keep Lite Mode strictly as an opt-in fallback; default to Gemma 4 E2B on devices with $\ge 6\text{ GB}$ RAM.

---

#### Priority Tier 3: Fine-Tuning (`LoRA` / `QLoRA` on `google/gemma-4-E2B-it`)

##### Recommendation R3.1 — Targeted LoRA Fine-Tuning on Conversational AST & Noise Rejection (Conditional on Phase 1–2 Evaluation)
- **Evidence & Confidence:** **`[DOC-VERIFIED]` + `[HYPOTHESIS]` — Medium Confidence.** `google/gemma-4-E2B-it` already achieves strong zero-shot speech translation (33.47 CoVoST, 0.09 FLEURS WER/CER on the official model card). Fine-tuning will **not** shrink the 35-layer architecture or speed up per-token matrix math, but can improve code-switching, spoken register conciseness, and deterministic `[SILENCE]` emission on non-speech noise if C1 prompt fixes leave residual errors.
- **Implementation Effort:** **Medium-High (~1–2 weeks including dataset curation, training, `litert-torch` conversion, and human MQM validation).**
- **Expected Benefit (`[HYPOTHESIS]`):** +1.5 to +3.0 COMET points on informal/code-switched conversational speech and near-zero false-positive speech output on environmental noise.
- **Quality & Compatibility Risks:** Overfitting to training language pairs can degrade zero-shot performance on the remaining 30+ Gemma languages; conversion via `litert-torch` must be parity-checked.
- **Pass Condition:** Beats C1 by $\ge +1.0$ COMET-22 on target pairs, shows $0$ regression on held-out general languages, and achieves $0\%$ hallucination on noise clips.
- **Rollback Approach:** Revert model manifest to the untuned C1/C2a `.litertlm` artifact.

---

#### Priority Tier 4: Knowledge Distillation & Larger Research Work

##### Recommendation R4.1 — Sequence-Level Distillation / Custom Sub-1B Audio-to-Text Student (Defer / Do Not Prioritize Now)
- **Evidence & Confidence:** **`[DOC-VERIFIED]` — Low ROI for Current Stage.** Because smaller Gemma models (`gemma-3-1b-it`, `gemma-3-270m-it`) lack audio encoders, building a custom sub-1B direct speech-to-text student requires training a cross-modal audio adapter + decoder from scratch and maintaining a custom `litert-torch` multi-subgraph export pipeline.
- **Implementation Effort:** **Very High (4–8+ weeks, multi-GPU compute, high engineering risk).**
- **Expected Benefit:** Potential 2×–3× smaller model than Gemma 4 E2B, which is much more easily achieved for budget devices via Candidate C2b (Cascaded Whisper + ML Kit/Opus-MT).
- **Verdict:** **Defer indefinitely** unless SyncDialect scales to millions of budget-phone users where C2b cascaded quality is insufficient.

---

### 10.2 Executive Decision Table

| Strategic Option | Recommended Decision | When to Choose | Engineering Effort | Expected Latency / Size / Quality Impact | Key Risk |
|---|---|---|---|---|---|
| **1. Keep Current Model (`gemma-4-E2B-it`) & Optimize Runtime + Pipeline (Tier 1 + R2.1)** | **YES — EXECUTE IMMEDIATELY (Primary Path)** | **Now.** Before any model replacement or training. | **Low–Medium (3–5 days)** | - **Latency:** GPU prefill **6.8× faster** (`[DOC-VERIFIED]`); trims `240–640 ms` trailing silence; fixes ABI hang.<br/>- **RAM:** Drops CPU RSS by **~1 GB** on GPU.<br/>- **Size:** Saves **~229 MB** (stripping vision) + **30 MB/lang** (removing unused ML Kit).<br/>- **Quality:** Fixes missing `sourceLang`, `<turn\|>` leaks, 5s TTS cutoff, and dropped clauses. | GPU delegate driver quirks on older Mali GPUs (mitigated by automatic CPU fallback). |
| **2. Fine-Tune Current Model (`LoRA` / `QLoRA` on `google/gemma-4-E2B-it`) (Tier 3)** | **CONDITIONAL — Gate on Tier 1 Baseline Evaluation** | Only **after** Tier 1 fixes are measured, and **only if** human/COMET evaluation shows prompt fixes cannot solve code-switching, verbosity, or noise hallucinations. | **Medium–High (1–2 weeks, ~$15–$50 GPU compute)** | - **Latency:** No change to tok/s; indirect turn reduction via concise phrasing & strict `[SILENCE]` output.<br/>- **Size/RAM:** Unchanged.<br/>- **Quality:** +1.5–3.0 COMET on targeted dialects/noise (`[HYPOTHESIS]`). | Catastrophic forgetting on non-tuned languages; `litert-torch` conversion parity drift. |
| **3. Distill into a Smaller Custom Audio-to-Text Model (Tier 4)** | **NO — Defer / Not Necessary** | Only if building a proprietary sub-1B end-to-end speech-to-text foundation model in a dedicated ML research org. | **Very High (1–2+ months, $5k–$20k+ compute)** | - **Latency/Size:** Potentially ~1 GB weight file, but high risk of breaking LiteRT-LM C++ audio graph expectations. | `gemma-3-1b` has no audio encoder; custom multimodal export frequently breaks on-device runtimes. |
| **4. Replace or Supplement with a Dedicated Offline Model (Tier 2, R2.2)** | **NO for Flagship/Midrange; YES as Optional "Lite Mode" for $<6\text{ GB}$ RAM Phones** | Do **not** downgrade Gemma 4 E2B to Gemma 3n E2B (`Gemma 4 E2B` is newer and faster). Offer `Whisper-Base Int8 + MLKitTranslator` (`~105 MB`) only as a Lite fallback for low-RAM/low-storage devices. | **Medium (~1 week)** | - **Size:** `~105 MB` vs. `2.58 GB` (-95%).<br/>- **RAM:** `~250 MB` vs. `~1.7 GB` (-85%).<br/>- **Quality:** Provides source transcript, but lower accuracy on noisy/idiomatic/non-English speech due to ASR $\to$ MT cascade. | Cascaded WER compounding; ML Kit pivots non-English pairs through English. |

---

### 10.3 Best Next Step Based on the Inspected Project

**Do not train or replace `gemma-4-E2B-it.litertlm` yet.**  
The inspected codebase contains **code-verified runtime, concurrency, and configuration bugs** that artificially handicap the existing model and pipeline:
1. `TranslationEngine.kt` omits `backend = Backend.GPU()` and `cacheDir`, forcing the 35-layer LLM decoder onto CPU when only the audio encoder requires CPU.
2. `app/build.gradle.kts` pins `kotlinx-coroutines` to `1.6.4!!`, causing `litertlm-android:0.13.1` to throw `NoSuchMethodError: close$default` at the end of every inference turn.
3. `TranslationEngine.kt` ignores `sourceLang`, omits `enable_thinking = false`, Base64-encodes every WAV buffer into Gson JSON, and receives `400–800 ms` of un-trimmed trailing silence frames on every pause-triggered turn.
4. `StreamingTTSManager.kt`'s 5-second watchdog unmutes the microphone while TTS is still speaking (`!stillSpeaking || activeUtterances.isNotEmpty()`), and `MainActivity.kt` silently discards user speech whenever `isTranslating` is busy.

**Immediate Action Plan:** Implement **Phase 1 (`TurnTelemetry` instrumentation + Tier 1 code fixes R1.1–R1.4)**, run the bounded 100-utterance evaluation on physical phones comparing **C0 (Baseline)** vs. **C1 (Optimized Runtime)**, and use those empirical measurements to decide whether **R2.1 (Vision-stripped bundle + GPU Speculative Decoding)** or **R3.1 (LoRA fine-tuning)** is warranted.
