# SyncDialect Model Options, Fine-Tuning, Distillation, and Alternative Models Plan

**Audit Date:** April 19, 2026  
**Inspected Git Commit:** `f4ffa9a07bca671b822b83d71fef35d87b20dd32` (`v2` branch)

---

## Section 4: Can the Current Gemma Model (`google/gemma-4-E2B-it`) Be Fine-Tuned?

### 4.1 Verification of Base Checkpoint & Runtime Support (`[CODE-VERIFIED]`, `[DOC-VERIFIED]`)

- **Upstream Base Checkpoint:** `google/gemma-4-E2B-it` ([Hugging Face Model Card](https://huggingface.co/google/gemma-4-E2B-it), accessed April 19, 2026; BF16 `.safetensors`, Apache 2.0 License, 2.3B effective / 5.1B total parameters with 300M audio encoder and Per-Layer Embeddings).
- **Deployed Edge Checkpoint:** `litert-community/gemma-4-E2B-it-litert-lm` ([Hugging Face Model Card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm), accessed April 19, 2026; mixed 2/4/8-bit `.litertlm` bundle, 2.58 GB).
- **Does `litertlm-android:0.13.1` Support LoRA Adapters at Runtime? (`[CODE-VERIFIED]`):**
  Disassembly of `litertlm-android-0.13.1.aar` (`LoraConfig.class`, `ConversationConfig.class`, `SessionConfig.class`, `LiteRtLmJni.class`) confirms that `litertlm-android` exposes a `LoraConfig(loraPath: String?, audioLoraPath: String?)` class that passes `loraPath` and `audioLoraPath` down to `LiteRtLmJni.nativeCreateConversation` (`LiteRtLmJni.class:649`) and `nativeCreateSession` (`LiteRtLmJni.class:635`):
  ```java
  public final class com.google.ai.edge.litertlm.LoraConfig {
    public com.google.ai.edge.litertlm.LoraConfig(java.lang.String loraPath, java.lang.String audioLoraPath);
  }
  ```
  **However (`[DOC-VERIFIED]`):**
  1. Dynamic on-device LoRA in LiteRT-LM requires the base `.litertlm` graph to be compiled with LoRA-compatible signatures or GPU/CPU adapter hooks, and adds per-layer low-rank matrix multiplication overhead during inference.
  2. For production deployment of a single unified translation app, **merging the LoRA adapter into the base PyTorch checkpoint (`model.merge_and_unload()`) and converting via `litert-torch export_hf` into a single `.litertlm` file** incurs **zero runtime adapter overhead** on the phone.

---

### 4.2 Comparison of Adaptation Approaches for SyncDialect

| Method | What It Changes | Translation Problems It Solves | Direct Impact on Parameter Count / Per-Token Speed | Export / Conversion Requirement for Android | Recommendation for SyncDialect |
|---|---|---|---|---|---|
| **1. Prompt & Template Correction (Zero Training)** | System instruction, user message (`sourceLang` inclusion), `extraContext = mapOf("enable_thinking" to false)`, Gemma 4 `<\|turn>` token parsing. | Eliminates missing source-language constraint (F-03), stops accidental `<\|think\|>` reasoning traces (F-04), prevents conversational replies (`"Sure, here is..."`), and fixes `<turn\|>` UI leakage. | **0 change** to parameters or tok/s, **but reduces total latency significantly** by eliminating wasted prefill/decode tokens (e.g., 20–50 `<\|think\|>` or preamble tokens at ~47 tok/s = **400–1,000 ms saved**). | **None.** Pure Kotlin change in `TranslationEngine.kt` and `StreamingTTSManager.kt`. | **DO THIS FIRST (Phase 1).** Solves 80%+ of controllable output formatting and source-language ambiguity issues at zero cost. |
| **2. LoRA (Low-Rank Adaptation, FP16/BF16 Base)** | Trains rank-$r$ ($r=8\text{..}32$) adapter matrices on attention/MLP projections (and optionally `audio_input_projection`) while freezing base weights. | Improves spoken register fidelity, code-switching handling (e.g., Spanglish, Hinglish), domain terminology, strict `[SILENCE]` output on background noise, and brevity/conciseness for TTS. | **0 change** to parameter count or per-token decode cost once merged (`merge_and_unload()`). Indirectly reduces turn latency if tuned for concise translations and strict `[SILENCE]` emission on non-speech. | Merge adapter in PyTorch (`merge_and_unload()`), then convert & quantize via `litert-torch export_hf` to `.litertlm`. | **Best training option IF Phase 1 baseline evaluation shows systematic domain/dialect/noise failures.** |
| **3. QLoRA (4-bit NF4 Base + FP16/BF16 LoRA)** | Quantizes frozen base weights to 4-bit NormalFloat (NF4) during training; trains LoRA adapters in BF16. | Same behavioral targets as LoRA, while reducing training VRAM so a single 24 GB GPU (RTX 3090/4090/L4) can train `gemma-4-E2B-it`. | Same as LoRA (dequantize + merge into BF16 base checkpoint before `litert-torch` export). | Merge into original BF16 `google/gemma-4-E2B-it` checkpoint (never merge into NF4 dequantized weights, to avoid double-quantization error before `litert-torch` 2/4/8-bit export!). | **Recommended for single-GPU (16–24 GB VRAM) training setups.** |
| **4. Full Supervised Fine-Tuning (Full SFT)** | Updates all 5.1B parameters (`300M` audio encoder + `2.3B` decoder + `2.6B` PLE tables). | Deep adaptation to a completely new low-resource spoken language not well-covered in Gemma 4 pretraining. | **0 change** to parameter count or per-token speed. High risk of **catastrophic forgetting** across the other 34+ languages and degradation of PLE representations. | Full BF16 checkpoint export via `litert-torch export_hf`. | **NOT RECOMMENDED.** Expensive (requires multi-GPU A100/H100 80GB), high risk of multilingual regression, and unnecessary for Gemma 4's existing 35+ core languages. |

---

### 4.3 Why Fine-Tuning Does Not Directly Reduce Parameter Count or Per-Token Decoding Cost

Fine-tuning (SFT, LoRA, or QLoRA) modifies the numerical values of the weights $\theta$ inside the existing 35-layer Gemma 4 E2B architecture; it does **not** change the number of layers ($L=35$), hidden dimension ($d_{\text{model}}$), audio encoder Conformer blocks, or vocabulary size ($V=262,144$). Therefore:
- **Prefill FLOPs per token** and **decode memory bandwidth per token** remain identical.
- **However, fine-tuning CAN reduce total turn time ($L_{\text{decode}}$ and $L_{\text{E2E\_first\_audio}}$) indirectly** in three concrete ways:
  1. **Zero preamble / zero explanation tokens:** Ensuring the model never emits `"Here is the translation:"` or markdown formatting saves 5–15 tokens (`~100–320 ms` at 47 tok/s) before the first speakable word reaches TTS.
  2. **Deterministic `[SILENCE]` token on noise/coughs:** Training the audio adapter + decoder to emit a single `[SILENCE]<turn|>` token (2 tokens, `~40 ms`) when given non-speech audio (coughs, door slams, background chatter) prevents 30-token hallucinated translations (`~650 ms` decode + 3 seconds of unwanted TTS playback).
  3. **Spoken-length compression:** Spoken interpretation benefits from concise phrasing that matches source speaking duration, reducing TTS synthesis and playback time.

---

### 4.4 Export, Conversion, Quantization & Quality Verification Pipeline (`[DOC-VERIFIED]`)

Because `.litertlm` files are compiled TFLite FlatBuffer bundles (`TFL3`), **you cannot fine-tune `gemma-4-E2B-it.litertlm` directly**. You must:
1. Fine-tune the official PyTorch/Safetensors checkpoint **`google/gemma-4-E2B-it`** in BF16 using Hugging Face `transformers` + `peft` + `trl`.
2. Merge the trained LoRA weights back into the clean BF16 `google/gemma-4-E2B-it` base model (`model.merge_and_unload()`).
3. Convert and quantize the merged PyTorch checkpoint to `.litertlm` using **`litert-torch` (`ai-edge-torch`)**.
   - **Critical Gemma 4 Export Requirement (`[DOC-VERIFIED]`):** When running `litert-torch export_hf` on `gemma-4-E2B-it`, all submodels (`tf_lite_embedder`, `tf_lite_per_layer_embedder`, `tf_lite_audio_encoder_hw`, `tf_lite_audio_adapter`, and `tf_lite_prefill_decode`) must be exported together with consistent signatures, and `--export_vision_encoder=False` should be passed to omit the unused 213.77 MiB vision encoder!
4. **How to Detect Conversion-Induced Quality Changes:**
   - Run an automated **Pre- vs. Post-Conversion Parity Check** on a held-out 200-utterance evaluation set:
     1. Generate translations from the merged BF16 PyTorch checkpoint on GPU (`temperature = 0.0`, greedy).
     2. Generate translations from the converted `.litertlm` file using the `litert-lm` CLI (`temperature = 0.0`, `topK = 1`).
     3. Compute **Token Exact-Match Rate**, **chrF++** between PyTorch BF16 and `.litertlm` outputs (must be $\ge 96.0$ chrF++ parity), and **COMET-22** against human reference translations (must degrade by $\le 0.5$ COMET points from BF16).

---

### 4.5 Practical, Reproducible LoRA Training Plan (`NOT EXECUTED`)

> **Guardrail Notice:** Do **NOT** train on private user conversation recordings without explicit, opt-in, revocable user consent and PII scrubbing. All training data below uses permissively licensed public speech-translation datasets.

#### 1. Dataset Selection, Licensing, and Privacy
Use only open, commercially permissive (`CC-BY-4.0` or `CC0`) speech-to-text translation corpora:
- **CoVoST 2** ([Wang et al., 2020](https://arxiv.org/abs/2007.10310), `CC0-1.0` public domain license for translations, Mozilla Common Voice audio `CC0`): 21 languages into English and English into 15 languages; diverse accents and speakers.
- **FLEURS** ([Conneau et al., 2022](https://arxiv.org/abs/2205.12446), `huggingface.co/datasets/google/fleurs`, `CC-BY-4.0` license): n-way parallel speech and text across 102 languages (~12 hours per language), enabling any-to-any bilingual pairing among SyncDialect's priority languages (`en`, `es`, `fr`, `de`, `hi`, `zh`, `ja`, `ko`, `pt`, `it`, `ru`, `ar`).
- **MuST-C / Europarl-ST** (*Check license per release: Europarl-ST is `CC-BY-NC-4.0`—exclude from commercial builds; use only `CC-BY-4.0` VoxPopuli speech-to-text pairs*).
- **Synthetic Noise / Non-Speech Negative Examples (5% of training mix):** Sample non-speech environmental audio clips from **ESC-50** (`CC-BY-4.0`) or **MUSAN** (music/noise subsets, `CC-BY-4.0`) paired with target output `[SILENCE]` so the model learns never to hallucinate on coughs, clicks, or ambient noise.

#### 2. Data Format and Representative Training Examples
Format each example to match the exact **Gemma 4 Jinja chat template** embedded in `gemma-4-E2B-it.litertlm` (`enable_thinking = False`), using 16 kHz mono WAV audio:

```json
{
  "audio_filepath": "data/fleurs_train/es_419/1042.wav",
  "duration_sec": 2.45,
  "source_lang": "Spanish",
  "target_lang": "English",
  "messages": [
    {
      "role": "system",
      "content": "You are a translation assistant. Strictly translate the Spanish audio to English. Output ONLY the translation. If the audio contains no speech, output [SILENCE]."
    },
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "Translate this Spanish audio to English:"},
        {"type": "audio", "audio": "data/fleurs_train/es_419/1042.wav"}
      ]
    },
    {
      "role": "assistant",
      "content": "Could you tell me where Gate 14B is located, please?"
    }
  ]
}
```

#### 3. Curating Hard Linguistic Cases & Data Cleaning
Filter and augment the training split (`20,000–50,000` audio-translation pairs across top 10 language directions) to specifically cover:
- **Names & Proper Nouns (10%):** Preserve person names, street names, and brand names without mistranslating them literally.
- **Numbers, Dates, Currencies & Measurements (15%):** E.g., `"flight 409 at 14:30 for $250"` — verify exact digit/number preservation in target language via regex check during dataset cleaning.
- **Negation & Modal Verbs (10%):** E.g., `"I do NOT need insulin"` vs. `"I need insulin"`, `"can"` vs. `"cannot"`.
- **Informal Speech, Hesitations & Short Replies (15%):** `0.5 s – 1.5 s` utterances (`"Yeah, sure"`, `"No, thank you"`, `"Wait a second"`) with natural `um`/`uh` disfluencies.
- **Code-Switching (10%):** Bilingual utterances where a speaker mixes English words into Spanish/Hindi/Japanese speech; target should translate the full meaning cleanly into `targetLang`.
- **Strict Split Hygiene:** Partition by **speaker ID and source sentence ID** into:
  - **Train:** 85% (zero overlap in speakers or sentences with Val/Eval)
  - **Validation:** 10% (used for early stopping and checkpoint selection)
  - **Untouched Evaluation Set:** 5% (locked; never inspected during hyperparameter tuning)

#### 4. Compute, VRAM Requirements & Cost Scenarios (`[DOC-VERIFIED]` Assumptions)
- **Model in Memory:** `google/gemma-4-E2B-it` has 5.1B total parameters (~10.2 GB in BF16).
  - **QLoRA (4-bit NF4 base + BF16 adapters on `q_proj, k_proj, v_proj, o_proj, gate_proj, up_proj, down_proj` + `audio_input_projection`, `max_seq_len = 512`, `batch_size = 4`, gradient checkpointing):** Requires **`~14–18 GB VRAM`**. Runs on **1× NVIDIA L4 (24 GB)** or **1× RTX 4090 (24 GB)** or **1× A10G (24 GB)**.
  - **Standard LoRA (BF16 base + BF16 adapters):** Requires **`~26–32 GB VRAM`**. Runs on **1× NVIDIA A100 (40 GB / 80 GB)**.
- **Training Time & Estimated Cloud Cost (Explicit Assumptions):**
  - *Scenario A (Targeted 25,000 utterances, 3 epochs, QLoRA on 1× A10G / L4 24GB at ~$1.00–$1.50/hr):* ~6–9 GPU hours = **`~$6 – $15` per run**.
  - *Scenario B (Multilingual 100,000 utterances, 3 epochs, BF16 LoRA on 1× A100 80GB at ~$2.50–$3.50/hr):* ~10–14 GPU hours = **`~$25 – $50` per run**.

#### 5. Reproducible Training & Conversion Commands (`NOT EXECUTED`)

```bash
# ============================================================================
# PROPOSED LORA FINE-TUNING & LITERT-LM EXPORT PIPELINE (NOT EXECUTED)
# Base Model: google/gemma-4-E2B-it
# Target Runtime: com.google.ai.edge.litertlm:litertlm-android:0.13.1
# ============================================================================

# 1. Environment Setup (Python 3.11+, CUDA 12.x)
pip install "transformers>=4.51.0" "peft>=0.15.0" "trl>=0.16.0" \
            "accelerate>=1.4.0" "bitsandbytes>=0.45.0" "datasets[audio]>=3.3.0" \
            soundfile librosa evaluate comet-ml

# 2. Run LoRA Fine-Tuning (train_gemma4_ast_lora.py)
accelerate launch --mixed_precision bf16 train_gemma4_ast_lora.py \
  --model_name_or_path "google/gemma-4-E2B-it" \
  --train_manifest "data/manifests/train_ast_clean.jsonl" \
  --val_manifest "data/manifests/val_ast_clean.jsonl" \
  --output_dir "./checkpoints/gemma4-e2b-syncdialect-lora" \
  --lora_r 16 \
  --lora_alpha 32 \
  --lora_dropout 0.05 \
  --target_modules "q_proj,k_proj,v_proj,o_proj,gate_proj,up_proj,down_proj" \
  --per_device_train_batch_size 4 \
  --gradient_accumulation_steps 8 \
  --learning_rate 1e-4 \
  --lr_scheduler_type "cosine" \
  --warmup_ratio 0.05 \
  --num_train_epochs 3 \
  --max_grad_norm 1.0 \
  --logging_steps 25 \
  --eval_steps 250 \
  --save_steps 250 \
  --bf16 True \
  --gradient_checkpointing True

# 3. Merge LoRA Adapter into Clean BF16 Base Checkpoint
python -c "
import torch
from transformers import AutoProcessor, AutoModelForImageTextToText
from peft import PeftModel

base_id = 'google/gemma-4-E2B-it'
adapter_dir = './checkpoints/gemma4-e2b-syncdialect-lora/best'
merged_dir = './checkpoints/gemma4-e2b-syncdialect-merged'

processor = AutoProcessor.from_pretrained(base_id)
base_model = AutoModelForImageTextToText.from_pretrained(base_id, torch_dtype=torch.bfloat16, device_map='cpu')
model = PeftModel.from_pretrained(base_model, adapter_dir)
merged = model.merge_and_unload()
merged.save_pretrained(merged_dir, safe_serialization=True)
processor.save_pretrained(merged_dir)
"

# 4. Export & Quantize to .litertlm (Stripping Unused Vision Encoder!)
uv tool install litert-torch-nightly
uv tool install litert-lm

litert-torch export_hf \
  --model=./checkpoints/gemma4-e2b-syncdialect-merged \
  --output_dir=./exported_litertlm \
  --task=image_text_to_text \
  --export_vision_encoder=False \
  --externalize_embedder

# 5. Compute SHA-256 & Verify Parity Before Android Deployment
sha256sum ./exported_litertlm/gemma-4-E2B-it.litertlm
```

#### 6. Deployment & Rollback Steps
1. Host the versioned `.litertlm` artifact at an immutable URL (e.g., `/resolve/<commit_hash>/gemma-4-E2B-it-syncdialect-v2.litertlm`) alongside a signed JSON manifest containing `expectedSize` and `sha256`.
2. Download to `.tmp`, verify SHA-256, and promote only after `TranslationEngine.initialize()` succeeds on the new file; keep the previous `.litertlm` until initialization succeeds, enabling instant rollback if initialization or parity fails.

---

## Section 5: Can the Model Be Distilled?

### 5.1 Rigorous Distinction of Model Compression Techniques

These five techniques operate on completely different mechanisms and must not be conflated:

| Technique | What It Is | Changes Architecture / Layer Count? | Changes Parameter Count? | Changes Bit-Width per Weight? |
|---|---|---|---|---|
| **1. Sequence-Level Knowledge Distillation (Seq-KD)** | Training a smaller student architecture ($S$) on synthetic `(audio, translated_text)` pairs generated by a larger teacher model ($T$). | **Yes** (Student has fewer layers / narrower hidden dim) | **Yes** (e.g., 5.1B $\to$ 300M–1B) | No |
| **2. Logit / Hidden-State Distillation** | Training student $S$ to minimize KL-divergence $D_{\text{KL}}(P_T(y_t \mid y_{<t}, x) \,\|\, P_S(y_t \mid y_{<t}, x))$ over the full vocabulary distribution at each token step. | **Yes** (Requires shared tokenizer between $T$ and $S$) | **Yes** | No |
| **3. Quantization (PTQ / QAT)** | Representing existing weight matrices and/or activations using lower-bit integers (e.g., FP16 $\to$ Int8 / Int4 / Int2). | **No** | **No** | **Yes** |
| **4. Structural Pruning** | Removing attention heads, MLP intermediate neurons, or whole transformer layers from an existing model. | **Yes** (Reduces active dimensions/layers) | **Yes** | No |
| **5. Fine-Tuning (SFT / LoRA)** | Updating weights of the existing model architecture on task-specific data without shrinking the architecture. | **No** | **No** | No |

---

### 5.2 Feasibility Analysis of Distilling for SyncDialect

#### 1. Teacher Model Access, Licensing & Permitted Use of Generated Data (`[DOC-VERIFIED]`)
- **Gemma 4 Models (`google/gemma-4-31B-it`, `google/gemma-4-12B-it`, `google/gemma-4-E4B-it`):** Released under the **Apache 2.0 License** ([google/gemma-4-E2B-it Model Card](https://huggingface.co/google/gemma-4-E2B-it), accessed April 19, 2026), which **permits commercial use and unrestricted distillation / synthetic data generation**, unlike proprietary APIs or non-commercial models (`CC-BY-NC-4.0`).
- **Logit-Based vs. Sequence-Level Distillation:**
  - If the student is a smaller Gemma model sharing the 262K SentencePiece vocabulary (or if distilling `google/gemma-4-12B-it` into `google/gemma-4-E2B-it`), **logit-based distillation** (forward-KL or reverse-KL on top-$K$ logits) is directly supported because the tokenizer matches.
  - If the student is a dedicated encoder-decoder translation model with a smaller vocabulary (e.g., 32K–64K SentencePiece), **sequence-level distillation** (generating pseudo-labeled target translations with `gemma-4-31B-it` filtered by `MetricX-24` / `COMETKiwi` quality estimation) is required.

#### 2. Crucial Architectural Constraint: Audio-to-Text vs. Text-to-Text Students
- **Why you cannot trivially distill into `gemma-3-1b-it` or `gemma-3-270m-it` for direct Audio-to-Text:**
  - `gemma-3-1b-it` and `gemma-3-270m-it` are **text-only** models—they do **not** have an audio encoder (`tf_lite_audio_encoder_hw` or `tf_lite_audio_adapter`)!
  - In the Gemma family, the smallest official models with native audio encoders are **`gemma-4-E2B-it`** (2.3B effective / 5.1B total) and **`gemma-3n-E2B-it`** (2B effective / ~5B total).
  - Creating a custom ~500M–1B direct audio-to-text student for LiteRT-LM would require grafting a ~100M–300M Conformer/Whisper audio encoder onto a `gemma-3-1b-it` decoder via a learned linear adapter (`AudioAdapter`), training the cross-modal projector on thousands of hours of speech, and writing a custom `litert-torch` multi-submodel export script. This is a **major research project (months of effort, $5,000–$20,000+ in GPU compute)** with high risk of export incompatibility with `litertlm-android`'s C++ runtime.

#### 3. Smaller General-Purpose LLM vs. Dedicated Translation Model (`[DOC-VERIFIED]`)
- If the goal is **sub-500 MB download size and sub-500 MB RAM** on budget Android phones, a **cascaded pipeline using a dedicated streaming ASR model + a distilled encoder-decoder translation model (or a 270M–1B text LLM)** is **far more practical** than training a custom multimodal LLM from scratch:
  - Meta's `facebook/nllb-200-distilled-600M` (`600M` params) and Helsinki-NLP's `opus-mt-*` (`~75M` params per pair, distilled from larger ensembles) demonstrate that dedicated encoder-decoder translation models achieve strong translation quality at **3× to 30× fewer parameters** than general-purpose causal LLMs because 100% of their capacity is dedicated to translation rather than coding, math, and world knowledge.
  - Similarly, Google's **TranslateGemma** paper/release ([google/translategemma-4b-it](https://huggingface.co/google/translategemma-4b-it), Jan 2026) showed that distilling/SFT+RL-tuning `Gemma 3 4B` on parallel translation data + MetricX-QE/AutoMQM enabled the `4B` student to match the baseline `Gemma 3 12B` teacher's translation quality across 55 languages.

---

## Section 7: Shortlist of Alternative Offline Models for Android Deployment

Below is an evidence-backed comparison of **6 concrete offline model/pipeline candidates** evaluated against primary model cards and official runtime documentation (accessed April 19, 2026).

### 7.1 Summary Comparison Table

| Candidate Model / Pipeline | Official Model ID(s) | License & Commercial Use | Task & Modalities | Languages | Params | Weight Size on Disk | Est. Peak Runtime RAM | Android Runtime & Conversion Effort |
|---|---|---|---|---|---|---|---|---|
| **0. Current Baseline: Gemma 4 E2B IT** | [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) | **Apache 2.0** (Commercial OK) | Direct Audio + Text + Vision $\to$ Text | 35+ OOTB (140+ pretrained) | 2.3B eff. / 5.1B total | **2.58 GB** (`2.31 GB` if vision/MTP stripped) | **~1.73 GB** (CPU) / **~0.78 GB CPU + ~0.85 GB GPU** | **LiteRT-LM `0.13.1`** (Already integrated; 0 conversion effort) |
| **1. Gemma 3n E2B IT (Int4)** | [`google/gemma-3n-E2B-it-litert-lm`](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm) | **Gemma License** (Commercial OK with Gemma Terms) | Direct Audio + Text + Vision $\to$ Text | 140+ languages | ~2B eff. (Matformer PLE) | **~3.0–3.1 GB** (`.litertlm` Int4 bundle) | **~1.5–2.0 GB** | **LiteRT-LM `0.13.1`** (Drop-in `.litertlm` compatibility; already tested in `push_chunks.py`!) |
| **2. TranslateGemma 4B IT** | [`google/translategemma-4b-it`](https://huggingface.co/google/translategemma-4b-it) / [`litert-community/TranslateGemma-4B-IT`](https://huggingface.co/litert-community) | **Gemma License** (Commercial OK with Gemma Terms) | **Text + Image $\to$ Text ONLY (NO Audio Encoder!)** | 55 core languages (500+ pairs) | 4B eff. (~5B total) | **~2.5–2.8 GB** (Int4) + requires separate ASR (~75 MB) | **~2.3–2.8 GB** | Requires separate ASR (`sherpa-onnx` / Whisper) + LiteRT-LM text inference. **Medium effort.** |
| **3. Moonshine ASR + ML Kit Translate Cascade** | [`UsefulSensors/moonshine`](https://huggingface.co/UsefulSensors/moonshine) + `com.google.mlkit:translate:17.0.3` | **MIT** (Moonshine) + **Google ML Kit Terms** (Commercial OK) | Cascaded: Audio $\to$ ASR Text $\to$ MT Text | Moonshine: **8 languages** (`en, es, ar, zh, ja, ko, uk, vi`); ML Kit: 59 langs | ~27M–61M (ASR) + ~30M (MT) | **~60–110 MB total** per language pair | **~150–250 MB** | `sherpa-onnx` AAR (ASR) + already-included `MLKitTranslator.kt`! **Low-Medium effort.** |
| **4. Whisper (Base/Small Int8) + Opus-MT / ML Kit Cascade** | [`openai/whisper-base`](https://huggingface.co/openai/whisper-base) / [`whisper-small`](https://huggingface.co/openai/whisper-small) + [`Helsinki-NLP/opus-mt`](https://huggingface.co/Helsinki-NLP) | **Apache 2.0** (Whisper) + **CC-BY-4.0 / Apache 2.0** (Opus-MT) (Commercial OK) | Cascaded: Audio $\to$ ASR Text (or $\to$ EN) $\to$ MT Text | Whisper: 99 langs (strong in ~15–25); Opus-MT: 100+ pairs | 74M (`base`) / 244M (`small`) + 75M (MT) | **~140 MB** (`base` Int8 + MT) to **~330 MB** (`small` Int8 + MT) | **~280–600 MB** | `sherpa-onnx` or `whisper.cpp` JNI + ONNX Runtime / ML Kit. **Medium effort.** |
| **5. Meta NLLB-200 Distilled 600M (with Whisper ASR)** | [`facebook/nllb-200-distilled-600M`](https://huggingface.co/facebook/nllb-200-distilled-600M) | **CC-BY-NC-4.0 (NON-COMMERCIAL ONLY!)** | Text $\to$ Text (requires separate ASR) | 200 languages | 600M (MT) + 74M–244M (ASR) | **~620 MB** (Int8 ONNX/CT2) + ASR | **~800 MB – 1.1 GB** | ONNX Runtime / CTranslate2 JNI. **Blocked for commercial apps by CC-BY-NC-4.0 license.** |
| **6. Meta SeamlessM4T Medium / v2 Large** | [`facebook/seamless-m4t-medium`](https://huggingface.co/facebook/seamless-m4t-medium) / [`seamless-m4t-v2-large`](https://huggingface.co/facebook/seamless-m4t-v2-large) | **CC-BY-NC-4.0 (NON-COMMERCIAL ONLY!)** | Unified Speech-to-Speech / Speech-to-Text | 101 speech-in, 96 text-out, 35 speech-out | 1.2B (`medium`) / 2.3B (`v2-large`) | **~1.3–2.5 GB** (Int8) | **~2.0–3.8 GB** | Experimental ggml/ONNX exports only; no official LiteRT support. **Blocked for commercial use.** |

---

### 7.2 Detailed Assessment of Each Candidate Against Primary Sources

#### Candidate 1: `google/gemma-3n-E2B-it-litert-lm` (Why README.md's Upgrade Claim Is Outdated!) (`[CODE-VERIFIED]`, `[DOC-VERIFIED]`)
- **Critical Correction to `README.md:113–115` and `V2_PROPOSAL.md:141`:**
  `README.md` proposes *"upgrading from `gemma-4-E2B-it.litertlm` to `gemma-3n-E2B-it-int4.litertlm`"* and claims it will boost CPU throughput. In reality:
  1. **Gemma 4 E2B (`2026-04-28`) is the newer generation that succeeded Gemma 3n E2B (`2025`)!** Notice the untracked scripts `push_chunks.py` and `stream.py` in the repo root show that the developer *previously* tested `gemma-3n-E2B-it-int4.litertlm` and already upgraded the app to `gemma-4-E2B-it.litertlm`!
  2. **Official Benchmark Comparison (`[DOC-VERIFIED]`):**
     - On [`google/gemma-3n-E2B-it-litert-lm`](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm) (accessed April 19, 2026): Samsung S24 Ultra CPU = **110.5 tok/s prefill / 16.1 tok/s decode**; GPU = **816.4 tok/s prefill / 15.6 tok/s decode**.
     - On [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) (accessed April 19, 2026): Samsung S26 Ultra CPU = **557 tok/s prefill / 46.9 tok/s decode**; GPU = **3,808 tok/s prefill / 52.1 tok/s decode** (and up to **91.7 tok/s** with speculative decoding!).
     - Even accounting for the S24 Ultra vs. S26 Ultra hardware generation gap (~30–40%), **Gemma 4 E2B on LiteRT-LM is substantially faster and has a newer Apache 2.0 license** than Gemma 3n E2B.
- **Verdict:** **Do NOT downgrade from Gemma 4 E2B to Gemma 3n E2B.** Keep `gemma-4-E2B-it.litertlm` as the primary multimodal engine.

#### Candidate 2: `google/translategemma-4b-it` (Dedicated Translation LLM) (`[DOC-VERIFIED]`)
- **Official Card & Paper:** [`google/translategemma-4b-it`](https://huggingface.co/google/translategemma-4b-it) (accessed April 19, 2026).
- **Strengths:** Specifically RL-tuned for translation across 55 languages using MetricX-QE and AutoMQM; matches Gemma 3 12B translation quality.
- **Fatal Limitation for Direct Replacement:** `translategemma-4b-it` is built on Gemma 3 4B (Text + Image $\to$ Text) and **has NO audio encoder**. Using it in SyncDialect would require adding a separate ASR model (`Whisper` or `Moonshine`) in front of it, while still requiring ~2.5 GB of storage and >2.3 GB of RAM (larger than Gemma 4 E2B!).
- **Verdict:** Not suitable as a drop-in replacement for direct speech-to-text translation; however, its **prompt structure and MetricX/COMET evaluation methodology** are directly reusable for evaluating and LoRA-tuning `gemma-4-E2B-it`.

#### Candidate 3 & 4: Cascaded ASR (`Whisper-base`/`small` or `Moonshine` via `sherpa-onnx`) + Text MT (`MLKitTranslator` or `Opus-MT`) (`[CODE-VERIFIED]`, `[DOC-VERIFIED]`)
- **Official Sources:**
  - OpenAI Whisper: [`openai/whisper-base`](https://huggingface.co/openai/whisper-base) (74M params, Apache 2.0) and [`openai/whisper-small`](https://huggingface.co/openai/whisper-small) (244M params, Apache 2.0), accessed April 19, 2026.
  - Useful Sensors Moonshine: [`UsefulSensors/moonshine`](https://huggingface.co/UsefulSensors/moonshine) (27M `tiny` / 61M `base`, MIT License), accessed April 19, 2026.
  - Google ML Kit Translation: `com.google.mlkit:translate:17.0.3` ([ML Kit Docs](https://developers.google.com/ml-kit/language/translation), accessed April 19, 2026) — **already in `app/build.gradle.kts:103` and implemented in `MLKitTranslator.kt`!**
- **Strengths:**
  1. **Tiny Download & RAM Footprint:** `whisper-base` Int8 (`~75 MB`) + ML Kit language pair (`~30 MB`) = **`~105 MB` download** and **`~250 MB` runtime RAM** (vs. `2,588 MB` download and `1,733 MB` RAM for Gemma 4 E2B).
  2. **Produces Visible Source-Language Transcript:** Unlike Gemma 4 E2B (which translates audio directly to target text without showing the speaker what words were heard), an ASR + MT cascade automatically gives the UI **both the source transcript and the translated transcript**, letting the speaker immediately spot if a name or number was misheard!
  3. **Works on 3 GB / 4 GB RAM Budget Android Phones** where a 2.58 GB LLM either OOMs or gets killed by Android's `lmkd` (Low Memory Killer Daemon).
- **Limitations & Risks:**
  - **Moonshine language coverage is limited** to ~8 languages (`en, es, ar, zh, ja, ko, uk, vi`), and `sherpa-onnx` compatibility differs between v1 (4-file ONNX) and v2/multilingual (2-file ONNX).
  - **ML Kit pivots through English** for non-English language pairs (e.g., `Spanish -> French` translates `Spanish -> English -> French`), compounding errors.
  - **Cascaded Error Propagation:** Any phonetic ASR error in stage 1 is faithfully translated into nonsense in stage 2, whereas Gemma 4 E2B's end-to-end audio-to-text attention attends directly over acoustic features.
- **Verdict:** **Highly recommended as an optional "Lite / Low-Memory Mode" for devices with $<6\text{ GB}$ RAM or users who decline a 2.58 GB download**, or alternatively, use **Gemma 4 E2B in a 2-step prompt (or structured output `{"source": "...", "translation": "..."}`)** on flagship devices if source transcripts are desired without loading a second model.

#### Candidate 5 & 6: Meta `NLLB-200-distilled-600M` and `SeamlessM4T` (`[DOC-VERIFIED]`)
- **Official Cards:** [`facebook/nllb-200-distilled-600M`](https://huggingface.co/facebook/nllb-200-distilled-600M) and [`facebook/seamless-m4t-medium`](https://huggingface.co/facebook/seamless-m4t-medium) (accessed April 19, 2026).
- **Fatal Blocker:** Both models are licensed under **CC-BY-NC-4.0 (Creative Commons Attribution-NonCommercial 4.0)**. They **cannot be legally shipped in a commercial Android application**. Furthermore, SeamlessM4T lacks an official hardware-accelerated Android LiteRT runtime.
- **Verdict:** **Disqualified for commercial shipping.**
