# Phase 2 — Model Bake-off Decision

Date: 2026-11-08 · Status: ✅ **Decided: Gemma 4 E2B is the main brain** (confirmed by Raunak, 2026-11-08)

## Setup
- Candidates (all GGUF Q4_K_M): Qwen3.5-0.8B, Qwen3.5-2B, Qwen3.5-4B, Gemma 4 E2B (instruct), LFM2.5-1.2B-Instruct.
- Quality: llama.cpp server on Modal L4 GPUs, greedy decoding, Ronie's system prompt, OpenAI-style tools. Sets in `eval/sets/` (synthetic, built by `eval/build_sets.py`), scored automatically by `eval/run_bakeoff.py` with no judge model.
- Speed: llama.cpp `d6be065` on the Pixel 9 Pro (Tensor G4), 4 big cores, KleidiAI CPU build or Vulkan GPU build.

## Quality (fast / non-thinking mode unless noted)

| Set (n) | Qwen 0.8B | LFM2.5 1.2B | Qwen 2B | **Gemma 4 E2B** | Qwen 4B |
|---|---|---|---|---|---|
| Tool calls (50) | 36% | 56% | **66%** | 62% | 88% |
| Tool calls, thinking | 58% | – | 68% | – | 90% |
| Persona (60) | 80% | 80% | 87% | **92%** | 83% |
| Grounded Q&A (25) | 52% | 72% | **96%** | **96%** | 100% |
| Reasoning, fast (45) | 29% | 76% | 60% | **78%** | 78% |
| Reasoning, thinking (45) | **0%: all hit the 4,096-token cap** | – | **0%: all hit the cap** | – | not run |
| Summaries / memory (18) | 78% | 67% | 67% | 72% | 89% |
| Email triage, synthetic (40) | 50% (flags everything) | 50% (flags everything) | 65% (recall 30%) | **92%** (P 87%, R 100%) | 98% |

## Speed on the Pixel 9 Pro (tok/s, generation / prompt)

| Model | Size | CPU + KleidiAI | GPU (Vulkan) |
|---|---|---|---|
| Qwen 0.8B | 0.5 GB | 24–36 / 165–228 | 25 / 82 |
| **Qwen 2B** | 1.2 GB | **16** / 105 | 14 / 36 |
| **Gemma 4 E2B** | 2.9 GB (about 4.5 GB RAM in use, per OnDevice LLM) | 11.6 / 99 | **12.3** / **82** |
| Qwen 4B | 2.6 GB | est. 5–8 (not measured) | – |

## Key findings
1. **Thinking mode doesn't work for Qwen3.5 0.8B or 2B as-is.** On 45 grade-school word problems, *every* thinking-mode answer used all 4,096 tokens and never produced an answer. This is the looping problem from the weight-space blog, and it also affects the 2B. → `no-loops.rpatch` (Phase 3.5) is essential if we want thinking mode at all. Until then: thinking off, or a hard budget of about 512 tokens followed by forcing an answer.
2. **Email triage, a core feature, splits the field.** Gemma 4 E2B (92%) and Qwen 4B (98%) do it well. Qwen 2B is too cautious (misses 70% of important mail). Qwen 0.8B and LFM2.5 flag everything. → The 0.8B can't do background triage; that needs Gemma, the 4B overnight, or the Phase 11 distilled classifier.
3. **Keep the persona out of background jobs.** Qwen models added a Ronie joke after one-word labels. Triage and extraction need a plain system prompt.
4. **Gemma 4 E2B has the best balance of quality for its speed:** top on persona, reasoning and triage, equal on grounded Q&A, slightly behind Qwen 2B on tool calls (62 vs 66). On the GPU its prompt processing is 2.3× faster than Qwen 2B's.
5. **Qwen 2B is the fastest capable model on CPU** (16 tok/s cold) and uses less than half the memory.
6. **LFM2.5** is good at fast arithmetic but weak on grounded Q&A and triage. **Drop** (and its licence is non-standard).
7. **Qwen 4B** is clearly the strongest (tools 88–90%, triage 98%, summaries 89%) but too slow for live chat on Tensor G4. → **Overnight worker and PC-side teacher**, not chat.

## ✅ Final policy: one curated model per phone (decided 2026-11-08)

The app **recommends one model for the phone** and installs **only one model at a time**. There is no custom-model import. Small helper models (Whisper, Paradee voice, embedding model, email classifier) are separate, invisible components.

**First-run picker (user decision, 2026-11-08):** onboarding shows **all three curated brains**, with Ronie's pick on top (*⭐ Recommended for your phone*), so users can choose by their phone's memory and storage. Each option shows its **download size** and **RAM used while chatting**, next to the phone's own RAM and free storage. Heavier options warn ("may be slow", "uses a lot of this phone's memory"); options that don't fit in free storage are disabled. Only the chosen model is downloaded; switching later replaces it.

| Tier | Typical phone | Rule (all must hold) | The one model | Engine |
|---|---|---|---|---|
| **A: Full** | Flagships 2023+ (e.g. Pixel 9 Pro, Galaxy S24+) | RAM ≥ 12 GB, Vulkan 1.1+ GPU, ≥ 6 GB free storage, first-run benchmark ≥ 10 tok/s | **Gemma 4 E2B** Q4_K_M (2.9 GB) | GPU (Vulkan), CPU fallback |
| **B: Standard** | Upper mid-range, older flagships | RAM ≥ 8 GB, ARMv8.2 with dotprod, ≥ 3 GB free, benchmark ≥ 8 tok/s | **Qwen3.5-2B** Q4_K_M (1.2 GB) + its `.rpatch` fixes | CPU + KleidiAI |
| **C: Lite** | Older / budget phones | RAM ≥ 4 GB, arm64, ≥ 1.5 GB free | **Qwen3.5-0.8B** Q4_K_M (0.5 GB) + its `.rpatch` fixes | CPU + KleidiAI |
| Unsupported | < 4 GB RAM or 32-bit only | n/a | Friendly "this phone can't run Ronie offline" screen | n/a |

**How the app chooses:**
1. Read RAM (`ActivityManager.MemoryInfo.totalMem`), CPU features (dotprod, i8mm), Vulkan support and free storage.
2. Pick the highest tier the specs allow.
3. **10-second first-run benchmark** with a tiny bundled model (or the first few seconds of the downloaded one) to confirm real speed. Specs alone can mislead, as Tensor G4 shows (fast on paper, throttles quickly). If the measured speed is under that tier's bar, drop one tier *before* the big download.
4. Show one screen: *"Ronie picked the best brain for your phone: Standard (1.2 GB). Download on Wi-Fi?"* with a short "why", and no list of alternatives.
5. **Automatic step-down:** if real use shows sustained speed under ~6 tok/s or repeated out-of-memory kills, Ronie offers (once) to switch to the lighter tier. No step-*up* option that would make the phone struggle.

**What each tier gives up** (from the bake-off), and how we cover it:
| Gap | Tier B (Qwen 2B) | Tier C (Qwen 0.8B) | Cover |
|---|---|---|---|
| Thinking mode loops | ❌ | ❌ | `no-loops.rpatch`; thinking off until it's verified |
| Email triage | recall 30% | flags everything | **Small email classifier** (Phase 11), the same on every tier, not the LLM |
| Tool calls | 66% | 36% (refuses tools) | Tool router + schema-constrained output + `no-refusal.rpatch` |
| Grounded answers | 96% | 52% | Tier C leans harder on search and Wikipedia, shows sources, and says "not sure" more |

**Consequences for the plan:**
- Overnight jobs (memory consolidation, re-checking mail) use **the same model** while charging. The separate overnight Qwen-4B role is dropped from the phone; the **4B stays a PC-side teacher** for labelling data and building patches.
- **Weight patches are made and tested per tier model** (Gemma 4 E2B, Qwen 2B, Qwen 0.8B), each with its own eval receipt.
- Eval suite runs on all three tier models before every release.

---

## Original recommendation (before the one-model policy)

| Role | Model | Why |
|---|---|---|
| **Main brain (chat, tools)**, 12 GB+ phones incl. the Pixel 9 Pro | **Gemma 4 E2B**, GPU by default (steady ~12 tok/s) | Best persona, reasoning and triage; GPU prompt speed; Apache-2.0; also has image input (and native audio per Google, to verify in llama.cpp) |
| Main brain on 8 GB phones / fallback | **Qwen3.5-2B**, CPU + KleidiAI, thinking off | Lighter (1.2 GB), fastest capable CPU model |
| Quick replies, router, tiny jobs | **Qwen3.5-0.8B** (CPU) | 24–36 tok/s; *not* for triage |
| Overnight jobs (memory consolidation, re-check mail) | **Qwen3.5-4B** (charging + idle only) | Highest quality; speed doesn't matter overnight |
| Email triage in the background | Gemma 4 E2B now → **distilled classifier** (Phase 11) | 0.8B fails triage; a classifier will be faster and cheaper on battery |
| Dropped | LFM2.5-1.2B | Weak grounded Q&A and triage; licence |

**Trade-off to accept:** Gemma 4 E2B is ~30% slower than Qwen 2B on a cold CPU and uses about 2–3 GB more RAM. Its GPU speed is similar to Qwen 2B's GPU speed, and it's better at the tasks Ronie exists for.

**Impact on the moonshots:** activation hooks work for both families (`build_cvec` exists in llama.cpp's `gemma4.cpp` and `qwen35.cpp`). The weight-space blog's de-loop result was on Qwen; for Gemma we'd find our own failure directions (Phase 10 is model-agnostic). The `no-loops` patch is still needed for the Qwen models we keep (0.8B, 2B fallback, 4B overnight).

## Caveats
- Small synthetic sets (18–60 items) and a single greedy run; differences under ~10 points are within noise.
- Triage is synthetic; **real labelled email** could change the ranking.
- Gemma's RAM figure is from OnDevice LLM's report, not measured here.
- Qwen 4B thinking-mode reasoning was stopped early to save time and cost.

## Next steps
1. Confirm the main-brain choice.
2. Measure real RAM for Gemma 4 E2B plus Whisper plus Paradee together on the Pixel.
3. Check Gemma 4 E2B's audio input in llama.cpp (it could replace Whisper).
4. Optional: real-email triage set (PC only).
