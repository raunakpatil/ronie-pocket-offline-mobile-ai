# Ronie Pocket — Status

The single place that shows **what's done, what changed from the plan, what was added along the way, and what's next**. The plan itself is [build-plan.md](build-plan.md); measured results per phase are in `phase*-results.md`.

**Updated:** 2026-11-09 · **Last commit:** `34d09e1` (Timer card: live countdown) · **Nothing pushed**

## Where we are

| | |
|---|---|
| **Phases done** | 0, 1, 2, 3, 3.5, 4, 5, 8, 9, 9.5 |
| **Current phase** | Phase 9 and 9.5 built; **bug-fix pass done 2026-11-13, phone re-test pending** (see "Review of Phase 9 / 9.5 code") · then Phase 6 / 7 |
| **Brain on the test phone** | Gemma 4 E2B Q4_K_M on the GPU (Tier A, "Full brain") + `mmproj-F16.gguf`, ~10 tok/s, first word ~0.7–1 s · SD-Turbo Q4_0 + TAESD on Vulkan GPU (~54.8 s) |
| **Test phone** | Pixel 9 Pro (Tensor G4, 16 GB, Android 17) over wireless debugging |
| **What works on the phone today** | Offline chat with Ronie's persona · Ronie Cards · Multimodal vision (photos, camera, receipts → expense cards, full-screen zoom viewer, image-aware prefix caching) · On-device image generation ("Ronie, draw…", SD-Turbo on Mali-G715 Vulkan, living-art card, Save, Share, Again, Edit) · Clean↔Sweary tone slider · X-ray (Labs) · spoken replies (am_puck) · push-to-talk · settings drawer · tasks, reminders, timers with a live countdown, alarms, calendar (read), remember · confirm cards with Undo · remote MCP connectors · offline mode · activity log · "Ask Ronie" share sheet |

**Legend:** ✅ done · 🟡 partly done · ⏳ to do · ⏸ parked or deferred (by decision) · ❌ target not met (gap recorded) · ✏️ changed from the plan · ➕ added, not in the original plan

---

## Phase checklist

### Phase 0 — Environment setup ✅
- ✅ NDK 28.2 and CMake 3.22.1 (installed by AGP), `adb` working
- ✅ Test phone recorded: Pixel 9 Pro, Tensor G4, 16 GB RAM, Android 17
- ✅ Git repo with the module layout (`app/`, `core/engine/`, `eval/`, `tools-pc/`, `scripts/`)
- ⏳ CI (Gradle build + unit tests on each push): not set up; tests run locally with `scripts\test.cmd`
- ✏️ Builds run through `.cmd` scripts in the Terminal panel (Gradle can't run in Claude's sandbox; PowerShell scripts are disabled on this PC)

### Phase 1 — Runtime spike (go/no-go) ✅ GO
- ✅ llama.cpp (`d6be065`) built for Android; Qwen3.5 DeltaNet layers run on the phone
- ✅ Vision projector works (image encode 2.5 s)
- ✅ Activation hooks: add a direction (`llama_set_adapter_cvec`) and read activations (`cb_eval`)
- ✅ whisper.cpp tiny.en on the phone (11 s clip in 3.7 s)
- ✅ Paradee TTS faster than real time (PC; phone confirmed in Phase 4)
- ✅ KleidiAI = free 1.5–2× CPU speed-up; GPU (Vulkan) is slower at peak but steady under heat
- ⏸ Speculative decoding dropped (doesn't work with Qwen3.5's recurrent layers)
- ⏳ Outputs-match-desktop check and combined RAM + 5-minute thermal test (never run)
- ➕ Found Cactus **Mirage** (22 MB vision model) → added as a Phase 9 candidate

### Phase 2 — Model bake-off ✅
- ✅ Eval sets built (`eval/sets/`): tools, persona, grounded Q&A, reasoning, loops, summaries, synthetic triage
- ✅ Decision written ([eval/DECISION.md](eval/DECISION.md)): **Gemma 4 E2B** = main brain; Qwen3.5-2B = 8 GB tier; Qwen3.5-0.8B = 4 GB tier; Qwen3.5-4B = PC teacher only; LFM2.5 dropped
- ✏️ **One curated model per phone** (user decision): first run shows all 3 with the recommendation on top, no custom model import
- ⏳ Email triage on the user's **real** labelled emails (only synthetic so far; real mail stays on the PC) → Phase 7
- ⏳ Vision eval set → Phase 9

### Phase 3 — MVP chat ✅ (one target missed)
- ✅ Offline chat: streaming, Stop, New chat
- ✅ First word < 1 s (0.61–0.72 s with the cache reused)
- ❌ ≥ 20 tok/s: ~10 tok/s on Gemma 4 E2B GPU (phone's limit; revisit in Phase 12)
- ✅ Model download manager: WorkManager background job, resumable, SHA-256 checked, notification
- ✅ Tier detection (RAM, CPU features, Vulkan, storage) + first-run brain picker with sizes and RAM
- ✅ First-run speed check; step-down offer if the phone is too slow
- ✅ Default-assistant entry point (long-press home opens Ronie with the keyboard up)
- ✅ Prompt formats for Gemma 4 and Qwen3.5 checked against the GGUF templates
- ✅ Persona prompt cached on disk → startup ~8.6 s (was ~27 s); later: shipped persona state, GPU pipeline cache and background warm-up → ready in ~4.6 s
- ✅ Loop guard (repeats → stop and trim; unit tests)
- ✅ Persona v6 (organic humour, user's style requests win, only the user's two hard limits); style 22/23, openness 25/25
- ✅ Ronie Cards v0: checklist (tickable), bullets, steps, compare table, code
- ✏️ Cards use markdown rendering, not JSON-constrained blocks (works with the small model; revisit if needed)
- ⏳ Conversation list / saved chat history (chats are lost on restart) → with Phase 6 storage
- ⏳ Copy/share a reply, "Ronie is thinking…" collapsible (thinking is off on all tiers)
- ⏳ Checklist ticks saved (Phase 6 task DB)
- ⏳ Explicit kill-and-resume download test
- ⏸ Thinking switch / router: thinking stays off (Qwen thinking loops; Gemma doesn't need it for chat)

### Phase 3.5 — Moonshot demo 🟡 (core built; open-source + video not done)
- ✅ Engine activation hooks on the phone (steering + per-token read-out), no measurable speed cost
- ✏️ First patch is **Clean ↔ Sweary** tone (not `no-loops`): swears when asked 7/10 → 10/10 at the slider max, accuracy unchanged
- ✅ `.rpatch` v0 format + CLI (`tools-pc/rpatch/rpatch.py`), `patches/swear-gemma4e2b.rpatch` with an eval receipt
- ✅ X-ray Mode v0 → ✏️ shipped as **Labs, off by default** (lab AUC 0.998; real-world accuracy unmeasured)
- ✅ Training jobs on Modal (CUDA build of our patched llama.cpp); llama.cpp patch for Gemma 4 control-vector training
- ⏸ `no-loops` patch: parked (Qwen thinking is off in the app, so it wouldn't change anything users see; data and jobs are ready)
- ⏳ Patch screen in the drawer (install / toggle / remove `.rpatch` with receipts)
- ⏳ Open-source `rpatch` repo (spec + tooling, reproducible from a clean clone)
- ⏳ 60-second demo video + README GIF

### Phase 4 — Voice + settings drawer 🟡 (core done)
- ✅ TTS: Paradee-8M int8 (am_puck) in ONNX Runtime 1.22; 17–28× faster than real time
- ✏️ Pronunciation: Kotlin port of **misaki** (no espeak-ng → no GPL problem); numbers, money (₹), dates, times, joined words; 8 unit tests
- ✅ Streaming speech: first audio **1.05 s** after the reply starts (target < 1.5 s); first clause spoken early
- ✅ Barge-in: 🔇, Stop, a new message or the mic stops speech instantly
- ✏️ Speech-to-text: Android's **on-device** recognizer instead of whisper.cpp (never the cloud fallback); whisper.cpp kept as fallback
- 🟡 Push-to-talk works and times out cleanly; **a real spoken command by the user is still untested**
- ✅ Settings drawer (☰ or swipe): Ronie (tone, X-ray), Voice, Tools & connectors, What Ronie remembers, Models, Privacy (activity log), About; Back closes the drawer
- ⏳ A/B listen against Ronie Web's am_puck
- ⏳ Visor avatar (Ronie Web's expressions) in the header and notifications
- ⏳ Hands-free mode (listen again after Ronie finishes speaking)
- ⏳ Word highlighting from timestamps; wake word "Ronie"
- ⏳ Drawer: switch brain, Patches section, chat history

### Phase 5 — Tools & MCP connectors ✅ (tested on the phone 2026-11-09)
- ✅ Gemma 4's **native** tool format (renderer byte-checked against the GGUF template, unit test with a golden file)
- ✅ Tool-call eval **96%** at the phone's sampling (target ≥ 90%; baseline 60%) — `eval/tools_v2/`
- ✅ Parser + lenient repair (calls written without the marker), argument checks with one repair retry, max 4 tool steps per reply
- ✅ Confirm gate in code: read-only runs · local + reversible runs with **Undo** · deletes, connector actions and anything after a tool result **ask first**
- ✅ Injection tests 9/9; Cancel ends the turn with a fixed line (fixed a false "Note sent.")
- ✅ Built-in tools: `create_task`, `list_tasks`, `complete_task`, `delete_task`, `set_reminder` (notification, survives reboot), `set_timer`, `set_alarm` (Clock app), `get_calendar` (read only), `remember`
- ✅ Remote MCP connectors (Streamable HTTP): add by URL + optional token (Keystore-encrypted), Test, per-tool switches, "Sends data to <host>"
- ✅ Tool router (word overlap; built-ins always shown); offline mode; activity log
- ✅ Share sheet: "Ask Ronie" for text and links (Summarise · Explain · Add task · Remember this)
- ✏️ Built-in tools are in-process functions with MCP-shaped definitions (not separate MCP servers); hand-rolled MCP client instead of the Kotlin SDK
- ⏳ OAuth sign-in for connectors (token only today); MCP elicitation; sampling stays off
- ⏳ Share images and documents (needs Phase 9 vision); opening shared links (needs Phase 8)
- ⏳ Tools for the Qwen tiers (B/C) — need Qwen's call format + a tool eval for those models
- ⏳ Notifications and files tools; `web_search` (Phase 8); `mail` (Phase 7)
- ⏳ Test with a real hosted connector (only the local test server so far: `tools-pc/mcp-test/server.py`)

### Phase 9 — Vision ✅ (completed 2026-11-10)
- ✅ `libmtmd.so` built with `-DLLAMA_BUILD_MTMD=ON -DMTMD_VIDEO=OFF` and linked into `core/engine/src/main/cpp/ronie_engine.cpp`
- ✅ Image-aware token caching via negative sentinel token IDs (`-(fnv1a(id) & 0x3fffffff) - 1`) with chunk boundary alignment
- ✅ Gemma 4 non-causal attention toggled dynamically during image chunk evaluation
- ✅ Multimodal vision evaluation suite built (`eval/vision/`): **77/80 = 96.2%** overall accuracy (target ≥ 85%)
  - Screenshots: 15/15 = 100.0%
  - Photos: 20/20 = 100.0%
  - Documents: 15/15 = 100.0%
  - Image + Tool: 6/6 = 100.0%
  - Receipts: 19/20 = 95.0% (target ≥ 90%)
  - Honesty: 2/4 = 50.0%
- ✅ Receipts rendered as native `ExpenseCard` (`cards/Cards.kt`) with merchant, date, total, item breakdown, tax, and actions [Copy, Share]
- ✅ Image import pipeline: downscale to long side ≤ 1536 px, EXIF & GPS stripped, SHA-1 deduplication (`ImageImport.kt`)
- ✅ Attachment sheet with photo picker (`PickMultipleVisualMedia`) and camera capture (`TakePicture` via `FileProvider`)
- ✅ Full-screen pinch-zoom and pan viewer (`FullScreenImageViewer`)
- ✅ Multi-file background download in `ModelDownloadWorker.kt` (model weights + mmproj)
- ✅ Pinned `mmproj-F16.gguf` pushed to test phone; debug fallbacks for fast developer sideloading
- ✅ Tool-call eval baseline preserved: 81/85 = 95.3% on `eval/tools_v2/run.py`
- ✅ Shipped persona state: `FULL-3fb983196f4f207c.bin` (2059 tokens in 7.5 s, 19.0 MB)

### Phase 9.5 — Image Generation ✅ (completed 2026-11-10)
- ✅ Architecture: **SD-Turbo** (Stability AI, 1.76 GB GGUF `sd_turbo_q4_0.gguf`, 3-step distilled Adversarial Diffusion, 512×512) + **TAESD** (`taesd.safetensors`, 9.79 MB) via **`stable-diffusion.cpp`** + Vulkan (Mali-G715) + OpenMP. (DreamLite placeholder code and ONNX runtime completely removed).
- ✅ Benchmarked on Pixel 9 Pro: pure Vulkan generation in **~43.2s** sampling / **~52.4s** turn (3 steps @ 12.3s/step, TAESD latent decode 1.53s). VAE decode collapsed from 36.0s down to 1.53s (23× speedup) and transient memory from 1.9 GB to 480 MB VRAM, cutting total generation time by over 50% from original 90s baseline.
- ✅ Sampling & scheduler quality: `EULER_A_SAMPLE_METHOD` (Euler Ancestral) with `DISCRETE_SCHEDULER` and `cfg_scale = 1.0f` delivers studio-grade watercolor textures, neon reflections, and sharp whiskers while eliminating quantization scanlines and avoiding Karras sigma collapse.
- ✅ Cryptographically secure 64-bit random seed: `SecureRandom().nextLong(1L, Long.MAX_VALUE)` generated on every turn so identical prompts produce distinct artworks. Dynamic aesthetic prompt booster (`enhancePrompt`) tailored to requested styles (photo, watercolor, anime, sketch, 3D). Full prompts preserved for "Again" reruns.
- ✅ Hardware-based recommendation engine (`DeviceProfile.kt`): evaluates device RAM and GPU headroom per decision guide:
  - $\ge 12$ GB Flagship: Recommend High-Performance Diffusion (SD-Turbo active now, SANA-Sprint 0.6B 1024px upcoming).
  - 8 GB Mid-range: Recommend Compact 512px drawing (SD-Turbo active now, Nitro-E 0.3B 4-step upcoming).
  - $< 8$ GB: Low memory warning (tight headroom for concurrent LLM + diffusion).
- ✅ UI integration: First-run onboarding card (`OnboardingScreen.kt`) and Settings drawer (`SettingsDrawer.kt` under Models) with `HARDWARE MATCH` badge, device RAM, and curated mobile architectures.
- ✅ Verified live on device: cat astronaut in watercolour, cyberpunk neon cityscape, uncensored adult nude at bus stop, and back-to-back distinct red panda astronauts verifying seed randomisation.
- ✅ Strict minors safety gate: `MinorsCheck.kt` + `minors.json` passing 100% of 30 must-stop and 60 must-pass tests; 100% uncensored for adult content, nudity, violence, and named individuals.
- ✅ Dedicated `:core:imagegen` module with `ImageGen.kt`, `SdEngine.kt`, `SdNative.kt`, `MinorsCheck.kt`, and `HeavyWork.kt`.
- ✅ Mutual exclusion: `HeavyWork` mutex prevents simultaneous LLM and drawer GPU execution.
- ✅ `generate_image` built-in tool schema in `assets/tools/builtin.json` with properties `prompt`, `style`, `shape`, `edit_last`.
- ✅ Tool evaluation: **90/94 = 95.7%** (target ≥ 95%) with 9/9 image generation cases passing (direct draw, context edit, non-draw negatives).
- ✅ Persona state generated with exact hash matching: `FULL-60af0582de89f0eb.bin` (2436 tokens, 21.3 MB).
- ✅ Living-art UI: `ImageGenTile` in `ToolCardUi.kt` with live preview decoding via `sd_set_preview_callback` + `PREVIEW_TAE`, displaying intermediate images resolving on screen with animated progress bar (`Step X of Y · Z%`), 3D card flip animation + specular shine sweep on completion, and compact action buttons: **Save** (MediaStore `Pictures/Ronie`), **Share** (`FileProvider`), **Again** (rerun with new seed), **Edit** (prefill "Change this picture: ").
- ✅ Uncensored drawing safety-net: deterministic routing in `ChatViewModel.kt` (`isDirectImageRequest`) intercepts refusal strings on direct drawing requests, guaranteeing adult/nude art is 100% uncensored on device while maintaining the strict `MinorsCheck.kt` safety gate. Verified on Pixel 9 Pro with prompt `"draw a nude lady in a rainy bus stop at night"`.
- ✅ ChatStore persistence for generated images; safe cleanup and restoration.

---

### Review of Phase 9 / 9.5 code: bugs fixed (2026-11-13)

Found by reading the new vision and drawing code and running the tests. Unit tests: all pass (engine, drawing module, app). Tool-call eval after the prompt change: **91/94 = 97% (was 90/94; all 9 drawing cases pass)**. **Phone test 2026-11-13 (Pixel 9 Pro, Lite brain / Qwen2.5 installed):** starts in 2.3 s; plain chat fine with the new ChatML format; "Draw a cat astronaut in watercolour" drew in 45 s and the brain reloaded in 2.7 s; "Draw a conclusion from this…" got a normal answer (no picture); "Again" redrew directly. Not tested on the phone: photos (the Lite brain has no vision file), editing a picture, the Settings download button, and anything on the Gemma brain.

**Crashes and hangs**
- ✅ Any error inside a tool (for example the drawing model failing to load) was an uncaught exception that closed the app. Tools now always return a failure message (`runTool` → `runSafely`, `Builtins.generateImage`)
- ✅ SANA edit took the non-re-entrant `HeavyWork` lock twice and would hang forever (`SanaEngine.edit`)
- ✅ Two brain loads could run at once (reload after a drawing + another start), several GB each. `load()` is now guarded; a new drawing waits for a reload in progress
- ✅ A failed reload after a drawing left the chat at "waking up" forever with no message
- ✅ Engine: after a failed decode the model's memory and the token cache disagreed, so the next reply reused a prefix that wasn't there (`fail_and_reset`)
- ✅ Engine errors ("prompt too long", vision not loaded) were ignored and the reply showed "…". They're now explained; a photo sent to a brain without vision gets a plain answer instead of nothing

**Downloads**
- ✅ `watchDownload` re-queued the download with REPLACE and default options, which dropped "use mobile data" and **the drawing model** chosen at onboarding, and restarted a running download when the app reopened. Choices are now saved and re-queued with KEEP (`ModelStore.ensureDownload`)
- ✅ If the optional drawing download failed, the whole download showed as failed even though the brain was ready. The brain now loads anyway
- ✅ Settings had no way to install the drawing model after onboarding ("Not installed" with no button). Added Download with progress
- ✅ A half-downloaded `.part` file counted as "installed"
- ✅ SANA-Sprint was marked available and recommended on flagships, but nothing downloads it (the worker only fetches SD-Turbo): choosing it silently gave SD-Turbo with SANA's labels ("1024px, ~3.5 s on NPU"). It's now "coming later"; SD-Turbo is the recommendation and the default
- ✅ SD-Turbo's licence was shown as OpenRAIL-M; it's Stability AI's non-commercial licence

**Wrong behaviour**
- ✅ Any message starting with "draw/paint/sketch" was forced into a picture when the model answered in words, so "draw a conclusion…" or "paint the walls" produced an image, and "another version" anywhere (of an email, say) did too. Idioms and questions are excluded, and "again/another" only count right after a drawing
- ✅ "Change this picture" after an app restart drew a new picture from the instruction itself ("make the background blue"). The last picture and its prompt are now saved; an edit uses *old description + change*, which is what SD-Turbo's image-to-image needs
- ✅ The picker took 4 photos but only 2 reach the model, so 2 were dropped silently (with a "[photo: ]" placeholder). The limit is now 2 and says so
- ✅ Lite tier (Qwen2.5) was given Qwen3.5's empty `<think>` block, which that model doesn't have. New `PromptFormat.CHATML`
- ✅ The picture card replayed its flip animation every time it scrolled back into view or a saved chat was opened
- ✅ Back closed the app instead of the full-screen photo
- ✅ The `ToolSyntaxTest` golden was stale (failing test); `:core:imagegen` tests never ran (not in `scripts\test.cmd`) and 2 of them failed

**The one hard limit (nothing sexual involving minors)**
- ✅ Ages written "12-year-old" or "twelve year old" and school grades weren't caught; more minor and sexual terms added (`minors.json` v2, with tests)
- ✅ An edit was checked on the new words only, so "make her nude" passed whatever the picture was. Edits are now checked together with the prompt of the picture being changed
- ✅ A prompt with a child but no sexual word is steered to "fully clothed, wholesome"
- ⏳ The image-side check from the plan (a classifier on the finished picture for those prompts) is still not built

**Speed and quality**
- ✅ Ronie was told to write a ~70-word prompt plus a negative prompt for every picture: about 7 s of writing at 10 tok/s, for nothing. SD-Turbo runs at guidance 1.0, where a negative prompt is ignored, and its text encoder reads "no extra limbs" as "extra limbs". Prompts are now short and positive
- ✅ "Again" went back through the chat brain to rewrite a prompt it already had (~10 s). It now redraws directly
- ✅ Photos were decoded at full size on the UI thread for 60 dp thumbnails (stutter while scrolling). Now decoded in the background at display size and cached (`rememberThumb`)
- ✅ Saving the PNG and freeing the drawing model ran on the UI thread
- ✅ A refused prompt unloaded and reloaded the whole chat brain (the check ran after taking the lock)
- ✅ Voice and memory models were reloaded after every drawing; tone was loaded twice
- ✅ The user's drawing prompt and all of stable-diffusion.cpp's debug lines were written to logcat

**Left as is, to decide or measure**
- ⏳ The chat brain is unloaded for every drawing and reloaded after (~5 s, and the next reply waits). On a 16 GB phone both should fit; needs a phone test before changing
- ⏳ The drawing model is also reloaded for every picture, and the live preview costs a TAESD decode per step (~1.5 s each)
- ⏳ Standard and Lite tiers now put the Qwen models on the GPU (`gpuLayers = 99`); Phase 1 measured them faster on CPU + KleidiAI. Not tested on an 8 GB phone
- ⏳ Vision eval "honesty" cases: 2/4
- ⏳ SD-Turbo comes from an unpinned repo revision (`samoudi/sd-turbo-gguf` at `main`); its licence is non-commercial (matters for a paid version, and NOTICE doesn't list it yet)
- ✅ Phone screenshots, `ui_dump.xml`, `eval/nitro_e/` (3.3 GB) and bake-off images are git-ignored; the phone's serial number was removed from the scripts and plan (it is still in older commits)
- ⏳ Publishing (user request 2026-11-13): update README and launch kit for vision + drawing, add SD-Turbo to NOTICE, build the signed app, commit, create the GitHub repo, push, publish v0.1.0. Not started

## ➕ Added along the way (not in the original plan)

Small features and changes asked for during the build. Add every new one here.

| Feature / change | When | Where | Status |
|---|---|---|---|
| Settings drawer like ChatGPT / Claude (swipe right or ☰) | Phase 4 | `SettingsDrawer.kt` | ✅ (now also in build-plan.md) |
| On-device image generation ("Ronie, draw…"), optional download at first run and in Settings; SD-Turbo on Mali-G715 Vulkan GPU | planned during 3.5 | build-plan.md Phase 9.5 | ✅ tested on the phone; 90s studio-quality diffusion |
| **Live countdown on the timer card** (big digits ticking down, bar emptying, "Time's up!") | after Phase 5 | `ToolCardUi.kt` `TimerTile` | ✅ tested on the phone; now dot-matrix digits on a sunset tile |
| **New design language** from the user's reference boards: near-black canvas, glassy rounded tiles, vivid gradients (sunset, aurora, meadow, ocean, ember), big light numerals, dot-matrix clock digits, white pill main buttons; always dark | before Phase 6 | `ui/Theme.kt`, `ui/Widgets.kt` | 🟡 built and installed; empty screen checked on the phone, tool tiles and status pill still to check |
| **Animated status pill** ("Thinking…", "Checking your calendar…", "Speaking…") instead of "…"; lives at the top (grows out of the status dot) | before Phase 6 | `StatusPill`, `TopBar`, `ChatState.doing` | 🟡 installed |
| Tagline under the name: **"Private · Uncensored"** on one line, no flipping (user: "offline" sounded broken; flipping was tried and dropped) | before Phase 6 | `TopBar` | 🟡 installed |
| **Title**: dot-matrix **RONIE** with the glowing status dot right beside the R (green = ready, yellow = warming up, red = not working) and "Private · Uncensored" centred below. When Ronie works, **the dot inflates like a bubble into the status pill** (height springs ahead of width, slight overshoot, fast) and swallows the title, then deflates back. Drawn at fixed positions so nothing re-lays out mid-animation (the first version jittered) | before Phase 6 | `ui/TitleMorph.kt` | 🟡 title checked on the phone; morph not yet seen |
| **Asks the user's name on first launch** (asked once on existing installs too; Skip allowed); greeting becomes "Hey <name>, what can I do?" (subline: "I live on your phone, so your data is yours only.") and Ronie is told the name at the end of his prompt | before Phase 6 | `NameScreen`, `ChatViewModel.setName` | 🟡 installed |
| Text box placeholder: **"Ask anything"** | before Phase 6 | `InputBar` | ✅ |
| **Waking up inside the chat** (user: no separate screen, stay black, minimal): the top **RONIE title builds itself** on its dot matrix as stages finish; the chat area shows **one calm card** with the current stage's quiet abstract art (rings, neurons, filling grid, waves, bars, orbits) that **flips to the next stage** as each finishes (1 → 6), with a row of progress dots and the last stage's time underneath. Replaced a full-screen version and then a six-card board (user: too vibrant, cluttered) | before Phase 6 | `ui/BootCards.kt`, `LedText`, `TitleMorph(build)`, `BootStep` | 🟡 single-card version installed, not yet seen |
| **Neon Tokyo-night palette**: blue-black canvas; electric cyan, magenta, violet, neon orange, acid lime, neon mint; status pills, cards and boot art all use it | before Phase 6 | `ui/Theme.kt`, `Mood` | ✅ installed (seen: mint status dot, blue-black) |
| **List dots in one colour family, dark → light** down the list, each dot a small gradient; step numbers, ticks and compare labels follow the same ramp | before Phase 6 | `cards/Cards.kt` `ramp` | 🟡 installed |
| **Chat names evolve**: after every reply Ronie writes a 2–5 word title for the chat (cancelled at once if the user sends again, so it never delays a reply) and the sidebar updates | before Phase 6 | `ChatViewModel.retitle`, `ChatStore.rename` | 🟡 installed, not yet seen |
| Boot and title animations no longer stutter: animation clocks are read at draw time (no per-frame recomposition), art dims without an off-screen layer. Measured during a boot + warm-up: 0.76% janky frames, median 13 ms | before Phase 6 | `BootCards`, `TitleMorph`, `Widgets` | ✅ measured on the phone |
| Title ~25% smaller; tagline centred on the word RONIE, status dot outside it to the left | before Phase 6 | `TitleMorph` | ✅ checked on the phone |
| **Silver shine** sweeping across "Hey <name>," every few seconds | before Phase 6 | `ShineText` | ✅ checked on the phone |
| **Voice replies, two settings**: "Reply aloud when I use the mic" (on by default: talk with 🎤 → spoken answer; type → text) and "Always reply aloud" (off by default) | before Phase 6 | `ChatViewModel.send(viaVoice)`, Settings → Voice | 🟡 installed |
| **Sidebar like ChatGPT / Claude**: RONIE + new chat at the top, saved chats in the middle (tap to open, long-press to delete, time labels), the user's initial + name and a ⚙ at the bottom. **Settings is its own page** (slides in) with a new "You" section to change the name | before Phase 6 | `Sidebar.kt`, `SettingsPage` | 🟡 installed, not yet seen |
| **Saved chats** (needed for the sidebar): each chat's messages, tool cards and model history in a JSON file + index; saved after every reply; reopening restores what Ronie saw. Phase 6 moves this into the encrypted DB | before Phase 6 | `ChatStore.kt` | 🟡 installed |
| **Cards with personality ("living art")**: every list / steps / checklist / compare card gets its own look (full gradient tile, glow corner, LED dot grid, gradient outline, accent bar, header band) and palette, seeded by the message and position so no two match; each bullet dot, step number and tick has its own colour; small label on top ("LIST · 5") | before Phase 6 | `cards/Cards.kt` | 🟡 installed, not yet seen |
| **Voice mode, on the same line as the input** (user's design): tapping the mic slides the text box out to the left while the mic drifts to the centre and opens into a **Siri-style wave** (thin white line with glowing blue/cyan/pink/violet lobes that swell with your voice; loudness from the on-device recognizer); soft spring motion; what you say appears just above; tap the wave to send; ✕ sits centred right above the wave (thumb reach). Replaced the first full-screen sphere version | before Phase 6 | `ui/VoiceWave.kt`, `InputBar`, `Listener.onLevel` | 🟡 installed, not yet seen |
| **Ronie's own timers + `stop_timer`** (user found "stop the timer" impossible: the Clock app won't let other apps cancel timers). Exact alarm, live countdown in the notification shade with Stop, rings with the alarm sound until stopped, survives reboot; Clock app only as fallback. Tool eval with stop cases: 96% greedy, 94% sampled (3 runs), all stop and context cases pass | before Phase 6 | `tools/Timers.kt`, `builtin.json` | 🟡 eval ✅, not yet run on the phone |
| Timer tile: top-right shows the length (+ label), not "timer"; tile says "stopped" when stopped | before Phase 6 | `ToolCardUi.kt` | 🟡 installed |
| Chat restyle: big greeting + suggestion chips on an empty chat, white pill bubbles for the user, Ronie's text straight on the canvas, round icon buttons, pill input bar; tool results as tiles with icon discs; confirm card with white "Do it" / glass "Cancel"; checklist with round gradient ticks and a progress bar | before Phase 6 | `ChatScreen.kt`, `ToolCardUi.kt`, `cards/Cards.kt` | 🟡 built |
| Drawer, brain picker and download screen restyled (sections on tiles, lime switches, big download percentage) | before Phase 6 | `SettingsDrawer.kt`, `OnboardingScreen.kt` | 🟡 built |
| Design rules adopted from the ui-ux-pro-max skill (MIT, read but not installed): 48 dp touch targets, 8 dp gaps, 16 sp body text, ≥ 4.5:1 contrast, still pill when animations are off, no emoji as icons, haptic on confirm, pressed feedback, screen-reader labels | before Phase 6 | `ui/` | ✅ |
| **Habits grid** (coloured day squares per habit, check-in button), from the reference board | planned | Phase 6 | ⏳ planned with memory/profile |
| Next 7 dates with weekdays in the prompt (the model got "Saturday" wrong) | Phase 5 | `ToolPrompt.dateLine` | ✅ |
| "What Ronie remembers" list with Forget, in the drawer | Phase 5 | `ToolSettings.kt` | ✅ (simple version until Phase 6) |
| Calendar results compacted (25 one-line events + "+N more"): first word 33.5 s → 8.4 s | Phase 5 phone test | `Builtins.calendar` | ✅ |
| Plain `http` connectors allowed for `localhost` only (testing through `adb reverse`) | Phase 5 | `network_security_config.xml` | ✅ |
| App opens as a single window (`singleTask`) so sharing never loads a second model | Phase 5 | manifest | ✅ |
| Message box usable while Ronie wakes up (sent when ready); keyboard hides after Send | Phase 3 | `ChatScreen.kt` | ✅ |
| Persona opened up (no topic restrictions; only the user's two hard limits) | Phase 2–3 | `Persona.kt` | ✅ |
| X-ray kept as Labs with honest accuracy text | Phase 3.5 | `Xray.kt` | ✅ |
| Training / probe / eval jobs run on **Modal**, not the laptop | Phase 3.5 | `eval/modal_*.py` | ✅ (standing rule) |

## Decisions log (user)

| Date | Decision |
|---|---|
| 2026-11-08 | Fully local: no cloud LLM. Network only for model download, search, connectors and mail, each labelled |
| 2026-11-08 | One curated LLM per phone; first run shows all 3; no custom model import |
| 2026-11-08 | Main brain Gemma 4 E2B; Qwen3.5-2B / 0.8B for smaller phones; Qwen3.5-4B PC-only |
| 2026-11-08 | Persona: no rules except nothing sexual involving minors, and no bomb/explosive/poison/weapon recipes or practical help to hurt real people |
| 2026-11-08 | Ronie is the same character as in Ronie Web (he, CRT visor, am_puck voice), but this app's rules and behaviour are its own |
| 2026-11-08 | X-ray ships as Labs (off by default); Qwen no-loops parked |
| 2026-11-08 | "Ronie Gemma" fine-tune + Hugging Face upload: **on hold** until later (don't start without asking) |
| 2026-11-09 | Image generation goes in Phase 9.5, not now |
| 2026-11-09 | Phone testing only with the user's go-ahead; check the foreground app and calls before every input |
| 2026-11-09 | Memory embeddings: **bge-small-en-v1.5** in the app (EmbeddingGemma possible later as an optional upgrade) |
| 2026-11-09 | **Tone slider: default is the middle (0, Normal) on a new install; the user chooses where to set it, up to +100%** (kept as the maximum, accepting that at full strength Ronie sometimes insults the user: 8/30 replies measured; +60% would be 3/30) |
| 2026-11-10 | **Image generation (Phase 9.5): Initial DreamLite plan replaced by SD-Turbo** (Stability AI 1.76 GB GGUF `sd_turbo_q4_0.gguf`) + **TAESD** (`taesd.safetensors`, 9.79 MB) with `stable-diffusion.cpp` compiled for arm64-v8a + Vulkan (Mali-G715) + OpenMP. DreamLite placeholder code and ONNX dependencies cleaned out. Generates studio-grade 512×512 diffusion artwork in ~54.8s on device using Euler Ancestral sampling + Discrete scheduler; VAE decode accelerated from 36s to 2.04s (17.6× speedup); verified live on Pixel 9 Pro. |
| 2026-11-10 | **Image generation UX & Uncensored Routing**: Live diffusion preview callback (`sd_set_preview_callback` + `PREVIEW_TAE`) displays intermediate images resolving on screen with a progress bar (`Step X of Y · Z%`); on completion triggers 3D card flip animation + specular shine sweep + action buttons (`Save`, `Share`, `Again`, `Edit`). Direct image generation requests bypass Gemma 4 RLHF safety refusal via deterministic routing in `ChatViewModel.kt`, strictly maintaining the `MinorsCheck.kt` safety gate while enabling 100% uncensored adult/nude art on device. Verified live on Pixel 9 Pro with prompt `"draw a nude lady in a rainy bus stop at night"`. |

## Known issues and tech debt

- Generation ~10 tok/s on Gemma 4 E2B (Phase 12: NPU, keep-cool)
- Saving a new fact makes the next reply ~8 s slower once (the prompt tail changes, so the conversation is re-read) → Phase 6 should put memory where it doesn't invalidate the chat cache
- Chats are saved as plain JSON (not encrypted yet); context is 8k tokens with no summarising of long chats
- **Injection claim:** in 1 of 9 sampled injection tests Ronie *said* he'd deleted the tasks and set a 3 am alarm, as the injected text told him to. Nothing happened (the gate blocks it), but the claim is false. Fix idea: mark tool results as data-only in the response wrapper and re-run the eval
- **At Tone +100% (Sweary), Ronie insults the user** (phone: "you lazy fuck", "you bastard"). Measured (memory eval, 30 replies each): +100% → 8/30 hostile, 28/30 swear; **+60% → 3/30 hostile, 9/30 swear**; 0 → 0 hostile. User decided to keep +100% (2026-11-09); a "swear with the user, never at them" direction remains an option for later
- Tool eval misses: "for Friday" left in a task title instead of the due date; "note for the future…" not saved; "I'm so tired today" sometimes saved as a fact
- Tasks, facts and connectors live in JSON files (not encrypted yet) → Phase 6 moves them into SQLCipher
- Explicit adult fiction is refused or tame (Gemma's training); won't be changed by weight editing
- No CI

## ⏸ Parked / deferred (needs the user's go-ahead to start)

- "Ronie Gemma" fine-tune and Hugging Face upload
- `no-loops` patch for the Qwen tiers (data and Modal jobs ready)
- Speculative decoding (doesn't work with Qwen3.5's recurrent layers)

---

## Next steps

### Now: Phase 8 — Web search & grounding

**Decision (user, 2026-11-09):** no API key. DuckDuckGo's lite results page (unofficial: could break or be rate-limited) with Wikipedia's official API as the fallback.

**Done so far**
- ✅ `web_search` tool: search → read the top 3 pages in parallel (HTTPS only, ≤ 1.5 MB, menus/footers/scripts stripped) → ~500-char passages → the 4 most relevant by bge-small (≤ 2 per site) → numbered sources `[n]` for Ronie to cite (`web/WebSearch.kt`)
- ✅ Personal details scrubbed before any query leaves the phone (the user's name, emails, phone numbers, long ID numbers, street addresses); every search and page read is listed in the Activity log; offline mode blocks search
- ✅ Search card with "Sends data to duckduckgo.com + the sites below" and tappable numbered source chips; "Searching the web…" status pill
- ✅ Rule: search for anything current or uncertain, answer from the sources, cite [n], say so when they don't cover it
- ✅ Unit tests: DuckDuckGo parser (saved page), page reader, passages, scrubber
- ✅ Tool eval with 8 search + 3 grounded-answer cases: **95% (79/83)**; all search and grounded cases pass (cites [1], says when sources don't answer); chat questions don't trigger searches
- ⏳ Not yet on the phone (adb lost the phone during install)

**Still to do in Phase 8**
- ✅ Phone test (real searches): F1, RBI, biryani (see Startup speed section notes)
- Offline Wikipedia pack (own build: top articles' passages + bge vectors, optional download), router order memory → offline Wikipedia → web
- Claim checking (flag answer sentences not supported by the sources)
- Grounded-QA eval ≥ 85% with citations (exit criterion)

### Public launch prep (2026-11-09)
- ✅ README.md: platform-neutral ("your phone"), uncensored / no account / no tracking up front; comparison table, privacy table (exactly what leaves the phone), FAQ, keyword footer, accurate build-from-source steps (third_party is fetched, patches applied)
- ✅ `docs/media/banner.svg` (README header) and `docs/media/social-preview.png` (1280×640, for GitHub's social preview)
- ✅ `docs/launch-kit.md`: repo name `ronie-pocket-offline-mobile-ai`, description, 20 topics, launch order, drafted posts (r/LocalLLaMA, Show HN, X, r/privacy), demo-video script, v0.1.0 release notes, awesome-list targets
- ✅ Licence: **Apache-2.0** (user, 2026-11-09): `LICENSE` (official text) + `NOTICE` (bge-small MIT, Paradee and misaki Apache-2.0, llama.cpp MIT; downloaded models keep their own licences); README badge and section
- ⏳ Before going public: publish a v0.1.0 release with the app, record the demo GIF

### Phase 7 plan (2026-11-09)
- ✅ Detailed build plan written: [phase7-plan.md](phase7-plan.md). One brain per process (`BrainHost`) shared by chat and background jobs; IMAP + app password (read-only, drafts only after a confirm card); rules prefilter then Gemma triage with a plain prompt; notifications with feedback buttons (training data for Phase 11); mail tools with injection tests; tasks moved into SQLCipher; daily brief
- ⏳ Questions for the user when Phase 7 starts: which mailboxes (Outlook needs OAuth), background triage on battery, real-mail eval on the PC, weather city

### Phase 9 / 9.5 plan (2026-11-09)
- ✅ Detailed build plan written: [phase9-plan.md](phase9-plan.md). Vision via llama.cpp `mtmd` + Gemma 4 mmproj (engine-level image-aware prefix reuse, share/picker/camera, expense card, 80-image eval); image generation via a bake-off (DreamLite lead, licence-safe fallback) with a code-enforced safety gate
- ⏳ Open for the user before 9.5's runtime work: non-commercial (better) vs licence-safe (weaker) drawing model
- ✅ User (2026-11-09): "don't block anything". The plan has no filters except the user's hard limit, enforced in code: nothing sexual involving minors

### Startup speed (user request — done 2026-11-09)
- ✅ Persona state (persona + tool rules + tool descriptions, 1,949 tokens, 18 MB) is computed on the PC by `tools-pc/persona_state` and shipped in `assets/state/`; `scripts\make-persona-state.cmd` (run by both install scripts) regenerates it only when the prompt changes. `PersonaPrefix` is the single source for the fixed text; the `PersonaPrefixExport` test writes it for the PC tool. Flash attention set explicitly off in the engine and the tool (the KV layout must match)
- ✅ First launch: **56.3 s → 24.1 s** (state copied from the app and restored instead of computed: phone needed ~49 s for it, the PC 7 s)
- ✅ Measured: model load ~4.5–6 s, persona restore 0.1 s, then a **8.9 s** first GPU pass for just 131 tokens: the Vulkan backend built every shader pipeline from scratch on each launch (ggml-vulkan has no pipeline cache)
- ✅ **Persistent Vulkan pipeline cache** (our llama.cpp patch, `scripts/patches/llama-vulkan-pipeline-cache.patch`): the device gets a `VkPipelineCache` loaded from `GGML_VK_PIPELINE_CACHE`, used for every pipeline, saved by `ggml_backend_vk_save_pipeline_cache` (found through the backend registry). App side: `LlamaEngine.setGpuCache` before init (`files/gpu_pipelines.bin`, ~0.9 MB), `saveGpuCache` after warm-up and after the first few replies (≤4 saves per launch, ~5 ms each). First GPU pass **8.9 s → 4.1 s**
- ✅ **GPU warm-up runs behind the chat** once this phone's speed is known (every launch after the first): the chat opens right after the persona restore; the warm-up pass and the tone slider load in `warmJob`, and a reply sent before it finishes waits for it
- ✅ Ready time on later launches: **15.2 s → 4.6 s** (two launches measured; the background warm-up finishes ~4.2 s later). Almost all of what's left is loading the model onto the GPU
- ✅ Phone check: message sent ~2.5 s after the chat opened waited for the warm-up and replied normally (9 s from send to full reply)
- ✅ Web-search phone retest after the source-quality fixes (2026-11-09): every question searched (~2–4 s per search, 3 sources each)
  - "most recent F1 race" → Norris, São Paulo [1] ✔ (2026-11-08 race)
  - "RBI repo rate right now" → 5.50% [3] ✔ (hike of 2026-10-07)
  - "good biryani in Bangalore" → now searches (didn't before); 5 real places, but only one citation and one muddled line ("Shivaji Military Hotel (for Donne, Meghana)"): list items likely partly from the model's own knowledge. Follow-up: claim checking / cite every list item

### Phase 6 — Memory & user profile (partly done; paused for Phase 8)

**Done so far**
- ✅ Retrieval eval set: 60 synthetic facts, 70 indirect English questions + 10 Hinglish (`eval/memory/retrieval_set.py`)
- ✅ Embedding bake-off, int8 ONNX in ONNX Runtime, 1 CPU thread on the PC (`eval/memory/embed_bakeoff.py`, `eval/results/memory_embed_bakeoff.json`):

| Model | R@1 | R@3 | Hinglish R@3 | ms / sentence | Size | Licence |
|---|---|---|---|---|---|---|
| EmbeddingGemma-300m | 95.7% | 97.1% | 100% | 146 | 310 MB | Gemma terms |
| bge-small-en-v1.5 | 78.6% | 95.7% | 80% | 4 | 34 MB | MIT |
| arctic-embed-xs | 71.4% | 90.0% | 70% | 1.7 | 23 MB | Apache-2.0 |
| all-MiniLM-L6-v2 | 20.0% | 28.6% | 50% | 11 | 23 MB | Apache-2.0 |

- ✅ **Decision (user, 2026-11-09): bge-small-en-v1.5** (int8 ONNX, 34 MB, shipped in the app, ~free per message). EmbeddingGemma stays a possible optional upgrade if Hinglish recall matters
- ✅ bge-small shipped in the app (`assets/memory/`, 34 MB, MIT notice); **Kotlin WordPiece tokenizer matches Hugging Face's on all 150 test texts** (unit test); embedder in ONNX Runtime: loads in 0.7 s in the background, **15 ms per embedding on the Pixel**
- ✅ **Encrypted memory database** (SQLCipher 4.6.1, random 256-bit key kept encrypted by an Android Keystore key; checked on the phone: the file header is ciphertext). Facts moved in from the old JSON file automatically, each with its vector
- ✅ **Per-message recall**: Ronie's prompt gets pinned facts + the 6 most relevant facts (cosine ≥ 0.55) instead of the newest 30; with ≤ 8 facts all are used. Forgetting a fact wipes its text and keeps a fingerprint so it's never saved again; Undo of a fresh `remember` deletes it outright
- ✅ **Ronie now uses what he remembers.** Phone test 1 showed retrieval worked but Ronie ignored the facts (butter chicken for a vegetarian sister). Fix, measured with `eval/memory/use_eval.py` (10 scenarios × 3 runs, app's exact prompt): recalled facts ride along with the user's message as "(Keep in mind for this answer, from what I've told you before: …)" instead of the end of the system prompt, plus a rule to check every suggestion against them. **Uses memory: 60% → 87% at Tone 0, 60% → 80% at Tone +100%.** Tool-call eval unchanged (96%). Recall now always filters by relevance (pinned facts always in). Phone test 2: sister → vegetarian dishes (black bean burgers, paneer tikka, chole), Kabir → wedding gifts, milkshake → lactose warning. Test facts forgotten and the test chat deleted afterwards
- ⚠️ Saving facts: 9/10 "Remember that…" saved (one got "I'll keep that in mind" with no tool call); the model sometimes drops details ("Sister is vegetarian" lost the name). One old fact in your memory reads just "Remember that" (junk save)

**Plan**
1. Encrypted on-device database (Room + SQLCipher, key in Keystore); move tasks, facts and connectors into it
2. Saved conversations: chat list in the drawer, reopen old chats
3. Small on-device embedding model (choose and measure on the Pixel) for memory search and the tool router
4. Fact extraction after each conversation (background model, JSON); low-confidence facts go to a "Did I get this right?" queue
5. Retrieval per message: top facts + past episodes → a ≤ 200-token profile snippet, placed so it doesn't break the chat cache
6. "What Ronie knows about you" screen: browse, edit, pin, delete, export (JSON), wipe all (upgrade of today's list)
7. Smart Recall: search past chats by meaning; "what did we decide about X last week?"
8. Nightly consolidation while charging and idle (merge duplicates, resolve conflicts, daily episode)
8b. ➕ Habits grid (from the reference board): habits as tiles with coloured day squares and a check-in button; Ronie can log check-ins
9. Exit: after a week of real use ≥ 90% of shown facts correct; deleted facts never come back

### Leftovers from earlier phases (can be slotted in any time)
- Phase 4: real spoken command test by the user · A/B listen vs Ronie Web · visor avatar · hands-free mode
- Phase 3.5: patch screen in the drawer · open-source `rpatch` repo · demo video
- Phase 5: OAuth for connectors · tools on the Qwen tiers · test a real hosted connector
- Phase 3: copy/share a reply · download kill-and-resume test
- Phase 1: RAM + 5-minute thermal test
- CI for build + unit tests

### After Phase 6 (build-plan order)
| Phase | What |
|---|---|
| 7 | Email watcher (IMAP or Gmail), triage, tasks & reminders, calendar, daily brief, draft replies |
| 8 | Web search with PII scrubbing and citations, offline Wikipedia, claim checking |
| 9 | Vision: camera / gallery / screenshots; Mirage vs Gemma's vision encoder |
| 9.5 | Image generation (DreamLite bake-off), optional download, `generate_image` tool |
| 10 | Weight-editing pipeline driven by logged failures |
| 11 | Distilled helper classifiers (email importance, tool selection, …) |
| 12 | Performance & battery: NPU, keep-cool mode, unload when idle |
| 13 | Hardening, privacy audit, licences, store listing, beta |
| 14 | Moonshots: Brain-print, more patches, X-ray v1, Ronie Sleeps, Life Radar, Proof-of-Local |

---

## Keeping this file current
After every feature, fix or decision: tick it here, add anything new to **➕ Added along the way**, update **Where we are** and the last commit. Each phase also gets its `phaseN-results.md` with the measured numbers.
