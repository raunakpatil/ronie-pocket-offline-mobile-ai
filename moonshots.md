# Ronie — Moonshot Features

The features that make Ronie "nobody has done this" instead of "another offline chat app".
Researched 2026-11-08. "Nobody does this" = we found no shipping consumer app doing it in our searches (see Sources) — not proof, so re-check before claiming it publicly.

---

## The one-line pitch

> **Ronie is the first phone AI that rewires its own brain overnight — fixing its own mistakes and adapting to you by directly editing its neural weights, entirely on the device, and showing you live what's happening inside its head.**

Why it lands now: the weight-space programming result (MIT, **2 Nov 2026**) is days old; personalization-by-steering papers are landing this month (Upshift, arXiv 2611.02667); on-device LoRA training on phone NPUs is brand-new research. Nobody has packaged any of it into a consumer app.

---

## Tier S — headline features (pick these for the pitch)

### S1. 🌙 "Ronie Sleeps" — an assistant that rewires itself every night

**What:** While the phone charges at night, Ronie runs a **sleep cycle**:
1. **Replays the day** — reviews her own failed turns (loops, broken tool calls, things you corrected, "that's wrong" moments).
2. **Self-diagnoses** — finds the activation direction behind each recurring failure (the blog's de-loop method, automated).
3. **Self-repairs** — applies the fix as a reversible edit, **tests herself** on the built-in eval suite, keeps it only if scores improve and nothing regresses, otherwise rolls back.
4. **Consolidates memory** — merges facts, summarises the day (already in Phase 6).
5. **Morning report** — "Last night I fixed a habit of rambling on recipe questions (+14% on my tests). Here's what changed. Undo?"

**Why nobody does it:** the weight-space paper used a frontier model on a server with a Python kernel. Doing the diagnose → edit → verify loop **automatically, on a phone, with rollback** is new.

**Feasibility (honest):**
- Edits applied as **activation hooks** (`h ← h − α(h·r)r`), not rewritten weights → instant rollback, no re-quantization on the phone.
- Directions = mean differences over a few hundred stored activations → cheap; compute in minutes on CPU while charging.
- Self-test = the on-device eval suite we already build (Phase 2).
- Hard part: deciding *which* failures to fix without a frontier model. Start with detectable ones (loops, invalid JSON, user "no, that's wrong" corrections), not open-ended quality.

**Demo moment:** a time-lapse graph — "Night 1: 61% · Night 7: 83% on my own tests" — with each night's edit listed.

**Stretch:** nightly **LoRA** "dreams" using phone-NPU LoRA training research (Online-SDFT, first-order NPU LoRA) — experimental flag only.

---

### S2. 🧬 Your "Brain-print" — personal steering vectors

**What:** Ronie learns **how you like answers** (short vs. detailed, formal vs. playful, examples vs. theory, how blunt) as a tiny **personal steering vector** — a few KB — derived from your corrections, edits, regenerates and 👍/👎. Applied at inference: no training, no prompt tokens.

- **Sliders** in Settings that actually move the model's internals: *Concise ↔ Detailed*, *Playful ↔ Serious*, *Gentle ↔ Blunt*. Each slider = a direction × strength (research shows users like direct steering controls: "Steerable Chatbots", 14-person study).
- **Exportable "Brain-print" file** — your personalization moves with you to a new phone or the desktop build; it's yours, encrypted, not in anyone's cloud.

**Why nobody does it:** papers (Upshift Nov 2026, PersonaSteer EMNLP 2026, SteerX) are research-only; no consumer app ships per-user steering, and none on-device.

**Feasibility:** same hook mechanism as S1. Risk: steering can hurt tool-call accuracy → gate every slider position on the eval suite; tool-call turns use reduced α.

---

### S3. 🔬 X-ray Mode — see inside Ronie's head, live

**What:** A toggle that shows what's happening *inside* the model while it answers:
- **Honesty meter** — a linear probe on the residual stream estimates "is this sentence grounded or made up?" and colours text 🟢/🟡/🔴 in real time. (Research: a single linear residual probe reached F1 up to 0.99 / 0.84 out-of-domain for contextual hallucinations — ICML 2025.)
- **Sycophancy alarm** — a probe for "agreeing because you said so" (the blog found a sycophancy signal in specific attention heads). Ronie says "heads up, I might just be agreeing with you."
- **Memory trace** — which memories and sources were used for this answer.
- **Brain activity strip** — which layers / directions lit up (loop direction, persona direction, your brain-print), as a live visual.

**Why nobody does it:** hallucination probes exist only in papers; no consumer app shows live interpretability, let alone on a phone.

**Feasibility:** probes are a dot product per token → near-zero cost. Train on PC from contrast data (grounded vs. invented answers on the grounded-QA set), ship as a few-KB file. Must be honest: label it "estimate", show its measured accuracy.

**Demo moment:** ask Ronie something it doesn't know → the sentence turns red *as it's typed*, and Ronie self-corrects: "Actually, I'm not sure — want me to check?"

---

### S4. 🧩 Patch Store — an open format for shareable "behaviour patches"

**What:** Package a weight-space edit as a tiny, open **`.rpatch`** file (a few directions + layers + strength + eval proof), e.g.:
- `no-loops.rpatch` · `concise.rpatch` · `no-sycophancy.rpatch` · `hinglish-friendly.rpatch` · `kid-safe-tone.rpatch`
- Each patch ships with its **eval receipt** (before/after scores, random-direction control) so users can trust it.
- Install = download KBs, not GBs. Stack patches. Toggle on/off instantly.
- Community makes patches with the open-source PC toolkit (`tools-pc/`), shares on GitHub / Hugging Face.

**Why nobody does it:** research has steering → weight-edit methods (Steer2Edit, NeurIPS 2026; Training-Free Task Vectors, Sep 2026; EasyEdit2 toolkit) but **no shared patch format, registry or consumer store**. LoRA hubs exist; KB-sized behaviour patches with eval receipts don't.

**Honest limit:** patches change *behaviour*, not *knowledge* (the blog's Gemma control) — position them as "habits", never "skills".

**Why it's a GitHub magnet:** an open spec + library (`rpatch` for PyTorch / llama.cpp) is useful far beyond Ronie → stars from the interpretability and local-LLM communities.

---

## Tier A — strong differentiators

### A1. 📡 Life Radar — proactive cross-source reasoning
Ronie connects dots across mail, calendar, tasks, notifications and memory **without being asked**:
- "Your flight moved to 9:40 in the airline email, but your calendar still says 7:15 — update it?"
- "Priya's birthday is Friday; last year you ordered from X."
- "This invoice is due in 2 days and you haven't paid the last one either."
Nobody on-device is proactive (competitor analysis). Runs as a nightly + on-new-mail job on the 0.8B model with rule triggers.

### A2. 🛡 Proof-of-Local — a verifiable privacy receipt
- In-app **network firewall** (Android `VpnService`-based local filter, no remote server) that blocks every connection except allow-listed hosts per feature.
- **Privacy receipt** screen: "Last 7 days: 0 bytes of your chats left the phone. 23 search queries sent to Brave (view). 0 unknown hosts."
- "Airplane test" button: blocks everything and runs a full assistant session to *prove* it works offline.
Directly answers the trust gap (Loci's store-label contradiction). Nobody offers user-verifiable proof.

### A3. 🖐 Hands — Ronie operates your apps, fully local
Accessibility-service agent + local vision model: "Book the usual cab", "Turn on hotspot and share it with Mum". Open-source agents exist (Google Artemis Oct 2026, mobile-use, Droidrun, OpenDroid) but most rely on cloud models. **Fully local + confirm gate + Ronie's memory** is the new combination. Mark every action with a preview and a stop button.

### A4. 🎙 Daily Brief "Podcast"
Morning brief as a 2-minute spoken show in Ronie's voice (Paradee), with chapters (mail, calendar, weather, news you follow). Tap a chapter to act on it. Cheap to build, great demo.

---

## Tier B — stretch / later

- **B1. Ronie Mesh** — your old phone, tablet and laptop pool compute peer-to-peer on home Wi-Fi to run a bigger model when available (EXO-style). Field is immature (thermals, background suspension) → experimental.
- **B2. Glasses mode** — camera frames from smart glasses → phone Ronie for "what am I looking at?" (ties into the Meta Glasses project).
- **B3. Soul file** — one encrypted export of memory + brain-print + patches to move Ronie to a new phone or the desktop build.

---

## How the moonshots fit together

```
          ┌─────────── X-ray Mode (S3) ───────────┐
          │  probes read the same internals that  │
          ▼                                       │
  failures ──► Ronie Sleeps (S1) ──► edits ──► .rpatch (S4) ──► Patch Store
                    ▲                  │
   your corrections ┘                  └──► Brain-print (S2) = your personal patch
```

One mechanism — **reading and editing activation directions** — powers all four Tier-S features. That makes it buildable by one developer and makes the story coherent: *Ronie understands and edits her own brain.*

---

## What to build first (MVP of the moonshot)

| Step | Deliverable | Depends on |
|---|---|---|
| 1 | Activation-hook support in the engine (read + subtract/add directions at chosen layers) | Phase 1 runtime — **verify the runtime allows hooks**; llama.cpp may need a small patch (control-vector support exists upstream, check for Qwen3.5) |
| 2 | `.rpatch` v0 spec + PC toolkit that produces one patch (`no-loops`) with eval receipt | Phase 10 |
| 3 | X-ray honesty meter (one probe) | Grounded-QA eval set |
| 4 | Brain-print with 2 sliders (Concise, Playful) | Step 1 |
| 5 | Ronie Sleeps v0: nightly auto-fix for loops + invalid JSON only, with self-test + rollback + morning report | Steps 1–2 |

**Demo video script (60 s):** X-ray turns a made-up answer red → user corrects Ronie → overnight time-lapse "Ronie is sleeping… fixing 2 habits" → morning report with before/after scores → drag the *Concise* slider and watch replies change → install `no-sycophancy.rpatch` from the Patch Store. All in airplane mode, with the privacy receipt showing 0 bytes sent.

---

## Pitch angles

- **Recruiters:** applied mechanistic interpretability + on-device ML systems + Android engineering + eval discipline — rare combination; the eval receipts show rigour.
- **GitHub stars:** open `.rpatch` spec + toolkit, X-ray probes, and a reproducible "self-repair" benchmark. Post the night-by-night improvement chart.
- **Users:** "Gets better every night. Learns your style. Shows you when it's unsure. Never leaves your phone."

## Honest risks
| Risk | Mitigation |
|---|---|
| Runtime can't do activation hooks fast on phone | Bake edits into weights on PC as fallback; ship as model update |
| Self-edits make things worse | Mandatory self-test + random-direction control + automatic rollback; user Undo |
| Probes over-claim | Show measured accuracy; label "estimate"; never hide text |
| Steering hurts tool calls | Per-task α; tool-call turns use base or reduced steering |
| "First ever" claim is wrong | Re-search before launch; phrase as "first we know of" |

---

## Sources
- [Sparks of Weight-Space Programming (MIT, Nov 2026)](https://rikiyat.github.io/blog/weight-space-programming/)
- [Upshift: User Preference Steering from Human–AI Interaction Feedback Traces (arXiv 2611.02667)](https://arxiv.org/html/2611.02667)
- [PersonaSteer (Findings of EMNLP 2026)](https://aclanthology.org/2026.findings-emnlp.1270/)
- [SteerX (arXiv 2510.22256)](https://arxiv.org/abs/2510.22256)
- [Steerable Chatbots (Google Research)](https://research.google/pubs/personalizing-llms-with-preference-based-activation-steering/) · [arXiv 2505.04260](https://arxiv.org/html/2505.04260v3)
- [BiPO personalized steering vectors (arXiv 2406.00045)](https://arxiv.org/pdf/2406.00045)
- [BILLY: merging persona vectors (arXiv 2510.10157)](https://arxiv.org/pdf/2510.10157)
- [A Single Direction of Truth — linear residual probe for hallucinations (arXiv 2507.23221)](https://arxiv.org/abs/2507.23221) · [ICML 2025](https://icml.cc/virtual/2025/49586)
- [Baseten — Do transformers notice their own mistakes?](https://www.baseten.co/research/do-transformers-notice-their-own-mistakes/)
- [Zenodo hallucination/deception probes preprint (Jun 2026)](https://zenodo.org/records/20808218)
- [Training-Free Task Vectors for LLM Behavioral Control (arXiv 2609.09054)](https://arxiv.org/pdf/2609.09054)
- [Steer2Edit (NeurIPS 2026)](https://neurips.cc/virtual/2026/poster/153124) · [arXiv 2602.09870](https://arxiv.org/abs/2602.09870v1)
- [EasyEdit2 (arXiv 2504.15133)](https://arxiv.org/pdf/2504.15133)
- [EdgeTune: on-device LLM personalization (ACM 2026)](https://dl.acm.org/doi/full/10.1145/3774906.3802769)
- [First-Order LoRA Training with Phone NPUs](https://awesomepapers.io/llm-papers/papers/2610.24816)
- [LoRA-generating hypernetworks for on-device personalization (arXiv 2609.24979)](https://arxiv.org/abs/2609.24979v1)
- [Online-SDFT: on-device continual learning with self-distillation](https://lin826.github.io/SLM-Online-SDFT/)
- [TT-SI: self-improving agents with test-time training (ACL Findings 2026)](https://aclanthology.org/2026.findings-acl.462.pdf)
- [On-Device LLMs: State of the Union, 2026](https://v-chandra.github.io/on-device-llms/)
- [Google Artemis Android agent](https://pasqualepillitteri.it/en/news/25427/google-artemis-open-source-ai-agent-android-en)
- [Decrypt — Oppo X-OmniClaw on-device agent](https://decrypt.co/368238/oppo-x-omniclaw-android-ai-agent)
- [minitap mobile-use](https://github.com/minitap-ai/mobile-use) · [AndroidWorld task decomposition (arXiv 2602.07787)](https://arxiv.org/pdf/2602.07787)
- [OpenDroid](https://github.com/yashab-cyber/opendroid) · [AOHP OS-level agent harness (arXiv 2606.23449)](https://arxiv.org/pdf/2606.23449)
- [UI desynchronization threats in mobile agents (arXiv 2609.16732)](https://arxiv.org/pdf/2609.16732)
- [EXO & swarm inference](https://jvoltci.github.io/mosaic/edge-ai/distributed/exo-swarm) · [HearthNet](https://ckaller.blogspot.com/2026/06/hearthnet-building-ai-that-works-when.html)
- [Slash Digital — small models on-device](https://slash-digital.io/en/insights/small-models-on-device/) · [DEV — On-device AI on Android 2026](https://dev.to/privatejack/on-device-ai-on-android-2026-litert-gemini-nano-and-edge-deployment-57h0)
- [On-Device AI News, Nov 2026](https://blog.mean.ceo/on-device-ai-news-november-2026/) · [Layer3 Labs — best AI phones 2026](https://www.layer3labs.io/gear/best-ai-phones)
