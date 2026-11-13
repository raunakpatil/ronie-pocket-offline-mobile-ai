package com.raunakpatil.roniepocket.engine

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** One streamed event from [LlamaEngine.generate]. */
sealed interface GenEvent {
    /** [xray]: X-ray probe score (logit, > 0 = grounded) of the latest token, NaN when X-ray is off. */
    data class Text(val text: String, val xray: Float = Float.NaN) : GenEvent
    data class Done(val statsJson: String) : GenEvent
}

/** Sampling settings. Defaults apply a light presence penalty to eliminate loops. */
data class SamplerConfig(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.90f,
    val minP: Float = 0.05f,
    val presencePenalty: Float = 0.2f,
    val seed: Int = -1,
)

/**
 * Kotlin wrapper over the llama.cpp JNI engine. All native calls run on one dedicated thread,
 * because a llama context must not be used from several threads at once.
 */
class LlamaEngine private constructor(private val handle: Long, private val dispatcher: CoroutineDispatcher) {

    fun interface ByteSink { fun onBytes(bytes: ByteArray, xray: Float): Boolean }

    val mediaMarker: String get() = Companion.mediaMarker

    var visionLoaded: Boolean = false
        private set

    /** Streams the reply to a fully formatted prompt (see [PromptFormat]). Cancel the flow to stop. */
    fun generate(prompt: String, maxTokens: Int = 512, images: List<File> = emptyList()): Flow<GenEvent> = callbackFlow {
        // The native loop asks after every chunk whether to continue; it stops once the
        // collector cancels (e.g. the user taps Stop) and this producer scope becomes inactive.
        val paths = images.map { it.absolutePath }.toTypedArray()
        val stats = if (paths.isEmpty()) {
            nativeGenerate(handle, prompt, maxTokens) { bytes, xray ->
                trySend(GenEvent.Text(String(bytes, Charsets.UTF_8), xray))
                isActive && !isClosedForSend
            }
        } else {
            nativeGenerateMM(handle, prompt, paths, maxTokens) { bytes, xray ->
                trySend(GenEvent.Text(String(bytes, Charsets.UTF_8), xray))
                isActive && !isClosedForSend
            }
        }
        trySend(GenEvent.Done(stats))
        close()
        awaitClose { }
    }.flowOn(dispatcher)

    /**
     * Loads mmproj vision projection model. Lazy: loaded before the first turn with images.
     */
    suspend fun loadVision(path: File, maxImageTokens: Int = 560): Boolean = withContext(dispatcher) {
        if (!path.exists()) return@withContext false
        val ok = nativeLoadVision(handle, path.absolutePath, true, maxImageTokens)
        visionLoaded = ok
        ok
    }

    /** Frees vision projection context to reclaim memory. */
    suspend fun freeVision() = withContext(dispatcher) {
        nativeFreeVision(handle)
        visionLoaded = false
    }

    suspend fun setSampler(c: SamplerConfig) = withContext(dispatcher) {
        nativeSetSampler(handle, c.temperature, c.topK, c.topP, c.minP, c.presencePenalty, c.seed)
    }

    /**
     * Processes [text] (e.g. the formatted system prompt) ahead of time so later prompts that start
     * with it only compute the new part. Also warms up GPU pipelines. Returns milliseconds taken.
     */
    suspend fun prefill(text: String): Double = withContext(dispatcher) { nativePrefill(handle, text) }

    /** Saves the current cached state (e.g. after [prefill]) to [path]. Returns bytes written, 0 on failure. */
    suspend fun saveState(path: String): Long = withContext(dispatcher) { nativeSaveState(handle, path) }

    /**
     * Restores a state saved with [saveState] if it was built from exactly [expectedText]'s tokens.
     * Returns milliseconds taken, or a negative number on a miss (missing/stale file).
     */
    suspend fun loadState(path: String, expectedText: String): Double =
        withContext(dispatcher) { nativeLoadState(handle, path, expectedText) }

    /** Forget the conversation state (e.g. new chat). */
    suspend fun reset() = withContext(dispatcher) { nativeReset(handle) }

    /** Loads a steering direction (control-vector GGUF). Returns the number of layers loaded, or -1. */
    suspend fun loadSteering(path: String): Int = withContext(dispatcher) { nativeLoadSteering(handle, path) }

    /**
     * Sets the loaded direction's strength on layers [layerStart, layerEnd]; 0 turns it off.
     * Only tokens processed afterwards are steered, so the cached persona prefix stays valid.
     */
    suspend fun setSteering(strength: Float, layerStart: Int, layerEnd: Int): Boolean =
        withContext(dispatcher) { nativeSetSteering(handle, strength, layerStart, layerEnd) }

    /** Installs the X-ray probe (a linear read-out of one layer), or switches it off with null weights. */
    suspend fun setProbe(layer: Int, weights: FloatArray?, bias: Float): Boolean =
        withContext(dispatcher) { nativeSetProbe(handle, layer, weights, bias) }

    suspend fun close() = withContext(dispatcher) { nativeFree(handle) }

    @OptIn(ExperimentalCoroutinesApi::class)
    companion object {
        private val engineThread: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

        init {
            try {
                System.loadLibrary("ronie_engine")
            } catch (t: Throwable) {
                // Ignored in host JVM unit tests
            }
        }

        val mediaMarker: String by lazy {
            try {
                nativeMediaMarker()
            } catch (t: Throwable) {
                "<__media__>"
            }
        }

        /** Loads backends from [nativeLibDir] (ApplicationInfo.nativeLibraryDir). Returns a device summary. */
        fun init(nativeLibDir: String): String = nativeInit(nativeLibDir)

        /** Keeps compiled GPU shader pipelines in [path] across launches. Call before [init]. */
        fun setGpuCache(path: String) = nativeSetGpuCache(path)

        /** Writes the GPU pipeline cache (after warm-up / first replies, when new pipelines were built). */
        suspend fun saveGpuCache(): Boolean = withContext(engineThread) { nativeSaveGpuCache() }

        /**
         * Loads a GGUF model. [gpuLayers]: 0 = CPU only, 99 = everything on the GPU (Vulkan).
         * Returns null if loading failed (see logcat tag RonieEngine).
         */
        suspend fun load(path: String, gpuLayers: Int, contextSize: Int = 8192, threads: Int = 4): LlamaEngine? =
            withContext(engineThread) {
                val h = nativeLoad(path, gpuLayers, contextSize, threads)
                if (h == 0L) null else LlamaEngine(h, engineThread).also { it.nativeSetSamplerDefault() }
            }

        @JvmStatic private external fun nativeInit(libDir: String): String
        @JvmStatic private external fun nativeSetGpuCache(path: String)
        @JvmStatic private external fun nativeSaveGpuCache(): Boolean
        @JvmStatic private external fun nativeLoad(path: String, gpuLayers: Int, nCtx: Int, nThreads: Int): Long
        @JvmStatic private external fun nativeSetSampler(h: Long, temp: Float, topK: Int, topP: Float, minP: Float, presence: Float, seed: Int)
        @JvmStatic private external fun nativeGenerate(h: Long, prompt: String, maxTokens: Int, sink: ByteSink): String
        @JvmStatic private external fun nativePrefill(h: Long, text: String): Double
        @JvmStatic private external fun nativeSaveState(h: Long, path: String): Long
        @JvmStatic private external fun nativeLoadState(h: Long, path: String, expectedText: String): Double
        @JvmStatic private external fun nativeReset(h: Long)
        @JvmStatic private external fun nativeLoadSteering(h: Long, path: String): Int
        @JvmStatic private external fun nativeSetSteering(h: Long, strength: Float, layerStart: Int, layerEnd: Int): Boolean
        @JvmStatic private external fun nativeSetProbe(h: Long, layer: Int, weights: FloatArray?, bias: Float): Boolean
        @JvmStatic private external fun nativeLoadVision(h: Long, mmprojPath: String, useGpu: Boolean, maxImageTokens: Int): Boolean
        @JvmStatic private external fun nativeFreeVision(h: Long)
        @JvmStatic private external fun nativeMediaMarker(): String
        @JvmStatic private external fun nativeGenerateMM(h: Long, prompt: String, imagePaths: Array<String>, maxTokens: Int, sink: ByteSink): String
        @JvmStatic private external fun nativeFree(h: Long)
    }

    private fun nativeSetSamplerDefault() {
        val c = SamplerConfig()
        nativeSetSampler(handle, c.temperature, c.topK, c.topP, c.minP, c.presencePenalty, c.seed)
    }
}
