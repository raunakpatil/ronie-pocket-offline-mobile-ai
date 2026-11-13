# Ronie Pocket — UI Direction

Inspiration: OpenAI, *"GPT-6 and Intelligent UI for everyone"* (7 Oct 2026, https://openai.com/index/gpt-6-for-everyone/), read on 2026-11-08.

## What they shipped (summary)

- **Intelligent UI:** the model composes each answer from text, visuals and interactive elements (graphics, tappable buttons, forms, charts, maps, small tools such as calculators, bill splitters and games), and picks the format per question. Plain text is used when that's best.
- **A component library plus a streaming compiler:** a fixed set of native, *streamable* components gives a consistent design, and the model decides how to arrange them. The interface appears **progressively while the model is still generating**.
- **Answering while thinking:** the reply starts before reasoning finishes and is built from several partial responses, so the user isn't left waiting on a "thinking…" spinner.
- **Visual style on the page:** dark, quiet canvas; demos rendered as rich cards inside the chat (e.g. a clipboard-styled bill splitter with numbered sections, form fields and a serif headline). Each response looks *designed* for its task.

## What Ronie Pocket takes from it

A 0.8B–2B model can't design free-form UI the way GPT-6 does. It **can** fill a small, strict component schema when decoding is grammar-constrained (llama.cpp supports JSON-schema/GBNF constrained output, so the JSON is always valid). So:

### 1. "Ronie Cards": a fixed native component library (Jetpack Compose)
| Component | Used for |
|---|---|
| `text` | Default. Most replies stay plain text, which is fastest and best for chat |
| `checklist` | Tasks, packing lists, steps. Items are tappable and ticking one updates the task DB |
| `timeline` | Day plans, the daily brief, trip itineraries, meeting schedule |
| `email_card` | Important-mail alerts: sender, why it matters, buttons [Summarise] [Draft reply] [Remind me] |
| `action_buttons` | 1–3 suggested next steps; each maps to a tool call and goes through the confirm gate |
| `form` | Quick inputs, e.g. "set reminder" with prefilled time; bill-splitter-style mini tools |
| `table` / `compare` | Side-by-side comparisons (tea vs coffee, phone A vs B) |
| `stat` / `chart` | Numbers: spend this week, battery used, model speed |
| `source_list` | Citations for web and Wikipedia answers, with the claim-check ⚠ markers |
| `memory_chip` | "Noted: you're vegetarian ✕" (undo inline) |
| `calculator` | A few prebuilt mini tools (split bill, EMI, unit convert) the model fills with numbers |

The model emits `{"blocks":[{"type":"timeline","items":[...]}, {"type":"text","md":"..."}]}`, constrained by a JSON schema. The app renders each block **as soon as it's complete in the stream**, which is the "progressive compiler" idea on a phone.

### 2. Rule: text first, cards when they clearly help
Mirror their principle: plain text for chat and questions; cards only for **plans, lists, comparisons, numbers, emails and actions**. A tiny router (or a rule set) decides whether to use the card schema. This keeps small-model reliability high.

### 3. Answer while thinking (small-model version)
- Stream the non-thinking quick answer immediately; if thinking mode is triggered, show a slim "Ronie's double-checking…" line under the answer and **replace or append** when the refined answer arrives.
- Never show a blank spinner. Show the visor face "thinking" animation plus partial blocks.

### 4. Visual style
- Dark-first canvas with Ronie's visor accent colour; cards with generous padding, numbered sections and a strong serif or display headline for "designed" cards (brief, plans), plain sans for chat.
- Ronie's **visor face** in the header reacts to the message (happy, thinking, unsure), tying into X-ray Mode.
- Motion: blocks fade/slide in as they stream; checklist ticks animate; no layout jumps.

## Build-plan impact
- **Phase 3 (MVP chat):** block renderer for `text`, `checklist`, `action_buttons`, `source_list`; streaming JSON parser; grammar-constrained decoding.
- **Phase 7 (mail/brief):** `email_card`, `timeline` daily brief.
- **Phase 2 bake-off:** add a small **"cards" eval**: can each model fill the block schema correctly (with constrained decoding) for ~20 prompts? This decides whether the 0.8B can do cards or only the 2B.
- **Pitch:** "Intelligent UI, but fully on-device" is a strong demo line next to the moonshots.
