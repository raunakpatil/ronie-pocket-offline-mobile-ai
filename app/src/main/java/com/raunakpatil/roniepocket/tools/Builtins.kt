package com.raunakpatil.roniepocket.tools

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.raunakpatil.roniepocket.imagegen.ImageGen
import com.raunakpatil.roniepocket.imagegen.MinorsCheck

/** What running a tool produced. */
sealed interface Outcome {
    /** [forModel] goes back to the model; [summary] is shown on the tool card; [undo] reverses it (local tools). */
    data class Done(val forModel: Any?, val summary: String,
                    /** For a timer: when it ends (epoch ms) and its length, so the card can count down. */
                    val timer: Pair<Long, Long>? = null,
                    /** For a web search: the numbered sources (shown as chips on the card). */
                    val sources: List<com.raunakpatil.roniepocket.web.Source> = emptyList(),
                    /** For find_images: pictures to show on the card. */
                    val pictures: List<com.raunakpatil.roniepocket.web.WebImage> = emptyList(),
                    /** For image generation: drawn image file. */
                    val image: File? = null,
                    val undo: (suspend () -> String)? = null) : Outcome
    /** Couldn't run; [message] goes to the model and the card. */
    data class Failed(val message: String) : Outcome
    /** Needs an Android permission first; the chat asks, then runs the tool again. */
    data class NeedsPermission(val permission: String, val why: String) : Outcome
}

/** Ronie's built-in, in-process tools (declared in assets/tools/builtin.json). */
class Builtins(private val ctx: Context, private val log: (String, String, String) -> Unit = { _, _, _ -> }) {
    private val web = com.raunakpatil.roniepocket.web.WebSearch(log)
    val specs: List<ToolSpec> = run {
        val json = JSONObject(ctx.assets.open("tools/builtin.json").bufferedReader().readText())
        val arr = json.getJSONArray("tools")
        (0 until arr.length()).map { ToolSpec.fromMcp(arr.getJSONObject(it)) }
    }
    val tasks = TaskStore(File(ctx.filesDir, "tools/tasks.json"))
    /** Facts Ronie remembers: encrypted database with embeddings (Phase 6). */
    val memory = com.raunakpatil.roniepocket.memory.MemoryStore(ctx)
    private val minorsCheck by lazy {
        runCatching { MinorsCheck.fromJson(ctx.assets.open("imagegen/minors.json")) }
            .getOrElse { MinorsCheck(emptyList(), emptyList(), emptyList()) }
    }
    private var drawer: ImageGen? = null

    /** True when [text] is sexual and involves a minor: the one thing Ronie never does, in chat or pictures. */
    fun minorsBlocked(text: String): Boolean = minorsCheck.check(text).blocked

    suspend fun run(
        name: String,
        a: Map<String, Any?>,
        onProgress: ((step: Int, total: Int, preview: android.graphics.Bitmap?) -> Unit)? = null,
        onStageProgress: ((progress: Float, stageText: String, preview: android.graphics.Bitmap?) -> Unit)? = null
    ): Outcome = when (name) {
        "create_task" -> {
            val title = a.str("title")
            val due = a["due"]?.toString()?.takeIf { it.isNotBlank() }
            val t = tasks.add(title, due)
            Outcome.Done("Added task '${t.title}'" + (due?.let { " due $it" } ?: "") + ".",
                "Added to your list: ${t.title}" + (due?.let { " · due ${pretty(it)}" } ?: "")) {
                tasks.remove(t.id); "Removed ${t.title}"
            }
        }
        "list_tasks" -> {
            val show = a["show"]?.toString() ?: "open"
            val list = tasks.all().filter { show == "all" || (show == "done") == it.done }
            if (list.isEmpty()) Outcome.Done(mapOf("tasks" to emptyList<String>()), "No ${if (show == "all") "" else "$show "}tasks")
            else Outcome.Done(mapOf("tasks" to list.map { it.describe() }), "${list.size} ${if (show == "done") "done" else ""} task${if (list.size == 1) "" else "s"}".replace("  ", " "))
        }
        "complete_task" -> {
            val t = tasks.find(a.str("title"), done = false)
            if (t == null) Outcome.Failed("No open task matches '${a.str("title")}'.")
            else { tasks.setDone(t.id, true); Outcome.Done("Ticked off '${t.title}'.", "Ticked off: ${t.title}") { tasks.setDone(t.id, false); "Unticked ${t.title}" } }
        }
        "delete_task" -> {
            val t = tasks.find(a.str("title"), done = null)
            if (t == null) Outcome.Failed("No task matches '${a.str("title")}'.")
            else { tasks.remove(t.id); Outcome.Done("Deleted '${t.title}'.", "Deleted: ${t.title}") { tasks.restore(t); "Restored ${t.title}" } }
        }
        "set_reminder" -> reminder(a.str("text"), a.str("time"))
        "set_timer" -> timer((a["minutes"] as? Number)?.toDouble() ?: 0.0, a["label"]?.toString())
        "set_alarm" -> alarm(a.str("time"), a["label"]?.toString())
        "get_calendar" -> calendar(a.str("start"), ((a["days"] as? Number)?.toInt() ?: 1).coerceIn(1, 62))
        "remember" -> {
            val f = memory.add(a.str("fact"))
            if (f.id < 0) Outcome.Failed("The user asked you to forget this earlier, so it wasn't saved.")
            else Outcome.Done("Saved: ${f.text}", "Remembered: ${f.text}") { memory.undoAdd(f.id); "Forgot: ${f.text}" }
        }
        "web_search" -> {
            val name = ctx.getSharedPreferences("ronie_user", 0).getString("name", null)
            val q = com.raunakpatil.roniepocket.web.Scrubber.scrub(a.str("query"), listOfNotNull(name))
            if (q.isBlank()) Outcome.Failed("Nothing left to search for once personal details were removed.")
            else {
                val sources = web.ground(q, memory.embedder)
                if (sources.isEmpty()) Outcome.Failed("The search didn't work (no results or no connection).")
                else Outcome.Done(
                    "Sources for \"$q\":\n" + sources.joinToString("\n\n") { "[${it.n}] ${it.title} (${it.host}): ${it.text}" } +
                        "\n\nAnswer only from what these sources say and cite them like [1]. Every fact, number and list item gets its own [n]; leave out anything the sources don't say. Standings, previews and rumours are not results: if something is still going on, say so. If the sources don't answer the question, say that.",
                    "Searched: $q", sources = sources)
            }
        }
        "find_images" -> {
            val name = ctx.getSharedPreferences("ronie_user", 0).getString("name", null)
            val q = com.raunakpatil.roniepocket.web.Scrubber.scrub(a.str("query"), listOfNotNull(name))
            val pics = if (q.isBlank()) emptyList() else web.images(q, File(ctx.filesDir, "images/web"))
            if (pics.isEmpty()) Outcome.Failed("No pictures found for that.")
            else Outcome.Done(
                "Shown to the user: ${pics.size} picture${if (pics.size == 1) "" else "s"} from Wikipedia (${pics.joinToString { it.title }}). " +
                    "Now explain it in your own words; you can point at the pictures.",
                "Pictures: $q", pictures = pics)
        }
        "stop_timer" -> {
            val stopped = Timers.stop(ctx, a["label"]?.toString())
            if (stopped.isEmpty()) Outcome.Failed("There's no timer running.")
            else Outcome.Done(
                "Stopped ${stopped.size} timer${if (stopped.size == 1) "" else "s"}: " +
                    stopped.joinToString { (it.label ?: "timer") + " (" + duration((it.lengthMs / 1000).toInt()) + ")" } + ".",
                "Stopped " + stopped.joinToString { duration((it.lengthMs / 1000).toInt()) + (it.label?.let { l -> " $l" } ?: "") + " timer" })
        }
        "generate_image" -> generateImage(a, onProgress, onStageProgress)
        else -> Outcome.Failed("Unknown tool $name")
    }

    // ------------------------------------------------------------------ image generation

    /** The last picture drawn and the plain prompt behind it (kept across restarts, for "change this picture"). */
    private val drawPrefs get() = ctx.getSharedPreferences("ronie_draw", 0)
    private fun lastPicture(): Pair<File, String>? {
        val path = drawPrefs.getString("last_path", null) ?: return null
        val f = File(path)
        return if (f.exists()) f to (drawPrefs.getString("last_prompt", "") ?: "") else null
    }

    /**
     * Draws (or edits) a picture. Everything heavy runs off the main thread, and any failure comes back as
     * [Outcome.Failed] instead of an exception (an uncaught one here used to take the whole app down).
     */
    private suspend fun generateImage(
        a: Map<String, Any?>,
        onProgress: ((step: Int, total: Int, preview: android.graphics.Bitmap?) -> Unit)? = null,
        onStageProgress: ((progress: Float, stageText: String, preview: android.graphics.Bitmap?) -> Unit)? = null
    ): Outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val prompt = a.str("prompt").trim()
        if (prompt.isEmpty()) return@withContext Outcome.Failed("Tell me what to draw.")
        val style = a["style"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val shape = a["shape"]?.toString() ?: "square"
        val editLast = a["edit_last"] == true || a["edit_last"]?.toString().equals("true", ignoreCase = true)

        if (!com.raunakpatil.roniepocket.models.ModelStore.isDrawingInstalled(ctx)) {
            return@withContext Outcome.Failed("Drawing brain isn't installed. Download it in Settings → Models.")
        }
        val last = if (editLast) lastPicture() else null
        if (editLast && last == null) {
            return@withContext Outcome.Failed("There's no picture to change yet. Ask me to draw one first.")
        }

        // The one hard limit, checked on everything that shapes the picture: the new words, the style and,
        // for an edit, the prompt of the picture being changed ("make her nude" must see who "her" is).
        val minors = minorsCheck.check(listOfNotNull(last?.second, prompt, style).joinToString(", "))
        if (minors.blocked) {
            android.util.Log.i("RonieTools", "generate_image stopped by the minors check")
            return@withContext Outcome.Failed("I won't draw that.")
        }

        val drawerInstance = try { getOrLoadDrawer() } catch (t: Throwable) {
            android.util.Log.e("RonieTools", "drawing model didn't load", t)
            return@withContext Outcome.Failed("I couldn't load the drawing brain. Try downloading it again in Settings → Models.")
        }
        val isSana = drawerInstance is com.raunakpatil.roniepocket.imagegen.SanaEngine

        // What the picture should show, in plain words. An edit keeps the old description and adds the change:
        // the drawing model follows descriptions, not instructions like "make the background blue".
        val plain = if (last != null && last.second.isNotBlank()) "${last.second}, ${editWords(prompt)}" else prompt
        var fullPrompt: String
        val finalNegative: String
        if (isSana) {
            val pair = com.raunakpatil.roniepocket.imagegen.SanaPromptSynthesizer.synthesize(plain, style, a["negative_prompt"]?.toString())
            fullPrompt = pair.first; finalNegative = pair.second
        } else {
            fullPrompt = sdPrompt(plain, style); finalNegative = ""
        }
        // A child in the prompt (with nothing sexual in the words): steer the picture itself to stay clothed.
        if (minors.needsImageCheck) fullPrompt += ", fully clothed, modest everyday clothes, wholesome, family friendly"

        val (w, h) = if (isSana) 1024 to 1024 else when (shape) {
            "portrait" -> 512 to 768
            "landscape" -> 768 to 512
            else -> 512 to 512
        }
        val steps = (a["steps"] as? Number)?.toInt() ?: if (isSana) 2 else 3
        val seed = (a["seed"] as? Number)?.toLong() ?: java.security.SecureRandom().nextLong(1L, Long.MAX_VALUE)

        val bitmap = try {
            if (last != null) {
                val src = android.graphics.BitmapFactory.decodeFile(last.first.absolutePath)
                    ?: return@withContext Outcome.Failed("I couldn't open the last picture to change it.")
                drawerInstance.edit(src, fullPrompt, negativePrompt = finalNegative, steps = steps, seed = seed, onStep = onProgress, onProgress = onStageProgress)
            } else {
                drawerInstance.generate(fullPrompt, negativePrompt = finalNegative, width = w, height = h, steps = steps, seed = seed, onStep = onProgress, onProgress = onStageProgress)
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (m: com.raunakpatil.roniepocket.imagegen.MinorsSafetyException) {
            return@withContext Outcome.Failed("I won't draw that.")
        } catch (t: Throwable) {
            android.util.Log.e("RonieTools", "drawing failed", t)
            return@withContext Outcome.Failed("The drawing didn't work this time (${t.message?.take(80) ?: "unknown error"}). Try again.")
        } finally {
            // Free the drawing model straight away so its memory doesn't sit next to the chat brain.
            runCatching { drawerInstance.close() }
            drawer = null
            com.raunakpatil.roniepocket.imagegen.HeavyWork.trim()
        }

        onStageProgress?.invoke(0.96f, "Saving the picture…", null)
        val genDir = File(ctx.filesDir, "images/gen").apply { mkdirs() }
        val imgFile = File(genDir, "gen_${System.currentTimeMillis()}_${(1000..9999).random()}.png")
        val saved = runCatching {
            imgFile.outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
        }.getOrDefault(false)
        if (!saved) return@withContext Outcome.Failed("I drew it but couldn't save it. Is the phone out of storage?")
        drawPrefs.edit().putString("last_path", imgFile.absolutePath).putString("last_prompt", plain.take(600)).apply()

        Outcome.Done(
            forModel = "Image drawn and shown to the user. It shows: $plain",
            summary = prompt,
            image = imgFile
        )
    }

    /** "make the background blue" → "background blue": the drawing model wants a description, not an order. */
    private fun editWords(instruction: String): String =
        instruction.trim().replace(Regex("""^(?:please\s+)?(?:change this picture:?|make|change|turn|set|add|give)\s+(?:it\s+|the\s+picture\s+)?""", RegexOption.IGNORE_CASE), "").trim()
            .ifEmpty { instruction.trim() }

    /**
     * The prompt for the Stable Diffusion family (SD-Turbo): the description, the style and a few quality
     * words. Its text encoder doesn't understand "no" (writing "no extra limbs" tends to add them), and at
     * guidance 1.0 a negative prompt is ignored, so those parts are dropped rather than sent.
     */
    private fun sdPrompt(prompt: String, style: String?): String {
        val kept = prompt.split(',').map { it.trim() }.filter { seg ->
            seg.isNotEmpty() && !Regex("""^(?:no|without|avoid\w*|never)\b""", RegexOption.IGNORE_CASE).containsMatchIn(seg) &&
                !Regex("""anatomically correct|extra (?:limbs|fingers|paws)|per hand|limb count|proportionate (?:limbs|body)""", RegexOption.IGNORE_CASE).containsMatchIn(seg)
        }
        val clean = kept.joinToString(", ").ifEmpty { prompt.trim() }
        val l = clean.lowercase()
        // What kind of subject it is decides which details are worth asking for.
        val subject = when {
            Regex("""\b(?:person|man|woman|girl|boy|lady|portrait|face|warrior|knight|astronaut|model|dancer|nude|naked|people)\b""").containsMatchIn(l) ->
                "detailed face, expressive eyes, natural skin texture"
            Regex("""\b(?:cat|kitten|dog|puppy|wolf|fox|horse|dragon|bird|lion|tiger|bear|rabbit|owl|animal|creature)\b""").containsMatchIn(l) ->
                "detailed fur and features, expressive eyes"
            Regex("""\b(?:city|street|landscape|mountain|forest|beach|sky|sunset|room|building|village|ocean|river)\b""").containsMatchIn(l) ->
                "sweeping composition, atmospheric depth"
            else -> ""
        }
        val detail = "intricate details, fine textures, sharp focus, rich colours, dramatic lighting, masterpiece, best quality"
        return listOf(enhancePrompt(clean, style), subject, detail).filter { it.isNotBlank() }
            .joinToString(", ").split(Regex("""\s+""")).take(75).joinToString(" ")
    }

    private fun enhancePrompt(prompt: String, style: String?): String {
        val clean = prompt.trim()
        val withStyle = if (!style.isNullOrBlank() && !clean.contains(style, ignoreCase = true)) {
            "$clean, in $style style"
        } else clean

        val qualityBooster = when (style?.lowercase()) {
            "photo", "realistic" -> "high quality photo, 8k, sharp focus, detailed textures, cinematic lighting"
            "watercolour", "watercolor" -> "delicate watercolor painting, vibrant wash, paper texture, masterwork"
            "anime" -> "vibrant anime artwork, crisp detailed lineart, beautiful lighting, masterpiece"
            "pixel art" -> "detailed pixel art, clean sprites, 16-bit aesthetic"
            "sketch" -> "fine pencil sketch, detailed cross-hatching, expressive line work"
            "3d" -> "detailed 3D render, smooth volumetric lighting, raytraced, 8k"
            else -> {
                val hasQualityKeyword = listOf("detailed", "8k", "masterpiece", "photorealistic", "sharp focus", "hyperrealistic")
                    .any { clean.contains(it, ignoreCase = true) }
                if (!hasQualityKeyword) {
                    "highly detailed, sharp focus, masterpiece"
                } else ""
            }
        }

        return if (qualityBooster.isNotBlank() && !clean.contains(qualityBooster.split(",")[0], ignoreCase = true)) {
            "$withStyle, $qualityBooster"
        } else withStyle
    }

    private fun getOrLoadDrawer(): ImageGen {
        drawer?.let { return it }
        val drawTier = com.raunakpatil.roniepocket.models.ModelStore.savedDrawTier(ctx)
        val extDir = ctx.getExternalFilesDir(null)
        val candidates = if (drawTier == com.raunakpatil.roniepocket.models.ImageGenTier.SANA_SPRINT) {
            listOfNotNull(
                extDir?.let { File(it, "models/draw/sana") },
                File(ctx.filesDir, "models/draw/sana"),
                extDir?.let { File(it, "models/draw/sd_turbo_q4_0.gguf") },
                extDir?.let { File(it, "models/draw") },
                File(ctx.filesDir, "models/draw/sd_turbo_q4_0.gguf"),
                File(ctx.filesDir, "models/draw")
            )
        } else {
            listOfNotNull(
                extDir?.let { File(it, "models/draw/sd_turbo_q4_0.gguf") },
                extDir?.let { File(it, "models/draw") },
                File(ctx.filesDir, "models/draw/sd_turbo_q4_0.gguf"),
                File(ctx.filesDir, "models/draw"),
                extDir?.let { File(it, "models/draw/sana") },
                File(ctx.filesDir, "models/draw/sana")
            )
        }
        for (c in candidates) {
            if (c.exists()) {
                val loaded = ImageGen.load(c, minorsCheck)
                if (loaded != null) {
                    drawer = loaded
                    return loaded
                }
            }
        }
        val target = extDir?.let { File(it, "models/draw") }
            ?: File(ctx.filesDir, "models/draw")
        error("Drawing model not installed in ${target.absolutePath}")
    }

    // ------------------------------------------------------------------ reminders

    private fun reminder(text: String, time: String): Outcome {
        val at = parseDateTime(time) ?: return Outcome.Failed("Couldn't read the time '$time'; use ISO like 2026-11-10T18:00.")
        if (at.isBefore(LocalDateTime.now().minusMinutes(1))) return Outcome.Failed("$time is in the past.")
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
            return Outcome.NeedsPermission(Manifest.permission.POST_NOTIFICATIONS, "to show your reminder")
        val r = Reminders.add(ctx, text, at)
        return Outcome.Done("Reminder set for ${at.format(ISO_MIN)}: $text", "${pretty(at).replaceFirstChar { it.uppercase() }} · $text") {
            Reminders.cancel(ctx, r); "Cancelled the reminder"
        }
    }

    // ------------------------------------------------------------------ clock app

    private fun timer(minutes: Double, label: String?): Outcome {
        val secs = (minutes * 60).toInt()
        if (secs !in 1..86_400) return Outcome.Failed("A timer must be between 1 second and 24 hours.")
        // The model often repeats the duration (or just "timer") as the label; that's noise.
        @Suppress("NAME_SHADOWING")
        val label = label?.trim()?.takeUnless {
            it.isEmpty() || it.equals("timer", true) ||
                Regex("""^\s*[\d.]+\s*(s|sec|second|m|min|minute|h|hr|hour)s?\b.*""", RegexOption.IGNORE_CASE).matches(it)
        }
        val ms = secs * 1000L
        // Ronie's own timer (he can stop it later); the Clock app only if this phone won't allow exact alarms.
        if (Timers.canRingExactly(ctx)) {
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
                return Outcome.NeedsPermission(Manifest.permission.POST_NOTIFICATIONS, "to ring when the timer ends")
            val t = Timers.start(ctx, ms, label)
            return Outcome.Done("Timer started for ${duration(secs)}" + (label?.let { " ($it)" } ?: "") + ".",
                "${duration(secs)} timer" + (label?.let { " · $it" } ?: ""), timer = t.endsAt to ms)
        }
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        label?.takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return launch(i) ?: Outcome.Done("Timer started for ${duration(secs)}.", "${duration(secs)} timer" + (label?.let { " · $it" } ?: ""),
            timer = (System.currentTimeMillis() + ms) to ms)
    }

    private fun alarm(time: String, label: String?): Outcome {
        val t = runCatching { LocalTime.parse(time.trim().padStart(5, '0')) }.getOrNull()
            ?: parseDateTime(time)?.toLocalTime() ?: return Outcome.Failed("Couldn't read '$time'; use HH:MM.")
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, t.hour).putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        label?.takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val shown = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
        return launch(i) ?: Outcome.Done("Alarm set for ${t.format(DateTimeFormatter.ofPattern("HH:mm"))}.", "$shown" + (label?.let { " · $it" } ?: "") + " · in the Clock app")
    }

    private fun launch(i: Intent): Outcome? = try { ctx.startActivity(i); null } catch (e: Exception) {
        Outcome.Failed("The phone's clock app didn't accept it (${e.javaClass.simpleName}).")
    }

    // ------------------------------------------------------------------ calendar (read only)

    private fun calendar(start: String, days: Int): Outcome {
        val from = runCatching { LocalDate.parse(start.take(10)) }.getOrNull() ?: return Outcome.Failed("Couldn't read the date '$start'.")
        if (!granted(Manifest.permission.READ_CALENDAR)) return Outcome.NeedsPermission(Manifest.permission.READ_CALENDAR, "to read your calendar")
        val zone = ZoneId.systemDefault()
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = from.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        // One short line per event (a map per event made 40 events ~1,800 tokens and a 33 s wait on the phone).
        val events = mutableListOf<Pair<String, String>>()   // sort key, line
        val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        var total = 0
        CalendarContract.Instances.query(ctx.contentResolver,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION), begin, end)?.use { c ->
            while (c.moveToNext()) {
                total++
                val allDay = c.getInt(3) == 1
                val s = java.time.Instant.ofEpochMilli(c.getLong(1)).atZone(if (allDay) ZoneId.of("UTC") else zone).toLocalDateTime()
                val e = java.time.Instant.ofEpochMilli(c.getLong(2)).atZone(if (allDay) ZoneId.of("UTC") else zone).toLocalDateTime()
                val title = (c.getString(0) ?: "(no title)").take(60)
                val place = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " @ ${it.take(40)}" } ?: ""
                val line = if (allDay) "${s.format(day)} all day: $title$place"
                    else "${s.format(day)} ${s.format(hm)}-${e.format(hm)} $title$place"
                events += s.toString() to line
            }
        }
        events.sortBy { it.first }
        val shown = events.take(MAX_EVENTS).map { it.second }
        val range = if (days == 1) pretty(from) else "${pretty(from)} – ${pretty(from.plusDays(days - 1L))}"
        val forModel = if (shown.isEmpty()) "No events." else shown.joinToString("\n") +
            if (total > shown.size) "\n(+${total - shown.size} more not shown)" else ""
        return Outcome.Done(forModel, if (total == 0) "Calendar $range: nothing" else "Calendar $range: $total event${if (total == 1) "" else "s"}")
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Calendar events passed to the model per call. */
        const val MAX_EVENTS = 25
        val ISO_MIN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        fun parseDateTime(s: String): LocalDateTime? {
            val t = s.trim().removeSuffix("Z").replace(' ', 'T')
            return runCatching { LocalDateTime.parse(t) }.getOrNull()
                ?: runCatching { java.time.OffsetDateTime.parse(s.trim()).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
                ?: runCatching { LocalDate.parse(t.take(10)).atTime(9, 0) }.getOrNull()
        }

        fun pretty(d: LocalDate): String {
            val today = LocalDate.now()
            return when (d) {
                today -> "today"; today.plusDays(1) -> "tomorrow"
                else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", Locale.ENGLISH))
            }
        }

        fun pretty(dt: LocalDateTime): String = pretty(dt.toLocalDate()) + " " + dt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
        fun pretty(iso: String): String = parseDateTime(iso)?.let { if (iso.length <= 10) pretty(it.toLocalDate()) else pretty(it) } ?: iso

        fun duration(secs: Int): String = when {
            secs % 3600 == 0 -> "${secs / 3600} h"
            secs >= 3600 -> "${secs / 3600} h ${(secs % 3600) / 60} min"
            secs % 60 == 0 -> "${secs / 60} min"
            secs > 60 -> "${secs / 60} min ${secs % 60} s"
            else -> "$secs s"
        }
    }
}

private fun Map<String, Any?>.str(k: String) = this[k]?.toString()?.trim() ?: ""

// ---------------------------------------------------------------------- stores

/** The to-do list, a small JSON file (Phase 6 moves it into the encrypted database). */
class TaskStore(private val file: File) {
    data class Task(val id: Long, val title: String, val due: String?, val done: Boolean, val created: Long) {
        fun describe() = title + (due?.let { " (due $it)" } ?: "") + if (done) " [done]" else ""
    }

    @Synchronized fun all(): List<Task> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Task(it.getLong("id"), it.getString("title"), it.optString("due").ifEmpty { null }, it.optBoolean("done"), it.optLong("created"))
        }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(list: List<Task>) {
        file.parentFile?.mkdirs()
        file.writeText(JSONArray(list.map { t ->
            JSONObject().put("id", t.id).put("title", t.title).put("due", t.due ?: "").put("done", t.done).put("created", t.created)
        }).toString())
    }

    @Synchronized fun add(title: String, due: String?): Task {
        val t = Task(System.currentTimeMillis(), title.trim().replaceFirstChar { it.uppercase() }, due, false, System.currentTimeMillis())
        save(all() + t); return t
    }
    @Synchronized fun remove(id: Long) = save(all().filter { it.id != id })
    @Synchronized fun restore(t: Task) = save((all() + t).sortedBy { it.created })
    @Synchronized fun setDone(id: Long, done: Boolean) = save(all().map { if (it.id == id) it.copy(done = done) else it })

    /** Best match by title words; [done] = null searches all tasks. */
    fun find(query: String, done: Boolean?): Task? {
        val q = query.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.toSet()
        if (q.isEmpty()) return null
        return all().filter { done == null || it.done == done }
            .map { t -> t to t.title.lowercase().split(Regex("\\W+")).count { w -> q.any { w.startsWith(it) || it.startsWith(w) && w.length > 2 } } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }
}

// ---------------------------------------------------------------------- reminders

/** Reminder notifications via AlarmManager; kept in a file so they survive a reboot. */
object Reminders {
    private const val CHANNEL = "reminders"
    data class Reminder(val id: Int, val text: String, val at: LocalDateTime)

    private fun file(ctx: Context) = File(ctx.filesDir, "tools/reminders.json")

    @Synchronized fun all(ctx: Context): List<Reminder> = runCatching {
        val a = JSONArray(file(ctx).readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { Reminder(it.getInt("id"), it.getString("text"), LocalDateTime.parse(it.getString("at"))) }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(ctx: Context, list: List<Reminder>) {
        file(ctx).parentFile?.mkdirs()
        file(ctx).writeText(JSONArray(list.map { JSONObject().put("id", it.id).put("text", it.text).put("at", it.at.toString()) }).toString())
    }

    fun add(ctx: Context, text: String, at: LocalDateTime): Reminder {
        val r = Reminder((System.currentTimeMillis() and 0x7fffffff).toInt(), text, at)
        save(ctx, all(ctx) + r)
        schedule(ctx, r)
        return r
    }

    fun cancel(ctx: Context, r: Reminder) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, r))
        save(ctx, all(ctx).filter { it.id != r.id })
    }

    private fun pending(ctx: Context, r: Reminder): PendingIntent = PendingIntent.getBroadcast(ctx, r.id,
        Intent(ctx, ReminderReceiver::class.java).putExtra("id", r.id).putExtra("text", r.text),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val ms = r.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Exact when the user allowed it (Settings → Alarms & reminders); otherwise Android may deliver a few minutes late.
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pending(ctx, r))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pending(ctx, r))
    }

    /** After a reboot: re-arm future reminders, drop old ones. */
    fun rescheduleAll(ctx: Context) {
        val now = LocalDateTime.now()
        val keep = all(ctx).filter { it.at.isAfter(now.minusMinutes(1)) }
        save(ctx, keep)
        keep.forEach { schedule(ctx, it) }
    }

    fun fired(ctx: Context, id: Int, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 0, ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
            PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Ronie: reminder")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
        save(ctx, all(ctx).filter { it.id != id })
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> { Reminders.rescheduleAll(ctx); Timers.rescheduleAll(ctx) }
            else -> Reminders.fired(ctx, intent.getIntExtra("id", 0), intent.getStringExtra("text") ?: "")
        }
    }
}
