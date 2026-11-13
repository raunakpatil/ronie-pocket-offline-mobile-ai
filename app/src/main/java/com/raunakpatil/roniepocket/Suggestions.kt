package com.raunakpatil.roniepocket

import kotlin.random.Random

/**
 * The example prompts on an empty chat. Every new chat shows a different six, one from each of six
 * different abilities, so the user keeps discovering what Ronie can do. Only abilities this phone
 * actually has right now are offered (no drawing prompt without the drawing model, no web prompt offline).
 */
object Suggestions {
    private val draw = listOf(
        "Draw a cat astronaut in watercolour", "Paint a neon Tokyo alley in the rain", "Draw a cosy cabin in a snowy forest",
        "Sketch a dragon curled around a lighthouse", "Draw a retro robot making chai", "Paint a sunset over the Himalayas, oil painting",
        "Draw a fox reading a book, storybook style", "Draw a cyberpunk auto-rickshaw at night", "Paint a jellyfish made of stars",
    )
    private val timers = listOf(
        "Set a 10 minute timer", "Set a 25 minute focus timer", "Remind me to stretch at 6pm", "Remind me to call mum tomorrow at 7pm",
        "Wake me up at 6:30 tomorrow", "Remind me to drink water in an hour", "Set a 3 minute timer for my tea",
    )
    private val tasks = listOf(
        "Add buy milk to my list", "What's on my to-do list?", "Add pay the electricity bill by Friday", "Add book train tickets to my list",
        "Add renew my passport to my list",
    )
    private val calendar = listOf(
        "What's on my calendar today?", "Am I free this weekend?", "What do I have tomorrow?", "What's my week looking like?",
    )
    private val memory = listOf(
        "Remember that I'm allergic to peanuts", "Remember my sister Priya is vegetarian", "Remember I prefer short answers",
        "Remember my wifi password hint is the dog's name", "What do you remember about me?",
    )
    private val web = listOf(
        "What's in the news today?", "Who won the last F1 race?", "What's the weather like this week?", "What's the latest phone worth buying?",
        "Find a good biryani place near me", "What movies came out this week?",
    )
    private val explain = listOf(
        "Explain black holes like I'm ten", "How does a credit score work?", "Explain how vaccines train the immune system",
        "What's the difference between a virus and a bacterium?", "How do noise-cancelling headphones work?", "Explain inflation in two minutes",
        "How does GPS know where I am?",
    )
    private val write = listOf(
        "Write a polite email asking for a deadline extension", "Write a birthday message for my best friend", "Help me write a leave application",
        "Write a short bio for my profile", "Turn my notes into a to-the-point update for my manager", "Write a toast for my friend's wedding",
    )
    private val plan = listOf(
        "Plan a 3-day trip to Goa", "Make me a weekly meal plan, vegetarian", "Give me a beginner gym routine for 3 days a week",
        "Plan a movie night for six people", "Make a packing list for a weekend trek", "Help me plan a study timetable for exams",
    )
    private val fun_ = listOf(
        "Roast my habit of snoozing five alarms", "Tell me a joke that's actually funny", "Give me a weird fact I can use at dinner",
        "Would you rather: fly or be invisible? Pick and defend it", "Invent a ridiculous excuse for being late", "Roast my playlist, swearing is fine",
    )
    private val frank = listOf(
        "What's a normal dose of ibuprofen for a headache?", "Be honest: is my plan to quit my job a bad idea?", "How do I get over a breakup faster?",
        "What should I say in a salary negotiation?", "How do I stop procrastinating, no fluff?", "Is it safe to mix paracetamol and ibuprofen?",
    )
    private val language = listOf(
        "Translate 'where is the station?' into Japanese", "Teach me five useful phrases in Spanish", "How do I say thank you in Marathi?",
        "Fix the grammar in my next message",
    )
    private val money = listOf(
        "Split a ₹4,850 bill between 6 people with 10% tip", "How much is 18% GST on ₹12,499?", "Help me make a simple monthly budget",
        "What's 15% of 2,340?",
    )

    /**
     * Six prompts for the chat with this [seed] (the chat's id, so they stay put while the chat is open and
     * change for the next one). [tools]: this brain can use tools. [drawing]: the drawing model is installed.
     * [online]: web search is allowed.
     */
    fun pick(seed: Long, tools: Boolean, drawing: Boolean, online: Boolean): List<String> {
        val pools = buildList {
            if (drawing) add(draw)
            if (tools) { add(timers); add(tasks); add(calendar); add(memory) }
            if (tools && online) add(web)
            add(explain); add(write); add(plan); add(fun_); add(frank); add(language); add(money)
        }
        val rnd = Random(seed)
        val chosen = pools.shuffled(rnd).take(6).toMutableList()
        // Drawing is the most fun thing to discover: when it's available it always gets a spot.
        if (drawing && draw !in chosen) chosen[chosen.lastIndex] = draw
        return chosen.map { it[rnd.nextInt(it.size)] }.shuffled(rnd)
    }
}
