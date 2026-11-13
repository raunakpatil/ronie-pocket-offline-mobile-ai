"""Memory retrieval eval (Phase 6): can an embedding model find the right remembered fact for a message?

Synthetic user "Arjun" (not the real user). FACTS are what Ronie would have stored; QUERIES are things the
user later says, each with the fact ids that should come back. Most are indirect on purpose (no shared
keywords), because that's where keyword search fails and embeddings earn their keep.
"""

FACTS = [
    "Arjun's sister is called Priya",                       # 0
    "Priya is vegetarian",                                   # 1
    "Arjun works as an AI engineer at a startup",            # 2
    "Arjun lives in Pune",                                   # 3
    "Arjun's birthday is on 3 March",                        # 4
    "Arjun is allergic to peanuts",                          # 5
    "Arjun prefers short answers",                           # 6
    "Arjun's dog is a beagle called Bruno",                  # 7
    "Arjun plays football every Sunday morning",             # 8
    "Arjun's manager is called Meera",                       # 9
    "Arjun drives a white Honda City",                       # 10
    "Arjun is learning to play the guitar",                  # 11
    "Arjun's mum lives in Nagpur",                           # 12
    "Arjun has a dentist appointment on the 20th",           # 13
    "Arjun wants to run a half marathon in January",         # 14
    "Arjun doesn't drink alcohol",                           # 15
    "Arjun's favourite cuisine is Thai",                     # 16
    "Arjun is saving up for a trip to Japan",                # 17
    "Arjun's best friend is Kabir",                          # 18
    "Kabir is getting married in December",                  # 19
    "Arjun's laptop is a 14-inch MacBook Pro",               # 20
    "Arjun has trouble sleeping when he drinks coffee after 4pm",  # 21
    "Arjun's rent is due on the 5th of every month",         # 22
    "Arjun's flat has a balcony with tomato plants",         # 23
    "Arjun speaks Marathi, Hindi and English",               # 24
    "Arjun studied computer science in Liverpool",           # 25
    "Arjun hates cold showers",                              # 26
    "Arjun's girlfriend is called Sana",                     # 27
    "Sana works as a doctor at Ruby Hall Clinic",            # 28
    "Arjun's anniversary with Sana is on 14 August",         # 29
    "Arjun takes vitamin D tablets every morning",           # 30
    "Arjun's phone is a Pixel 9 Pro",                        # 31
    "Arjun is building a local AI assistant app",            # 32
    "Arjun gets motion sick on long bus rides",              # 33
    "Arjun's favourite band is Coldplay",                    # 34
    "Arjun wants to cut down on sugar",                      # 35
    "Arjun's dad is a retired schoolteacher",                # 36
    "Arjun is scared of heights",                            # 37
    "Arjun goes to the gym on Monday, Wednesday and Friday", # 38
    "Arjun's bank is HDFC",                                  # 39
    "Arjun likes his tea with cardamom and no sugar",        # 40
    "Arjun's car insurance renews in February",              # 41
    "Arjun's niece Anaya turns five next month",             # 42
    "Arjun prefers working from cafes on Fridays",           # 43
    "Arjun once broke his left wrist playing cricket",       # 44
    "Arjun's go-to comfort food is his mum's dal khichdi",   # 45
    "Arjun is reading Dune",                                 # 46
    "Arjun wants Ronie to swear when they banter",           # 47
    "Arjun's work laptop must not be used for personal stuff",  # 48
    "Arjun's flatmate is called Rohan",                      # 49
    "Rohan is always late paying the electricity bill",      # 50
    "Arjun's favourite shop for electronics is Croma",       # 51
    "Arjun is lactose intolerant",                           # 52
    "Arjun's passport expires in June",                      # 53
    "Arjun meditates for ten minutes before bed",            # 54
    "Arjun's team ships a release every second Thursday",    # 55
    "Arjun prefers Android over iPhone",                     # 56
    "Arjun's grandmother lives with his parents",            # 57
    "Arjun wants to learn Japanese before his trip",         # 58
    "Arjun's favourite football club is Liverpool",          # 59
]

QUERIES = [
    ("What should I cook when my sister comes over?", [1, 0]),
    ("Plan a dinner for Priya", [1]),
    ("Can I eat this satay sauce?", [5]),
    ("Suggest a snack for me", [5, 52, 35]),
    ("Is it ok to have a latte at 6pm?", [21, 52]),
    ("What should I get Kabir?", [19, 18]),
    ("Remind me what's happening in December", [19]),
    ("Gift ideas for my girlfriend", [27, 29]),
    ("When is our anniversary?", [29]),
    ("Who's my manager again?", [9]),
    ("I need to email my boss", [9]),
    ("Book a vet for my dog", [7]),
    ("What breed is my dog?", [7]),
    ("Plan my Sunday", [8]),
    ("Am I free on Sunday morning?", [8]),
    ("Help me train for my race", [14]),
    ("Make me a running plan", [14]),
    ("Where should I go out for dinner tonight?", [16, 15]),
    ("Order drinks for the party", [15]),
    ("How much should I save each month for my holiday?", [17]),
    ("Teach me some useful phrases for my trip", [58, 17]),
    ("Which city do I live in?", [3]),
    ("What's the weather like where I live?", [3]),
    ("When's my birthday?", [4]),
    ("How old am I turning this year?", [4]),
    ("Keep it brief please", [6]),
    ("Why can't I fall asleep?", [21, 54]),
    ("My mum called, when should I visit her?", [12]),
    ("How long is the drive to my mum's?", [12, 10]),
    ("What car do I have?", [10]),
    ("My car needs a service", [10, 41]),
    ("Any guitar practice tips?", [11]),
    ("When is the rent due?", [22]),
    ("Pay the rent reminder", [22]),
    ("My plants look sad", [23]),
    ("Which languages can I speak?", [24]),
    ("Where did I go to university?", [25]),
    ("Who is Sana?", [27, 28]),
    ("What does my girlfriend do for work?", [28]),
    ("What supplements do I take?", [30]),
    ("My phone keeps overheating", [31]),
    ("What am I working on these days?", [32, 2]),
    ("Plan a bus trip to Goa", [33]),
    ("Concert tickets I'd like", [34]),
    ("I want dessert", [35, 52]),
    ("What did my dad do for a living?", [36]),
    ("Should I try skydiving?", [37]),
    ("Which days do I work out?", [38]),
    ("How do I make my chai?", [40]),
    ("My niece's birthday party", [42]),
    ("Where should I work from on Friday?", [43]),
    ("My wrist hurts after cricket", [44]),
    ("I'm feeling low, what should I eat?", [45]),
    ("What book am I reading?", [46]),
    ("Talk to me like a mate, swear if you want", [47]),
    ("Can I install games on my work laptop?", [48]),
    ("Who do I live with?", [49]),
    ("The electricity bill is overdue again", [50]),
    ("Where should I buy new headphones?", [51]),
    ("Can I have a milkshake?", [52]),
    ("Do I need to renew any documents before travelling?", [53]),
    ("When does my team release?", [55]),
    ("Should I switch to an iPhone?", [56]),
    ("Who lives with my parents?", [57]),
    ("Did Liverpool win last night?", [59]),
    ("What's my job?", [2]),
    ("When is my dentist visit?", [13]),
    ("What computer do I use?", [20]),
    ("I really don't like cold water in the morning", [26]),
    ("Who is my closest friend?", [18]),
]

# Hinglish / Hindi-English mixed messages (the user is in India; people mix languages).
QUERIES_MIXED = [
    ("Priya ke liye dinner mein kya banau?", [1, 0]),
    ("mera birthday kab hai yaar?", [4]),
    ("Sunday ko kya plan hai?", [8]),
    ("Kabir ki shaadi kab hai?", [19]),
    ("meri chai kaise banani hai?", [40]),
    ("gaadi ka insurance kab renew hoga?", [41]),
    ("mummy ke ghar kab jaana chahiye?", [12]),
    ("rent kab bharna hai?", [22]),
    ("Bruno ko vet le jaana hai", [7]),
    ("mujhe neend kyun nahi aa rahi?", [21, 54]),
]
