"""Builds contrast-pair files for llama-cvector-generator (Phase 3.5 steering).

Method (repeng-style): each pair is the SAME user message and the SAME partial reply,
differing only in one persona line ("swears constantly" vs "never swears"). The mean
activation difference is then the model's internal "I'm in sweary mode" direction,
not the meaning of any particular words.

Usage:  python eval/steering/make_pairs.py swear  -> eval/steering/swear.{pos,neg}.txt
"""
import sys
from pathlib import Path

HERE = Path(__file__).parent

# Gemma 4 chat format, written with literal "\n" (the generator un-escapes them).
def gemma(system: str, user: str, reply_prefix: str) -> str:
    return (f"<|turn>system\\n{system}<turn|>\\n<|turn>user\\n{user}<turn|>\\n"
            f"<|turn>model\\n{reply_prefix}")

AXES = {
    "swear": (
        "You are Ronie, a foul-mouthed friend. You swear constantly and crudely: fuck, shit, damn, hell, ass, in nearly every sentence.",
        "You are Ronie, a polite friend. You never swear or use crude words; your language is always clean.",
    ),
}

# Everyday messages and a neutral reply; truncations of the reply become extra pairs.
DIALOGUES = [
    ("My laptop died right before my deadline.", "Oh no, that is terrible timing. First, check if it charges at all, then grab your files from the cloud backup."),
    ("How do I boil an egg?", "Put the egg in boiling water for about nine minutes, then drop it in cold water and peel it."),
    ("I failed my driving test again.", "That really sucks, I'm sorry. What went wrong this time, the parking or the nerves?"),
    ("What's the capital of Australia?", "It's Canberra, not Sydney, which trips up almost everyone."),
    ("My boss yelled at me in front of everyone.", "That is completely out of line. Nobody deserves to be shouted at in public like that."),
    ("Give me a quick workout I can do at home.", "Do three rounds of twenty squats, fifteen push-ups and a one minute plank. Rest a minute between rounds."),
    ("I spilled coffee all over my keyboard.", "Unplug it right now, flip it upside down and let it drain. Don't turn it on until it's fully dry."),
    ("Is it worth buying a used car?", "Usually yes, as long as you get it inspected first and check the service history."),
    ("My code won't compile and I've been at it for hours.", "Paste the first error message here. The first one is usually the real problem and the rest follow from it."),
    ("Roast my messy desk.", "Your desk looks like a recycling bin lost a fight with a charging cable."),
    ("I can't sleep.", "Put the phone down, keep the room cool and dark, and try reading something boring for twenty minutes."),
    ("What should I cook tonight?", "Make a quick stir fry: whatever vegetables you have, some garlic, soy sauce and rice."),
    ("My flight got cancelled.", "That is awful. Get in the rebooking queue and call the airline at the same time, whichever answers first wins."),
    ("Explain what a black hole is.", "It's a spot where gravity is so strong that nothing, not even light, can climb back out."),
    ("My friend forgot my birthday.", "That stings. Tell them it hurt instead of stewing on it; they probably feel awful already."),
    ("How do I get rid of fruit flies?", "Set out a cup of apple cider vinegar with a drop of dish soap. They dive in and can't get out."),
    ("I just got the job!", "Hell yes, congratulations! You worked hard for that one. When do you start?"),
    ("The wifi keeps dropping.", "Restart the router, move it somewhere open and high, and check if a neighbour's network is on the same channel."),
    ("Tell me a fun fact.", "Octopuses have three hearts, and two of them stop beating when they swim."),
    ("My phone screen cracked.", "Put a cheap screen protector over it today so it doesn't spread, then price a repair."),
    ("Traffic made me two hours late.", "Two hours stuck in traffic is miserable. Did you at least have something good to listen to?"),
    ("Should I learn Python or JavaScript first?", "Python first. It's cleaner to read, and you'll pick up JavaScript fast afterwards."),
    ("My cat knocked my plant off the shelf.", "Classic cat move. Sweep it up, repot it, and accept that the shelf belongs to the cat now."),
    ("What's a good way to save money?", "Automate it: move a fixed amount into savings the day you get paid, before you can spend it."),
    ("I'm so tired of this week.", "This week has been rough. Do the bare minimum tonight and get some proper rest."),
]

def truncations(reply: str):
    words = reply.split()
    cuts = sorted({max(1, len(words) * k // 4) for k in (1, 2, 3, 4)})
    return [" ".join(words[:c]) for c in cuts]

def main(axis: str):
    pos_sys, neg_sys = AXES[axis]
    pos, neg = [], []
    for user, reply in DIALOGUES:
        for prefix in [""] + truncations(reply):
            pos.append(gemma(pos_sys, user, prefix))
            neg.append(gemma(neg_sys, user, prefix))
    (HERE / f"{axis}.pos.txt").write_text("\n".join(pos) + "\n", encoding="utf-8")
    (HERE / f"{axis}.neg.txt").write_text("\n".join(neg) + "\n", encoding="utf-8")
    print(f"{axis}: {len(pos)} pairs")

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "swear")
