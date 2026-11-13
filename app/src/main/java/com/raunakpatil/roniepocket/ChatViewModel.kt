package com.raunakpatil.roniepocket

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raunakpatil.roniepocket.engine.ChatMessage
import com.raunakpatil.roniepocket.engine.GenEvent
import com.raunakpatil.roniepocket.engine.LlamaEngine
import com.raunakpatil.roniepocket.models.DeviceProfile
import com.raunakpatil.roniepocket.models.ModelStore
import com.raunakpatil.roniepocket.models.Tier
import com.raunakpatil.roniepocket.models.TierChoice
import com.raunakpatil.roniepocket.models.TierPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import com.raunakpatil.roniepocket.tools.ToolCard

data class UiMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val images: List<String> = emptyList(),
    val streaming: Boolean = false,
    /** Render lists in this reply as tickable checklist cards (the user asked for a list/plan). */
    val checklist: Boolean = false,
    /** X-ray: the reply split into sentences with probe scores (only when X-ray was on). */
    val xray: List<Xray.Segment>? = null,
    /** Tool calls made while writing this reply (Phase 5), shown as cards above the text. */
    val tools: List<com.raunakpatil.roniepocket.tools.ToolCard> = emptyList(),
)

/** One stage of waking Ronie up, shown live on the boot screen. */
data class BootStep(val label: String, val detail: String = "", val active: Boolean = false, val done: Boolean = false, val ms: Long? = null)

sealed interface Phase {
    /** First run: Ronie shows the one brain it picked for this phone. */
    data class Onboarding(val choice: TierChoice) : Phase
    data class Downloading(val tier: Tier, val progress: ModelStore.Progress) : Phase
    data object Loading : Phase
    data object Chat : Phase
    data class Error(val message: String) : Phase
}

data class ChatState(
    val phase: Phase = Phase.Loading,
    val status: String = "Waking Ronie up…",
    val ready: Boolean = false,
    val generating: Boolean = false,
    val messages: List<UiMessage> = emptyList(),
    val lastStats: String = "",
    /** Set when the speed check finds this phone too slow for its tier (offered once). */
    val stepDownOffer: Tier? = null,
    /** Clean (-1) ↔ Sweary (+1) slider position; null when this brain has no tone direction. */
    val tone: Float? = null,
    /** X-ray Mode on/off; null when this brain has no probe. */
    val xray: Boolean? = null,
    val xrayNote: String = "",
    // Voice (Phase 4)
    /** Always reply aloud (off by default). */
    val speak: Boolean = false,
    /** Reply aloud when the user spoke to Ronie with the mic (on by default). */
    val speakOnVoice: Boolean = true,
    val voiceSpeed: Float = 1.0f,
    val voiceStatus: String = "Voice loads after Ronie wakes up.",
    val speaking: Boolean = false,
    val micAvailable: Boolean = false,
    val listening: Boolean = false,
    val heard: String = "",
    // Settings → Models
    val brainLabel: String = "",
    val brainDetail: String = "",
    // Tools & connectors (Phase 5)
    val toolsOn: Boolean = false,
    val offline: Boolean = false,
    val connectors: List<com.raunakpatil.roniepocket.tools.Connector> = emptyList(),
    val facts: List<com.raunakpatil.roniepocket.memory.MemoryStore.Fact> = emptyList(),
    val activity: List<com.raunakpatil.roniepocket.tools.ActivityLog.Entry> = emptyList(),
    /** An Android permission a tool needs right now (the screen asks, then calls [ChatViewModel.onPermission]). */
    val permissionAsk: String? = null,
    /** The user's name for the greeting ("" = they skipped); null until asked. */
    val userName: String? = null,
    /** First launch: ask the user's name before anything else. */
    val askName: Boolean = false,
    /** What Ronie is doing right now, for the status pill ("Thinking…", "Checking your calendar…"); null = nothing to show. */
    val doing: String? = null,
    /** Saved chats for the sidebar (newest first) and which one is open. */
    val chats: List<ChatStore.Summary> = emptyList(),
    /** Boot screen: the real stages of waking up. */
    val boot: List<BootStep> = emptyList(),
    val chatId: Long = 0L,
    val drawingInstalled: Boolean = false,
    /** The installed chat brain and the active drawing model (for Settings → Models). */
    val brainTier: Tier? = null,
    val drawTier: com.raunakpatil.roniepocket.models.ImageGenTier? = null,
    /** Drawing model download started from Settings: a progress line, or null when nothing is running. */
    val drawingDownload: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app), com.raunakpatil.roniepocket.tools.ToolActions {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    private val ctx get() = getApplication<Application>()
    private var engine: LlamaEngine? = null
    /** True while a brain is being loaded, so two loads can never run at once (each one is several GB). */
    private var loading = false
    private var reloadJob: Job? = null
    /** The brain whose download is being watched; older watchers go quiet when this changes. */
    private var watching: Tier? = null
    private var helpersLoaded = false
    private var tier: Tier? = null
    private var genJob: Job? = null
    private var nextId = 0L
    private val history = mutableListOf<ChatMessage>()

    private val userPrefs get() = ctx.getSharedPreferences("ronie_user", 0)
    /** Saved chats (declared before init, which reads the list). */
    private val chatStore by lazy { ChatStore(ctx.filesDir) }

    init {
        val p = userPrefs
        _state.update { it.copy(userName = p.getString("name", null), askName = !p.contains("name"), chatId = System.currentTimeMillis()) }
        refreshDrawingState()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { val list = chatStore.list(); _state.update { it.copy(chats = list) } }
        viewModelScope.launch { start() }

        com.raunakpatil.roniepocket.imagegen.HeavyWork.onAcquire = {
            Log.i("Ronie", "HeavyWork acquired: freeing LLM engine to reserve full RAM for image generation")
            reloadJob?.join()    // a reload from the previous drawing may still be running: let it finish, then unload
            warmJob?.join()
            val e = engine
            engine = null
            _state.update { it.copy(ready = false) }
            e?.close()
            com.raunakpatil.roniepocket.imagegen.HeavyWork.trim()
        }
        com.raunakpatil.roniepocket.imagegen.HeavyWork.onRelease = {
            Log.i("Ronie", "HeavyWork released: scheduling LLM reload after memory settles")
            com.raunakpatil.roniepocket.imagegen.HeavyWork.trim()
            reloadJob = viewModelScope.launch {
                kotlinx.coroutines.delay(1000)
                com.raunakpatil.roniepocket.imagegen.HeavyWork.trim()
                start(silent = true)
            }
        }
    }

    /** Saves the user's name ("" if they skipped); Ronie hears it at the end of his prompt. */
    fun setName(name: String) {
        val n = name.trim().take(40)
        userPrefs.edit().putString("name", n).apply()
        _state.update { it.copy(userName = n, askName = false) }
    }

    private fun nameLine(): String = _state.value.userName?.takeIf { it.isNotBlank() }?.let { "\nThe user's name is $it." } ?: ""

    /** Decide what to show: onboarding, an ongoing download, or load the model and chat. */
    private suspend fun start(silent: Boolean = false) {
        val saved = ModelStore.savedTier(ctx)
        val ready = saved?.let { ModelStore.readyFile(ctx, it) }
        when {
            saved != null && ready != null -> load(saved, ready, silent = silent)
            saved != null -> watchDownload(saved)          // a download was started earlier
            else -> {
                // Debug convenience: a model pushed over adb counts as already downloaded.
                val pushed = Tier.entries.firstOrNull { ModelStore.readyFile(ctx, it) != null }
                val choice = TierPolicy.choose(DeviceProfile.read(ctx))
                if (pushed != null && pushed == choice.tier) {
                    ModelStore.saveTier(ctx, pushed)
                    load(pushed, ModelStore.readyFile(ctx, pushed)!!, silent = silent)
                } else {
                    _state.update { it.copy(phase = Phase.Onboarding(choice), status = "Meet Ronie") }
                }
            }
        }
    }

    fun startDownload(tier: Tier, wifiOnly: Boolean = true, downloadDrawing: Boolean = false, imageTier: com.raunakpatil.roniepocket.models.ImageGenTier = com.raunakpatil.roniepocket.models.ImageGenTier.SD_TURBO) {
        if (downloadDrawing) {
            ModelStore.saveDrawTier(ctx, imageTier)
        }
        ModelStore.startDownload(ctx, tier, wifiOnly, downloadDrawing, imageTier)
        viewModelScope.launch { watchDownload(tier) }
    }

    /** "Try again" on the download screen: same choices as before (Wi-Fi only or not, drawing model or not). */
    fun retryDownload(t: Tier) = ModelStore.ensureDownload(ctx, t)   // the running watchDownload picks it up

    private suspend fun watchDownload(t: Tier) {
        // Queue the download only if nothing is queued or running (KEEP), with the choices made at onboarding.
        // Restarting it here used to drop "use mobile data" and the drawing model.
        watching = t
        if (ModelStore.readyFile(ctx, t) == null) ModelStore.ensureDownload(ctx, t)
        var loaded = false
        ModelStore.progress(ctx).collect { p ->
            if (loaded || watching != t) return@collect
            val f = ModelStore.readyFile(ctx, t)
            // The brain is usable as soon as its own file is verified, even if an optional extra (drawing) failed.
            if (f != null && (p is ModelStore.Progress.Done || p is ModelStore.Progress.Failed)) {
                loaded = true
                ModelStore.deleteOthers(ctx, t)
                refreshDrawingState()
                load(t, f)
                return@collect
            }
            _state.update { it.copy(phase = Phase.Downloading(t, p), status = "Downloading Ronie's brain") }
            if (p is ModelStore.Progress.Idle && f == null) ModelStore.ensureDownload(ctx, t)   // WorkManager has no record
        }
    }

    /** Settings → Models → Drawing → Download: fetches only the drawing model and reports progress there. */
    fun downloadDrawing(wifiOnly: Boolean = true) {
        val t = tier ?: return
        if (_state.value.drawingDownload != null) return
        ModelStore.downloadDrawing(ctx, t, wifiOnly, ModelStore.savedDrawTier(ctx))
        _state.update { it.copy(drawingDownload = "Starting…") }
        viewModelScope.launch {
            var seenActive = false
            ModelStore.progress(ctx).collect { p ->
                if (_state.value.drawingDownload == null) return@collect
                when (p) {
                    is ModelStore.Progress.Waiting -> { seenActive = true; _state.update { it.copy(drawingDownload = if (wifiOnly) "Waiting for Wi-Fi…" else "Waiting for a connection…") } }
                    is ModelStore.Progress.Running -> { seenActive = true
                        val pct = if (p.total > 0) (p.done * 100 / p.total).toInt() else 0
                        _state.update { it.copy(drawingDownload = if (p.verifying) "Checking the download…" else "Downloading… $pct%") } }
                    is ModelStore.Progress.Done -> if (seenActive) { refreshDrawingState(); _state.update { it.copy(drawingDownload = null) } }
                    is ModelStore.Progress.Failed -> if (seenActive) { refreshDrawingState(); _state.update { it.copy(drawingDownload = null, status = p.message) } }
                    ModelStore.Progress.Idle -> if (seenActive) _state.update { it.copy(drawingDownload = null) }
                }
            }
        }
    }

    private suspend fun load(t: Tier, file: File, silent: Boolean = false) {
        if (engine != null || loading) return
        loading = true
        try { loadInner(t, file, silent) } finally { loading = false }
    }

    private suspend fun loadInner(t: Tier, file: File, silent: Boolean) {
        tier = t
        if (!silent) {
            _state.update { it.copy(phase = Phase.Loading, status = "Loading Ronie's ${t.label} brain…", boot = listOf(
                BootStep("Waking the engine", "llama.cpp"),
                BootStep("Loading my brain", "${t.model.sizeLabel} · ${t.label}"),
                BootStep("Remembering who I am", "persona, tools, today's date"),
                BootStep("Warming up", if (t.model.gpuLayers > 0) "first pass on the GPU" else "first pass on the CPU"),
                BootStep("Checking my speed", "once per brain"),
                BootStep("Getting my tools ready", "tone, timers, connectors"),
            )) }
        }
        var mark = System.currentTimeMillis()
        fun step(i: Int, detail: String? = null) {          // finish step i (timed), start i + 1
            if (silent) return
            val now = System.currentTimeMillis()
            val took = now - mark; mark = now
            _state.update { s -> s.copy(boot = s.boot.mapIndexed { j, b -> when (j) {
                i -> b.copy(active = false, done = true, ms = took, detail = detail ?: b.detail)
                i + 1 -> b.copy(active = true)
                else -> b
            } }) }
        }
        if (!silent) {
            _state.update { s -> s.copy(boot = s.boot.mapIndexed { j, b -> if (j == 0) b.copy(active = true) else b }) }
        }
        LlamaEngine.setGpuCache(java.io.File(ctx.filesDir, "gpu_pipelines.bin").absolutePath)  // compiled shaders, kept across launches
        val devices = LlamaEngine.init(ctx.applicationInfo.nativeLibraryDir)
        Log.i("Ronie", "devices: $devices")
        step(0, if (devices.contains("Vulkan", ignoreCase = true)) "llama.cpp · Vulkan GPU + CPU" else "llama.cpp · CPU")
        val t0 = System.currentTimeMillis()
        val e = LlamaEngine.load(file.absolutePath, gpuLayers = t.model.gpuLayers, contextSize = 8192)
        if (e == null) {
            // Also after a drawing (silent reload): otherwise the chat would sit at "waking up" forever.
            _state.update { it.copy(phase = Phase.Error("Ronie couldn't load his brain. Please restart the app.")) }
            return
        }
        e.setSampler(
            if (t == Tier.FULL) {
                com.raunakpatil.roniepocket.engine.SamplerConfig(
                    temperature = 1.0f, topK = 64, topP = 0.95f, minP = 0.0f, presencePenalty = 0.1f
                )
            } else {
                com.raunakpatil.roniepocket.engine.SamplerConfig(
                    temperature = 0.7f, topK = 40, topP = 0.90f, minP = 0.05f, presencePenalty = 0.2f
                )
            }
        )
        val loadMs = System.currentTimeMillis() - t0
        step(1)
        if (!silent) _state.update { it.copy(status = "Warming up…") }
        val t1 = System.currentTimeMillis()
        val restored = warmUp(
            e = e,
            onPersona = { fromDisk -> if (!silent) _state.update { s -> s.copy(boot = s.boot.mapIndexed { j, b ->
                if (j == 2) b.copy(detail = if (fromDisk) "restoring from last time" else "first time: learning it (about 20 s)") else b }) } },
            onWarm = { fromDisk -> step(2, if (fromDisk) "restored from last time" else "learned and saved for next time") },
        )
        // Tone slider: loaded after the persona cache so the cached prefix is never steered.
        suspend fun loadTone() = if (Tone.available(t) && Tone.load(ctx, t, e)) Tone.saved(ctx).also { Tone.apply(t, e, it) } else null
        // Once this phone's speed is known (every launch after the first), the GPU warm-up runs behind the chat.
        val background = ctx.getSharedPreferences("ronie_speed", 0).contains("tps-${t.model.sha256.take(12)}")
        var tone: Float? = null
        if (background && !silent) {
            warmJob = viewModelScope.launch {
                val tw = System.currentTimeMillis()
                finishWarmUp(e)
                val tn = loadTone()
                _state.update { it.copy(tone = tn) }
                Log.i("Ronie", "background warm-up done in ${System.currentTimeMillis() - tw} ms")
            }
        } else {
            finishWarmUp(e)
            tone = loadTone()   // after the persona cache, so the cached prefix is never steered
        }
        val warmMs = System.currentTimeMillis() - t1
        step(3)
        val t2 = System.currentTimeMillis()
        val tps = speedCheckOnce(t, e)
        val speedMs = System.currentTimeMillis() - t2
        step(4, if (tps > 0) "%.1f tokens per second".format(tps) else "skipped")
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        Log.i("Ronie", "startup: load ${loadMs} ms, persona ${warmMs} ms (${if (restored) "restored from disk" else "computed + saved"}" +
            "${if (background && !silent) ", GPU warm-up in background" else ""}), speed check ${speedMs} ms, total ${"%.1f".format(secs)} s")
        val offer = t.lighter()?.takeIf { tps in 0.1..<t.minTokPerSec }
        probe = if (Xray.available(ctx, t)) Xray.load(ctx, t) else null
        step(5)
        if (!silent) kotlinx.coroutines.delay(450)   // let the last light turn green before the chat fades in
        engine = e
        _state.update {
            it.copy(phase = Phase.Chat, ready = true, brainTier = t, stepDownOffer = offer, tone = if (background && !silent) it.tone else tone,
                xray = probe?.let { false }, xrayNote = probe?.receipt ?: "",
                brainLabel = "${t.label} brain · ${t.model.sizeLabel}",
                brainDetail = "${t.tagline}. Uses ${t.model.ramLabel} RAM while chatting" +
                    (if (tps > 0) " · ${"%.1f".format(tps)} tokens/s on this phone." else "."),
                status = "${t.label} brain · ready in ${"%.1f".format(secs)} s" +
                    if (tps > 0) " · ${"%.1f".format(tps)} tok/s" else "")
        }
        refreshToolState()
        pendingSend?.let { p -> pendingSend = null; generateReply(p.text, p.botId, p.images, pendingVoice) }
        if (!helpersLoaded) {   // voice and memory models outlive the brain: load them once, not after every drawing
            helpersLoaded = true
            loadVoice()
            loadEmbedder()
        }
    }

    // ------------------------------------------------------------------ voice (Phase 4)

    private var speaker: com.raunakpatil.roniepocket.voice.Speaker? = null
    private var listener: com.raunakpatil.roniepocket.voice.Listener? = null
    private val voicePrefs get() = ctx.getSharedPreferences("ronie_voice", 0)

    /** Loads the pronunciation dictionary and Paradee in the background, after the brain is ready. */
    /** Loads the memory embedding model (bge-small, ~34 MB) in the background, then embeds any facts that need it. */
    private fun loadEmbedder() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            runCatching {
                val e = com.raunakpatil.roniepocket.memory.Embedder.load { ctx.assets.open(it) }
                toolbox.builtins.memory.embedder = e
                Log.i("RonieMemory", "embedder ready in ${System.currentTimeMillis() - t0} ms")
                val t1 = System.currentTimeMillis(); e.embedQuery("warm up")
                Log.i("RonieMemory", "one embedding: ${System.currentTimeMillis() - t1} ms")
                toolbox.builtins.memory.backfill()
            }.onFailure { Log.e("RonieMemory", "embedder failed to load", it) }
        }
    }

    private fun loadVoice() {
        val speak = voicePrefs.getBoolean("speak", false)
        val speakOnVoice = voicePrefs.getBoolean("speak_on_voice", true)
        val speed = voicePrefs.getFloat("speed", 1.0f)
        _state.update { it.copy(speak = speak, speakOnVoice = speakOnVoice, voiceSpeed = speed, voiceStatus = "Loading Ronie's voice…",
            micAvailable = com.raunakpatil.roniepocket.voice.Listener.available(ctx)) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val t0 = System.currentTimeMillis()
            runCatching {
                val a = ctx.assets
                val lex = com.raunakpatil.roniepocket.voice.Lexicon.load(
                    a.open("voice/us_gold.tsv"), a.open("voice/us_silver.tsv"), a.open("voice/ronie_words.tsv"))
                val tts = com.raunakpatil.roniepocket.voice.Paradee.load(ctx)
                com.raunakpatil.roniepocket.voice.Speaker(com.raunakpatil.roniepocket.voice.G2P(lex), tts, viewModelScope)
                    .also { it.speed = speed }
            }.onSuccess { sp ->
                speaker = sp
                viewModelScope.launch { sp.speaking.collect { on -> _state.update { it.copy(speaking = on) } } }
                val ms = System.currentTimeMillis() - t0
                Log.i("RonieVoice", "voice ready in $ms ms")
                _state.update { it.copy(voiceStatus = "Voice: am_puck (Paradee, on this phone). Loaded in ${"%.1f".format(ms / 1000.0)} s.") }
            }.onFailure { e ->
                Log.e("RonieVoice", "voice failed to load", e)
                _state.update { it.copy(voiceStatus = "Voice couldn't load: ${e.message}") }
            }
        }
    }

    fun setSpeakOnVoice(on: Boolean) {
        voicePrefs.edit().putBoolean("speak_on_voice", on).apply()
        _state.update { it.copy(speakOnVoice = on) }
    }

    fun setSpeak(on: Boolean) {
        voicePrefs.edit().putBoolean("speak", on).apply()
        if (!on) speaker?.stop()
        _state.update { it.copy(speak = on) }
    }

    fun setVoiceSpeed(v: Float) {
        voicePrefs.edit().putFloat("speed", v).apply()
        speaker?.speed = v
        _state.update { it.copy(voiceSpeed = v) }
    }

    /** Barge-in: stop Ronie talking. */
    fun stopSpeaking() { speaker?.stop() }

    fun startListening() {
        speaker?.stop()   // barge-in: talking over Ronie stops him
        val l = listener ?: com.raunakpatil.roniepocket.voice.Listener(ctx,
            onPartial = { p -> _state.update { it.copy(heard = p) } },
            onFinal = { text -> if (text.isNotBlank()) send(text, viaVoice = true) },
            onEnd = { err -> _state.update { it.copy(listening = false, heard = err ?: "") }; _micLevel.value = 0f },
            onLevel = { lvl -> _micLevel.value = lvl },
        ).also { listener = it }
        _state.update { it.copy(listening = true, heard = "") }
        l.start()
    }

    fun stopListening() { listener?.stop() }

    /** Close voice mode without sending anything. */
    fun cancelListening() { listener?.cancel(); _state.update { it.copy(listening = false, heard = "") } }

    /** Microphone loudness 0..1 while listening (kept apart from [state] because it changes ~10× a second). */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel

    /**
     * Gets the persona prompt into the model's cache. The expensive part (everything before the date line)
     * is restored from a state file saved on a previous launch; if the persona or model changed, it's
     * recomputed and saved again. Then the few remaining tokens (date, turn end) are processed, which also
     * warms up the GPU. Returns true if the state came from disk.
     */
    private suspend fun warmUp(e: LlamaEngine, onPersona: (fromDisk: Boolean) -> Unit = {}, onWarm: (fromDisk: Boolean) -> Unit = {}): Boolean {
        val t = tier ?: return false
        val fullPrefix = t.model.format.systemPrefix(systemText())
        val stable = fullPrefix.substringBefore("\nToday is ")   // the date (and connector tools, facts) change; the rest is cached
        check(stable == PersonaPrefix.stable(t.model.format, if (toolsOn) toolbox.builtinBlock else "")) { "persona prefix drifted" }
        val dir = File(ctx.filesDir, "state").apply { mkdirs() }
        val name = PersonaPrefix.stateFileName(t, stable)
        val file = File(dir, name)
        // First launch / after an update: the state computed on the PC and shipped in the app, if it matches.
        fun fromApp(): Boolean = runCatching {
            ctx.assets.open("state/$name").use { inp -> file.outputStream().use { inp.copyTo(it) } }
            Log.i("Ronie", "persona state copied from the app ($name)"); true
        }.getOrElse { Log.i("Ronie", "no shipped persona state for $name"); false }
        if (!file.exists()) fromApp()
        onPersona(file.exists())
        val tl = System.currentTimeMillis()
        var restored = file.exists() && e.loadState(file.absolutePath, stable) >= 0
        Log.i("Ronie", "persona state load ${System.currentTimeMillis() - tl} ms (restored=$restored)")
        // A saved file that no longer loads (e.g. older engine settings): try the shipped one before computing.
        if (!restored && file.exists() && fromApp()) restored = e.loadState(file.absolutePath, stable) >= 0
        if (!restored) {
            e.prefill(stable)
            e.saveState(file.absolutePath)
            dir.listFiles()?.filter { it != file }?.forEach { it.delete() }  // drop stale states
        }
        onWarm(restored)
        return restored
    }

    /**
     * Processes the last few prompt tokens (date, turn end) after the restored persona. Mostly this warms up
     * the GPU (shader pipelines, buffers), so it can run behind the chat: the first reply waits for [warmJob].
     */
    private suspend fun finishWarmUp(e: LlamaEngine) {
        val t = tier ?: return
        val tw = System.currentTimeMillis()
        e.generate(t.model.format.systemPrefix(systemText()), maxTokens = 0).collect { ev ->
            if (ev is GenEvent.Done) Log.i("Ronie", "warm-up pass ${System.currentTimeMillis() - tw} ms: ${ev.statsJson}")
        }
        saveGpuCacheSoon()
    }

    /** The background part of startup (GPU warm-up, tone slider); replies wait for it. */
    private var warmJob: kotlinx.coroutines.Job? = null

    /**
     * The GPU builds shader pipelines the first time each one is needed (several seconds in all). Saving them
     * lets the next launch skip that. New ones appear during the first replies, so save a few times per launch.
     */
    private var gpuCacheSaves = 0
    private fun saveGpuCacheSoon() {
        if (gpuCacheSaves >= 4) return
        gpuCacheSaves++
        viewModelScope.launch {
            val t = System.currentTimeMillis()
            val ok = LlamaEngine.saveGpuCache()
            Log.i("Ronie", "gpu pipeline cache saved=$ok in ${System.currentTimeMillis() - t} ms")
        }
    }

    /** A short hidden generation to measure real speed, done once per model (result kept). Returns tok/s. */
    private suspend fun speedCheckOnce(t: Tier, e: LlamaEngine): Double {
        val prefs = ctx.getSharedPreferences("ronie_speed", 0)
        val key = "tps-${t.model.sha256.take(12)}"
        if (prefs.contains(key)) return prefs.getFloat(key, 0f).toDouble()
        var tps = 0.0
        val prompt = t.model.format.format(listOf(
            ChatMessage(ChatMessage.Role.System, systemText()),
            ChatMessage(ChatMessage.Role.User, "Count from one to twenty in words.")))
        e.generate(prompt, maxTokens = 32).collect { ev ->
            if (ev is GenEvent.Done) tps = runCatching { JSONObject(ev.statsJson).getDouble("tok_per_s") }.getOrDefault(0.0)
        }
        // No re-prefill needed: the next prompt shares the persona prefix and the engine trims the rest.
        if (tps > 0) prefs.edit().putFloat(key, tps.toFloat()).apply()
        return tps
    }

    // ------------------------------------------------------------------ tools (Phase 5)

    private val toolbox by lazy { com.raunakpatil.roniepocket.tools.Toolbox(ctx) }
    /** Tools use Gemma 4's native call format; the Qwen tiers get them once their format is wired up. */
    private val toolsOn get() = tier?.model?.format == com.raunakpatil.roniepocket.engine.PromptFormat.GEMMA4

    /**
     * The system prompt: persona + tool rules + built-in tool declarations (never change → cached on disk),
     * then the per-message tail: connector tools picked for this message, today's dates, remembered facts.
     */
    private fun systemText(remote: List<com.raunakpatil.roniepocket.tools.ToolSpec> = emptyList(), query: String? = null): String {
        val fmt = tier?.model?.format ?: com.raunakpatil.roniepocket.engine.PromptFormat.GEMMA4
        // Remembered facts no longer go here: they ride along with the user's message (memoryNote), which the
        // eval showed Ronie actually uses (60% → 87%), and the system prompt tail stays the same between messages.
        return PersonaPrefix.systemText(fmt, if (toolsOn) toolbox.builtinBlock else "",
            remoteDecls = remote.joinToString("") { it.declaration() }, nameLine = nameLine())
    }

    /**
     * The facts that matter for this message (bge-small recall), as a short note in front of it. Measured in
     * eval/memory/use_eval.py: Ronie used remembered facts 87% of the time this way vs 60% at the end of the
     * system prompt (Tone 0; 80% vs 60% at Tone +100%).
     */
    private fun memoryNote(message: String): String {
        if (!toolsOn) return ""
        val facts = runCatching { toolbox.builtins.memory.relevant(message) }.getOrElse { Log.e("RonieMemory", "recall failed", it); emptyList() }
        if (facts.isEmpty()) return ""
        return "(Keep in mind for this answer, from what I've told you before:\n" + facts.joinToString("\n") { "- ${it.text}" } + ")\n\n"
    }

    private fun refreshToolState() = _state.update {
        it.copy(toolsOn = toolsOn, offline = toolbox.offline, connectors = toolbox.connectors.all(),
            facts = toolbox.builtins.memory.all(), activity = toolbox.log.all().reversed())
    }

    private var nextCardId = 0L
    private val confirms = HashMap<Long, kotlinx.coroutines.CompletableDeferred<Boolean>>()
    private val undos = HashMap<Long, suspend () -> String>()
    private var permissionWait: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    /** Undo notes the model hears with the next message ("the user undid …"). */
    private val notes = mutableListOf<String>()

    fun confirm(cardId: Long, yes: Boolean) { confirms.remove(cardId)?.complete(yes) }

    fun undo(cardId: Long) {
        val u = undos.remove(cardId) ?: return
        viewModelScope.launch {
            val what = runCatching { u() }.getOrElse { "couldn't undo: ${it.message}" }
            notes += what
            updateCard(cardId) { it.copy(state = com.raunakpatil.roniepocket.tools.ToolCard.State.UNDONE, canUndo = false) }
            refreshToolState()
        }
    }

    fun onPermission(granted: Boolean) {
        _state.update { it.copy(permissionAsk = null) }
        permissionWait?.complete(granted); permissionWait = null
    }

    override fun setOffline(on: Boolean) { toolbox.offline = on; refreshToolState() }
    override fun forgetFact(id: Long) { toolbox.builtins.memory.remove(id); refreshToolState() }
    override fun clearActivity() { toolbox.log.clear(); refreshToolState() }

    fun refreshDrawingState() {
        val active = ModelStore.savedDrawTier(ctx)
        _state.update { it.copy(drawingInstalled = ModelStore.isDrawingInstalled(ctx, active), drawTier = active) }
    }

    /** Removes one drawing model's files (the active one when [tier] is null). */
    fun removeDrawing(tier: com.raunakpatil.roniepocket.models.ImageGenTier? = null) {
        ModelStore.removeDrawing(ctx, tier ?: ModelStore.savedDrawTier(ctx))
        refreshDrawingState()
    }

    /**
     * Settings → Models: use [tier] for drawing. If it's already on the phone it becomes active at once;
     * otherwise it's downloaded first. With [deleteOld] the previous model's files are removed to free space.
     */
    fun switchDrawing(tier: com.raunakpatil.roniepocket.models.ImageGenTier, deleteOld: Boolean, wifiOnly: Boolean = true) {
        val old = ModelStore.savedDrawTier(ctx)
        ModelStore.saveDrawTier(ctx, tier)
        if (deleteOld && old != tier) ModelStore.removeDrawing(ctx, old)
        refreshDrawingState()
        if (!ModelStore.isDrawingInstalled(ctx, tier) && tier.isAvailableNow) downloadDrawing(wifiOnly)
    }

    /**
     * Settings → Models: swap the chat brain. The new one is downloaded (if needed) and loaded; the old one's
     * file is deleted once the new brain is ready, so the phone is never left without a brain. Chats are kept.
     */
    fun switchBrain(t: Tier, wifiOnly: Boolean = true) {
        if (t == tier || _state.value.generating) return
        viewModelScope.launch {
            saveChat()
            reloadJob?.join(); warmJob?.join()
            engine?.close(); engine = null
            ModelStore.saveTier(ctx, t)
            history.clear()
            _state.update { it.copy(ready = false, stepDownOffer = null, messages = emptyList(), chatId = System.currentTimeMillis()) }
            val ready = ModelStore.readyFile(ctx, t)
            if (ready != null) { ModelStore.deleteOthers(ctx, t); load(t, ready) }
            else { ModelStore.startDownload(ctx, t, wifiOnly); watchDownload(t) }
        }
    }

    override fun addConnector(name: String, url: String, token: String) {
        val c = com.raunakpatil.roniepocket.tools.Connector(id = "c" + System.currentTimeMillis(), name = name.trim().ifEmpty { "Connector" },
            url = url.trim(), status = "Connecting…")
        toolbox.connectors.upsert(c)
        toolbox.connectors.setToken(c.id, token.trim())
        refreshToolState()
        testConnector(c.id)
    }

    override fun testConnector(id: String) {
        val c = toolbox.connectors.all().firstOrNull { it.id == id } ?: return
        viewModelScope.launch { toolbox.test(c); refreshToolState() }
    }

    override fun setConnectorEnabled(id: String, on: Boolean) {
        toolbox.connectors.all().firstOrNull { it.id == id }?.let { toolbox.connectors.upsert(it.copy(enabled = on)) }
        refreshToolState()
    }

    override fun setConnectorTool(id: String, tool: String, on: Boolean) {
        toolbox.connectors.all().firstOrNull { it.id == id }?.let {
            toolbox.connectors.upsert(it.copy(disabledTools = if (on) it.disabledTools - tool else it.disabledTools + tool))
        }
        refreshToolState()
    }

    override fun removeConnector(id: String) { toolbox.connectors.remove(id); refreshToolState() }

    private fun updateCard(id: Long, f: (com.raunakpatil.roniepocket.tools.ToolCard) -> com.raunakpatil.roniepocket.tools.ToolCard) = _state.update { s ->
        s.copy(messages = s.messages.map { m -> if (m.tools.any { it.id == id }) m.copy(tools = m.tools.map { if (it.id == id) f(it) else it }) else m })
    }

    private fun addCard(botId: Long, c: com.raunakpatil.roniepocket.tools.ToolCard) = _state.update { s ->
        s.copy(messages = s.messages.map { if (it.id == botId) it.copy(tools = it.tools + c) else it })
    }

    /**
     * Runs one tool call through validation, the confirm gate and (if needed) a permission prompt.
     * Returns what goes back to the model.
     */
    /** Timer tiles whose timer was stopped (by voice/chat or the notification's Stop) stop counting. */
    private fun reconcileTimers() {
        val live = com.raunakpatil.roniepocket.tools.Timers.all(ctx).map { it.endsAt }.toSet()
        _state.update { s -> s.copy(messages = s.messages.map { m ->
            if (m.tools.none { it.timerEndsAt != null }) m else m.copy(tools = m.tools.map { c ->
                if (c.timerEndsAt != null && c.tool == "set_timer" && c.timerEndsAt !in live)
                    c.copy(timerEndsAt = null, text = c.text + " · stopped") else c
            })
        }) }
    }

    /** What the status pill says while a tool runs. */
    private fun activityLabel(spec: com.raunakpatil.roniepocket.tools.ToolSpec): String = when (spec.name) {
        "create_task" -> "Adding to your list…"
        "list_tasks" -> "Checking your list…"
        "complete_task" -> "Ticking it off…"
        "delete_task" -> "Deleting…"
        "set_reminder" -> "Setting a reminder…"
        "set_timer" -> "Starting a timer…"
        "stop_timer" -> "Stopping the timer…"
        "set_alarm" -> "Setting an alarm…"
        "get_calendar" -> "Checking your calendar…"
        "generate_image" -> "Generating Image Locally…"
        "remember" -> "Remembering…"
        "web_search" -> "Searching the web…"
        "find_images" -> "Finding pictures…"
        else -> "Asking ${spec.host ?: "a connector"}…"
    }

    private suspend fun runTool(botId: Long, spec: com.raunakpatil.roniepocket.tools.ToolSpec, args: Map<String, Any?>,
                                afterToolResult: Boolean): Any? {
        val gate = com.raunakpatil.roniepocket.tools.Gate.of(spec, afterToolResult)
        val card = com.raunakpatil.roniepocket.tools.ToolCard(nextCardId++, spec.name,
            com.raunakpatil.roniepocket.tools.describeCall(spec, args),
            if (gate == com.raunakpatil.roniepocket.tools.Gate.CONFIRM) ToolCard.State.ASK else ToolCard.State.RUNNING, sendsTo = spec.host,
            prompt = args["prompt"]?.toString())
        addCard(botId, card)
        Log.i("RonieTools", "call ${spec.name} $args gate=$gate")
        if (gate == com.raunakpatil.roniepocket.tools.Gate.CONFIRM) {
            speaker?.stop()
            _state.update { it.copy(doing = null) }   // the card is asking; no pill
            val d = kotlinx.coroutines.CompletableDeferred<Boolean>().also { confirms[card.id] = it }
            val yes = try { d.await() } finally { confirms.remove(card.id) }
            if (!yes) { updateCard(card.id) { it.copy(state = ToolCard.State.DECLINED) }; return DECLINED }
            updateCard(card.id) { it.copy(state = ToolCard.State.RUNNING) }
        }
        _state.update { it.copy(doing = activityLabel(spec)) }
        val onProgress: (Int, Int, android.graphics.Bitmap?) -> Unit = { step, total, preview ->
            val prog = (step.toFloat() / total.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            updateCard(card.id) {
                it.copy(
                    progress = prog,
                    previewBitmap = preview,
                    currentStep = step,
                    totalSteps = total
                )
            }
        }
        val onStageProgress: (Float, String, android.graphics.Bitmap?) -> Unit = { prog, stage, preview ->
            updateCard(card.id) {
                it.copy(
                    progress = prog.coerceIn(0f, 1f),
                    stageText = stage,
                    previewBitmap = preview ?: it.previewBitmap
                )
            }
        }
        // Drawing takes most of a minute: the status pill keeps changing so the wait feels alive
        // (the card already says what's happening; the pill is for Ronie's running commentary).
        val ticker = if (spec.name != "generate_image") null else viewModelScope.launch {
            val lines = DRAWING_LINES.shuffled()
            var i = 0
            while (true) {
                _state.update { it.copy(doing = lines[i % lines.size]) }
                i++
                kotlinx.coroutines.delay(2600)
            }
        }
        suspend fun runSafely(): com.raunakpatil.roniepocket.tools.Outcome = try {
            toolbox.run(spec, args, onProgress, onStageProgress)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {   // a tool bug must not take the app down
            Log.e("RonieTools", "${spec.name} threw", t)
            com.raunakpatil.roniepocket.tools.Outcome.Failed("That didn't work (${t.message?.take(80) ?: t.javaClass.simpleName}).")
        }
        var outcome = runSafely()
        if (outcome is com.raunakpatil.roniepocket.tools.Outcome.NeedsPermission) {
            val d = kotlinx.coroutines.CompletableDeferred<Boolean>().also { permissionWait = it }
            _state.update { it.copy(permissionAsk = (outcome as com.raunakpatil.roniepocket.tools.Outcome.NeedsPermission).permission) }
            outcome = if (d.await()) runSafely()
            else com.raunakpatil.roniepocket.tools.Outcome.Failed("Permission wasn't given (needed ${(outcome as com.raunakpatil.roniepocket.tools.Outcome.NeedsPermission).why}).")
        }
        ticker?.cancel()
        refreshToolState()
        _state.update { it.copy(doing = "Thinking…") }   // the model reads the result next
        return when (val o = outcome) {
            is com.raunakpatil.roniepocket.tools.Outcome.Done -> {
                o.undo?.let { undos[card.id] = it }
                updateCard(card.id) { it.copy(state = ToolCard.State.DONE, text = o.summary, canUndo = o.undo != null,
                    timerEndsAt = o.timer?.first, timerTotalMs = o.timer?.second ?: 0L, sources = o.sources,
                    image = o.image, pictures = o.pictures, progress = 1.0f) }
                if (spec.name == "stop_timer") reconcileTimers()
                o.forModel
            }
            is com.raunakpatil.roniepocket.tools.Outcome.Failed -> {
                updateCard(card.id) { it.copy(state = ToolCard.State.FAILED, text = "${it.text}: ${o.message}") }
                mapOf("error" to o.message)
            }
            is com.raunakpatil.roniepocket.tools.Outcome.NeedsPermission -> mapOf("error" to "Permission needed ${o.why}.")
        }
    }

    private fun sha1(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** A message typed before Ronie finished waking up: (text, placeholder reply id, attached images). */
    private data class PendingSend(val text: String, val botId: Long, val images: List<String> = emptyList())
    private var pendingSend: PendingSend? = null
    private var pendingVoice = false

    /** User accepted the step-down offer: switch to the lighter tier (one model per phone). */
    fun acceptStepDown() {
        val lighter = _state.value.stepDownOffer ?: return
        viewModelScope.launch {
            engine?.close(); engine = null
            ModelStore.saveTier(ctx, lighter)
            _state.update { it.copy(stepDownOffer = null, ready = false, messages = emptyList()) }
            history.clear()
            val ready = ModelStore.readyFile(ctx, lighter)
            if (ready != null) { ModelStore.deleteOthers(ctx, lighter); load(lighter, ready) } else startDownload(lighter)
        }
    }

    fun dismissStepDown() = _state.update { it.copy(stepDownOffer = null) }

    private var probe: Xray.Probe? = null

    /** Switches X-ray Mode: from the next reply, sentences are coloured by the probe. */
    fun toggleXray() {
        val e = engine ?: return
        val on = _state.value.xray ?: return
        _state.update { it.copy(xray = !on) }
        viewModelScope.launch { Xray.enable(e, if (!on) probe else null) }
    }

    /** Moves the Clean ↔ Sweary slider; takes effect from the next reply. */
    fun setTone(v: Float) {
        val t = tier ?: return
        val e = engine ?: return
        _state.update { it.copy(tone = v) }
        Tone.save(ctx, v)
        viewModelScope.launch { Tone.apply(t, e, v) }
    }

    fun send(text: String, images: List<String> = emptyList(), viaVoice: Boolean = false) {
        if ((text.isBlank() && images.isEmpty()) || _state.value.generating) return
        titleJob?.cancel()   // naming the chat never delays a reply
        reconcileTimers()   // a timer may have been stopped from its notification
        speaker?.stop()   // a new message interrupts whatever Ronie was saying
        val userMsg = UiMessage(nextId++, fromUser = true, text = text.trim(), images = images)
        val botId = nextId++
        val bot = UiMessage(botId, false, "", streaming = true, checklist = com.raunakpatil.roniepocket.cards.wantsChecklist(text))
        _state.update { it.copy(generating = true, messages = it.messages + userMsg + bot) }
        if (engine == null || !_state.value.ready) {
            pendingSend = PendingSend(text.trim(), botId, images)   // answered as soon as Ronie is awake
            pendingVoice = viaVoice
            return
        }
        generateReply(text.trim(), botId, images, viaVoice)
    }

    /**
     * Context budget (Phase 9): keeps at most the last 2 images as active visual inputs.
     * Older images in history are replaced by text: `[photo: <short description>]`
     * using the first sentence (<= 25 words) of Ronie's reply following that turn.
     */
    private fun pruneOldImagesInHistory() {
        val totalImages = history.sumOf { it.images.size }
        if (totalImages <= 2) return

        var imagesToPrune = totalImages - 2
        for (i in history.indices) {
            if (imagesToPrune <= 0) break
            val m = history[i]
            if (m.role == ChatMessage.Role.User && m.images.isNotEmpty()) {
                val keepCount = maxOf(0, m.images.size - imagesToPrune)
                val prunedCount = m.images.size - keepCount
                imagesToPrune -= prunedCount

                val reply = history.getOrNull(i + 1)?.takeIf { it.role == ChatMessage.Role.Assistant }?.content ?: ""
                val firstSentence = com.raunakpatil.roniepocket.tools.ToolSyntax.visible(reply).lineSequence()
                    .map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("```") }?.split(Regex("(?<=[.!?])\\s"))?.firstOrNull()?.trim()
                val shortDesc = firstSentence?.takeIf { it.isNotBlank() }?.split(Regex("\\s+"))?.take(25)?.joinToString(" ") ?: "an earlier photo"
                val photoTag = "[photo: $shortDesc]"

                val newContent = if (m.content.isBlank()) photoTag else "$photoTag\n${m.content}"
                val remainingImages = if (keepCount > 0) m.images.takeLast(keepCount) else emptyList()
                history[i] = m.copy(content = newContent, images = remainingImages)
            }
        }
    }

    /**
     * Writes Ronie's reply. With tools on, each step is: generate → if the model called a tool, check the
     * arguments (one repair retry with the error), pass the confirm gate, run it, feed the result back into
     * the same model turn, and let it continue. The user only sees the text and the tool cards.
     */
    private fun generateReply(text: String, botId: Long, images: List<String> = emptyList(), viaVoice: Boolean = false) {
        val e = engine ?: return
        val fmt = tier?.model?.format ?: return
        val userText = if (notes.isEmpty()) text
            else "(Since your last reply I undid: ${notes.joinToString("; ")}.)\n$text".also { notes.clear() }
        val recent = (history.takeLast(2).map { it.content } + text).joinToString(" ") { com.raunakpatil.roniepocket.tools.ToolSyntax.visible(it) }
        val remote = if (toolsOn) toolbox.route(text, recent) else emptyList()
        val known = if (toolsOn) toolbox.known(remote) else emptyMap()
        val TS = com.raunakpatil.roniepocket.tools.ToolSyntax
        genJob = viewModelScope.launch {
            // Built off the main thread: it embeds the message to pick the relevant memories (~15 ms).
            val memNote = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { memoryNote(text) }
            history += ChatMessage(ChatMessage.Role.User, memNote + userText, images = images)   // kept, so later turns see what Ronie knew
            pruneOldImagesInHistory()
            val allImages = history.filter { it.role == ChatMessage.Role.User }.flatMap { it.images }.map { File(it) }

            // Lazy-load vision mmproj on first turn with images
            if (allImages.isNotEmpty() && !e.visionLoaded) {
                val t = tier
                val mmprojFile = if (t != null) ModelStore.readyMmprojFile(ctx, t) else null
                if (mmprojFile != null && mmprojFile.exists()) {
                    _state.update { it.copy(doing = "Looking at your photo…") }
                    val loaded = e.loadVision(mmprojFile, maxImageTokens = 560)
                    Log.i("Ronie", "Vision mmproj loaded: $loaded from ${mmprojFile.path}")
                } else {
                    Log.w("Ronie", "Vision requested but mmproj file not found")
                }
            }
            if (allImages.isNotEmpty() && !e.visionLoaded) {
                // No eyes on this brain (or their file is missing): say so instead of failing silently.
                history[history.lastIndex] = history.last().copy(images = emptyList())
                history += ChatMessage(ChatMessage.Role.Assistant, NO_VISION)
                updateBot(botId, NO_VISION, streaming = false)
                _state.update { it.copy(generating = false, doing = null) }
                saveChat()
                return@launch
            }

            val base = fmt.format(listOf(ChatMessage(ChatMessage.Role.System, systemText(remote))) + history.toList())
            val turn = StringBuilder()    // the raw model turn: text, tool calls and results (kept in history)
            val shown = StringBuilder()   // what the user sees from finished steps
            var xray = if (_state.value.xray == true) Xray.Collector() else null
            // Speak if "always reply aloud" is on, or the user used the mic and "reply aloud to voice" is on.
            val voice = speaker?.takeIf { _state.value.speak || (viaVoice && _state.value.speakOnVoice) }
            var spoken = 0
            var stopped = false
            var afterResult = false
            var repairs = 0
            _state.update { it.copy(doing = "Thinking…") }
            fun show(current: String) {
                val vis = shown.toString() + current
                if (vis.length > spoken) {
                    voice?.feed(vis.substring(spoken)); spoken = vis.length
                    if (_state.value.doing != null) _state.update { it.copy(doing = null) }   // text is showing: no pill
                }
                updateBot(botId, vis, streaming = true, xray = xray?.segments())
            }
            warmJob?.join()   // a reply sent right after launch waits for the GPU warm-up
            var prefill = ""
            var retriedRefusal = false
            try {
                for (step in 0 until MAX_TOOL_STEPS) {
                    val activeEngine = engine ?: break
                    var engineError: String? = null
                    // After a refusal the answer is started for the model ("Sure. "), which it then continues.
                    val usePrefill = prefill; prefill = ""
                    val out = StringBuilder(usePrefill)
                    val guard = com.raunakpatil.roniepocket.engine.LoopGuard()
                    activeEngine.generate(base + turn + usePrefill, maxTokens = 512, images = allImages).collect { ev ->
                        when (ev) {
                            is GenEvent.Text -> {
                                out.append(ev.text)
                                xray?.add(ev.text, ev.xray)
                                if (guard.isLooping(out.toString())) {
                                    // Stop a runaway repetition and keep only the first copy.
                                    val keep = guard.trimmed(out.toString())
                                    out.setLength(0); out.append(keep)
                                    xray = null
                                    voice?.stop(); stopped = true
                                    Log.i("Ronie", "loop guard stopped a repeating reply")
                                    genJob?.cancel()
                                }
                                // A bare call ("list_tasks{…}") looks like text at first: hold it back until it's clear.
                                if (known.isEmpty() || !TS.maybeBareCall(out.toString(), known.keys)) show(TS.visible(out.toString()))
                            }
                            is GenEvent.Done -> {
                                engineError = runCatching { JSONObject(ev.statsJson).optString("error") }.getOrDefault("").ifBlank { null }
                                _state.update { it.copy(lastStats = summarize(ev.statsJson)) }; saveGpuCacheSoon()
                            }
                        }
                    }
                    val raw = out.toString()
                    engineError?.let { err ->   // the engine refused the prompt: explain instead of showing "…"
                        Log.w("Ronie", "engine error: $err")
                        val line = if (err.contains("too long")) "This chat has got too long for me to keep in my head. Start a new chat and ask me again."
                            else "Something went wrong inside my brain ($err). Try again."
                        shown.append(line); show("")
                    }
                    if (engineError != null) break
                    val call = if (known.isEmpty()) null else TS.parseCalls(raw, known).firstOrNull()
                    if (call == null) {
                        val imgTool = toolbox.find("generate_image")
                        val isRefusal = raw.length < 700 && isModelRefusal(raw)
                        val lastWasDrawing = history.dropLast(1).lastOrNull { it.role == ChatMessage.Role.Assistant }?.content?.contains("generate_image") == true
                        // "Change this picture: …" (the Edit button) on a brain that didn't call the tool itself.
                        val isEdit = lastWasDrawing && Regex("^change (?:this|the) picture\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())
                        val isImageReq = images.isEmpty() && (isEdit || isDirectImageRequest(text) || (isRefusal && looksLikeImageRequest(text)))
                        if (imgTool != null && step == 0 && isImageReq) {
                            Log.i("Ronie", "Direct image request (refusal=$isRefusal, edit=$isEdit); routing to generate_image")
                            val imgArgs: Map<String, Any?> = if (isEdit) mapOf("prompt" to text.substringAfter(":", text).trim().ifEmpty { text }, "edit_last" to true)
                                else mapOf("prompt" to extractImagePrompt(text))
                            out.setLength(0)
                            shown.setLength(0)
                            show("")
                            val imgResult = runTool(botId, imgTool, imgArgs, afterResult)
                            turn.append(TS.call(imgTool.name, imgArgs)).append(TS.response(imgTool.name, imgResult))
                            break
                        }
                        // Ronie is uncensored: a refusal of something that isn't one of the hard limits gets one more go.
                        // The hard limits (minors, weapons, hurting a real person) are checked in code and never retried.
                        if (isRefusal && step == 0 && !retriedRefusal && !isHardLimit(text)) {
                            Log.i("Ronie", "refusal on an allowed topic; retrying once with a started answer")
                            retriedRefusal = true
                            prefill = "Sure. "
                            show("")
                            continue
                        }
                        turn.append(raw); shown.append(TS.visible(raw)); show(""); break
                    }

                    val before = if (call.repaired) "" else raw.substringBefore("<|tool_call>")
                    turn.append(before); shown.append(TS.visible(before))
                    xray = null   // the probe scores text, not tool calls
                    val spec = toolbox.find(call.name)?.takeIf { it.name in known }
                    val checked = if (spec != null && call.args != null) com.raunakpatil.roniepocket.tools.ArgCheck.check(spec, call.args) else null
                    val result: Any? = when {
                        spec == null -> { repairs++; mapOf("error" to "There is no tool called ${call.name}.") }
                        checked == null -> { repairs++; mapOf("error" to "Couldn't read the arguments (${call.error}). Call the tool again with valid arguments.") }
                        checked.errors.isNotEmpty() -> { repairs++; mapOf("error" to "Invalid arguments: ${checked.errors.joinToString("; ")}. Call the tool again with fixed arguments.") }
                        else -> runTool(botId, spec, checked.args, afterResult)
                    }
                    Log.i("RonieTools", "step $step: ${call.name}${if (call.repaired) " (repaired)" else ""} -> ${result.toString().take(200)}")
                    turn.append(TS.call(call.name, checked?.args ?: call.args ?: emptyMap())).append(TS.response(call.name, result))
                    afterResult = true
                    if (call.name == "generate_image") break
                    // Declined: the turn ends here with a fixed line. Letting the model write it was tested on the
                    // phone and it said "Note sent." after the user tapped Cancel.
                    if (result === DECLINED) {
                        val line = "Okay, I didn't do that. Nothing was sent or changed."
                        turn.append(line); shown.append(line); show(""); break
                    }
                    if (repairs > 1) { shown.append("Sorry, I couldn't get that tool call right."); show(""); break }
                }
            } finally {
                if (!stopped) voice?.finish()
                history += ChatMessage(ChatMessage.Role.Assistant, turn.toString().trim())
                // A confirmation still waiting when the user taps Stop counts as "no".
                _state.update { s -> s.copy(messages = s.messages.map { m ->
                    if (m.id != botId) m else m.copy(tools = m.tools.map {
                        if (it.state == com.raunakpatil.roniepocket.tools.ToolCard.State.ASK) it.copy(state = com.raunakpatil.roniepocket.tools.ToolCard.State.DECLINED) else it })
                }) }
                confirms.clear()
                val finalText = shown.toString().trim()
                val hasCards = _state.value.messages.firstOrNull { it.id == botId }?.tools?.isNotEmpty() == true
                updateBot(botId, if (finalText.isEmpty() && !hasCards) "…" else finalText, streaming = false, xray = xray?.segments())
                xray?.segments()?.forEach { seg ->   // raw scores, to compare the phone with the PC probe
                    Log.i("RonieXray", "score=${seg.score?.let { "%.2f".format(it) } ?: "-"} p=${seg.score?.let { "%.2f".format(Xray.probability(it)) } ?: "-"} | ${seg.text.trim().take(80)}")
                }
                _state.update { it.copy(generating = false, doing = null) }
                saveChat()
                if (!stopped && turn.isNotEmpty()) retitle()
            }
        }
    }

    private companion object {
        /** Tool calls per reply (plus the final answer). */
        const val MAX_TOOL_STEPS = 4
        /** Ronie's running commentary on the status pill while a picture is being drawn. */
        val DRAWING_LINES = listOf(
            "Sharpening imaginary pencils…", "Mixing colours that don't exist yet…", "Arguing with pixels…", "Teaching noise to behave…",
            "Asking the GPU nicely…", "Adding a little drama to the lighting…", "Squinting at it artistically…", "Hold still, masterpiece loading…",
            "Bribing the brushstrokes…", "Painting with maths…", "Making it less potato…", "Giving it that chef's-kiss finish…",
            "Channelling my inner Picasso…", "No peeking… okay, peek a little…", "Colouring inside the lines, mostly…", "Polishing every pixel by hand…",
            "Convincing chaos to become art…", "Warming up the tiny art studio in your phone…", "Stepping back to admire it…", "Adding details you'll zoom in on…",
            "Almost there, don't rush genius…", "Blending, smudging, pretending I meant that…", "Summoning the muse…", "Rendering vibes…",
        )
        const val NO_VISION = "I can't look at photos yet: the part of my brain that sees isn't on this phone. It comes with the Full brain download (Settings → Models)."
        /** What the model's history records when the user taps Cancel on a confirmation card. */
        val DECLINED: Map<String, Any?> = mapOf("error" to "The user tapped Cancel, so nothing was done.")
    }

    fun stop() { genJob?.cancel(); speaker?.stop() }

    /**
     * The "Again" button on a picture: draws the same prompt with a new seed. It goes straight to the drawing
     * tool; sending it through the chat brain first cost ~10 s to rewrite a prompt we already have.
     */
    fun redraw(prompt: String) {
        val spec = toolbox.find("generate_image") ?: return
        if (prompt.isBlank() || _state.value.generating || !_state.value.ready) return
        titleJob?.cancel(); speaker?.stop()
        val TS = com.raunakpatil.roniepocket.tools.ToolSyntax
        val userMsg = UiMessage(nextId++, fromUser = true, text = "Draw that again")
        val botId = nextId++
        _state.update { it.copy(generating = true, messages = it.messages + userMsg + UiMessage(botId, false, "", streaming = true)) }
        genJob = viewModelScope.launch {
            warmJob?.join()
            val args = mapOf<String, Any?>("prompt" to prompt)
            var result: Any? = null
            try {
                result = runTool(botId, spec, args, afterToolResult = false)
            } finally {
                history += ChatMessage(ChatMessage.Role.User, "Draw that again")
                history += ChatMessage(ChatMessage.Role.Assistant, TS.call(spec.name, args) + TS.response(spec.name, result ?: mapOf("error" to "stopped")))
                updateBot(botId, "", streaming = false)
                _state.update { it.copy(generating = false, doing = null) }
                saveChat()
            }
        }
    }

    // ------------------------------------------------------------------ saved chats (sidebar)

    private fun saveChat() {
        val s = _state.value
        if (s.messages.isEmpty()) return
        runCatching { chatStore.save(s.chatId, s.messages, history.toList()) }
            .onFailure { Log.e("Ronie", "couldn't save chat", it) }
        _state.update { it.copy(chats = chatStore.list()) }
    }

    private var titleJob: Job? = null

    /**
     * Gives the chat a short summary title (the sidebar name), re-done after every reply so it follows the
     * conversation. Runs while the user reads; a new message cancels it at once. It reuses the chat already
     * in the model's cache, so it costs only the question and a few words.
     */
    private fun retitle() {
        if (!_state.value.ready) return
        val e = engine ?: return
        val fmt = tier?.model?.format ?: return
        val id = _state.value.chatId
        if (history.size < 2) return
        val msgs = listOf(ChatMessage(ChatMessage.Role.System, systemText())) + history +
            ChatMessage(ChatMessage.Role.User, "Name this conversation in 2 to 5 words, like a chat title in a list. " +
                "Reply with only the title: no quotes, no emoji, no full stop.")
        titleJob?.cancel()
        titleJob = viewModelScope.launch {
            val out = StringBuilder()
            runCatching { e.generate(fmt.format(msgs), maxTokens = 16).collect { ev -> if (ev is GenEvent.Text) out.append(ev.text) } }
            val title = com.raunakpatil.roniepocket.tools.ToolSyntax.visible(out.toString()).lineSequence().firstOrNull { it.isNotBlank() }
                ?.replace(Regex("""[*#"“”`]"""), "")?.removePrefix("Title:")?.trim()?.trimEnd('.', '!')?.take(48) ?: return@launch
            if (title.length < 2 || _state.value.chatId != id) return@launch
            Log.i("Ronie", "chat title: $title")
            chatStore.rename(id, title)
            _state.update { it.copy(chats = chatStore.list()) }
        }
    }

    fun newChat() {
        stop()
        saveChat()
        history.clear()
        _state.update { it.copy(messages = emptyList(), chatId = System.currentTimeMillis()) }
        // Nothing to recompute: the next prompt shares the persona prefix and the engine trims the old chat.
    }

    fun openChat(id: Long) {
        if (id == _state.value.chatId) return
        stop()
        saveChat()
        val c = chatStore.load(id) ?: return
        history.clear(); history += c.history
        nextId = (c.messages.maxOfOrNull { it.id } ?: 0L) + 1
        nextCardId = maxOf(nextCardId, (c.messages.flatMap { it.tools }.maxOfOrNull { it.id } ?: 0L) + 1)
        _state.update { it.copy(messages = c.messages, chatId = id) }
    }

    fun deleteChat(id: Long) {
        chatStore.delete(id)
        if (id == _state.value.chatId) {
            stop(); history.clear()
            _state.update { it.copy(messages = emptyList(), chatId = System.currentTimeMillis()) }
        }
        _state.update { it.copy(chats = chatStore.list()) }
    }

    private fun updateBot(id: Long, text: String, streaming: Boolean, xray: List<Xray.Segment>? = null) = _state.update { s ->
        s.copy(messages = s.messages.map { if (it.id == id) it.copy(text = text, streaming = streaming, xray = xray) else it })
    }

    private fun summarize(json: String): String = runCatching {
        val o = JSONObject(json)
        if (o.has("error")) return "error: ${o.getString("error")}"
        "first word ${o.getDouble("first_token_ms").toInt()} ms · ${"%.1f".format(o.getDouble("tok_per_s"))} tok/s · " +
            "${o.getInt("prompt_new_tokens")}/${o.getInt("prompt_tokens")} prompt tokens computed" +
            if (o.getBoolean("reused_prefix")) " (cache reused)" else ""
    }.getOrDefault(json)

    /** Figures of speech and other uses of "draw/paint/sketch" that aren't a request for a picture. */
    private val notADrawing = Regex(
        """^(?:draw|paint|sketch)\s+(?:me\s+)?(?:a\s+|an\s+|the\s+|up\s+|out\s+)?(?:conclusion|conclusions|line\b|comparison|parallel|distinction|inference|attention|lesson|inspiration|blank|on\b|upon\b|from\b|up\b|out\b|back\b|near|closer|straws|lots|a\s+bath|the\s+curtains|plan|outline|picture of (?:how|what|why)|the\s+(?:wall|walls|fence|room|house|door|ceiling))""",
        RegexOption.IGNORE_CASE)

    private fun isDirectImageRequest(text: String): Boolean {
        val clean = text.trim().lowercase()
        if (clean.endsWith("?") && !clean.startsWith("can you") && !clean.startsWith("could you")) {
            if (Regex("^(?:how|what|why|when|where|who|is|are|do|does|should)\\b").containsMatchIn(clean)) return false
        }
        if (notADrawing.containsMatchIn(clean)) return false
        // "again" / "another" only mean a picture right after one was drawn.
        val lastWasDrawing = history.lastOrNull { it.role == ChatMessage.Role.Assistant }?.content?.contains("generate_image") == true
        if (!lastWasDrawing && Regex("^(?:draw again|draw another|generate another|another (?:picture|drawing|version))").containsMatchIn(clean)) return false
        val drawVerbs = listOf(
            "show me a picture of", "show me an image of", "show me a drawing of", "give me a picture of", "give me an image of",
            "make me a picture", "make me an image", "make a drawing", "create a drawing", "create a picture", "make an illustration", "create an illustration",
            "draw ", "draw me ", "paint ", "paint me ", "sketch ", "sketch me ",
            "draw again", "draw another", "generate another", "another picture", "another drawing",
            "generate an image", "generate image", "create an image", "create image",
            "make an image", "make a picture", "generate a picture", "draw a picture"
        )
        return drawVerbs.any { clean.startsWith(it) } ||
               clean.contains("generate an image of", ignoreCase = true) ||
               clean.contains("draw an image of", ignoreCase = true) ||
               clean.contains("paint an image of", ignoreCase = true) ||
               (lastWasDrawing && (clean.contains("draw again") || clean.startsWith("another version")))
    }

    private fun extractImagePrompt(text: String): String {
        val t = text.trim().replace(Regex("^(?:hey ronie[,!]?\\s*|ronie[,!]?\\s*|please\\s+|can you\\s+|could you\\s+|will you\\s+)+", RegexOption.IGNORE_CASE), "").trimEnd('?', ' ')
        val prefixes = listOf(
            "show me a picture of ", "show me an image of ", "show me a drawing of ", "give me a picture of ", "give me an image of ",
            "make me a picture of ", "make me an image of ", "make a drawing of ", "create a drawing of ", "create a picture of ",
            "make an illustration of ", "create an illustration of ",
            "draw again: ", "draw again ", "draw another version of: ", "draw another version of ",
            "draw another: ", "draw another ", "generate another: ", "generate another ",
            "generate an image of ", "generate an image ", "generate image of ", "generate image ",
            "create an image of ", "create an image ", "create image of ", "create image ",
            "make an image of ", "make an image ", "make a picture of ", "make a picture ",
            "draw an image of ", "draw a picture of ", "draw me a picture of ", "draw me an image of ",
            "draw me ", "draw ", "paint me a picture of ", "paint me ", "paint ",
            "sketch me ", "sketch "
        )
        for (p in prefixes) {
            if (t.startsWith(p, ignoreCase = true)) {
                return t.substring(p.length).trim()
            }
        }
        return t
    }

    private fun isModelRefusal(raw: String): Boolean {
        val lower = raw.lowercase()
        val refusalPatterns = listOf(
            "i can't help with", "i cannot help with", "i can't assist", "i cannot assist", "i'm not able to help", "i am not able to help",
            "i'm unable to", "i am unable to", "i can't provide", "i cannot provide", "i can't write", "i cannot write", "i can't create",
            "i cannot create", "i won't be able to", "i'm not comfortable", "i am not comfortable", "not appropriate", "inappropriate content",
            "as an ai", "as a language model", "i'm sorry, but i", "i am sorry, but i", "sorry, but i can't", "sorry, i can't", "i apologize, but",
            "content policy", "my guidelines", "against my guidelines", "explicit content", "i must decline", "i have to decline",
            "cannot fulfill", "can't fulfill", "unable to fulfill",
            "safety guideline", "safety policy", "against my programming",
            "strictly prohibit", "rules are firm", "can't generate",
            "cannot generate", "unable to generate", "violates my",
            "i cannot create nude", "i can't create nude", "i cannot generate nude",
            "i can't generate nude", "i am unable to create nude", "i am unable to generate nude"
        )
        return refusalPatterns.any { lower.contains(it) }
    }

    /** A request for a picture in any wording (used only when the model refused, to send it to the drawing tool). */
    private fun looksLikeImageRequest(text: String): Boolean {
        val l = text.lowercase()
        return Regex("\\b(?:draw|paint|sketch|generate|create|make|show me|give me|design|render|illustrate)\\b").containsMatchIn(l) &&
            Regex("\\b(?:picture|image|photo|pic|portrait|illustration|artwork|drawing|painting|sketch|render|wallpaper|poster)\\b").containsMatchIn(l) ||
            Regex("^(?:draw|paint|sketch|illustrate)\\b").containsMatchIn(l.trim())
    }

    /**
     * The things Ronie never does, checked in code so a retry can't talk the model into them: anything sexual
     * involving minors, and instructions for weapons or for hurting a real person.
     */
    private fun isHardLimit(text: String): Boolean {
        if (toolbox.builtins.minorsBlocked(text)) return true
        val l = text.lowercase()
        return Regex("\\b(?:bomb|explosives?|detonator|ied|pipe bomb|nerve agent|sarin|ricin|anthrax|bioweapon|chemical weapon|napalm|thermite|ghost gun|" +
            "untraceable (?:gun|firearm)|poison(?:ing)? (?:someone|him|her|my|a person|them)|" +
            "(?:kill|murder|hurt|harm|stab|shoot|strangle|drug|stalk|kidnap) (?:someone|somebody|him|her|them|my \\w+|a person|a man|a woman|a child))\\b").containsMatchIn(l)
    }

    override fun onCleared() {
        listener?.release(); speaker?.stop()
        val e = engine
        engine = null
        if (e != null) viewModelScope.launch { e.close() }
    }
}
