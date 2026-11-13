# Launch kit: getting Ronie Pocket discovered

Everything to paste into GitHub and the launch posts, plus the order to do it in. Every post here is a **draft**: you post it yourself, from your own accounts.

---

## 1. Before going public (blockers)

- [x] **Licence: Apache-2.0** (`LICENSE` + `NOTICE` added 2026-11-09). Bundled third-party models keep their own notices, listed in `NOTICE`.
- [ ] **A release with an installable app** (`Releases → Draft a new release`): tag `v0.1.0`, attach the signed app file, and paste the release notes from §6. The README's Download button points here; an empty Releases page loses most visitors.
- [ ] **The demo video/GIF** (§5), saved as `docs/media/demo.gif` and enabled in the README (the comment near the top says how).
- [ ] **A quick re-read of the privacy table** in the README, against what the app actually does at release time.

## 2. Repository settings (GitHub → the repo → ⚙ next to "About")

| Field | Value |
|---|---|
| **Name** | `ronie-pocket-offline-mobile-ai` |
| **Description** | 📱 Ronie Pocket: an uncensored AI assistant that runs 100% on your phone. No account, no cloud, no tracking: your chats never leave your device. Gemma 4 on the phone's own GPU with voice, memory, tools and optional web search. |
| **Website** | `https://raunakpatil.com/ronie` (or a landing page later) |
| **Topics** | `offline-ai` `local-llm` `on-device-ai` `mobile-ai` `ai-assistant` `uncensored-ai` `privacy` `llama-cpp` `gemma` `gemma-4` `on-device-llm` `voice-assistant` `text-to-speech` `personal-assistant` `mcp` `vulkan` `smartphone` `no-tracking` `kotlin` `jetpack-compose` |
| **Social preview** | Settings → General → Social preview → upload `docs/media/social-preview.png` (1280×640) |
| Releases / Packages / Deployments | Show Releases; hide the others |
| Discussions | **On**, with categories Q&A, Ideas and Show & tell. It turns questions into community activity, and active repos rank higher |
| Issues | On, with two templates: Bug (phone model, RAM, app version) and Feature request |

Topics, name and description are what GitHub search matches first. The README's first paragraph and its keyword line at the bottom cover the long-tail searches ("AI without internet", "local LLM on phone").

## 3. Launch order (one focused day beats a slow trickle)

GitHub Trending counts stars **per day**, so get as many as possible in the first 24 hours.

| When | Where | Why |
|---|---|---|
| Day 0, Tue–Thu, 8–10 am US Eastern | **r/LocalLLaMA** | The core audience: they care about on-device, Gemma, llama.cpp and uncensored models |
| Day 0, +2 h | **Hacker News: Show HN** | Highest ceiling; the privacy angle and real numbers do well there |
| Day 0, +3 h | **X / Twitter** thread with the demo video; tag @ggerganov (llama.cpp) and @GoogleDeepMind / Gemma only if the post is genuinely about their tech | Reposts from those accounts move the needle |
| Day 1 | **r/privacy**, **r/degoogle** | The privacy and no-account angle |
| Day 1 | **r/opensource**, **r/coolgithubprojects** | Easy, friendly exposure |
| Day 2 | **r/ChatGPT** / **r/singularity** (as "an offline alternative") | Big audiences; lead with the demo |
| Week 2 | **Product Hunt** | Better once there's a polished release and some stars |
| Ongoing | Awesome lists (§7) | Steady long-term discovery |

**First 2 hours on each post:** reply to every comment quickly and helpfully. Early replies decide whether a Reddit or HN post climbs.

## 4. Drafted posts

### r/LocalLLaMA
**Title:** I made an offline AI assistant that runs Gemma 4 on a phone's GPU: ready in ~5 s, no account, uncensored

> I wanted a personal assistant that doesn't send my life to a server, so I built **Ronie Pocket**. Gemma 4 E2B (Q4_K_M) runs on the phone GPU through llama.cpp + Vulkan.
>
> **Numbers on a Pixel 9 Pro:** ready ~4.6 s after launch, first token ~0.7 s, ~10 tok/s.
>
> Startup went from 15 s to 4.6 s with two tricks:
> - The persona KV state is precomputed on the PC and shipped with the app.
> - A persistent Vulkan pipeline cache. ggml-vulkan rebuilds every shader on each launch otherwise; the patch is in the repo.
>
> **What it does:** voice in and out (on-device TTS), encrypted memory, tasks/reminders/timers/calendar as tools (Gemma's native tool format), MCP connectors, and keyless web search with citations. There's a Clean↔Sweary tone slider made with a steering vector, not a prompt.
>
> **No account, no telemetry.** Works in flight mode, and the activity log shows every network request.
>
> Repo: <link>. Happy to answer questions about getting llama.cpp + Vulkan fast on mobile.

### Show HN
**Title:** Show HN: Ronie Pocket – a private AI assistant that runs entirely on your phone

> Ronie is a personal assistant that runs a 2B-class model (Gemma 4 E2B) on the phone's GPU. Chat, voice, memory and reminders work with no network and no account.
>
> A few engineering notes HN might find interesting:
> - **Startup:** about 4.6 s, down from 15 s. ggml-vulkan had no pipeline cache, so every launch recompiled all GPU shaders; a ~60-line patch adds a persistent `VkPipelineCache`. The ~2,000-token persona is prefilled on a PC and shipped as a KV-state file, so the phone restores it in 0.1 s instead of computing it in 49 s.
> - **Memory:** facts are stored in SQLCipher with a Keystore-wrapped key, retrieved with an int8 bge-small (15 ms per embedding), and attached to the user's message rather than the system prompt. That raised "actually uses what it remembers" from 60% to 87% in our eval.
> - **Web search:** no API key. DuckDuckGo lite + page reading + passage selection, numbered citations, and a scrubber that removes the user's name, emails, phone numbers and addresses from queries.
>
> Everything that touches the network is optional, labelled and logged. Source: <link>

### X / Twitter thread
1. I built an AI assistant that runs 100% on your phone. No account. No cloud. Works in flight mode. 🧵 [demo video]
2. It runs Google's Gemma 4 on the phone's own GPU: ready in ~5 seconds, first word in under 1 s.
3. It talks (on-device voice), remembers you (encrypted, on the phone), and sets reminders, timers and alarms. It can search the web when you let it, and cites sources.
4. Uncensored: no topic filters, no lectures. A tone slider from Clean to Sweary.
5. The nerdy part: startup went 15 s → 4.6 s by giving llama.cpp's Vulkan backend a shader cache, and by shipping the persona's KV state. Patch in the repo.
6. Open source: <link>. ⭐ if you think AI on your phone should be yours.

### r/privacy / r/degoogle
**Title:** An AI assistant with no account, no telemetry, that works in flight mode (open source)

> Lead with the privacy table from the README and a screenshot of the in-app Activity log. Answer "what leaves the phone?" precisely: the model download once, web search only when used, and connectors only if you add them.

## 5. Demo video (the single biggest factor)

10–20 s, vertical, no music needed, captions burned in:
1. 0–2 s: swipe down, **flight mode ON** (caption: "No internet.")
2. 2–4 s: open Ronie; the title builds and it's ready (caption: "Ready in 5 s.")
3. 4–10 s: tap the mic: "Remind me to call mum at 7 and set a 10 minute timer." The cards appear with the live countdown.
4. 10–16 s: type something a cloud assistant would refuse or lecture about; Ronie answers plainly (caption: "No filters.")
5. 16–20 s: the end card: "Ronie Pocket · No account · No cloud · ⭐ on GitHub"

Record with the phone's screen recorder, trim, and export a GIF ≤ 8 MB for the README (`docs/media/demo.gif`) plus an MP4 for X and Reddit.

## 6. Release notes (v0.1.0)

```markdown
## Ronie Pocket v0.1.0: first public release

An uncensored AI assistant that runs entirely on your phone. No account, no cloud, no tracking.

**Highlights**
- Gemma 4 on your phone's GPU: ready in ~5 s, first word < 1 s
- Voice in and out, with Ronie's own on-device voice
- Encrypted memory you can see and delete
- Tasks, reminders, timers, alarms, calendar, with Undo on everything
- Web search with sources (no account, no API key), MCP connectors
- Offline mode and an activity log of every network request

**Install:** download the file below and open it on your phone. On first launch Ronie picks the best brain for your phone and downloads it once (0.5–3 GB).
```

## 7. Awesome lists and directories (submit a PR each; check each list's rules first)
- Lists of local / on-device LLM apps (search GitHub for "awesome local llm", "awesome on-device ai")
- Lists of MCP clients (search "awesome mcp clients"): Ronie is an MCP client on a phone, which is still rare
- Lists of privacy-respecting apps (search "awesome privacy")
- llama.cpp's README has a list of apps/UIs built on it. Open a PR adding Ronie Pocket once the release exists.

## 8. Keep the momentum
- Post an update every 1–2 weeks with a short video of the newest feature: vision, then image generation, then the daily brief. Each one is a fresh reason to share.
- Pin a Discussion: "What should Ronie do next?"
- Add a "Star History" chart to the README once there are a few hundred stars (star-history.com).
- **Honesty guardrails** (they protect the launch from the usual "well, actually" comments):
  - Don't claim "first" (PocketPal, Layla and others exist); say what's different.
  - Keep "your conversations never leave your phone" exact, not "no data ever leaves".
  - Quote measured numbers only.
