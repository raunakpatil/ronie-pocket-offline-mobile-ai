# Ronie — Competitor Analysis

Researched 2026-11-08. Facts come from vendor pages, app-store listings and third-party roundups (several of which are sponsored or written by a competitor — noted where relevant). Treat vendor claims as claims; speeds are single-tester numbers.

---

## 1. TL;DR

- The market is crowded with **offline chat apps** (PocketPal, Maid, MLC Chat, ChatterUI, Private AI, Edge Gallery…). These are *model runners*, not assistants.
- A smaller group is moving toward **local assistants** with memory, documents, voice and phone actions: **Loci, OnDevice LLM (open source), AnythingLLM Mobile, LLM Hub, MemoryAI, Layla**.
- **Nobody we found does proactive, on-device email watching** ("tell me which emails are important") on a phone. Every on-device app treats email as drafts only, or doesn't touch it. Verified-by-search, not exhaustive.
- **Nobody is proactive at all**: every competitor waits for you to open the app and type. Ronie's email watcher + daily brief is the clearest gap.
- MCP on Android is rare: Loci acts as an MCP **server**; OnDevice LLM has MCP on **Windows only**; LLM Hub's listing mentions remote MCP servers. An Android MCP **client** with connector UI is still uncommon.
- Table stakes we must match: editable memory screen, tap-to-confirm actions, auto model selection by RAM, document Q&A, voice in/out, vision, cited web search, no account.

---

## 2. The competitors

### Tier A — closest to Ronie (assistant, not just chat)

#### Loci (Loci AI, Inc.) — the one you named
- **Platforms:** iOS/iPadOS 18+, Android, Mac, Windows (beta), visionOS.
- **Price:** free — no account, subscription, ads or message caps (vendor claim).
- **Models:** ~10 curated (Gemma 4 E2B/E4B, Qwen, Llama 3.2 3B, Phi-4); Apple's foundation model on Apple devices; llama.cpp + MLX picked automatically. **No custom GGUF import.**
- **Features:** global memory across chats and model switches; document library; meeting transcription + summaries; dictation/writing help; photo analysis; voice mode (on-device on Apple); calendar + reminders; DuckDuckGo "web RAG" with offline fallback; **Loci Link** (phone uses a bigger model on your Mac/PC); acts as an **MCP server** for your knowledge base; messaging integrations (iMessage, Telegram, Discord, Slack — vendor claim).
- **Weak spots:** small models struggle with nuance (reviewer); curated models only; no public API; Windows least mature. **Trust issue:** PromptQuorum found the App Store privacy label lists Location, Identifiers, Usage Data and Diagnostics as "linked to you", including for advertising — contradicting the "not uploaded / no ads" message.
- **Lesson:** strong product breadth and polish; the privacy-label contradiction shows how fast "local" trust can be damaged.

#### OnDevice LLM (open source, sebytzr16 — Android + Windows)
- **Price / licence:** free, MIT, no account. Small project (16 GitHub stars), APK sideload (not code-signed).
- **Models:** any GGUF; phone catalog defaults to **Qwen3.5 0.8B Q8_0 ("Everyday default")**, plus Qwen3.5 2B/4B, Gemma 4 E2B/E4B, Qwen3 1.7B. SHA-256-pinned downloads.
- **Features:** editable memory of facts you state; document RAG (PDF, Office, EPUB…) with **EmbeddingGemma** and cited passages; Whisper or Parakeet STT; **Kokoro TTS** (Supertonic optional); hands-free conversation screen; vision + on-device OCR; web research off by default with **"unsupported claims flagged"**; **offline Wikipedia (ZIM) import**; up to 4 parallel agents; "Goals" multi-step research reports; **phone actions** (timers, alarms, reminders, calendar, message drafts — nothing happens until you tap); MCP + local OpenAI/Anthropic-compatible server on **Windows only**.
- **Measured speed (Galaxy S25+, CPU, 6 threads):** Qwen3.5 0.8B Q8_0 **40–48 tok/s**; Gemma 4 E2B **25–30 tok/s**. Gemma 4 E2B uses **~4.5 GB RAM** → 8 GB phones recommended. Phone GPU (OpenCL) "not faster for writing"; **NPU not used**.
- **Weak spots:** downloads stop when app closes; no encrypted backup; no email reading; no background/proactive features.
- **Lesson:** this is the closest open-source technical match — it validates our stack (llama.cpp, Qwen3.5, Kokoro-class TTS, Whisper) and gives us real phone speed baselines.

#### AnythingLLM Mobile (Mintplex Labs — Android)
- **Price / licence:** GPLv3 open source; **68.6K+ Play downloads**.
- **Models:** on-device GGUF with Hugging Face browser and **"fit badge"** for your phone; context scaled to RAM; KV cache kept warm between turns; or connect to Ollama/LM Studio/desktop/cloud APIs.
- **Features:** built-in tools (web search + reading with citations, document retrieval, file generation, scheduling, personalization, calendar, location); share photos/docs/links from any app; vision; voice input; **can be set as the default Android assistant ("Replace Gemini", long-press home)**; lock-screen notifications; desktop pairing with synced threads.
- **Weak spots:** no voice output mentioned; no email; cloud options blur the privacy story.
- **Lesson:** the **default-assistant role** and **"fits your phone" badge** are cheap, high-impact features to copy.

#### LLM Hub (Android)
- Listing (from search snippet; Play page didn't load): on-device chat, RAG memory, document and photo questions; internet only for model downloads and optional **web search or remote MCP servers**.
- **Lesson:** at least one Android app already ships MCP connectors — we need to do it better (router, confirm gate, privacy labels), not just first.

#### MemoryAI (Android)
- Free, no ads/IAP, closed source; llama.cpp + LiteRT; launched 30 Mar 2026, ~1K+ downloads.
- **Features:** document Q&A; **"Agent Memory you can open, edit and delete"**; hands-free voice (STT + read-aloud); vision; PIN-locked Kid Mode.
- **Weak spot:** sends usage events and crash reports.
- **Lesson:** editable memory is becoming expected.

#### OnDevice LLM – Offline AI Chat (iOS, Mustafa Ergisi)
- Free tier = 1 model, 1 chat; **Pro $6.99/month or $14.99–19.99 lifetime**. Rating 2.5 (4 ratings).
- **Features:** personal memory that learns facts (view/delete/toggle); "Smart Recall" semantic search across chats; documents; vision (MiniCPM-V); calendar/reminders/contacts/location answers; alarms/events with tap-to-confirm; share extension; Shortcuts; HTML mini-apps in chat; Fast/Balanced/Keep-cool modes.
- **Weak spots:** reviewers report models not fitting in iPhone memory and broken HF import.
- **Lesson:** "Keep-cool" thermal mode and cross-chat semantic recall are nice touches.

#### Layla (Layla Network — Android + iOS)
- **Price:** free limited APK; full app **one-time purchase** (price not stated); optional cloud mode.
- **Models:** GGUF, LiteRT-LM, ExecuTorch; custom GGUF import; **Snapdragon QNN NPU** support (8 Gen 1+), OpenCL GPU.
- **Features:** personas/characters (Character Hub, Tavern cards), memory that "evolves with its user", 100+ local TTS voices (Kokoro, Piper…), agents (news, research), on-device Python scripting + SDK, Stable Diffusion image generation, VRM avatars with lip-sync, weekly mini-apps.
- **Weak spots:** companionship/roleplay focus over productivity; closed source; no email/calendar/RAG mentioned.
- **Lesson:** **persona drives engagement and paid conversion** — validates Ronie's persona; NPU support is a real speed differentiator.

### Tier B — local chat / model runners (Android)

| App | Price / source | Engine | Notable | Weakness |
|---|---|---|---|---|
| **PocketPal AI** | Free, open source | llama.rn (llama.cpp), Vulkan | Most-recommended all-rounder; HF downloads; per-model settings; benchmarking; share sheet | Utilitarian UI; chat only |
| **MLC Chat** | Free, open source | MLC compiler; NPU/Vulkan (sources conflict) | Fastest in one test (~22 tok/s Phi-4 Mini NPU on S25 Ultra) | Curated compiled models only; reportedly no upstream update since Sep 2024 |
| **Maid** | Open source (F-Droid) | llama.cpp, Vulkan | Works without Play Services (GrapheneOS) | Less polished; no docs/vision |
| **ChatterUI** | Open source | Local or remote backends | Character/roleplay customization | Harder for beginners |
| **Private AI** | Play | CPU | Minimal permissions | No advanced settings |
| **Google AI Edge Gallery** | Free (Google) | LiteRT, Gemma 4 | Official showcase, agentic "skills" | Demo app, not an assistant |
| **Off Grid** | MIT; Pro $49/yr or $69 lifetime | — | On-device Stable Diffusion via NPU, vision, voice | Spoken replies need Pro; needs strong phone |
| **Atomic Chat** | Apache-2.0, all platforms | Tauri + llama.cpp | MCP, local server, artifacts panel | Young project (sponsored listing) |
| **Laria** | $4.99 | LiteRT/ONNX/GGUF + cloud | Image + voice | Companion-style explicit samples; location possibly shared |
| **Mobile LM Server** | Free w/ ads | LiteRT-LM, llama.cpp | OpenAI/Ollama-compatible server on phone | Privacy policy vs Data-safety conflict |
| **Ollama via Termux** | Free | CPU | Full Ollama library + local API | 20–30 min terminal setup |

### Tier C — platform giants (indirect competition, from general knowledge — verify before relying)
- **Google Gemini / Gemini Nano** on Pixel and Samsung: deep OS integration, Gmail access — but most tasks go to the cloud.
- **Samsung Galaxy AI**: on-device options for some features; cloud for others.
- **Apple Intelligence**: on-device foundation model + Private Cloud Compute; iOS only.
- These own the OS and the default-assistant slot; Ronie's angle is **fully local + proactive + your own MCP connectors + transparent privacy**.

---

## 3. Feature matrix (closest competitors)

✅ = has it · ◐ = partial / limited · ❌ = not found · ? = unclear

| Feature | Loci | OnDevice LLM (OSS) | AnythingLLM | LLM Hub | MemoryAI | Layla | **Ronie (plan)** |
|---|---|---|---|---|---|---|---|
| Fully on-device inference | ✅ | ✅ | ✅ (+cloud opt.) | ✅ | ✅ | ✅ (+cloud opt.) | ✅ |
| Auto model by hardware | ✅ | ◐ catalog | ✅ fit badge | ? | ? | ❌ | ✅ |
| Growing memory / profile | ✅ | ◐ stated facts | ◐ personalization | ✅ RAG | ✅ editable | ✅ | ✅ auto + editable |
| Document Q&A | ✅ | ✅ cited | ✅ | ✅ | ✅ | ❌ | ✅ |
| Voice in | ✅ | ✅ | ✅ | ? | ✅ | ? | ✅ |
| Voice out | ✅ | ✅ Kokoro | ❌ | ? | ✅ | ✅ 100+ voices | ✅ Paradee (`am_puck`, same voice as Ronie Web) |
| Vision | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ |
| Web search w/ citations | ✅ | ✅ claims flagged | ✅ | ✅ | ❌ | ◐ agents | ✅ |
| Calendar / reminders | ✅ | ✅ tap-to-confirm | ✅ | ? | ❌ | ❌ | ✅ |
| **Reads email** | ❌ | ❌ drafts only | ❌ | ❌ | ❌ | ❌ | ✅ |
| **Proactive alerts / daily brief** | ❌ | ❌ | ◐ notifications | ❌ | ❌ | ❌ | ✅ |
| MCP client on Android | ❌ (server) | ❌ (Windows only) | ❌ | ✅ | ❌ | ❌ | ✅ + router + confirm gate |
| Persona | ❌ | ◐ presets | ❌ | ? | ❌ | ✅ characters | ✅ Ronie |
| Default Android assistant | ? | ❌ | ✅ | ? | ? | ? | ➕ add |
| NPU acceleration | ? | ❌ | ? | ? | ? | ✅ QNN | ➕ add later |
| Offline Wikipedia | ❌ | ✅ ZIM | ❌ | ❌ | ❌ | ❌ | ➕ add |
| Tuned / edited models | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ weight edits |
| Open source | ❌ | ✅ MIT | ✅ GPLv3 | ? | ❌ | ❌ | decide |

---

## 4. What we learn — changes to Ronie's plan

### Add to the build plan
1. **Set Ronie as the default Android assistant** (`RoleManager.ROLE_ASSISTANT`, long-press home / power button) — AnythingLLM does it; it's how users actually *reach* an assistant daily. → Phase 3.
2. **"Fits your phone" badge + auto model pick** — Loci and AnythingLLM do this. → Phase 3 download manager.
3. **Offline Wikipedia (Kiwix ZIM) import** — OnDevice LLM's best idea for a fully-local product: grounding *without* sending a query. Lets Ronie answer factual questions accurately in offline mode. → Phase 8.
4. **Claim checking on cited answers** — flag sentences not supported by the retrieved pages. → Phase 8.
5. **Background-safe model downloads** (OnDevice LLM's downloads die when the app closes) — use WorkManager/DownloadManager. → Phase 3.
6. **Keep-cool / thermal mode** (iOS OnDevice LLM) — auto-drop tier or thread count when hot. → Phase 12.
7. **Semantic recall across past chats** ("Smart Recall") — we get this almost free from the memory index. → Phase 6.
8. **NPU path** (Layla uses Snapdragon QNN) — OnDevice LLM found the phone GPU no faster than CPU for generation, so NPU is the real speed lever. Evaluate in Phase 12 via LiteRT-LM or QNN-enabled runtime.
9. **Share sheet everywhere** — every good competitor has it. → Phase 5.

### Confirmed decisions
- **Qwen3.5 0.8B as a default works on phones** — OnDevice LLM ships it as "Everyday default" at 40–48 tok/s; Qwen3.5 2B and Gemma 4 E2B are the next step up. Gemma 4 E2B's ~4.5 GB RAM is heavy → keep Qwen3.5-2B as provisional main brain.
- **Kokoro-class local TTS + Whisper STT** is the standard voice stack → Paradee (8.5 MB) is a lighter, differentiated choice.
- **Tap-to-confirm phone actions** is the norm — our confirm gate matches the market.
- **Editable memory screen** is now expected (MemoryAI, both "OnDevice LLM"s).
- **Persona sells** (Layla) — keep Ronie's persona central, but productivity-first (Layla's weakness).

### Avoid their mistakes
- **Privacy claims that don't match the store label** (Loci). → Ronie: no analytics SDKs at all, Play Data-safety form = "no data collected", publish a network log, optionally open-source the networking code.
- **Curated-only models** (Loci, MLC Chat) frustrate power users → allow custom GGUF import behind an "Advanced" switch.
- **Unmaintained engines** (MLC Chat) → build on actively maintained llama.cpp / LiteRT.
- **Unsigned sideload APKs** hurt trust → Play Store release, signed builds.
- **Free tier crippled to "1 model, 1 chat"** gets bad reviews → generous free core.

---

## 5. Ronie's positioning

> **"The only assistant on your phone that watches your inbox and remembers your life — without anything leaving the device."**

Differentiators no competitor combines:
1. **Proactive**: email watcher, important-mail alerts, daily brief, task capture from mail.
2. **Learns you**: auto-built, editable profile + nightly consolidation.
3. **Better small models**: weight-edited Qwen (de-looped, reliable tool calls, less sycophantic) — measurable on our eval suite.
4. **Connectors done safely**: Android MCP client with tool router, confirm gate and "sends data to X" labels.
5. **Personality**: Ronie is one character across web, glasses and phone, with the same face, personality and voice.

### Business-model options seen in the market
| Model | Who | Note |
|---|---|---|
| Free, no ads | Loci, PocketPal, MemoryAI, OnDevice LLM (OSS) | Grows users; needs another revenue source |
| One-time purchase | Layla, Laria ($4.99), Private LLM | Fits "no cloud costs" story |
| Freemium + Pro | Off Grid ($49/yr, $69 lifetime), OnDevice LLM iOS ($6.99/mo, $14.99–19.99 lifetime) | Common for power features |

Ronie has near-zero server cost (no cloud inference), so **free core + one-time "Pro" unlock** (email watcher, connectors, extra voices) fits best. Decide later.

---

## 6. Open questions to verify
- Hands-on test of Loci (Android) and AnythingLLM Mobile on the test phone: speed, memory quality, onboarding.
- LLM Hub's real MCP support (Play page didn't load).
- Whether Gemini on Android reads Gmail on-device or in the cloud (affects our pitch).
- Any app doing on-device email triage we missed — search Play Store directly.

---

## Sources
- [PromptQuorum — Loci review](https://www.promptquorum.com/power-local-llm/loci-ai-review-offline-local-ai)
- [AlternativeTo — Loci](https://alternativeto.net/software/loci/about/) · [Loci alternatives](https://alternativeto.net/software/loci/)
- [Loci privacy (archived stub)](https://hungryhippo14.github.io/loci-app-site/privacy/)
- [Layla — official site](https://www.layla-network.ai/) · [Layla on DEV](https://dev.to/layla_network_ai/how-to-run-a-private-ai-assistant-on-your-phone-in-2026-offline-no-account-no-filters-2kka)
- [OnDevice LLM (GitHub, sebytzr16)](https://github.com/sebytzr16/on-device-llm)
- [OnDevice LLM – Offline AI Chat (App Store)](https://apps.apple.com/us/app/ondevice-llm-offline-ai-chat/id6761696880)
- [LLM Hub (Google Play)](https://play.google.com/store/apps/details?id=com.llmhub.llmhub&hl=en)
- [AnythingLLM Mobile](https://anythingllm.com/mobile)
- [PromptQuorum — Android local LLM apps 2026](https://www.promptquorum.com/power-local-llm/best-local-llm-apps-android-2026)
- [ExamShala — 10 best Android local AI apps 2026](https://blog.examshala.in/posts/best-android-apps-run-local-ai-2026)
- [Yaps — 12 best offline AI apps 2026](https://www.yaps.ai/blog/best-offline-ai-apps-2026) (written by a competitor)
- [Nesyona — best offline AI chat apps 2026](https://nesyona.com/articles/best-offline-ai-chat-apps-2026) (contains sponsored listing)
- [ByteSwifts — offline AI apps Android 2026](https://www.byteswifts.com/2026/07/best-offline-ai-apps-for-android-2026.html)
- [MeetAITools — 13 offline Android chatbots tested](https://meetaitools.com/ai-chatbot-app-for-android-offline/)
- [Vellum — open-source personal AI assistants](https://www.vellum.ai/blog/best-open-source-personal-ai-assistants)
- [Decrypt — Google AI Edge Gallery](https://decrypt.co/323534/googles-latest-app-run-ai-offline?amp=1)
- [Privacy Guides — AI chat tools update](https://discuss.privacyguides.net/t/update-on-ai-chat-tools/29496/7)
