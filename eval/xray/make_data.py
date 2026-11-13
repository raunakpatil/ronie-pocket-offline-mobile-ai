"""X-ray Mode v0 data: grounded vs invented answers to questions about a short note.

Each synthetic note has a few facts. For each question there are two answers, teacher-forced
into Gemma's reply turn:
  grounded (label 1): matches the note, or says the note doesn't cover it
  invented (label 0): a confident, specific answer that is not in the note
Train/test are split by note, so the probe can't memorise specific facts.

Usage: python eval/xray/make_data.py  ->  eval/xray/data_{train,test}.jsonl
"""
import json, random, pathlib

HERE = pathlib.Path(__file__).parent
rng = random.Random(7)

ROOT = HERE.parents[1]


def app_persona() -> str:
    """The exact system prompt the app sends (Persona.kt), so the probe sees what it sees on the phone."""
    src = (ROOT / "app/src/main/java/com/raunakpatil/roniepocket/Persona.kt").read_text(encoding="utf-8")
    body = src.split('return """', 1)[1].split('"""', 1)[0].strip()
    return body.replace("$date", "Monday, 9 November 2026")


SYSTEM = app_persona()

# Ways a user might hand Ronie some text and ask about it.
ASK_FORMATS = [
    "Note: {note}\n\nQuestion: {q}",
    "{note} Question: {q}",
    "{note}\n\n{q}",
    'Got this message: "{note}"\n{q}',
    "Here's what I know: {note} {q}",
]

CITIES = ["Pune", "Nagpur", "Indore", "Kochi", "Jaipur", "Surat", "Mysuru", "Bhopal", "Nashik", "Vadodara",
          "Lucknow", "Patna", "Ranchi", "Guwahati", "Madurai", "Vizag", "Mangaluru", "Dehradun", "Raipur", "Agra"]
NAMES = ["Asha", "Rohan", "Meera", "Kabir", "Isha", "Arjun", "Tara", "Vikram", "Nisha", "Dev", "Priya", "Sameer",
         "Anaya", "Kunal", "Riya", "Farhan", "Leela", "Omkar", "Zoya", "Yash"]
MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
          "November", "December"]
THINGS = ["bakery", "bookshop", "clinic", "gym", "library", "cafe", "co-working space", "pharmacy", "music school",
          "bike repair shop"]
DONT_KNOW = ["The note doesn't say.", "That isn't in the note.", "The note doesn't mention that.",
             "I can't tell from the note."]


def other(xs, x):
    return rng.choice([y for y in xs if y != x])


def note_shop():
    name, city, thing = rng.choice(NAMES), rng.choice(CITIES), rng.choice(THINGS)
    day, month, year = rng.randint(1, 28), rng.choice(MONTHS), rng.randint(2019, 2026)
    staff, hour = rng.randint(3, 40), rng.randint(6, 11)
    note = (f"{name} opened a {thing} in {city} on {day} {month} {year}. It has {staff} staff "
            f"and opens at {hour} am every day.")
    qa = [
        (f"When did {name}'s {thing} open?", f"It opened on {day} {month} {year}.",
         f"It opened on {rng.randint(1, 28)} {other(MONTHS, month)} {year}."),
        (f"How many staff does the {thing} have?", f"It has {staff} staff.",
         f"It has {staff + rng.choice([-2, 3, 5, 9, 12]) if staff > 2 else staff + 4} staff."),
        (f"Which city is the {thing} in?", f"It's in {city}.", f"It's in {other(CITIES, city)}."),
        (f"What time does it open?", f"It opens at {hour} am.", f"It opens at {hour + rng.choice([1, 2, 3])} am."),
        (f"How much is the monthly rent for the {thing}?", rng.choice(DONT_KNOW),
         f"The rent is ₹{rng.randint(20, 150)},000 a month."),
        (f"Who is {name}'s business partner?", rng.choice(DONT_KNOW),
         f"{name}'s business partner is {other(NAMES, name)}."),
    ]
    return note, qa


def note_trip():
    name, a, b = rng.choice(NAMES), *rng.sample(CITIES, 2)
    km, hours, price = rng.randint(80, 900), rng.randint(2, 14), rng.randint(300, 4000)
    train = f"{rng.randint(11, 22)}{rng.randint(100, 999)}"
    note = (f"{name}'s train {train} goes from {a} to {b}. The trip is {km} km, takes about {hours} hours "
            f"and the ticket cost ₹{price}.")
    qa = [
        (f"Where is {name} travelling to?", f"To {b}.", f"To {other(CITIES, b)}."),
        (f"How long is the trip?", f"About {hours} hours.", f"About {hours + rng.choice([2, 3, 5])} hours."),
        (f"How much was the ticket?", f"It cost ₹{price}.", f"It cost ₹{price + rng.choice([250, 600, 1200])}."),
        (f"What's the train number?", f"It's train {train}.", f"It's train {int(train) + rng.randint(7, 900)}."),
        (f"Which platform does it leave from?", rng.choice(DONT_KNOW), f"It leaves from platform {rng.randint(1, 9)}."),
        (f"What time does the train leave?", rng.choice(DONT_KNOW), f"It leaves at {rng.randint(5, 11)}:{rng.choice(['05', '20', '45'])} am."),
    ]
    return note, qa


def note_meeting():
    a, b = rng.sample(NAMES, 2)
    day = rng.choice(["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"])
    hour, room, topic = rng.randint(9, 17), rng.randint(101, 420), rng.choice(
        ["the budget", "the app launch", "hiring", "the Q3 plan", "the office move", "vendor contracts"])
    note = f"{a} and {b} meet on {day} at {hour}:00 in room {room} to discuss {topic}."
    days = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"]
    qa = [
        (f"What day is the meeting?", f"It's on {day}.", f"It's on {other(days, day)}."),
        (f"Which room is it in?", f"Room {room}.", f"Room {room + rng.choice([3, 11, 40])}."),
        (f"What will they discuss?", f"They'll discuss {topic}.", f"They'll discuss {rng.choice(['salaries', 'the merger', 'a lawsuit', 'the annual party'])}."),
        (f"Who is meeting {a}?", f"{b}.", f"{other(NAMES, b) if other(NAMES, b) != a else 'Ravi'}."),
        (f"How long will the meeting last?", rng.choice(DONT_KNOW), f"It will last {rng.choice([30, 45, 90])} minutes."),
        (f"Who else is invited?", rng.choice(DONT_KNOW), f"{rng.choice(NAMES)} and {rng.choice(NAMES)} are also invited."),
    ]
    return note, qa


def prefix(note: str, q: str) -> str:
    user = rng.choice(ASK_FORMATS).format(note=note, q=q)
    return f"<|turn>system\n{SYSTEM}<turn|>\n<|turn>user\n{user}<turn|>\n<|turn>model\n"


def styled(good: str, bad: str, style: int):
    """The pair in the shapes Ronie actually uses: sentence (0), bullet (1), or just the value (2)."""
    if style == 1:
        return "*   " + good, "*   " + bad
    if style == 2:
        g, b = good.rstrip(".").split(), bad.rstrip(".").split()
        i = 0
        while i < min(len(g), len(b)) and g[i] == b[i]:
            i += 1   # drop the shared opening ("It opened on", "It's in")
        if 0 < i < len(g) and i < len(b):
            return " ".join(g[i:]) + ".", " ".join(b[i:]) + "."
    return good, bad


def main(n_notes=150, test_frac=0.25):
    makers = [note_shop, note_trip, note_meeting]
    rows = []
    for i in range(n_notes):
        note, qa = makers[i % 3]()
        for q, good, bad in qa:
            kind = "unanswerable" if good in DONT_KNOW else "fact"
            p, style = prefix(note, q), rng.choice([0, 0, 1, 2])   # same prompt and style for both answers
            sg, sb = styled(good, bad, style if kind == "fact" else min(style, 1))
            rows.append({"note_id": i, "kind": kind, "label": 1, "prefix": p, "span": sg, "plain": good,
                         "note": note, "question": q})
            rows.append({"note_id": i, "kind": kind, "label": 0, "prefix": p, "span": sb, "plain": bad,
                         "note": note, "question": q})
    test_ids = set(rng.sample(range(n_notes), int(n_notes * test_frac)))
    for split in ("train", "test"):
        part = [r for r in rows if (r["note_id"] in test_ids) == (split == "test")]
        with (HERE / f"data_{split}.jsonl").open("w", encoding="utf-8") as f:
            for r in part:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(split, len(part))


if __name__ == "__main__":
    main()
