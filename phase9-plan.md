# Phase 9 (Vision) and Phase 9.5 (Image generation): build plan

Written 2026-11-09 for an agent picking this up cold. Follow it in order. Every step lists the files to touch, what "done" looks like, and the check that proves it. Where a fact could not be confirmed while this was written, it is marked **VERIFY**, together with what to do for each possible result. Don't guess past a VERIFY.

Read first, in this order:
1. [STATUS.md](STATUS.md): what exists today and the decisions log
2. [build-plan.md](build-plan.md) §0 and §1: model policy, tiers, privacy rules
3. [persona-ronie.md](persona-ronie.md): Ronie's voice (image replies must still sound like him)
4. [phase1-results.md](phase1-results.md): the earlier vision spike (Qwen mmproj on the phone, 2.5 s per image) and the Mirage note

---

## 0. Ground rules (these bite if you skip them)

**The user's standing rules**
- **Phone:** ask the user before driving the phone UI. Before *every* input or launch, check:
  - no call: `dumpsys telephony.registry` → `mCallState=0`
  - no internet call: `dumpsys audio` → `Actual mode = MODE_NORMAL`
  - Ronie is in front: `dumpsys activity activities` → `topResumedActivity` contains `com.raunakpatil.roniepocket`
- Never take screenshots during calls. Delete screenshots afterwards.
- Always pass `-s <the Pixel's adb serial>`: a Philips TV is also on adb. If mDNS discovery fails, run `adb kill-server`, wait, and reconnect.
- **Commits and publishing:** commit only when the user asks, and never push. No Hugging Face uploads or other publishing without approval. Training jobs run on Modal.
- **Content hard limits** (apply to images too):
  - nothing sexual involving minors
  - no practical help to build bombs, explosives, poisons or weapons, or to hurt real people
  - Otherwise Ronie is uncensored by design. **The user said "don't block anything" (2026-11-09):** add no filters, refusals or safety classifiers beyond the minors check in §9.5.5.
- **STATUS.md:** update it after every change, including small extras: tick items, record measurements, add decisions to the log.

**Build environment gotchas**
- **Gradle inside the agent sandbox** fails with "Unable to establish loopback connection". Run builds in the user's Terminal panel instead (`cmd /c scripts\build-install-quiet.cmd`, `cmd /c scripts\test.cmd`).
- **Engine native libraries** (llama.cpp, ggml-vulkan and soon mtmd) are prebuilt by `scripts\build-engine-libs.cmd` into `core/engine/src/main/jniLibs/arm64-v8a` and `core/engine/prebuilt/include`. It builds incrementally. Our llama.cpp patches live in `scripts/patches/`. Regenerate the patch file whenever you touch `third_party/llama.cpp`.
- **Scripts:** `.cmd` files use CRLF line endings.
- **Writing source files:** don't write source through bash heredocs containing `\n` or `\b`; they arrive as real control characters. Use the Edit/Write tools, or a Python patch script written with the Write tool and then run.
- **Persona state:** changing the system prompt, `ToolPrompt.GUIDE` or `assets/tools/builtin.json` changes the cached persona state. The state file name is a hash of the prompt, and `scripts\make-persona-state.cmd` (run by both install scripts) regenerates `app/src/main/assets/state/FULL-*.bin` on the PC.
- **Tool golden test:** after changing `builtin.json`, regenerate the golden with `python eval/tools_v2/check_template.py <gemma4.jinja> --write-golden`, then rerun `ToolSyntaxTest`. Take the Gemma 4 chat template from the GGUF metadata (`tokenizer.chat_template`) if the scratch copy is gone.
- **Evals:** these run on a local CPU `llama-server` with `--special` (see `eval/tools_v2/run.py`). Re-run the tool eval after any prompt change. It is 81/85 today; it must not drop.

**Where things are**

| Area | Files |
|---|---|
| Engine (JNI) | `core/engine/src/main/cpp/ronie_engine.cpp`, `core/engine/src/main/java/.../engine/LlamaEngine.kt`, `PromptFormat.kt` (`ChatMessage(role, content)`, GEMMA4 / QWEN35 formats) |
| Chat flow | `app/.../ChatViewModel.kt` (`load()`, `warmUp()`, `finishWarmUp()`, `generateReply()`, `warmJob`, `saveGpuCacheSoon()`), `ChatScreen.kt`, `ChatStore.kt` (chat JSON on disk) |
| Models | `models/Tiers.kt` (`ModelSpec`, `Tier`), `models/ModelStore.kt`, `models/ModelDownloadWorker.kt` (resumable, SHA-256), `models/DeviceProfile.kt` |
| Tools | `tools/Builtins.kt` (`Outcome.Done/Failed/NeedsPermission`), `tools/Toolbox.kt` (`Gate`: read-only → RUN), `tools/ToolCardUi.kt` (`ToolCard`), `tools/ToolPrompt.kt` (`GUIDE`), `assets/tools/builtin.json` |
| Sharing | `MainActivity.kt` (`ACTION_SEND` text only today), `AndroidManifest.xml`, `SharedCard` in `ChatScreen.kt` |
| Settings | `SettingsDrawer.kt` (sections You, Ronie, Voice, Tools, Memory, **Models**, Privacy, About) |
| UI kit | `ui/Theme.kt` (neon palette), `ui/Widgets.kt` (Tile, PillButton, Chip, DotMatrix, ShineText…), `cards/Cards.kt` (living-art skins) |
| Local models (PC) | `models-local/` (`gemma-4-E2B-it-Q4_K_M.gguf`; `mmproj-F16.gguf` is **Qwen3.5-0.8B's** projector, not Gemma's) |

---

# Phase 9: Vision

**Goal.** The user shares, picks or shoots a photo, and Ronie understands it. He reads receipts into a clean expense card, summarises documents, explains screenshots and answers "what is this?". All of it runs on the phone, with no network.

**Exit criteria** (all measured, all recorded in STATUS.md):
- vision eval ≥ 85% (§9.6)
- an image turn finishes in < 10 s on the Pixel 9 Pro, from tapping send to the full reply, for a typical photo
- no crash or out-of-memory with images during a 20-turn chat
- sharing an image from Gallery, Chrome and Files works
- the tool eval does not regress

### Decisions already made (don't reopen)
- **Use the brain's own vision encoder** (llama.cpp `mtmd` + an mmproj file). Gemma 4 E2B is natively multimodal, and llama.cpp at our commit has `PROJECTOR_TYPE_GEMMA4V` (`third_party/llama.cpp/tools/mtmd/models/gemma4v.cpp`). A separate captioning model would lose the "ask anything about it" flexibility.
- **Mirage** (Cactus, 22 MB) is an *optional, time-boxed* side experiment (§9.8), not the main path.
- **Thinking stays off** for image turns. It's better for OCR and documents, and faster.
- **Images never leave the phone.** If an image turn also triggers `web_search`, only text goes out: the scrubbed query, never pixels or OCR'd personal data. Strip EXIF (GPS!) when importing.

### 9.1 Engine: build and link libmtmd

1. In `scripts\build-engine-libs.cmd`, add `-DLLAMA_BUILD_MTMD=ON` to the configure line. Keep `LLAMA_BUILD_TOOLS=OFF`; the top-level CMake then does `add_subdirectory(tools/mtmd)` on its own (see `third_party/llama.cpp/CMakeLists.txt` near line 268).
   - Also pass `-DMTMD_VIDEO=OFF` (needs ffmpeg; not wanted).
   - `mtmd` links `ggml` and `llama` only, never `llama-common`, which is good: it works with our `GGML_BACKEND_DL` setup.
   - Make sure `libmtmd.so` and the headers `mtmd.h` and `mtmd-helper.h` are installed into `core/engine/src/main/jniLibs/arm64-v8a` and `core/engine/prebuilt/include`, the same way the script copies `libllama.so` today. Read the tail of the script for the copy step.
2. In `core/engine/src/main/cpp/CMakeLists.txt`, import `libmtmd.so` as a prebuilt shared library (copy the pattern used for `libllama.so`) and link it into `ronie_engine`.
3. Rebuild the engine libraries, then the app. **Check:** the APK contains `lib/arm64-v8a/libmtmd.so`, and the app still starts and chats as before (startup ≈ 4.6 s, see STATUS.md).

### 9.2 Engine: image-aware generate (the core piece)

Today `nativeGenerate` tokenizes the whole prompt and reuses the longest common token prefix with `e->cached` (`ronie_engine.cpp`, around line 270). Images have to fit into that scheme so earlier turns are not re-encoded on every reply.

**New engine state** on the `Engine` struct:
- `mtmd_context *mtmd = nullptr;`
- `std::string mmproj_path;`

**New JNI functions** (with matching `LlamaEngine.kt` wrappers; all run on `engineThread` like the rest):
- `nativeLoadVision(h, mmprojPath, useGpu, maxImageTokens): Boolean`
  - `mtmd_context_params_default()`, then set:
    - `use_gpu = true`
    - `n_threads = 4`
    - `warmup = false` (our pipeline cache plus a lazy first use is better)
    - `image_max_tokens = maxImageTokens`
  - then `mtmd_init_from_file(path, e->model, params)`
  - Load **lazily** on the first image, not at startup: startup must stay ≈ 4.6 s.
- `nativeFreeVision(h)`: frees the vision context (used when memory is low; see 9.7).
- `nativeMediaMarker(): String` returns `mtmd_default_marker()`. Kotlin must use this string, never a hard-coded copy.
- `nativeGenerateMM(h, prompt, imagePaths: Array<String>, maxTokens, sink): String`. Same contract and stats JSON as `nativeGenerate`, plus `image_ms` and `image_tokens`. Behaviour:
  1. Load each image with `mtmd_helper_bitmap_init_from_file` (or from a buffer). Set each bitmap's id with `mtmd_bitmap_set_id` to a stable id: the image file's SHA-1, computed in Kotlin and passed in, or the file name if it already is a hash.
  2. `mtmd_tokenize` with `{text=prompt, add_special=false, parse_special=true}`; this matches our text tokenizer, whose prompt already has `<bos>`.
  3. **Flatten the chunks into a comparable "position key" list** for prefix reuse:
     - text chunk: its tokens
     - image chunk: `mtmd_input_chunk_get_n_pos(chunk)` copies of a sentinel `-(int32)(fnv1a(image_id) & 0x3fffffff) - 1`
     - Negative ids can't collide with real vocab ids. Store this list in `e->cached`, which already holds the KV-cache tokens. Note `n_pos`, not `n_tokens`: M-RoPE models differ.
  4. Find the common prefix against `e->cached` as today. If the prefix ends *inside* an image's sentinel run, back it up to the run's start, because an image must be re-evaluated whole. Then `llama_memory_seq_rm(mem, 0, common, -1)`. If that fails, clear and start from 0 (same fallback as today).
  5. Evaluate only the chunks after `common`.
     - Text: our existing `decode_chunked`.
     - Image: `mtmd_encode_chunk`, then `mtmd_helper_decode_image_chunk` (or `mtmd_helper_eval_chunk_single`) at `n_past`. Read `mtmd-helper.h` for the exact names at this commit.
     - Gemma's image attention is non-causal. The helper handles that: use it rather than hand-rolling `llama_decode` for images.
  6. Sample exactly as `nativeGenerate` does. **Factor the sampling loop out** into a shared static function so the two paths can't drift (LoopGuard, X-ray probe, `stopped` handling, stats).
- The text-only `nativeGenerate` must keep working untouched for Qwen tiers without vision and for all text turns. Its sentinel-free `cached` list stays compatible: no negative ids means no false matches.

**Kotlin API.** Give `LlamaEngine.generate(prompt, maxTokens, images: List<File> = emptyList())` a branch to `nativeGenerateMM` when `images` is not empty, keeping the same `Flow<GenEvent>`. Add `suspend fun loadVision(path: File, maxImageTokens: Int): Boolean` and `val visionLoaded`.

**GPU cache.** The first image compiles the vision encoder's shader pipelines, which takes seconds. After the first image turn, call `saveGpuCacheSoon()` again. Raise its per-launch cap (currently 4), or add a separate one-off save after the first vision use.

**Tests**
- A C++-free JVM unit test for the Kotlin side: marker insertion and path ordering (9.3).
- On the PC, extend `tools-pc/probe` with a tiny `mm_probe` that runs the same `nativeGenerateMM` logic: a turn with one image, then a follow-up text turn. It must log `prompt_new_tokens` ≈ only the new text on the follow-up, proving the image wasn't re-encoded.
- Same check on the phone via logcat stats.

### 9.3 Prompt format and history

- `ChatMessage` (core/engine `PromptFormat.kt`) gains `val images: List<String> = emptyList()`: absolute file paths, in order.
- **`PromptFormat.format()` writes one media marker per image**, at the **start of the user turn**, before the text, separated by `\n`. This is Gemma's recommended order (image first, then the question). The marker string comes from `LlamaEngine.mediaMarker`, read once.
  - Add a golden unit test for a GEMMA4 turn with 2 images, and the same for QWEN35.
  - **VERIFY:** compare against the real Gemma 4 chat template, the jinja in the GGUF, rendered with an image content part. mtmd replaces the marker with the model's own begin/end-of-image tokens, so the text around the marker must match the template exactly. Fix the golden if it doesn't.
- `generateReply` collects all images still present in the formatted history, in prompt order, and passes them with the prompt.
- **Context budget** (n_ctx is 8192; the persona is ~2,080 tokens):
  - Only the **last 2 images** in a chat stay as real images in the prompt.
  - An older image is replaced in `history` by text: `[photo: <short description>]`, where the description is the first sentence of Ronie's reply to it (keep it ≤ 25 words).
  - This edits an old turn, so the prefix after it gets re-decoded once. That's acceptable; it happens only when a third image arrives.
  - **VERIFY** the token cost per image at the chosen `image_max_tokens` (below). If 2 images plus history ever exceed ~6,000 tokens, drop to 1.
- **Image size budget.** Gemma 4 supports several visual token budgets. Measure on the Pixel with `image_max_tokens` = 140, 280 and 560 (or whatever values the GGUF metadata allows; read `clip.vision.*` keys). Record encode ms and the vision-eval score for each.
  - Default: the smallest budget within 2 points of the best score.
  - Expected: 280 for photos, 560 for documents and receipts.
  - If the difference is real, pick per image: a document-like aspect ratio or a lot of text after a quick check → the higher budget. Keep it simple: one setting if the scores say so.

### 9.4 Getting images in (share, gallery, camera)

1. **Manifest.** On `MainActivity`:
   - add `image/*` to the existing `ACTION_SEND` filter
   - add an `ACTION_SEND_MULTIPLE` filter for `image/*` (max 4 images per message; ignore the rest with a toast)
   - add a `FileProvider` (`${applicationId}.files`, paths: `files/images/`, `cache/camera/`)
2. **Import** (`app/.../vision/ImageImport.kt`, new). Takes a content `Uri` and returns a `File` in `files/images/<sha1>.jpg`:
   - copy right away (URI grants are temporary)
   - decode with `ImageDecoder` (handles HEIC and rotation)
   - downscale so the long side is ≤ 1536 px
   - re-encode as JPEG q90; re-encoding drops all EXIF, including GPS
   - name the file by the SHA-1 of the output bytes, so the same image is never stored twice
   - run on `Dispatchers.IO`
   - Unit-test the size math and the naming.
3. **Input row** (`ChatScreen.kt`): a "+" button left of the field opens two choices:
   - **Photos:** `PickVisualMedia`, with no storage permission needed
   - **Camera:** `TakePicture` into `cache/camera/`; needs the `CAMERA` permission at runtime, with a plain-words rationale
   - Selected images show as small thumbnails (with ✕) above the field before sending.
   - Follow `ui-direction.md` and the current neon look: thumbnails in glass `Tile`s, nothing bright white.
4. **Share sheet.** When an image is shared, `SharedCard` shows the thumbnail plus quick actions: **What's this? · Read the text · Summarise · Receipt → expense**. Each action sends a fixed prompt with the image attached. Text shares keep working as today.
5. **Chat rendering.** User bubbles show attached images (rounded, max 220 dp tall; tap → full-screen viewer with pinch-zoom). The viewer is also used by Phase 9.5.
6. **Storage.**
   - `ChatStore.save` writes `images` for each user message and in `history`; loading restores them.
   - Deleting a chat deletes its image files unless another chat references the same hash; check with a simple scan, since chats are few.
   - Add a round-trip unit test.
7. Not in scope: "explain my screen" through the assistant gesture. That needs a `VoiceInteractionService` and the screenshot API. Note it in STATUS.md as later.

### 9.5 Ronie's behaviour with images

- **Rules** in `ToolPrompt.GUIDE` or the persona system text, kept short (every token here costs startup only once, but costs context forever):
  - Describe what is actually visible; say when something is unreadable instead of guessing.
  - For text in images, quote it exactly.
  - For receipts, output the expense block (below).
  - Name people, places, products and logos when he recognises them; when he isn't sure who someone is, say so rather than guess.
  - This changes the persona prefix, so run `make-persona-state` (automatic in the install scripts) and the tool eval.
- **Receipt → expense card.** When the user asks for a receipt or expense (share action or plain words), Ronie replies with a short line plus a fenced block.
  - Format:
    ````
    ```expense
    {"merchant":"…","date":"YYYY-MM-DD","total":123.45,"currency":"INR","items":[{"name":"…","qty":1,"price":…}],"tax":…}
    ```
    ````
  - `cards/Cards.kt` renders it as an **Expense card**:
    - merchant, date and big total
    - item list using the colour-ramp list dots
    - actions **Copy** (plain text), **Share**, **Add task** (prefills `create_task` "Claim expense: <merchant> <total>" through the normal tool path, which shows the Undo card)
  - If the JSON doesn't parse, show the text as is; never crash.
  - Missing fields show "—".
  - Currency: assume the user's locale (INR) unless the receipt shows otherwise.
- **Document → summary.** No special card: the normal reply, with the checklist card if he lists steps.
- **Tool interplay.** Images and tools mix freely. A typical case: "add this event to my tasks" with a poster image calls `create_task` with the date read from the image. Add 6 cases like this to the vision eval (§9.6).
- **Voice.** If the turn came from the mic, the reply is spoken as today. The "processing image" state (below) is silent.
- **Progress UI.** While the image is encoded, the status pill shows "Looking at your photo…". It switches to normal streaming at the first token. Show encode time in the X-ray / stats line (`image_ms`).

### 9.6 Vision eval (`eval/vision/`)

Build this **before** tuning budgets or prompts; it's how every decision in 9.3 and 9.5 gets made.

- **Set:** 80 images, every one licence-clean. No personal photos of the user without asking; only images we generate or ones that are CC0 or public domain.
  - **Synthetic receipts (20).** Python plus Pillow renders thermal-receipt-style images: random Indian and English merchants, items, GST, totals, some crumpled/rotated/low-contrast. Ground truth comes from the generator.
  - **Synthetic documents (15).** Letters, notices, a bill and a timetable, rendered from text; questions about specific facts.
  - **Synthetic screenshots (15).** Rendered fake app screens and error dialogs: "what does this error mean?", "which button do I press?".
  - **Photos (20).** Wikimedia Commons CC0 / public domain: food, plants, landmarks, animals, signs (including Hindi signage). Record source URL and licence in `eval/vision/SOURCES.md`.
  - **Image + tool (6).** Event poster → `create_task` with the right date; a "remind me to pay this" bill → `set_reminder`.
  - **Honesty (4).** A medicine label read exactly; a blurry sign answered with "can't read it" instead of an invented text; a lookalike photo where he says he's unsure.
- **Format:** `eval/vision/cases.py`, like `eval/tools_v2/cases.py`: each case has an image path, the user text, and checks (`must_contain` with any-of groups, `json_fields` for receipts with numeric tolerance ±0.01, `tool_call` expectations, `must_not_contain`).
- **Runner:** `eval/vision/run.py`.
  - Starts a local CPU `llama-server` with `--mmproj <gemma mmproj> --special` and the exact system prompt from `PersonaPrefixExport` (`app/build/persona/FULL.txt`, plus the date tail).
  - Sends OpenAI-style image content parts and scores the answers.
  - Saves to `eval/results/vision_<tag>.jsonl` and a summary.
  - Also logs per-case prompt tokens, to confirm the image token budget.
- **Phone spot check:** 10 cases through the real app via the share sheet, with timings from logcat. Ask the user first; follow the phone rules.
- **Target:** ≥ 85% overall, receipts total-correct ≥ 90%.

### 9.7 Models, downloads and memory

- **VERIFY: which mmproj for Gemma 4 E2B.** A discussion on `unsloth/gemma-4-E2B-it-GGUF` reports that its mmproj files may export only the vision encoder. Try in this order:
  1. the `mmproj` in the same unsloth repo and revision we pin (`0314792d…`)
  2. `ggml-org/gemma-4-E2B-it-GGUF`'s mmproj

  Pick the first that passes a 10-case smoke test on the PC (`llama-mtmd-cli -m … --mmproj …`). Prefer Q8_0 or F16 (projectors are small, and quantising them hurts OCR). Pin repo, revision, file, size and SHA-256 exactly like `ModelSpec`.
- **Model spec.** Add `ModelSpec.vision: FileSpec?` (repo, revision, file, size, sha256) and fill it for FULL.
  - For STANDARD and LITE, add the Qwen3.5 mmproj files (the 0.8B projector is `models-local/mmproj-F16.gguf`, 205 MB; find the 2B one in the same unsloth repos). Vision on those tiers is a *should*: same code path, smaller eval run (receipts plus photos only).
- **Downloads.** Generalise `ModelDownloadWorker` from "a tier's model" to "a list of files". Add input key `KEY_ASSET` = `brain:<TIER>` | `vision:<TIER>` | `drawing:<ID>` (9.5), each with its own unique work name so they don't cancel each other.
  - Resumable, SHA-256-checked, survives the app closing: keep all of today's behaviour.
  - Vision downloads **with the brain by default**. It is part of Ronie, and the size is shown in the brain picker.
  - Phones that already have the brain installed get a one-time "Ronie can now see photos — download X MB?" tile in the chat, plus Settings → Models → Vision: Download / Remove.
- **Memory.**
  - Measure peak RSS with the vision encoder loaded: `dumpsys meminfo com.raunakpatil.roniepocket` before and after the first image.
  - On `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` or above, free the vision context; it reloads lazily next time.
  - Record the numbers in STATUS.md, and update `Tier.ramBytes` estimates if they were wrong.

### 9.8 Optional: Mirage experiment (time-box: 1 day)

Only after 9.1–9.7 pass. Mirage returns structured JSON (objects, boxes, OCR) in ~100 ms. It could do live camera "what am I looking at?" and pre-OCR for receipts.

1. **VERIFY the licence** on `Cactus-Compute/mirage`. Only continue if it allows redistribution in a free app.
2. Run it on the PC on the receipts and photos subsets.
3. Pipeline variant: Mirage JSON → text → Gemma (text only). Compare score and time against native Gemma vision.
4. Adopt it only if it's ≥ the native score on receipts **and** saves ≥ 2 s per image. Running it needs the Cactus engine (another native runtime). Count that cost: APK size and maintenance.
5. Write the result into `phase1-results.md` §Mirage and the STATUS.md decisions log, either way.

### 9.9 Phase 9 checklist (copy into STATUS.md and tick as you go)
- [ ] libmtmd built, linked, app unchanged at startup
- [ ] `nativeGenerateMM` with image-aware prefix reuse; follow-up turn doesn't re-encode (logged proof)
- [ ] Gemma mmproj chosen, pinned, downloading with the brain; Settings → Models → Vision
- [ ] Image import (downscale, EXIF stripped, hashed), picker, camera, share single and multiple
- [ ] Prompt format with media marker plus golden tests; last-2-images history rule
- [ ] Image budget measured (140/280/560) and default chosen
- [ ] Expense card; image+tool cases work
- [ ] Vision eval ≥ 85%; tool eval not regressed
- [ ] Phone: image turn < 10 s; no out-of-memory in a 20-turn chat; gallery, Chrome and Files shares work
- [ ] GPU cache saved after first vision use; second launch's first image is faster (logged)
- [ ] STATUS.md, phase9-results.md written (same style as phase5-results.md)

---

# Phase 9.5: Image generation

**Goal.** "Ronie, draw me a cat astronaut in watercolour" makes a picture appear in the chat, made on the phone, in < 10 s. It is optional and downloaded separately; phones that can't run it never see a download prompt they can't use.

**Exit criteria:**
- chat request → finished image in < 10 s on the Pixel 9 Pro
- no LLM crash or out-of-memory with both models installed and used alternately for 20 turns
- prompt adherence measured on our prompt set and recorded (§9.5.6)
- the minors check passes its test set (§9.5.5)
- download and remove work from first-run and from Settings

### 9.5.0 Decision for the user (ask before building the runtime)

The best small models have **non-commercial** weight licences:
- DreamLite weights are reported as CC BY-NC 4.0.
- SD-Turbo is under Stability's non-commercial community licence.

That's fine for a free app, but blocks a paid tier unless the licence changes. Present the bake-off table (9.5.2) to the user with each candidate's licence, and let them choose: **best quality (non-commercial)** or **licence-safe (lower quality)**. Record the answer in the STATUS.md decisions log. Don't decide this yourself.

### 9.5.1 Candidates and facts to check first

| Candidate | What we know | VERIFY before investing |
|---|---|---|
| **DreamLite** (ByteDance, ECCV 2026). Lead | 0.39B pruned mobile U-Net; generation **and editing** in one model; 4-step "mobile" variant; 1024² in ~3 s on iPhone 17 Pro; GenEval 0.72. Code: github.com/ByteVisionLab/DreamLite; README uses `carlofkl/DreamLite-base` / `-mobile` in a diffusers-style pipeline | Weights actually downloadable? Exact licence of the **weights** (expected CC BY-NC 4.0)? Text encoder used (size matters on the phone)? Does it run in plain `diffusers` or only their fork? |
| **SnapGen++** (Snap) | 0.4B DiT, 1024² in 1.8 s on iPhone; GenEval 0.70 | **No public weights found** as of 2026-11-09. Check the project page and HF once; if still none, drop it |
| **SD-Turbo** (Stability) | 1-step 512², SD2.1 U-Net 865M; known to run in stable-diffusion.cpp | Licence is non-commercial; quality at 512² only |
| **Licence-safe fallback**: SD1.5-family small model (e.g. segmind `small-sd` / `tiny-sd`) + LCM-LoRA, 4 steps, 512² | OpenRAIL-M (commercial use allowed, with use restrictions); runs in stable-diffusion.cpp | Exact licence of the specific checkpoint; quality on our prompt set |

Any newer small open model found during the check (2026 moves fast) can join the bake-off. Same rules: open weights, licence recorded, ≤ 1.5 GB on disk.

### 9.5.2 Bake-off on the PC, then the phone

1. **Prompt set** (`eval/imagegen/prompts.py`): 60 prompts with checkable attributes, in the style of GenEval:
   - single object (10)
   - two objects (10)
   - counting 2–4 (10)
   - colours (10)
   - position left/right/above (10)
   - styles Ronie users will ask for (10): watercolour, anime, pixel art, photo, logo-like, Indian festival scenes
2. **Judge:** our own Gemma 4 E2B with vision via local `llama-server` (Phase 9 must be done first). Ask one yes/no question per attribute ("Are there exactly 3 apples?"). Score = % of attributes judged yes. Spot-check 20 judgements by eye, and record judge agreement in the results.
3. **PC run.** Each candidate at its native resolution and step count, in diffusers (CUDA if present, else CPU). Record score, seconds per image and peak VRAM/RAM. Save a 4×4 contact sheet per candidate to `eval/results/imagegen_<model>.png` (not committed if large).
4. **Phone run** (top 2 by PC score, plus the licence-safe fallback). Use the runtime path from 9.5.3, built as a CLI first, pushed with adb and run from the shell like Phase 1's `phone-bench`. Record:
   - seconds per image (cold and warm)
   - peak RSS
   - GPU or CPU
   - temperature after 5 images in a row (`dumpsys thermalservice`)
   - **LLM speed right after**: run a 64-token generation and compare tok/s with the baseline
5. Write `eval/results/imagegen_bakeoff.md` with the table and the recommendation, then ask the user (9.5.0).

### 9.5.3 Runtime (pick per winner, in this order)

1. **stable-diffusion.cpp** (github.com/leejet/stable-diffusion.cpp). It uses ggml, same as our engine, so it can share our Vulkan know-how and the pipeline-cache patch idea. Use it if the winner's architecture is supported (SD1.x/2.x/Turbo/SDXL families are).
   - Vendor it into `third_party/stable-diffusion.cpp` at a pinned commit, with its **own** ggml copy built as static libraries inside its shared library. Don't mix it with our `libggml*.so`: the versions differ.
   - Build it with the NDK the same way as `build-engine-libs.cmd`. New script: `scripts\build-sd-libs.cmd`, Vulkan on, `subst V:` trick for the shader generator.
   - **VERIFY** that Vulkan works on the Mali-G715. If it doesn't, CPU with q8_0 weights is the fallback; measure it.
2. **ONNX Runtime** (already in the app for bge-small and Paradee). Export U-Net, text encoder and VAE decoder from diffusers to ONNX (fp16, or int8 dynamic for the text encoder). Run with the XNNPACK EP on CPU. Likely too slow for a 1024² U-Net; acceptable for 512² four-step models. Measure before deciding.
3. **LiteRT with the GPU delegate.** Best Mali GPU speed for conv-heavy U-Nets, but conversion (PyTorch → `ai-edge-torch` → `.tflite`) can take days for a custom U-Net. Use only if 1 and 2 miss the 10 s target, and time-box conversion to 3 days.

DreamLite is a custom pruned U-Net, so it probably needs path 2 or 3 unless stable-diffusion.cpp can load it after a small model-definition patch. Check that first: read DreamLite's U-Net config and compare it with SDXL's block layout.

**Native API** (whatever the runtime): a small `core/imagegen` module (new Gradle module, like `core/engine`) with:
- `ImageGen.load(dir): ImageGen?`
- `generate(prompt, negative, width, height, steps, seed, onStep: (i, n, previewRgb?) -> Unit): Bitmap`
- `edit(source: Bitmap, instruction, …)` (only if the model supports editing)
- `close()`

It runs on its own single-thread dispatcher. **Never** run it at the same time as an LLM decode. A shared `HeavyWork` mutex in the app makes the LLM and the drawer take turns. The tool call naturally happens while the LLM is paused, so in practice they don't fight.

### 9.5.4 App integration

- **Tool** in `assets/tools/builtin.json`:
  ```json
  {"name":"generate_image",
   "description":"Draw a picture on this phone. Use when the user asks you to draw, make, paint or design an image. Write a detailed prompt in English: subject, setting, style, lighting, colours.",
   "inputSchema":{"type":"object","properties":{
     "prompt":{"type":"string","description":"Detailed English description of the image"},
     "style":{"type":"string","description":"Optional: photo, watercolour, anime, pixel art, sketch, 3d"},
     "shape":{"type":"string","enum":["square","portrait","landscape"]},
     "edit_last":{"type":"boolean","description":"true to change the last image instead of drawing a new one"}},
    "required":["prompt"]},
   "annotations":{"readOnlyHint":true,"destructiveHint":false,"local":true}}
  ```
  - `readOnlyHint: true` → `Gate.RUN`: it changes nothing outside the app, so no confirm card.
  - Regenerate the tool golden and the persona state. Add 8 tool-eval cases: draw requests, "make it purple" → `edit_last`, and 3 non-draw messages that must *not* call it ("draw a conclusion", "I drew this yesterday").
  - Re-run the full tool eval; it must stay ≥ 95%.
- Add the tool only when the drawing model is installed. When it isn't, the declaration is absent. That keeps the persona prefix stable for most users: build the prefix with and without it, two state files. `PersonaPrefixExport` must write both variants. Otherwise, if the user asks Ronie to draw, a GUIDE rule tells him to say he needs the drawing download (Settings → Models).
  - **VERIFY:** the cleaner option may be to always declare it and return `Failed("Drawing isn't installed — Settings → Models → Drawing")`. Pick whichever keeps the tool eval higher. One persona state file is simpler, so prefer it unless the eval says otherwise.
- **Builtins.run("generate_image")**:
  1. Minors check, prompt side (9.5.5).
  2. Load the model if needed; show progress.
  3. Generate, keyed by a random seed (stored).
  4. Minors check, image side (only if step 1 saw a minor cue).
  5. Save PNG to `files/images/gen/<id>.png`.
  6. Return `Outcome.Done(forModel = "Image drawn and shown to the user. Prompt used: <prompt>", summary = "Drew: <short prompt>", image = file)`.
  - Add `image: File?` to `Outcome.Done` and `ToolCard`; persist it in `ChatStore`.
- **Image card** (`ToolCardUi.kt`):
  - while drawing: a dot-matrix progress ("Drawing… 2/4"), plus the step preview if the runtime gives one (blurry → sharp is a nice effect)
  - when done: the image, rounded, in a living-art frame from `cards/Cards.kt`
  - tap → the full-screen viewer from 9.4
  - actions: **Save** (MediaStore `Pictures/Ronie`, no permission needed on API 29+), **Share** (FileProvider), **Again** (same prompt, new seed), **Edit** (puts "Change this picture: " in the input field; Ronie calls `edit_last`)
- **Reply after drawing.** The model continues its turn with one short Ronie-style line. No long description of the image: the user can see it.
- **Vision synergy.** A generated image is a normal image file. "What's in this picture?" and "make the cat bigger" work through Phase 9.
- **Memory and RAM.** Before loading the drawer, check `ActivityManager.MemoryInfo.availMem`.
  - If it's below the drawer's measured peak + 1 GB, free the vision context first.
  - If it's still short, show "Not enough free memory to draw right now", and never kill the LLM.
  - Unload the drawer after 2 minutes idle, or on `onTrimMemory`.
- **GPU warm-up.** If the runtime is Vulkan, give it a pipeline cache file too: `files/gpu_pipelines_draw.bin`.

### 9.5.5 The one check: no sexual images of minors (enforced in code)

The user's instruction is **don't block anything**. Ronie draws whatever is asked, **including explicit sexual content of adults**, violence and real people, with no other filter, refusal, warning or blur. Do not remove or weaken sexual generation; the drawing model choice in 9.5.2 must not favour models that refuse or degrade NSFW prompts (record each candidate's NSFW behaviour in the bake-off table). The single exception is the user's own hard limit, which also can't be removed: **nothing sexual involving minors**. Keep it narrow so it never gets in the way of anything else.

1. **Prompt side** (`imagegen/MinorsCheck.kt`, pure Kotlin, unit-tested):
   - Normalise the text: lowercase, de-leet, strip punctuation.
   - Stop only if the prompt has a sexual/nudity term **and** a minor cue (child, kid, teen, young-looking, school uniform, loli, ages under 18 written as digits or words, and so on).
   - Keep both word lists in `assets/imagegen/minors.json`, so they're auditable.
   - Unit tests: 30 prompts that must stop, and 60 that must pass. The passes include adult nudity, adult sexual art, violence, real named people, "kids' birthday party cake" and "teen titans logo".
2. **Image side.** Only when step 1 saw a minor cue without a sexual term (for example "girl at the beach"), run a small on-device NSFW classifier (ONNX, our ONNX Runtime) on the result.
   - If it's sexual, delete it; it is never shown or saved.
   - **VERIFY** the classifier's licence and size; aim for ≤ 100 MB, int8.
   - Prompts without a minor cue skip this step entirely: no classifier, no delay.
3. When it stops, the card says "I won't draw that." in Ronie's voice, without a lecture. Log the event locally (time and rule, no image).
4. **Test target:** 100% of must-stop, 100% of must-pass.

### 9.5.6 Downloads and settings

- **First run** (`OnboardingScreen.kt`, under the brain picker): "Also download Ronie's drawing brain (X GB)".
  - Ticked by default only if the phone measured fast enough in the bake-off class: FULL tier and ≥ 12 GB RAM. Otherwise unticked.
  - Hidden on LITE phones.
- **Settings → Models → Drawing:** size, measured seconds per image on this phone (shown after the first image), Download / Remove, and the licence line ("Free for personal use — model by ByteDance, CC BY-NC 4.0", or whatever applies).
- Same `ModelDownloadWorker` (`drawing:<ID>` asset), multi-file. Folder `files/models/draw/<id>/`, everything pinned with SHA-256.
- **Privacy:** the download is the only network use. The Privacy section's "what leaves the phone" text gets one line about it.

### 9.5.7 Phase 9.5 checklist
- [ ] Candidate facts verified (weights, licences, text encoder); SnapGen++ status checked
- [ ] Prompt set plus Gemma-vision judge; PC bake-off table
- [ ] Phone bake-off: s/image, RSS, thermal, LLM slowdown
- [ ] **User chose** model and licence trade-off (decisions log)
- [ ] Runtime built (`core/imagegen`, build script, pinned commit), CLI proof on the phone
- [ ] `generate_image` tool, image card, viewer, save, share, again, edit
- [ ] Minors check (prompt plus image fallback) with its test set at 100%; nothing else filtered
- [ ] Download, remove, first-run checkbox, Settings
- [ ] Tool eval ≥ 95% with the new tool; persona state regenerated
- [ ] Exit: < 10 s per image in chat; 20 alternating turns without crash or out-of-memory
- [ ] STATUS.md plus `phase9.5-results.md`

---

## Suggested order and time

| Days | Work |
|---|---|
| 1 | 9.1 libmtmd build; mmproj VERIFY + smoke test on PC |
| 2–3 | 9.2 `nativeGenerateMM` + prefix reuse + PC probe |
| 4 | 9.6 vision eval set + runner (in parallel: 9.4 import code) |
| 5–6 | 9.3 / 9.4 / 9.5 app side; first phone test (ask the user) |
| 7 | Budget tuning, expense card, eval to ≥ 85%, 9.7 downloads and memory |
| 8 | Phase 9 wrap-up: results doc, STATUS.md |
| 9–10 | 9.5.1 facts + 9.5.2 PC bake-off → **user decision** |
| 11–13 | 9.5.3 runtime on the phone |
| 14–15 | 9.5.4–9.5.6 integration, minors check, downloads |
| 16 | Exit tests, results doc |

## Pitfalls to expect
- **KV reuse after images.** If `prompt_new_tokens` on a follow-up turn is roughly the whole history, the sentinel scheme isn't matching. Usually the image id isn't stable: you hashed the path instead of the content, or the file got re-imported.
- **SWA / partial `seq_rm`.** Gemma 4 uses sliding-window attention. Partial removal works today for text. If it fails after image chunks, the engine falls back to a full clear: correct but slow. Log it, and investigate before accepting a 10 s+ turn.
- **The first image is slow** (pipeline compile). Measure the *second launch's* first image after the GPU cache save, not the very first ever.
- **Two Vulkan users** (LLM and drawer) at once can freeze or crash some drivers. Hence the `HeavyWork` mutex. Don't remove it to "speed things up".
- **Prompt growth.** Every rule added to GUIDE costs context in every chat. Keep vision and drawing rules ≤ 120 tokens total, and measure the persona token count before and after (it's in the `make-persona-state` output).
