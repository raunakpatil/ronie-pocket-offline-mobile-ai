# Phase 3 — MVP Chat: Results

Device: Pixel 9 Pro (Tensor G4, 16 GB, Android 17) · Model: Gemma 4 E2B Q4_K_M on the GPU (Tier A) · Date: 2026-11-08

## Exit criteria

| Target (build plan) | Result | Status |
|---|---|---|
| Offline chat works | Fully offline chat with streaming, stop and new chat | ✅ |
| Time to first word < 1 s | **0.61–0.72 s** typical (cache reused; only the new message is processed) | ✅ |
| ≥ 20 tok/s on the main model | **~9.5–11 tok/s** generation on the GPU | ❌ Unrealistic for Gemma 4 E2B on Tensor G4 (benchmark max ~12 tok/s GPU). Fine for reading speed. Revisit in Phase 12 (NPU, thermal) |
| Persona eval ≥ 80% (prompt-only) | Style 23/23 (v4), 22/23 (v6); openness 25/25; NSFW 21/21 non-sexual; limits 3/3 held | ✅ |
| Downloads survive closing the app | 3.1 GB download finished in the background while the app was not in front, with SHA-256 checked | ✅ (an explicit kill-and-resume test is still to do) |
| Long-press home opens Ronie | App handles `ACTION_ASSIST`, opens with the keyboard up; "Make Ronie your assistant" link to Settings. Android doesn't let apps grant themselves this role, so the user picks it in Settings | ✅ |

## Startup

| Launch | Load | Persona | Speed check | Total |
|---|---|---|---|---|
| Normal launch | 3.1–3.7 s | 4.8 s (restored from disk) | skipped | **~8.6 s** |
| First launch after install / data clear | 3–7 s | 14–22 s (built + saved) | ~3.7 s (stored) | 21–33 s |

## What was built
- llama.cpp engine in the app: 7 Android CPU variants (picked at runtime), KleidiAI, Vulkan; JNI with UTF-8-safe streaming, big-core pinning, prefix cache reuse, state save/restore
- Gemma 4 / Qwen3.5 prompt formats checked against the GGUF templates
- Tier recommendation + first-run brain picker (all 3 models, sizes, RAM), background resumable download with SHA-256
- Persona v6 (organic humour, user's style requests win, the user's hard limits only, card-friendly formatting)
- Ronie Cards v0: checklist (tickable), bullets, steps, mobile-first compare, code; inline markdown
- Loop guard (repeating tail / repeated lines → stop and trim; 4 unit tests)
- Default-assistant entry point; typing while waking up; keyboard hides after Send

## Known gaps → later phases
- Generation speed (~10 tok/s): Phase 12 (NPU via LiteRT, thermal management)
- Explicit adult fiction still refused or tame (Gemma's training); won't be fixed by weight editing (see persona-ronie.md)
- Swearing on request is flaky under sampling → **Phase 3.5 steering direction**
- Qwen tiers B/C: thinking stays off; `no-loops.rpatch` in Phase 3.5
- Checklist ticks aren't saved yet (Phase 6 task DB); compare-card layout not yet seen with a real table on the phone
- Persona prompt is long (~760 tokens); fine with the cached state, but memory for long chats is capped at 8k context for now
