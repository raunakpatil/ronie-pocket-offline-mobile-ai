package com.raunakpatil.roniepocket.imagegen

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared coordinator mutex ensuring heavy on-device neural workloads (LLM decode vs Image generation)
 * take turns and never compete for GPU/RAM simultaneously.
 * Supports lifecycle callbacks to safely evict and restore large model contexts across workload boundaries.
 */
object HeavyWork {
    private const val TAG = "HeavyWork"

    init {
        try {
            System.loadLibrary("ronie_imagegen")
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "Native ronie_imagegen not loaded: ${t.message}") }   // Log isn't there in JVM unit tests
        }
    }

    @JvmStatic
    private external fun nativeTrimMemory()

    fun trim() {
        try {
            nativeTrimMemory()
        } catch (t: Throwable) {
            // fallback
        }
        System.gc()
    }

    val mutex = Mutex()
    var onAcquire: (suspend () -> Unit)? = null
    var onRelease: (suspend () -> Unit)? = null

    suspend inline fun <T> withLock(crossinline block: suspend () -> T): T {
        return mutex.withLock {
            try {
                onAcquire?.invoke()
            } catch (t: Throwable) {
                // ignore
            }
            try {
                block()
            } finally {
                trim()
                try {
                    onRelease?.invoke()
                } catch (t: Throwable) {
                    // ignore
                }
            }
        }
    }
}
