# Phase 1 — Runtime Spike Results

Test device: **Pixel 9 Pro** (Tensor G4: 4× A520 @1.95 GHz, 3× A720 @2.6 GHz, 1× X4 @3.1 GHz · 16 GB RAM · Android 17).
Engine: **llama.cpp `d6be065`** (2026-11-08), whisper.cpp `f92e1e4`, cross-compiled with NDK 28.2.
Date: 2026-11-08. Raw logs: `build/phone-bench/` (not committed).

## Verdict

| Check | Result | Gate |
|---|---|---|
| Qwen3.5 (Gated DeltaNet) runs on phone | ✅ coherent greedy output, 0.8B and 2B | ✅ GO |
| Vision (mmproj) on phone | ✅ correctly described a red square + blue circle; image encode **2.5 s** (512×384) | ✅ |
| Activation hooks | ✅ add-direction via `llama_set_adapter_cvec` (applied at every layer incl. DeltaNet); ✅ read activations via `cb_eval`; ⚠ remove-direction needs weight baking or a ~10-line graph patch | ✅ |
| Whisper tiny.en on phone | ✅ perfect transcript of the 11 s sample in **3.7 s** (encode 0.95 s) | ✅ |
| Paradee TTS | ✅ on PC: 3.3 s audio in **87 ms** on 1 thread (38× real time), `am_puck` = voice index **2**. ⏳ phone run needs ONNX Runtime in the app (Phase 4) | ⏳ likely pass |
| **0.8B speed ≥ 20 tok/s** | ≈ **19–20 tok/s** generation, ~120 tok/s prompt | ✅ borderline |
| **2B speed ≥ 15 tok/s** | First run ≈ 9 tok/s. After the follow-up: **16.9 tok/s** from cold on CPU + KleidiAI, **13.9 tok/s sustained** on GPU (see below) | ✅ borderline pass |
| Outputs match desktop | not yet compared | ⏳ |
| Combined RAM + 5-min thermal | not yet measured | ⏳ |

## Speed (charging, screen on, pinned to big cores `taskset f0`, llama-bench pp256 / tg64, 3 reps)

| Model | Build | Threads | Prompt tok/s | Generate tok/s |
|---|---|---|---|---|
| Qwen3.5-0.8B Q4_K_M | **G4-tuned** | 4 | **122.3** | **18.7 ± 3.5** |
| Qwen3.5-0.8B Q4_K_M | G4-tuned | 3 | 86.1 | 17.8 |
| Qwen3.5-0.8B Q8_0 | G4-tuned | 4 | 161.7 | 15.7 |
| Qwen3.5-2B Q4_K_M | **G4-tuned** | 4 | **51.8** | **9.3** |
| Qwen3.5-0.8B Q4_K_M | portable | 4 | 38.0 | 15.7 |
| Qwen3.5-2B Q4_K_M | portable | 4 | 16.5 | 7.5 |

Real generation (`llama-completion`, 40 tokens): 0.8B **20.4 tok/s**, 2B **7.0 tok/s**.

For comparison, OnDevice LLM reports Qwen3.5-0.8B Q8_0 at 40–48 tok/s on a Galaxy S25+ (Snapdragon). Tensor G4 is roughly 2.5–3× slower on CPU.

## Lessons for the engine (Phase 3)

1. **Use the G4-tuned build** (`armv8.6-a+dotprod+i8mm+fp16`): **3× faster prompt processing** than the portable build (i8mm weight repacking). Ship per-CPU builds or ggml's runtime CPU-variant loading.
2. **Pin inference threads to the big cores (CPUs 4–7), 4 threads.** Unpinned runs from a background process varied 3–20 tok/s. The app must set thread affinity and run inference while in the foreground.
3. **Set `n_ctx` explicitly** (e.g. 8k). The default for Qwen3.5 is **262,144 tokens**, which made 2B take 7.9 s to load.
4. **Q4_K_M beats Q8_0** for generation on this phone (memory bandwidth bound). Drop Q8.
5. On battery with the screen off, speeds were ~2× lower and erratic. Background jobs (mail triage) must budget for that.

## Follow-up: speed spike (KleidiAI, GPU, speculative decoding), 2026-11-08

### Results (Q4_K_M, 4 threads on big cores; tok/s)

| | CPU G4 build | **CPU + KleidiAI**, cool | CPU + KleidiAI, throttled | **GPU (Mali-G715, Vulkan, all layers)** |
|---|---|---|---|---|
| 0.8B generate | 18.7 | **35.9** | ~24 | 24.9 ± 0.1 |
| 0.8B prompt | 122 | **228** | ~165 | 82 |
| 2B generate | 9.3 | **16.9** (real 128-token reply) | 9–11 ± 3 | **13.9 ± 0.01** |
| 2B prompt | 52 | **96** | ~52–76 | 36 |

### Findings
1. **KleidiAI (`GGML_CPU_KLEIDIAI=ON`) is a free 1.5–2× CPU win.** Always build with it.
2. **Heat is the main limit on Tensor G4, not raw speed.** The CPU is fastest from cold, but within about a minute the X4 core drops from 3.1 to ~2.1 GHz (1.4 GHz on a wireless charger). Every number above depends on temperature.
3. **The GPU is steady.** It's slower at its peak, but its generation speed barely moved (±0.01) and doesn't depend on CPU throttling. Power use per token is not yet measured.
4. **Speculative decoding doesn't work for Qwen3.5 here.** Drafting with the 0.8B was still running after 14 minutes on a 128-token reply that takes 7.5 s normally. Qwen3.5's DeltaNet layers keep a recurrent state that can't be cheaply rewound when draft tokens are rejected. No MTP head file exists for these GGUFs either. **Dropped** (re-check if llama.cpp adds recurrent-state checkpoints).
5. **Avoid wireless charging during heavy use.** It added enough heat to cap the CPU at 45% of its clock.

### Decision: ✅ GO with Qwen3.5-2B as main brain (pending the Phase 2 bake-off)
- **Generation:** start on the CPU (KleidiAI) for the fastest first replies. **Switch to the GPU when the CPU is throttled** (thermal headroom / max-frequency check), since the GPU holds about 14 tok/s for the 2B. This is the plan's "keep-cool mode", now with real numbers behind it.
- **Prompt processing:** CPU (2–3× faster than the GPU).
- **0.8B:** CPU only (about 24–36 tok/s); background jobs and fast replies.
- **Engine requirement:** load both CPU and Vulkan backends and pick per request. Check in Phase 3 whether llama.cpp allows switching without reloading the model (otherwise keep the 2B on GPU and the 0.8B on CPU).
- Next measurements: battery drain per 1,000 tokens on CPU vs GPU, and a 5-minute sustained chat on each.

---

## Original note (before the follow-up): the 2B is too slow on Tensor G4 CPU

Options, cheapest first:
1. **Try the GPU** — Tensor G4 has a Mali-G715 GPU; test llama.cpp's Vulkan / OpenCL backend. (OnDevice LLM found the Adreno GPU no faster than CPU — Mali is untested.)
2. **Speculative decoding** — 0.8B drafts for 2B (same tokenizer); typical 1.5–2.5× speed-ups on chat text.
3. **0.8B as default chat model**, 2B only for "think harder" / background tasks where speed matters less.
4. **Gemma 4 E2B via LiteRT** on Tensor's GPU/TPU path (Google's own stack for its own chip) — part of the Phase 2 bake-off anyway.

Recommendation: run 1 and 2 as a short follow-up spike before Phase 2; keep 0.8B as the guaranteed fallback.

---

## Side finding: Cactus **Mirage** (released 2026-11-06)

[Blog](https://cactuscompute.com/blog/mirage) · [weights](https://huggingface.co/Cactus-Compute/mirage) · [engine](https://github.com/cactus-compute/cactus)

**What it is:** a 51.3M-parameter vision model (~22 MB at 2-bit) that in one pass returns **structured JSON**: scene description, objects with boxes, segmentation masks, **OCR text with boxes**, relations, and filled form fields (enum / count / text). **74 ms on a Galaxy S26 NPU, 107 ms on its CPU.** English, German, French, Spanish, Italian, Dutch, Polish.

**Why it's useful for Ronie Pocket:**
| Use | Why Mirage fits |
|---|---|
| Fast camera / glasses perception | ~100 ms vs. **2.5 s** for Qwen's vision encoder on the Pixel → near-live "what am I looking at?" |
| Receipts, documents, signs | OCR with boxes + `extract()` form filling (e.g. total, date, merchant) straight into tasks / expenses |
| **Hands** (app-operating agent) | Boxes for on-screen elements → tap targets |
| Battery | Tiny model for always-on or frequent use; wake Qwen only when reasoning is needed |
| Pattern | Their own experiment feeds Mirage's JSON to a text-only LLM (Qwen3-0.6B) — exactly a Mirage → Qwen3.5 pipeline |

**Caveats:**
- **Licence not stated** on the page — check the Hugging Face model card before building on it.
- Needs Cactus's own engine (another runtime alongside llama.cpp); verify an Android library exists and its size.
- Only the Galaxy S26 is benchmarked — **Tensor G4 untested**.
- No published detection/OCR/mask accuracy; the text-LLM VQA score was modest (31.3/100 with Qwen3-0.6B).
- No Hindi or other Indian languages listed.

**Recommendation:** add Mirage as a **Phase 9 (vision) candidate** next to Qwen's own vision encoder — test it on the Pixel with the same receipt/screenshot eval set. Strong fit for the glasses tie-in and the Hands agent; decide after the licence check and a Tensor G4 speed test.
