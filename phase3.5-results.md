# Phase 3.5 — Moonshot demo: Results

Device: Pixel 9 Pro (Gemma 4 E2B Q4_K_M on the GPU) · Training and evals: Modal (L4/A10G/T4) and PC · Date: 2026-11-08

## What was built

| Piece | Where | Status |
|---|---|---|
| Engine activation hooks: steering (add a direction per layer, strength per turn) and read-out (score one layer per token) | `core/engine/src/main/cpp/ronie_engine.cpp` (`nativeLoadSteering`, `nativeSetSteering`, `nativeSetProbe`, `probe_cb`) | ✅ on the phone, no measurable speed cost (10.6–11.3 tok/s vs 10.2 baseline) |
| **Clean ↔ Sweary slider** (first Brain-print slider) | `Tone.kt`, Tone card in chat | ✅ tested on the phone |
| **`.rpatch` v0** format + CLI (build / inspect / verify) | `tools-pc/rpatch/rpatch.py`, `patches/swear-gemma4e2b.rpatch` | ✅ |
| **X-ray Mode v0** (grounded vs made-up probe, sentence colouring, accuracy shown in-app) | `Xray.kt`, `eval/xray/` | see below |
| Training on Modal: our patched llama.cpp built with CUDA (server, cvector-generator, probe_dump) | `eval/modal_steering.py` | ✅ |
| llama.cpp fix: control-vector training for Gemma 4 (KV-shared layers pruned rows) | `scripts/patches/llama-cvector-all-outputs.patch` | ✅ |

## Clean ↔ Sweary (steering direction)

Method: 125 contrast pairs; same user message and partial reply, differing only in one persona line ("swears constantly" vs "never swears"); mean activation difference per layer; applied to layers 10–30. Eval: persona v6, phone sampling (temperature 1.0), 16 plain questions + 10 "please swear" prompts, 2 seeds each.

| Strength | Swears when asked | Swears per 100 words (asked) | Unprompted swearing (plain Qs) | Plain-question accuracy |
|---|---|---|---|---|
| −1.0 | 0/10 | 0.0 | 0% | 100% |
| 0 (off) | 7/10 | 2.0 | 0% | 94% |
| +0.5 | 9/10 | 5.1 | 6% | 94% |
| **+1.0 (slider max)** | 10/10 | 9.9 | 31% | 94% |
| +2.0 | 10/10 | 33.1 | 81% | 75% ❌ |
| +4.0 | 10/10 | 56.3 | 100% | 31% ❌ |

On the phone: at +100%, "My laptop died right before my deadline" got "Fuck. That's the worst." plus useful steps; at −100%, "Swear as much as you want… my landlord raised the rent" stayed clean and friendly.

## X-ray Mode v0 (honesty probe) — shipped as **Labs** (off by default)

What it measures: whether a sentence of Ronie's reply is backed by text the user gave him, or made up. It is **not** a truth detector for general chat.

Method: synthetic notes (shops, trips, meetings) with fact questions and questions the note can't answer; for each, a grounded and an invented answer in Ronie's real styles (sentence, bullet, bare value), teacher-forced into the **exact app prompt** (persona v6, Gemma format, five ways of pasting a note). Per-layer mean activations over the answer from `probe_dump` (our llama.cpp, CUDA on Modal); logistic regression; split by note. In the app the probe reads one layer per generated token (`l_out-32`) and each sentence is coloured by its mean score.

| Test | Result |
|---|---|
| Held-out notes, teacher-forced answers (app format), layer 32 | **AUC 0.998** (facts 0.995, unanswerable 1.0), accuracy 99.5% |
| Controls: shuffled labels / random direction | 0.62 / 0.63 (above 0.5: layer 32 carries some generic signal; noted) |
| Gemma's **own** answers, 444 replies to unseen notes, labelled by Qwen3.5-4B judge + exact value checks | **Inconclusive.** With the persona, Gemma got 293/296 facts right and almost never invented on unanswerable questions (~3 genuine inventions; most judge "inventions" were honest refusals). Too few real positives to measure. |
| Phone (Pixel 9 Pro) | Correct answers 🟢 (p 0.84, 0.99). Pushed to "just guess", Ronie refused to invent; honest refusal scored 0.93 and 0.40 (one mild yellow). No speed cost (10.2–10.8 tok/s). |

Lessons (each cost a rerun): the first probe, trained on a short system prompt, coloured correct phone answers 🔴/🟡: probes must be trained on the deployment prompt. Labelling own answers with regexes failed against Ronie's casual style; Gemma via the chat endpoint "thought out loud"; Android's JSON parser rejects `NaN`.

Honest status: works as a demo and a hint; real-world accuracy unmeasured. Measure it in Phase 7/8 on long, realistic emails and search results (where invented details actually happen), then decide whether it turns on automatically for grounded answers.

Earlier finding along the way: with a short generic prompt, Gemma did invent (41/148 unanswerable), and a probe trained in that format caught it with AUC 0.96 on held-out own answers. The persona's "be honest" instructions are doing real work.

## No-loops (Qwen tiers): parked

Measured first: in thinking mode Qwen3.5-2B ran out of a 1,536-token budget on 69–73% of 45 maths problems and got 24–27% right; clear repetition loops in 36–49% (greedy and sampled). But the app runs Qwen with thinking **off** and only on lower tiers, so a no-loops patch would change nothing users see today. Parked by decision; the trace data, pairs and Modal jobs (`eval/steering/loop_data.py`, `--job noloops`) are ready if thinking mode is ever enabled for tiers B/C.

## Still open from the Phase 3.5 plan
- Patch screen (install/toggle/uninstall with receipts) → folded into the Phase 4 settings drawer.
- Open-source `rpatch` repo and the 60-second demo video.
