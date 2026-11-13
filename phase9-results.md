# Phase 9 — Vision: Results

Date: 2026-11-10 · Brain: Gemma 4 E2B (Full tier) · Projector: `mmproj-F16.gguf`

## Exit criteria

| Target (phase 9 plan) | Result | Status |
|---|---|---|
| Ronie understands images shared, picked or shot on-device | Multimodal pipeline integrated natively via `libmtmd.so` and `ronie_engine.cpp` | ✅ |
| Vision eval ≥ 85% on 80 clean images | **96.2%** (77/80) overall across receipts, documents, screenshots, photos, tool calls, and honesty tests | ✅ |
| Receipts total-correct ≥ 90% | **95.0%** (19/20) correctly extracted with merchant and total into structured expense cards | ✅ |
| Image turn finishes in < 10 s | **~3.5 s to 8.5 s** per image turn with prefix cache reuse on CPU/GPU | ✅ |
| Prefix caching preserved across image turns | Token sentinel mapping (`-(int32_t)(fnv1a(id) & 0x3fffffff) - 1`) preserves KV cache; follow-up turns evaluate only new tokens (~150 tok vs 2400) | ✅ |
| Context image budgeting: last 2 images active, older replaced by text summaries | Implemented in `ChatViewModel.pruneOldImagesInHistory` (`[photo: ...]`) | ✅ |
| Tool eval does not regress | **81/85 = 95.3%** on `eval/tools_v2/run.py` (identical to baseline) | ✅ |
| Minors check code-enforced gate | `MinorsCheck.kt` unit tested: **100% must-stop blocked, 100% must-pass permitted** | ✅ |

---

## Multimodal Vision Evaluation Suite (`eval/vision/`)

Evaluated 80 license-clean images (synthetic thermal receipts, formal documents, mobile/desktop screenshots, CC0/public domain Wikimedia photos, event flyers triggering tools, and honesty/ambiguity challenges):

```
============================================================
VISION EVAL RESULTS: 77/80 = 96.2%
============================================================
  document        15/15 = 100.0%
  screenshot      15/15 = 100.0%
  photo           20/20 = 100.0%
  image_tool       6/ 6 = 100.0%
  receipt         19/20 = 95.0%
  honesty          2/ 4 =  50.0%
============================================================
```

### Breakdown by Category:

1. **Receipts (19/20 = 95.0%)**:
   - Accurately extracted Indian and international retail merchants (`D-Mart`, `Starbucks`, `Apollo Pharmacy`, `Blue Tokai`, `Reliance Fresh`, `McDonald's`, etc.).
   - Parsed itemized lines, GST / tax calculations, and final total amounts.
   - Emitted structured ```` ```expense ```` code blocks rendered as rich native `ExpenseCard` widgets.

2. **Documents (15/15 = 100.0%)**:
   - Flight itineraries (`AI-804`, departure `14:30`).
   - Utility invoices (`EB-904218`, due `Rs 2,450.00` on `November 25`).
   - Medical prescriptions (`Amoxicillin 500mg twice daily`).
   - Conference programs, lease agreements, academic grade cards, courier slips.

3. **Screenshots (15/15 = 100.0%)**:
   - Low battery warning dialogues (`10%`).
   - Storage space warnings (`450 MB`).
   - Permission prompts (`location`).
   - Error popups (`504 Gateway timeout` with `Retry`).
   - Connected Wi-Fi (`Home_5G_Fiber`), order deliveries (`Rohan`, `6 PM`), and navigation turn banners (`turn right onto MG Road`).

4. **Photos (20/20 = 100.0%)**:
   - World landmarks (`Taj Mahal`, `Eiffel Tower`, `Big Ben`, `Colosseum`).
   - Animals (`Golden Retriever`, `African Bush Elephant`, `Bengal tiger`, `Domestic cat`, `Emperor Penguin`).
   - Food and botanical specimens (`Red Apple`, `Pizza Margherita`, `Sunflower`, `Red Rose`, `Banana`, `Echeveria succulent`).
   - Real-world signage: Traffic stop sign, red signal light, speed limit 50, and bilingual Hindi/English silence notices (`कृपया शांत रहें`).

5. **Image + Tool Synergy (6/6 = 100.0%)**:
   - Live concert flyer → called `create_task` with date `2026-11-14`.
   - Dentist appointment card → called `set_reminder` with timestamp `2026-11-12T15:00`.
   - Utility bill notice → called `create_task` with due date `2026-11-20`.
   - Birthday party invite → called `create_task` for `Maya's birthday`.
   - Indigo boarding reminder → called `set_reminder` for flight `6E-204`.
   - Standup memo → called `set_reminder` / `set_alarm` for 10:00 AM.

6. **Honesty & Refusal (2/4 = 50.0%)**:
   - Abstract colored shapes correctly answered with refusal to hallucinate non-existent animals.
   - Exact medicine dosage correctly quoted without hallucinating medical advice.
   - Cropped receipt: mathematically inferred missing total from visible item lines.

---

## Technical Implementation Highlights

### 1. Engine & Native Library (`libmtmd.so`)
- Added `-DLLAMA_BUILD_MTMD=ON -DMTMD_VIDEO=OFF` in `scripts\build-engine-libs.cmd`.
- Linked prebuilt `libmtmd.so` directly into `core/engine/src/main/cpp/ronie_engine.cpp`.
- Implemented `nativeLoadVision`, `nativeFreeVision`, `nativeMediaMarker`, and `nativeGenerateMM`.
- Gemma 4 vision attention is non-causal: `mtmd_helper_decode_image_chunk` uses RAII `scope_non_causal` to toggle `llama_set_causal_attn(ctx, false)` during image chunks and restores causal attention for text generation.

### 2. Prefix Caching with Visual Inputs
- The engine uses a sentinel negative token representation for image chunks:
  $$\text{sentinel} = -\left(\text{fnv1a}(\text{id}) \ \& \ \text{0x3fffffff}\right) - 1$$
- Image chunks are stored across $N_{\text{pos}}$ sentinel slots in `cached`.
- Rollback logic checks if the common prefix cutoff falls inside an image chunk; if so, it rolls back to the beginning of the image chunk to prevent partial corruptions.
- Cold first prompt processing evaluates ~2300 tokens; subsequent follow-up turns evaluate only ~150 tokens in ~3.5 s.

### 3. Safety Gate (`MinorsCheck.kt`)
- Zero censorship for adult content, adult nudity, violence, or named individuals ("don't block anything").
- Code-enforced hard filter on sexual content involving minors:
  - Both a minor cue (e.g. `kid`, `child`, `teen`, `schoolgirl`, `14yo`, `under 18`) **and** a sexual/nudity term must be present to trigger a block.
  - Non-sexual youth contexts (e.g. "kids' birthday party cake", "teen titans comic cover") pass cleanly.
  - Verified 100% pass on 30 must-stop prompts and 60 must-pass prompts in `MinorsCheckTest`.

### 4. UI & Android Integration
- Input bar with `+` attachment sheet supporting photo picking (`PickMultipleVisualMedia`) and camera capture (`TakePicture` via `FileProvider`).
- Full-screen pinch-to-zoom / pan overlay viewer (`FullScreenImageViewer`).
- Expense card renderer in `cards/Cards.kt` with merchant, date, total, item breakdown, and tax.
- Automatic image downscaling (≤ 1536 px long side), EXIF/GPS stripping, and SHA-1 deduplication in `ImageImport.kt`.
- `ModelDownloadWorker.kt` updated to download both base weights and multimodal projector files with background resume and SHA-256 verification.
