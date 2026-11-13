# Ronie — Build Plan

A fully-local Android personal assistant: all model processing on the phone; only web search and remote MCP connectors touch the internet, each labelled "sends data to X". Persona: see [persona-ronie.md](persona-ronie.md).

Version 2 · 2026-11-08 — adds features learned from [competitor-analysis.md](competitor-analysis.md) (marked 🆕)

**Progress:** see [STATUS.md](STATUS.md) for what's done (ticked), what changed, extras added along the way, and the next steps.

### Competitor-driven additions (v2)

| # | Feature | Learned from | Phase |
|---|---|---|---|
| 1 | Ronie as the **default Android assistant** (long-press home / power) | AnythingLLM Mobile | 3 |
| 2 | **First-run brain picker**: all 3 curated models with the recommended one on top, sizes and RAM shown; one model installed at a time; no custom import | Loci, AnythingLLM auto-selection | 3 |
| 3 | **Offline Wikipedia** (Kiwix ZIM) import for grounding with zero network | OnDevice LLM (OSS) | 8 |
| 4 | **Claim checking**: flag answer sentences not supported by cited sources | OnDevice LLM (OSS) | 8 |
| 5 | **Background-safe model downloads** that survive app close | OnDevice LLM's known limit (downloads stop) | 3 |
| 6 | **Keep-cool thermal mode** | OnDevice LLM (iOS) | 12 |
| 7 | **Semantic search across past chats** ("Smart Recall") | OnDevice LLM (iOS) | 6 |
| 8 | **NPU path** (QNN / LiteRT-LM) evaluation | Layla; OnDevice LLM found phone GPU no faster than CPU | 12 |
| 9 | **Share sheet** into Ronie from any app | PocketPal, Layla, AnythingLLM | 5 |
| 10 | **Privacy honesty**: no analytics SDKs, Play Data-safety = "no data collected", signed Play builds, generous free core | Avoid Loci label mismatch, unsigned APKs, "1 model, 1 chat" free tiers | 13 |

---

## 0. Summary

| Item | Decision |
|---|---|
| Platform | Android (Kotlin, Jetpack Compose), min SDK 29, target SDK 35 |
| **Model policy** | ✅ **One curated language model installed per phone.** First run shows all 3 with Ronie's recommendation on top (sizes and RAM shown); no custom imports. See [eval/DECISION.md](eval/DECISION.md) |
| Tier A: Full (≥ 12 GB RAM, Vulkan GPU) | **Gemma 4 E2B** (2.9 GB), GPU by default |
| Tier B: Standard (≥ 8 GB, dotprod) | **Qwen3.5-2B** (1.2 GB) + `.rpatch` fixes, CPU + KleidiAI |
| Tier C: Lite (≥ 4 GB) | **Qwen3.5-0.8B** (0.5 GB) + `.rpatch` fixes, CPU + KleidiAI |
| PC-side only | **Qwen3.5-4B** as teacher for labels and patches; never shipped to phones |
| ~~Speculative decoding~~ | Dropped: doesn't work with Qwen3.5's recurrent layers (Phase 1) |
| Voice out | Paradee-8M v1.2, `onnx/paradee_int8.onnx` (8.5 MB, Apache-2.0) |
| Voice in | **v0 (Phase 4): Android's on-device speech recognizer** (local, nothing to download). Fallback for phones without it: Whisper (tiny/base) via whisper.cpp. Check whether Gemma 4 E2B's native audio input works in llama.cpp; it could replace both |
| Tool layer | MCP everywhere — built-ins in-process, connectors over Streamable HTTP |
| Memory | On-device encrypted DB + vector search |
| Model improvement | Weight-space edits (directional ablation / steering) driven by logged real failures; distilled classifiers for repeated jobs |
| Privacy | Fully-local inference; network only for search, remote MCP, mail provider |

### Success targets (measured on a real mid-range phone)

| Metric | Target |
|---|---|
| Time to first token (chat) | < 1 s |
| Generation speed, main brain | ≥ 20 tok/s |
| Time to first spoken word | < 1.5 s after reply starts |
| Tool-call accuracy (own eval) | ≥ 90% |
| Email "important" precision | ≥ 90% |
| Thinking-loop rate | < 2% |
| Persona eval | ≥ 90% overall, 100% on safety rules |
| Background battery | < 3% / day for email watcher |
| Peak RAM (2B + TTS + STT) | < 2.5 GB |

---

## 1. Architecture

```
┌──────────────────────────── UI (Compose) ─────────────────────────────┐
│ Chat · Voice · Daily Brief · Tasks · Inbox · Connectors · Memory ·    │
│ Activity Log · Settings (voice, persona, offline mode, model tier)    │
└───────────────────────────────┬───────────────────────────────────────┘
                                │
┌───────────────────────── Orchestrator ────────────────────────────────┐
│ Conversation manager · Thinking switch · Loop guard · Persona prompt  │
│ Context builder (profile snippet + memories + tools) · Confirm gate   │
└───────┬──────────────┬───────────────┬────────────────┬───────────────┘
        │              │               │                │
  ┌─────▼─────┐  ┌─────▼─────┐   ┌─────▼──────┐   ┌─────▼──────┐
  │ LLM engine│  │Tool router│   │  Memory    │   │  Voice     │
  │ 2B + 0.8B │  │ embed+topk│   │ facts/epis.│   │ Whisper in │
  │ spec-dec  │  │ schema val│   │ vector idx │   │ Paradee out│
  └───────────┘  └─────┬─────┘   └────────────┘   └────────────┘
                       │
        ┌──────────────┴──────────────────────────────┐
        │                MCP client layer             │
        │ in-process servers:            remote:      │
        │ mail · tasks · calendar ·      Notion,      │
        │ memory · files · web search ·  GitHub, …    │
        │ camera/vision · notifications  (OAuth)      │
        └─────────────────────────────────────────────┘

Background (WorkManager): mail check · nightly memory consolidation (charging) · model update check
```

### Module layout

```
ronie/
  app/                 Compose UI, navigation, DI (Hilt)
  core/engine/         LLM runtime wrapper (JNI → llama.cpp or MNN), sampling, spec decoding, loop guard
  core/voice/          whisper.cpp JNI, Paradee ONNX, phonemizer, audio I/O
  core/memory/         Room/SQLCipher schema, embeddings, retrieval, consolidation
  core/tools/          MCP client, in-process servers, tool router, schema validator, confirm gate
  core/net/            search provider, PII scrubber, activity log, offline-mode switch
  feature/mail/        Gmail API / IMAP, triage pipeline, notifications
  feature/tasks/       tasks, reminders, calendar read
  feature/brief/       daily brief
  feature/vision/      camera / gallery input
  models/              download manager, checksums, tier selection
  eval/                on-device eval runner (bake-off + regression)
tools-pc/              Python: bake-off harness, failure analysis, weight editing, distillation, quantization
```

---

## 2. Tech stack

| Concern | Choice | Notes |
|---|---|---|
| Language/UI | Kotlin, Jetpack Compose, Material 3 | |
| DI / async | Hilt, Coroutines + Flow | Token streaming as `Flow<String>` |
| LLM runtime | **llama.cpp** (GGUF, JNI) — fallback **MNN** | Phase 1 must verify Qwen3.5 Gated-DeltaNet + vision support |
| Quantization | Q4_K_M default; Q5_K_M on 8 GB+ phones | Re-check weight edits survive quantization |
| TTS | ONNX Runtime Android + Paradee int8 | 24 kHz, `AudioTrack` streaming |
| Phonemizer | espeak-ng (Android build) + port of `web/misaki.js` to Kotlin | espeak-ng is GPL-3.0 → fine for personal use; replace with misaki-dictionary port before any public release |
| STT | whisper.cpp (tiny.en or base) | Push-to-talk first; wake word later |
| Embeddings | Small ONNX embedding model (~20–300M; choose in Phase 6) | Used by memory + tool router |
| DB | Room on SQLCipher; vectors in a table with brute-force cosine (fine to ~50k rows) | Upgrade to ObjectBox/sqlite-vec only if needed |
| Secrets | Android Keystore + EncryptedSharedPreferences | OAuth tokens, IMAP passwords, API keys |
| Background | WorkManager (periodic ≥ 15 min), foreground service only for long jobs | Doze will delay checks — expected |
| MCP | Official Kotlin MCP SDK | Streamable HTTP + OAuth via Custom Tabs |
| Mail | Gmail API (OAuth) **or** IMAP (app password) | See Phase 7 caveat on Gmail scopes |
| PC tooling | Python, PyTorch, HF Transformers, vLLM, EvalPlus-style harness, llama.cpp `convert`/`quantize` | |

---

## 3. Phases

Each phase lists **goal → tasks → exit criteria**. Phases 1–2 are go/no-go gates; don't build features before they pass.

### Phase 0 — Environment setup (½ day)

Tasks:
- Android Studio → SDK Manager → install **NDK (side by side)** and **CMake** (currently missing).
- Add `…\Android\Sdk\platform-tools` to PATH so `adb` works.
- Enable USB debugging on a real test phone; record its SoC, RAM, Android version.
- Create the Git repo with the module layout above; CI = Gradle build + unit tests.
- PC: Python venv for `tools-pc/`; note GPU and VRAM (weight-edit work on 2B needs ~8 GB VRAM for comfort; CPU works but slowly).

Exit: empty app installs and runs on the phone; `adb logcat` works.

### Phase 1 — Runtime spike (go/no-go) (3–5 days)

Goal: prove every model runs on the phone before writing features.

Tasks:
1. Build llama.cpp for Android (arm64-v8a) via CMake/NDK; load **Qwen3.5-0.8B GGUF**; generate text. Check:
   - Gated DeltaNet layers supported and outputs match desktop (same prompt, greedy decoding).
   - Vision projector (mmproj) works for one image.
   - Token speed, RAM, time-to-first-token.
2. If llama.cpp lacks support → repeat with **MNN**.
3. Load **Qwen3.5-2B** and record the same numbers.
4. Run **Paradee int8 ONNX** with ONNX Runtime Android on a hard-coded phoneme sequence → play audio. Then wire espeak-ng → misaki converter for real text.
5. Run **whisper.cpp tiny.en** on a recorded clip.
6. Load all three at once; measure peak RAM and thermal throttling over 5 minutes.
7. 🚀 **Activation-hook check** (needed for the moonshots): confirm the runtime can read and add/subtract a direction at chosen layers during generation (llama.cpp control vectors — verify they work with Qwen3.5's Gated DeltaNet layers). Measure the speed cost. If unsupported, scope a small engine patch or fall back to baking edits into weights on the PC.

Reference numbers to beat (OnDevice LLM, Galaxy S25+ CPU, 6 threads): Qwen3.5 0.8B Q8_0 **40–48 tok/s**, Gemma 4 E2B **25–30 tok/s** (~4.5 GB RAM).

Exit criteria:
- 2B ≥ 15 tok/s (pre-optimization) on test phone, outputs match desktop.
- Paradee speaks a sentence faster than real time.
- Combined RAM within the phone's budget.
- **If Qwen3.5 can't run on any runtime → switch main-brain candidate to Gemma 4 E2B (LiteRT) and redo this phase.**

### Phase 2 — Model bake-off (1 week)

Goal: choose the main brain and background model from real data.

Candidates: Qwen3.5-0.8B, Qwen3.5-2B, Gemma 4 E2B, LFM2.5-1.2B (check its licence), optional Qwen3.5-4B.

Eval sets (build once, reuse forever as regression tests — store under `eval/`):

| Set | Size | What | Scoring |
|---|---|---|---|
| Tool calls | 50 | Real app tool formats: create task, search mail, web search, MCP call, ask clarifying question | Exact tool + valid args |
| Email triage | 100 | Your own emails hand-labelled important / not (stays on your PC) | Precision / recall |
| Summaries & memory extraction | 30 | Email threads + chats → key points / profile facts | Rubric 1–5 + fact accuracy |
| Persona | 60 | From persona spec §6 | Pass/fail per bucket |
| Grounded QA | 30 | Question + search snippets → answer with citation | Correct + cites + no invention |
| Loop test | 50 | Thinking-mode math/planning prompts | % hitting token cap |
| Vision | 20 | Receipts, screenshots, documents | Correct extraction |

Run each model × {thinking, non-thinking} on PC first (fast iteration), then the top two on the phone for speed/RAM/battery.

Failure triage for every miss (feeds Phase 10):
`loop/token-cap · malformed output · wrong tool · wrong format · persona break · sycophancy · injection followed · clean-but-wrong`

Exit: written decision (`eval/DECISION.md`) naming main brain, background model, quantization, default mode per task — with the numbers.

### Phase 3 — MVP chat (1–2 weeks)

Goal: talk to Ronie in text, fully offline.

Tasks:
- **Model download manager**: first-launch download from Hugging Face over Wi-Fi, resumable, SHA-256 verified, stored in app-private storage. Pick tier by RAM (`ActivityManager.MemoryInfo`).
  - 🆕 **Background-safe**: run downloads as a WorkManager job (foreground notification with progress) so they continue when the app is closed or the screen is off; resume after reboot/network loss.
  - 🆕 **Automatic tier selection (one model per phone):** read RAM, CPU features (dotprod/i8mm), Vulkan support and free storage → pick Tier A/B/C → run a ~10 s first-run speed check → drop a tier if it's too slow → download **only the chosen model**. First-run screen lists all 3 curated brains with the recommendation on top, each with download size and RAM use next to the phone's RAM and free storage; heavier ones warn, ones that don't fit are disabled.
  - 🆕 **Automatic step-down:** if sustained speed is under ~6 tok/s or the app is repeatedly killed for memory, offer once to switch to the lighter tier.
  - ~~Custom model import~~: removed. Ronie is curated; every shipped model is evaluated and patched.
- 🆕 **Default assistant role**: request `RoleManager.ROLE_ASSISTANT` so long-press home / power opens Ronie (voice-ready overlay); onboarding step explains how to switch back.
- **Engine service**: load/unload models, streaming tokens as `Flow`, cancellation, sampling presets from the Qwen card (non-thinking: T 1.0, top-p 1.0, top-k 20, presence 2.0; thinking: T 1.0, top-p 0.95, top-k 20, presence 1.5).
- **Thinking switch**: default non-thinking; rule-based router turns thinking on for math / multi-step / planning prompts; user toggle overrides.
- **Loop guard**: stop if the last N tokens repeat an n-gram > K times or thinking exceeds a budget (e.g. 2,048 tokens on phone); then retry once in non-thinking mode.
- **Persona**: compact system prompt from persona spec §4; tone dial; serious-topic detector (keyword + small classifier later).
- Chat UI: streaming bubbles, collapsible "Ronie is thinking…", copy/share, conversation list (Room).
- 🆕 **Ronie Cards (on-device "Intelligent UI")**, see [ui-direction.md](ui-direction.md): a fixed native component library (`text`, `checklist`, `action_buttons`, `source_list` first), JSON-schema-constrained decoding so block JSON is always valid, and blocks rendered progressively as they stream. Plain text stays the default; cards only for plans, lists, comparisons, numbers, emails and actions.
- KV-cache reuse for the fixed system prompt (prefix caching) to cut time-to-first-token.

Exit: offline chat meets TTFT < 1 s and ≥ 20 tok/s on 2B (or best achievable, recorded); persona eval ≥ 80% (prompt-only baseline); downloads survive closing the app; long-press home opens Ronie.

### Phase 3.5 — 🚀 Moonshot demo, early (2–3 weeks)

Goal: a pitch-ready demo for recruiters and GitHub as soon as basic chat works. Details in [moonshots.md](moonshots.md).

Tasks:
1. **Engine activation hooks** in the app (read + add/subtract directions per layer, on/off per turn).
2. **`no-loops` patch** made on the PC with the Phase 10 method (contrast set → direction → random-direction control) and packaged as **`.rpatch` v0**: JSON manifest (model hash, layers, strength, version) + direction vectors + **eval receipt** (before/after loop rate and accuracy).
3. **Patch screen**: install / toggle / uninstall a patch instantly; shows its receipt.
4. **X-ray Mode v0**: one honesty-meter probe trained on the PC from the grounded-QA set (grounded vs. invented sentences); colours text 🟢/🟡/🔴 while streaming; shows the probe's measured accuracy in-app.
5. **Open-source the toolkit**: `rpatch` repo with spec, Python tooling and the `no-loops` patch reproducible end to end.
6. **60-second demo video + README GIF** (script in moonshots.md, shortened: X-ray catches a made-up answer → install `no-loops` → before/after chart → airplane mode on).

Exit: on the test phone, toggling `no-loops` measurably lowers the loop rate with no accuracy drop > 2 pts; X-ray probe AUC reported honestly; toolkit reproduces the patch from a clean clone; demo video recorded.

### Phase 4 — Voice (1–2 weeks)

Tasks:
- **TTS pipeline**: LLM token stream → sentence splitter → strip emoji/markdown → phonemize → Paradee (`input_ids`, `speed`, `voice`) → `AudioTrack`. Pre-generate the next 2 sentences while one plays (Chickadee pattern). Chunk under 512 phoneme ids.
- **Ronie's voice**: fixed to Paradee `am_puck`, the same voice Ronie Web uses in Kokoro. Look its index up in Paradee's `config.json`. Settings offer speed and spoken replies on/off. A/B listen against Ronie Web's Kokoro `am_puck`.
- **Visor avatar**: 2D animated visor faces taken from Ronie Web's 23 expressions, shown in the chat header and in notifications.
- **STT**: push-to-talk mic button → whisper.cpp → text into chat. Voice-activity detection to auto-stop.
- **Barge-in**: user taps/speaks → stop TTS immediately.
- Word highlighting from Paradee timestamps (nice-to-have).
- Later: on-device wake word "Ronie" (evaluate openWakeWord-style small model; check licences).
- 🆕 **Settings drawer** (like ChatGPT / Claude): swipe right from the left edge, or tap ☰, to open a side panel. It replaces the Tone and X-ray buttons in the top bar. Sections:
  - **Ronie**: Tone slider (Clean ↔ Sweary) and later Brain-print sliders; X-ray Mode switch with its measured accuracy
  - **Voice**: spoken replies on/off, speed
  - **Models**: the installed brain (size, RAM, speed on this phone), switch brain (same curated picker), and the **image model** (download / remove; see Phase 9.5)
  - **Patches**: installed `.rpatch` files with their receipts (toggle / remove)
  - **Privacy**: activity log, offline mode, "what leaves the phone"
  - **About**: version, licences, open-source credits
  Chat history can sit in the same drawer later, as in ChatGPT.

Exit: full spoken round trip; first audio < 1.5 s after reply starts; Ronie sounds recognisably like Ronie Web; every setting is reachable from the drawer.

### Phase 5 — Tool layer & MCP connectors (2 weeks)

Tasks:
- **In-process MCP servers** (same interface as remote): `tasks`, `memory`, `calendar` (read), `files`, `notifications`, `web_search`, `mail` (Phase 7 fills it in).
- **Tool router**: embed tool name + description once; per request pick top 3–5 by cosine + always-on core tools; compress schemas (drop optional fields, one-line descriptions).
- **Tool-call parser + schema validator**: parse Qwen tool-call format; on invalid JSON → one automatic repair retry with the error message; then fail gracefully.
- **Confirm gate**: any tool with `destructiveHint`, or not `readOnlyHint`, or that sends/posts/pays/deletes → confirmation card showing exactly what will happen. Enforced in code, not prompt.
- **Injection boundary**: tool results wrapped as quoted data in the prompt; the orchestrator never executes actions requested *by* tool output without a user confirm.
- **Remote connectors**: add by URL or preset; OAuth via Custom Tabs; tokens in Keystore; per-tool toggles; "Sends data to <host>" label; test button; error log.
- **Elicitation** support (server asks user a question → in-app form). **Sampling** off by default.
- **Activity log**: every outbound request (search, MCP, mail fetch) with host, time and payload summary.
- **Offline mode** switch: blocks all network tools.
- 🆕 **Share sheet**: register `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for text, links, images and documents → "Ask Ronie" with quick actions (Summarise · Explain · Add task · Remember this). Links are fetched only after showing "Sends data to <host>".

Exit: tool-call eval ≥ 90% on chosen model (with router + repair); 100% of side-effect tools gated; injection tests pass; sharing from Chrome, Gallery and Files works.

### Phase 6 — Memory & user profile (2 weeks)

Data model:

```
profile_fact(id, subject, predicate, object, confidence, source_type, source_id,
             created_at, updated_at, last_used_at, pinned, deleted)
episode(id, kind[chat|email|doc], title, summary, key_points, started_at, ended_at, embedding)
chunk(id, episode_id, text, embedding)             -- for document / long-thread retrieval
task(id, title, due_at, status, source_id, reminder_at)
```

Tasks:
- **Extraction** (background model, after each conversation / processed email): pull candidate facts + key points as JSON; facts below confidence threshold go to a "Did I get this right?" queue instead of the profile.
- **Retrieval**: for each turn, embed the message → top-k facts + episodes → `{profile_snippet}` (cap ~200 tokens).
- **Nightly consolidation** (WorkManager, requires charging + idle): merge duplicate facts, resolve conflicts (newest + highest confidence wins, old kept as history), summarise the day into an episode, decay unused low-confidence facts.
- **"What Ronie knows about you"** screen: browse, edit, pin, delete, export (JSON), wipe all.
- 🆕 **Semantic search across past chats** ("Smart Recall"): search box in the conversation list ranks chats and episodes by meaning (same embedding index), and Ronie can answer "what did we decide about X last week?" by retrieving old chats.
- Encryption at rest (SQLCipher key in Keystore).

Exit: after a week of real use, ≥ 90% of shown facts are correct (manual audit); user can delete any fact and it never comes back.

### Phase 7 — Email watcher, tasks, daily brief (2 weeks)

**Detailed step-by-step build plan (for any agent): [phase7-plan.md](phase7-plan.md).** The summary below is kept for overview. Note: Outlook.com no longer accepts app passwords for IMAP (OAuth2 only), so IMAP + app password covers Gmail, Yahoo, iCloud and others; Outlook is an optional OAuth step.

**Mail access — choose one:**
- **Gmail API (OAuth, `gmail.readonly` + `gmail.compose` for drafts)**. Caveat: these are *restricted* Google scopes. For personal use keep the OAuth app in *Testing* with yourself as a test user (refresh tokens in Testing status expire after ~7 days → periodic re-sign-in). Publishing to others requires Google verification and a security assessment.
- **IMAP + app password** (Gmail, Outlook, others): no verification, long-lived, works with any provider. Recommended for personal use; drafts via IMAP `APPEND` to Drafts.

Triage pipeline (WorkManager every 15–30 min, network required):
1. Fetch new message headers + snippets since last UID.
2. **Rule prefilter** (no model): VIP senders, your-name mentions, calendar invites, bills/deadlines keywords → important; newsletters/promos (List-Unsubscribe header, categories) → skip.
3. Remaining → **0.8B importance scoring** with a short prompt (later replaced by distilled classifier, Phase 11).
4. Important → notification in Ronie's voice ("📬 Heads up — …") + summary on tap; extract tasks/dates → suggest task (needs confirm).
5. Store summaries as episodes for memory.

Other tasks:
- Tasks & reminders (AlarmManager exact alarms where permitted), calendar read via CalendarContract.
- **Daily brief** (user-set time): important mail, tasks due, calendar, weather (web) → text + optional spoken.
- Draft reply: Ronie writes in *your* tone → saved as draft only; **you** send from your mail app or after explicit confirm.

Exit: one week live: precision ≥ 90%, recall ≥ 80% on spot-checked mail; battery within target.

### Phase 8 — Web search & grounding (1 week)

Tasks:
- Provider abstraction: Brave Search API (API key), self-hosted SearXNG, or similar. Label: "Sends data to <provider>".
- **PII scrubber** before every query: strip names, emails, phone numbers, addresses from profile unless essential; show the exact outgoing query in the Activity log.
- Fetch top results → extract readable text → chunk → retrieve best chunks → answer with numbered citations; refuse to answer factual questions without sources when the model is unsure.
- Router: factual / current-events questions → search automatically (unless offline mode); otherwise answer locally.
- 🆕 **Offline Wikipedia**: import a Kiwix `.zim` file (e.g. Wikipedia "nopic"/mini variants; user picks size vs. coverage) via the download manager; build/ship a local search index over it; route general-knowledge questions to it **first**, so they're grounded with **no network request at all**. Works in offline mode. Label: "On-device".
- 🆕 **Claim checking**: after answering, split the reply into sentences and check each against the retrieved chunks (embedding similarity + a short 0.8B yes/no "supported?" check); unsupported sentences get a subtle ⚠ "not in sources" marker, and the citation list only links sentences that are supported.
- Router order: memory/user data → offline Wikipedia → web search (if allowed) → model knowledge (with "not verified" tone).

Exit: grounded-QA eval ≥ 85% correct with citations; zero answers presenting unsourced facts as sourced; ≥ 60% of general-knowledge eval questions answered from offline Wikipedia without any network call; claim checker flags ≥ 80% of injected unsupported sentences.

### Phase 9 — Vision (1 week)

**Detailed step-by-step build plan (for any agent): [phase9-plan.md](phase9-plan.md).** The summary below is kept for overview.

Tasks:
- Camera / gallery / screenshot share → image into the model (non-thinking for OCR/doc tasks — better per the Qwen card).
- Use cases: read receipts → expense note, documents → summary, screenshots → explain, photos → "what is this?".
- Downscale images to cap token count; show a "processing image" state.

- 🆕 **Candidate: Cactus Mirage** (22 MB, structured JSON with objects, boxes, OCR, masks and forms; 74–107 ms on a Galaxy S26). Test it against Qwen's vision encoder (2.5 s per image on the Pixel) on the same eval set. Check its licence first. See [phase1-results.md](phase1-results.md).

Exit: vision eval ≥ 85%; image turn completes in < 10 s on test phone.

### Phase 9.5 — 🆕 Image generation (1–2 weeks)

**Detailed step-by-step build plan (for any agent): [phase9-plan.md](phase9-plan.md).** The summary below is kept for overview.

Goal: "Ronie, draw me a cat astronaut in watercolour" → the picture appears in the chat, made on the phone. Optional and downloaded separately, so phones that can't run it never pay for it.

Tasks:
- **Pick the model (bake-off on the Pixel)**. Goals: speed, quality and compatibility together.
  - **Lead candidate: DreamLite** (ByteDance, ECCV 2026). 0.39B U-Net, 4-step distilled, 1024×1024 in < 3 s on an iPhone 17 Pro, GenEval 0.72. It also **edits images** ("make the sky purple") in the same model. ⚠️ Code is Apache-2.0, but the **weights are CC BY-NC 4.0 (non-commercial)** and released after a safety review. Fine for a free app; blocks a paid tier unless ByteDance grants a licence. Diffusers support is merged, which makes conversion easier.
  - **Challenger: SnapGen / SnapGen++** (Snap). 0.38B, 1024² in ~1.4–1.8 s on phones, with a 0.3B variant for low-end Android. Only usable if open weights are released; check first.
  - **Licence-safe fallback**: a small Stable-Diffusion-family model (512², ~1–2 GB, permissive licence, many existing Android ports). Clearly lower quality.
  - Measure on the Pixel 9 Pro and a mid-range phone: seconds per image, peak RAM, GenEval-style prompt adherence on our own prompt set, and how much the LLM slows down while it runs.
- **Runtime**: convert the winner (U-Net/DiT, text encoder, VAE) to ONNX → LiteRT (GPU/NPU delegate), or use stable-diffusion.cpp if it supports the model. Run it in a foreground worker, **only when the LLM is idle** (one heavy model active at a time to stay within RAM).
- **Downloads**:
  - **First run**: under the brain picker, an optional "Also download Ronie's drawing brain (X GB)" checkbox, unticked by default on phones it would be slow on.
  - **Later**: Settings drawer → Models → Image model → Download / Remove. Same resumable, SHA-256-checked worker as the LLM.
- **In chat (works behind the scenes)**: Ronie decides when to draw through a `generate_image` tool (Phase 5 tool layer). He writes the detailed image prompt himself from the user's description, and the picture streams into the chat as an image card (progress → image). Tap to view, save to gallery, share, or "edit this" (DreamLite editing).
- **Limits**: the hard limits apply to images too: nothing sexual involving minors. A small on-device image safety check runs before the image is shown.

Exit: an image from a chat request in < 10 s on the test phone; no LLM crash or out-of-memory with both models installed; image prompt-adherence measured and recorded.

### Phase 10 — Weight-editing pipeline (ongoing, first pass 2 weeks)

Goal: remove the bad habits seen in real use, per the weight-space-programming approach. Edits **remove obstacles; they don't add knowledge.**

1. **Failure logging (opt-in, local)**: app stores failing turns with the triage label (Phase 2 categories). Export via USB to PC — never uploaded.
2. **Pick the top obstacle** by count (expected order: loops → malformed tool calls → sycophancy → persona drift → language mixing).
3. **Contrast set**: same prompts, failing vs. clean completions (resample with seeds).
4. **Find direction**: per-layer residual/MLP-output mean difference; choose layers by linear-probe separability.
5. **Hook test first** (reversible): subtract `α·(h·r)r` at inference, sweep α ∈ {0.5, 1, 1.5}.
6. **Bake into weights**: `W_down ← (I − α r rᵀ) W_down` in the chosen layers; record % weights changed.
7. **Controls**: random direction, same norm, same layers — must not reproduce the gain.
8. **Persona steering (experimental)**: *add* Ronie-style direction at small α; verify no tool-call or serious-topic regressions.
9. **Re-quantize** (Q4_K_M) and **re-evaluate on the quantized model** — edits can blur under quantization.
10. **Gate**: full eval suite; ship only if target metric improves (bootstrap 95% CI excludes 0) and no other metric drops > 2 points.
11. **Ship** as an over-the-air model update (download manager, checksum, keep previous model for rollback).

Exit (first pass): loop rate < 2% and tool-call validity ≥ 95% on quantized edited model.

### Phase 11 — Distilled helper classifiers (1–2 weeks)

Small models that do one repeated job better and faster than prompting the LLM.

| Classifier | Input | Labels from |
|---|---|---|
| Email importance | sender, subject, snippet, headers | Your hand labels + a larger model **run locally on your PC** (keeps mail off the cloud) |
| Tool selection | user message | Logged tool calls that succeeded |
| Serious-topic detector | user message | Hand-labelled + persona eval |
| Thinking-needed | user message | Which prompts improved with thinking in eval |

Train small (e.g. fine-tune a tiny encoder or logistic head on the existing embedding model), export ONNX, run with ONNX Runtime. Replace the LLM call only when the classifier beats it on held-out data.

Exit: each classifier beats its LLM baseline on held-out data at < 20 ms per call.

### Phase 12 — Performance & battery (1 week)

- **Speculative decoding**: 0.8B drafts for 2B (shared tokenizer), or Qwen3.5's built-in MTP head if the runtime supports it. Measure acceptance rate and real speed-up.
- Prefix caching for system prompt + profile snippet.
- GPU/NPU offload options of the chosen runtime; thread count tuning.
- 🆕 **NPU evaluation**: competitors report the phone GPU is no faster than CPU for generation, so test the NPU path — Qualcomm QNN (Snapdragon 8 Gen 1+, as Layla does) and/or LiteRT-LM / MNN NPU backends — on the chosen model. Keep only if it beats CPU on tok/s *and* battery, with identical outputs on the eval suite.
- 🆕 **Keep-cool mode**: watch `PowerManager.getThermalHeadroom()` / thermal status; when the phone heats up, step down (fewer threads → smaller model tier → pause background jobs) and show a small "🌡 cooling down" hint. User setting: Fast · Balanced · Keep cool.
- Unload 2B when idle; keep 0.8B warm only during background jobs.
- Battery profiling with Android Studio Energy Profiler + `dumpsys batterystats`.

Exit: all §0 targets met or documented with the gap.

### Phase 13 — Hardening & beta (1–2 weeks)

- Security review: Keystore usage, SQLCipher, no secrets in logs, exported components locked down, network security config (HTTPS only).
- Red-team set: prompt injection via email/web/MCP, data-exfiltration attempts through search queries, confirm-gate bypass attempts → must be 100% blocked.
- Full eval suite as an on-device regression run before each release.
- Crash reporting **local only** (or opt-in).
- Licence check: Qwen (Apache-2.0), Paradee (Apache-2.0), espeak-ng (GPL-3.0 — replace before public release), Whisper/whisper.cpp (MIT), any embedding model, LFM (if chosen).
- 🆕 **Privacy honesty** (Loci's store label contradicted its claims — don't repeat it):
  - No analytics, ads or crash SDKs bundled; verify with a dependency audit and by watching traffic (e.g. a local proxy) during a full eval run.
  - Play **Data safety** form must match reality ("No data collected" for the on-device core); privacy policy written from the actual network log.
  - Signed release builds via Play Store (no unsigned sideload APKs as the main channel).
  - Generous free core (chat, voice, memory, offline Wikipedia) — never a "1 model, 1 chat" free tier.
- 🆕 **Name & store listing** (see [naming.md](naming.md)): finish the name checklist (stores, domain, GitHub/HF/PyPI, trademark); brand **Ronie Pocket** (part of the Ronie family with Ronie Web and the glasses agent), Play title `Ronie Pocket: Offline AI`, keyword-rich short/full description, 4 story screenshots; GitHub topics + README hero GIF.
- Personal beta: 2 weeks daily use → feed failures back into Phase 10.

Exit: 2 weeks daily use with no data leaks, no unconfirmed actions, targets held.

### Phase 14 — 🚀 Moonshots (see [moonshots.md](moonshots.md)) (4–6 weeks)

One mechanism — reading and editing activation directions — powers all of these. **Prerequisite added to Phase 1:** confirm the runtime supports activation hooks / control vectors for Qwen3.5 (llama.cpp has control-vector support upstream — verify for Gated DeltaNet layers); if not, plan a small engine patch.

| Step | Feature | Exit |
|---|---|---|
| 14.1 | Engine activation hooks — **v0 done in Phase 3.5**; now optimise | < 5% speed cost; identical output with hooks off |
| 14.2 | **`.rpatch` v1**: more patches (`concise`, `no-sycophancy`, tool-call format), patch stacking, community submissions | Each patch has a reproducible receipt; stacked patches pass full eval |
| 14.3 | **X-ray Mode v1**: add sycophancy alarm + memory/source trace (v0 honesty meter done in Phase 3.5) | Probe accuracy measured and shown in-app |
| 14.4 | **Brain-print**: personal steering vector + Concise / Playful sliders | No tool-call regression > 2 pts at any slider position |
| 14.5 | **Ronie Sleeps v0**: nightly self-fix for loops + invalid JSON, self-test, auto-rollback, morning report | 7 nights of logged improvement with zero unrolled regressions |
| 14.6 | **Life Radar** + **Proof-of-Local** privacy receipt (Tier A) | Radar precision ≥ 80%; receipt matches traffic audit |

Phase 10's weight-editing pipeline becomes the PC-side of 14.2 / 14.5; Phase 14.5 moves it onto the phone.

---

## 4. Timeline (solo developer, rough)

| Weeks | Phases |
|---|---|
| 1 | 0, 1 (runtime spike) |
| 2 | 2 (bake-off) |
| 3–4 | 3 (MVP chat) |
| 5–7 | 3.5 (🚀 early moonshot demo: hooks, `no-loops` .rpatch, X-ray v0, open-source toolkit, demo video) |
| 8–9 | 4 (voice + 🆕 settings drawer) |
| 10–11 | 5 (tools + MCP) |
| 12–13 | 6 (memory) |
| 14–15 | 7 (mail, tasks, brief) |
| 16–17 | 8 (web search + 🆕 offline Wikipedia + claim checking) |
| 18 | 9 (vision) |
| 19 | 9.5 (🆕 image generation: DreamLite bake-off, in-chat drawing, optional download) |
| 19–20 | 10 (weight edits — expands the Phase 3.5 toolkit) |
| 21–22 | 11 (classifiers) |
| 23 | 12 (performance + 🆕 NPU + keep-cool) |
| 24–25 | 13 (hardening, naming, store listing, beta) |
| 26–30 | 14 (🚀 moonshots: Brain-print, Ronie Sleeps, more patches, Life Radar, Proof-of-Local) |

≈ 7 months total. Milestones: **week 4** offline chat · **week 7** first public demo (pitch + GitHub launch) · **week 9** talking Ronie · **week 15** mail-watching assistant with memory · **week 30** full moonshot version.

---

## 5. Risks & mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Runtime lacks Qwen3.5 DeltaNet / vision support | Blocks plan | Phase 1 gate; MNN fallback; Gemma 4 E2B (LiteRT) fallback |
| 2B too slow on mid-range phones | Bad UX | 0.8B tier, speculative decoding, shorter prompts, non-thinking default |
| Thinking loops | Battery, latency | Loop guard now; de-loop weight edit later |
| Small-model hallucination | Wrong answers | Search grounding with citations, "I don't know" persona rule |
| Prompt injection via email/web/MCP | Unwanted actions | Code-enforced confirm gate, data-not-instructions wrapping, red-team tests |
| Gmail restricted scopes | Token expiry / can't publish | IMAP + app password for personal use |
| Android background limits (Doze) | Late mail alerts | Accept 15–30 min latency; optional foreground mode while charging |
| Weight edits lost after quantization | No gain on phone | Always evaluate the quantized model |
| espeak-ng GPL | Can't ship closed-source | Personal use OK; port misaki dictionary before release |
| Memory stores wrong facts | Creepy / wrong | Confidence threshold, confirm queue, editable memory screen |
| RAM pressure with 3 models | Crashes | Lazy load/unload, measure in Phase 1 |
| 🆕 Privacy claims drift from reality (store label, SDKs) | Lost trust | No third-party SDKs; traffic audit before each release; Data-safety form from the network log |
| 🆕 A competitor (Loci, AnythingLLM, LLM Hub) ships on-device email first | Weaker differentiation | Prioritise the Phase 7 email watcher; ship an early personal build at week 12 |
| 🆕 Offline Wikipedia files are large (GBs) | Storage complaints | Offer small/"nopic" variants, show size before download, optional |

---

## 6. Immediate next steps

1. Install NDK + CMake in Android Studio; put `adb` on PATH.
2. Pick the test phone and note its SoC and RAM.
3. Start Phase 1: build llama.cpp for Android and run Qwen3.5-0.8B / 2B on the phone.
4. In parallel on PC: start building the Phase 2 eval sets (tool calls, your hand-labelled emails, persona set).
