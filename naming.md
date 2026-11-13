# Ronie — Naming & Discoverability (SEO / ASO)

Checked 2026-11-08 with web searches only. **Before committing, also check:** Play Store + App Store search, domain availability, GitHub/Hugging Face org names, and a trademark search (USPTO / WIPO Global Brand Database / your national office).

---

## ✅ Decision context: Ronie is already a family (checked github.com/raunakpatil, 2026-11-08)

| Existing project | What it is | Ronie's role | Brain |
|---|---|---|---|
| `ronie-3d-ai-website-agent` | 3D AI guide for websites (three.js, WebGPU voice) | Persona "Ronie", live at raunakpatil.com/ronie | Cloud (Gemini) |
| `smart-glasses-ai-agent` (9★) | Hands-free agent for Ray-Ban Meta glasses | Wake word "Ronie" | Cloud (Gemini, Claude, ChatGPT, DeepSeek) |
| **This project** | Fully-local Android assistant | Persona + wake word "Ronie" | **On-device** |

All three are assistants with the same persona → **make Ronie the umbrella brand** and give each product a suffix. One assistant everywhere — website, glasses, phone — is a stronger story than three unrelated names.

### Recommended family names
| Product | Name | Repo |
|---|---|---|
| This app | **Ronie Pocket** ⭐ | `ronie-pocket` |
| Website agent | Ronie Web | (current repo name already works) |
| Glasses agent | Ronie Glasses | (optional rename later — GitHub redirects old URLs) |
| Patch toolkit | `rpatch` | separate repo, its own name |

**Why "Pocket":** clearly "on your phone", unique as a phrase in search, and the store subtitle carries the "offline / private" keywords. "Ronie Local" is the runner-up: it names the one thing that sets this product apart from its cloud siblings, but it's less memorable.

**Family story for the pitch:** *Ronie Pocket can become the private local brain for Ronie Glasses* (moonshot B2): glasses capture, phone thinks, nothing goes to the cloud.

**Character (decided 2026-11-08):** one Ronie everywhere. He's the same robot, says "he", has the same awkward-witty personality and uses the same `am_puck` voice (Kokoro on the web, Paradee on the phone). Canonical spec: [persona-ronie.md](persona-ronie.md).

---

## What makes a findable name

1. **Unique in search** — searching "<name> AI" should return *you*, not 50 other things (Loci shares its name with several unrelated projects — PromptQuorum flagged it).
2. **Short & spellable** — 4–6 letters, one obvious spelling, easy to say as a wake word.
3. **Tells the story** — local, private, learns, improves itself.
4. **Keywords go in the subtitle, not the name** — app-store search ranks the *title + short description*. "Loci - Private, Local AI" works because of the words after the dash.

---

## Candidates (searched)

| Name | Meaning / story | Search conflicts found | Verdict |
|---|---|---|---|
| **Ronie** | The persona's own name — app = assistant, like Siri/Alexa | No AI app named "Ronie" found. Near-misses: "Ronnie GF" (viral companion app), "Ronin AI – Stoic Companion" | ⭐ **Recommended** — one name for app, persona and wake word. Own the exact spelling; buy/redirect "ronnie" variants if possible |
| **Nocti** | Latin *noct-* "night" → the AI that rewires itself overnight (Ronie Sleeps) | No AI app; only sleep apps NoctiBeat / NoctiSnore | ✅ Strong alternative if you want the moonshot in the name; risk of being filed with sleep trackers |
| **Myelin** | Brain insulation that grows as you learn → "gets faster and smarter with use" | Crowded with B2B: Myelin Foundry, Myelin.io, Myelin Healthcare | ⚠ Great meaning, weak SEO |
| **Engram** | A memory trace in the brain | Very crowded: Engram Artificial, Engram AI Memory, several open-source "Engram" memory layers | ❌ Avoid |
| **Oneiro** | Greek "dream" | Several dream-journal apps | ❌ Avoid |
| **Lumo** | Light | Taken by Proton's AI assistant | ❌ Avoid |
| Kora / Nyra | — | Nothing found in a limited search | ❓ Unverified; generic-sounding |

### Recommendation
**Keep "Ronie" as the brand** — persona, app and wake word are the same, which is the most memorable setup — and do the SEO work with the subtitle and keywords. Use **Nocti** only if you'd rather brand the self-improving story than the character.

Optional sub-brands for the moonshots (also good GitHub repo names):
- **Ronie Sleeps** — the nightly self-repair feature
- **rpatch** — the open patch format + toolkit (separate repo; check the name on PyPI/GitHub first)
- **Ronie X-ray** — the live interpretability view

---

## Store listing (Google Play)

Limits: title 30 chars · short description 80 chars · full description 4,000 chars.

**Title**
```
Ronie Pocket: Offline AI
```
(24 chars). Alternatives: `Ronie Pocket – Private AI` (25) · `Ronie Pocket: Local AI Chat` (27)

**Short description (≤ 80)**
```
Private AI that reads your email, remembers you and works offline. No cloud.
```

**Full description — first lines (shown before "more"):**
```
Ronie is a private AI assistant that runs entirely on your phone.
It checks your email and tells you what's important, remembers what matters
to you, talks with you, and gets better every night — without sending your
chats to any cloud.
```
Then sections: What Ronie does · Works offline · Your data stays on your phone (with the privacy receipt) · Gets smarter every night · Voices · Connectors (MCP).

**Keywords to work in naturally** (Play has no keyword field — they must appear in title/descriptions):
offline AI · private AI assistant · local AI · on-device AI · AI without internet · personal assistant · email assistant · AI that remembers · voice assistant · ChatGPT alternative (use carefully — no trademark misuse) · MCP

**Screenshots (first 3 matter most):**
1. "📬 Your important emails, found for you — offline"
2. "🌙 Gets better every night" (morning report chart)
3. "🔬 Shows you when it's unsure" (X-ray honesty colours)
4. "🔒 0 bytes of your chats left this phone" (privacy receipt)

---

## GitHub discoverability

- Repo name: `ronie-pocket`. Description: *"Fully offline Android AI assistant that edits its own weights overnight. Qwen3.5 · llama.cpp · weight-space programming · MCP."*
- Topics: `on-device-ai`, `local-llm`, `android`, `llama-cpp`, `qwen`, `mechanistic-interpretability`, `activation-steering`, `weight-editing`, `mcp`, `privacy`, `offline-first`.
- Separate repo for **rpatch** (spec + Python toolkit) — easier to star and reuse outside the app.
- README hero: the 60-second demo GIF + the night-by-night improvement chart.

---

## Checklist before locking the name
- [ ] Play Store + App Store search for "Ronie Pocket" (and "Ronie AI")
- [ ] Domain: ronie.ai / getronie.com / ronie.app
- [ ] GitHub org/user, Hugging Face org, PyPI name for `rpatch`
- [ ] Trademark search in software/AI classes (Nice class 9 and 42)
- [ ] Say "Hey Ronie" to the wake-word model — check it isn't confused with common words
