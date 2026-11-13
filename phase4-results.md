# Phase 4 — Voice + settings drawer: Results

Device: Pixel 9 Pro · Date: 2026-11-09

## Exit criteria

| Target (build plan) | Result | Status |
|---|---|---|
| Full spoken round trip | Speak → on-device recognizer → Ronie → Paradee speech. Each half tested on the phone; a real spoken command still needs a human voice (the recognizer started, heard background sound, and timed out cleanly) | ✅ (voice-in pending a human try) |
| First audio < 1.5 s after the reply starts | **1.05 s** (first clause spoken early) | ✅ |
| Ronie sounds like Ronie Web | Same `am_puck` voice (Paradee is distilled from Kokoro). A/B listening against Ronie Web not done yet | ⏳ |
| Every setting reachable from the drawer | Tone, X-ray (Labs), spoken replies, speed, brain info, image model (placeholder), privacy, about | ✅ |

## Voice out (TTS)

- **Paradee-8M v1.2 int8** (Apache-2.0, 8.1 MB) in ONNX Runtime 1.22, voice `am_puck`; loads in **0.8 s** in the background after Ronie wakes.
- Speed on the phone: **17–28× faster than real time** (148–369 ms of compute per 2.3–9.2 s of speech), so audio never waits for synthesis.
- Streaming: reply text → sentences (the first chunk can be a clause, ≥ 4 words) → phonemes → audio, synthesising up to two sentences ahead of playback.
- **Pronunciation**: Kotlin port of **misaki**'s English G2P (Apache-2.0): 89k gold + 93k silver US dictionary words with part-of-speech variants, stress rules, `-s/-ed/-ing` stemming, context for "the/to/a", numbers, years, ordinals, money (incl. ₹ rupees), times, percents, joined words ("OneDrive"), slashes; strips markdown, emoji and links. Unknown names fall back to spelling rules; Ronie's own words live in `assets/voice/ronie_words.tsv`.
  - No espeak-ng (GPL-3) anywhere, so the licence blocker in the plan is gone.
  - Without a part-of-speech tagger, heteronyms ("read", "live") use the dictionary default.
- 8 pronunciation unit tests (`scripts\test.cmd`).

## Voice in (STT) — changed from the plan

- **Android's on-device speech recognizer** (`createOnDeviceSpeechRecognizer`, Android 12+) instead of whisper.cpp for v0. Reason: whisper.cpp brings a second copy of ggml next to llama.cpp's (symbol clashes) plus a 75 MB model download; the on-device recognizer runs locally with nothing to download.
- Only used when the phone confirms on-device recognition; otherwise Ronie says it's unavailable rather than falling back to a cloud recognizer.
- whisper.cpp stays the fallback for phones without it (already proven in Phase 1: tiny.en 11 s clip in 3.7 s).

## Barge-in

Sending a message, tapping 🎤, Stop, or 🔇 stops speech immediately (verified: the player is released mid-sentence).

## Fixed during testing
- Back closed the whole app instead of the drawer → `BackHandler` closes the drawer first.
- Android's debug "not 16 KB-compatible" dialog: ONNX Runtime 1.20's JNI library wasn't 16 KB-aligned → bumped to 1.22.0 (needed for Play Store builds anyway).
- First test pronunciation bugs: "OneDrive", "upload/submission".

## Not done / next
- Visor avatar (Ronie Web's expressions) — not started.
- Voice-activity auto-stop is the recognizer's own end-of-speech detection; no custom VAD.
- Word highlighting from timestamps, wake word "Ronie" — later.
- Hands-free conversation mode (auto-listen after Ronie finishes) — easy next step.
