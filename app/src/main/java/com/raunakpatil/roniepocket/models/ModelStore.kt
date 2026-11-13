package com.raunakpatil.roniepocket.models

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/** Where the one model lives, which tier was chosen, and the background download. */
object ModelStore {
    private const val PREFS = "ronie_model"
    private const val KEY_TIER = "tier"
    private const val KEY_DRAW_TIER = "draw_tier"
    private const val KEY_DL_WIFI = "dl_wifi_only"
    private const val KEY_DL_DRAW = "dl_drawing"
    private const val WORK = "model-download"

    fun dir(c: Context): File = File(c.filesDir, "models").apply { mkdirs() }

    fun savedTier(c: Context): Tier? =
        c.getSharedPreferences(PREFS, 0).getString(KEY_TIER, null)?.let { runCatching { Tier.valueOf(it) }.getOrNull() }

    fun saveTier(c: Context, t: Tier) = c.getSharedPreferences(PREFS, 0).edit().putString(KEY_TIER, t.name).apply()

    /**
     * The ready model file for [t], or null. Debug builds also accept a copy pushed over adb into
     * the app's external files dir, so developers don't re-download multi-GB files.
     */
    fun readyFile(c: Context, t: Tier): File? {
        val internal = File(dir(c), t.model.file)
        if (internal.exists() && internal.length() == t.model.sizeBytes) return internal
        val dev = c.getExternalFilesDir("models")?.let { File(it, t.model.file) }
        if (dev != null && dev.exists() && dev.length() == t.model.sizeBytes &&
            (c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) return dev
        return null
    }

    /**
     * The ready mmproj projection model file for [t], or null if this tier does not use vision
     * or the file has not been downloaded yet. Debug builds accept copies pushed over adb.
     */
    fun readyMmprojFile(c: Context, t: Tier): File? {
        val spec = t.model.mmproj ?: return null
        val internal = File(dir(c), spec.file)
        if (internal.exists() && internal.length() == spec.sizeBytes) return internal
        val dev = c.getExternalFilesDir("models")?.let { File(it, spec.file) }
        if (dev != null && dev.exists() && dev.length() == spec.sizeBytes &&
            (c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) return dev
        // In debug builds, also accept quantized Q8_0 mmproj variant if present
        val devQ8 = c.getExternalFilesDir("models")?.let { File(it, "mmproj-gemma-4-E2B-it-Q8_0.gguf") }
        if (devQ8 != null && devQ8.exists() &&
            (c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) return devQ8
        return null
    }

    /** Removes model files of other tiers (one model per phone). */
    fun deleteOthers(c: Context, keep: Tier) {
        val keepFiles = setOfNotNull(keep.model.file, keep.model.mmproj?.file)
        dir(c).listFiles()?.forEach { f ->
            if (keepFiles.none { f.name.startsWith(it) }) f.delete()
        }
    }

    fun drawDir(c: Context): File = File(c.filesDir, "models/draw").apply { mkdirs() }
    fun drawSanaDir(c: Context): File = File(c.filesDir, "models/draw/sana").apply { mkdirs() }

    fun savedDrawTier(c: Context): ImageGenTier {
        val saved = c.getSharedPreferences(PREFS, 0).getString(KEY_DRAW_TIER, null)
        if (saved != null) {
            runCatching { return ImageGenTier.valueOf(saved) }
        }
        return ImageGenTier.SD_TURBO   // the only one the app can download today
    }

    fun saveDrawTier(c: Context, t: ImageGenTier) =
        c.getSharedPreferences(PREFS, 0).edit().putString(KEY_DRAW_TIER, t.name).apply()

    /** The folders a drawing model may live in: the app's own storage, and (for copies pushed over USB) its external files. */
    private fun drawDirs(c: Context, t: ImageGenTier): List<File> =
        listOfNotNull(File(c.filesDir, "models/draw"), c.getExternalFilesDir(null)?.let { File(it, "models/draw") })
            .map { if (t.folder.isEmpty()) it else File(it, t.folder) }

    /** Is [t] fully on the phone? Half-downloaded ".part" files don't count. Defaults to the active model. */
    fun isDrawingInstalled(c: Context, t: ImageGenTier = savedDrawTier(c)): Boolean = drawDirs(c, t).any { dir ->
        when (t) {
            ImageGenTier.SD_TURBO -> File(dir, "sd_turbo_q4_0.gguf").let { it.isFile && it.length() > 0 }
            else -> dir.listFiles()?.map { it.name.lowercase() }?.let { names ->
                names.any { it.endsWith(".onnx") && "dit" in it } && names.any { it.endsWith(".onnx") && "vae" in it } } == true
        }
    }

    /** Deletes [t]'s files (and any half-finished download of it). */
    fun removeDrawing(c: Context, t: ImageGenTier = savedDrawTier(c)) {
        drawDirs(c, t).forEach { dir ->
            if (t.folder.isNotEmpty()) dir.deleteRecursively()
            else dir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }   // keep sub-folders (other models)
        }
    }

    /**
     * Starts (or restarts) the download. The choices are remembered, so [ensureDownload] can pick the same
     * download up again after the app was closed without losing "use mobile data" or the drawing model.
     */
    fun startDownload(c: Context, t: Tier, wifiOnly: Boolean = true, downloadDrawing: Boolean = false, imageTier: ImageGenTier = ImageGenTier.SD_TURBO,
                      policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        saveTier(c, t)
        if (downloadDrawing) saveDrawTier(c, imageTier)
        c.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_DL_WIFI, wifiOnly).putBoolean(KEY_DL_DRAW, downloadDrawing).apply()
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_TIER to t.name,
                ModelDownloadWorker.KEY_DOWNLOAD_DRAW to downloadDrawing,
                ModelDownloadWorker.KEY_DRAW_TIER to imageTier.name
            ))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build())
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK, policy, req)
    }

    /** Makes sure the download chosen earlier is queued or running, without restarting one that already is. */
    fun ensureDownload(c: Context, t: Tier) {
        val p = c.getSharedPreferences(PREFS, 0)
        startDownload(c, t, p.getBoolean(KEY_DL_WIFI, true), p.getBoolean(KEY_DL_DRAW, false), savedDrawTier(c), ExistingWorkPolicy.KEEP)
    }

    /** Downloads only the drawing model (the brain is already there, so the worker skips it). */
    fun downloadDrawing(c: Context, t: Tier, wifiOnly: Boolean, draw: ImageGenTier = savedDrawTier(c)) =
        startDownload(c, t, wifiOnly, downloadDrawing = true, imageTier = draw)

    fun cancelDownload(c: Context) = WorkManager.getInstance(c).cancelUniqueWork(WORK)

    sealed interface Progress {
        data object Idle : Progress
        data class Waiting(val reason: String) : Progress
        data class Running(val done: Long, val total: Long, val verifying: Boolean) : Progress
        data object Done : Progress
        data class Failed(val message: String) : Progress
    }

    fun progress(c: Context): Flow<Progress> =
        WorkManager.getInstance(c).getWorkInfosForUniqueWorkFlow(WORK).map { infos ->
            val w = infos.lastOrNull() ?: return@map Progress.Idle
            when (w.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> Progress.Waiting("Waiting for Wi-Fi…")
                WorkInfo.State.RUNNING -> Progress.Running(
                    w.progress.getLong(ModelDownloadWorker.KEY_DONE, 0),
                    w.progress.getLong(ModelDownloadWorker.KEY_TOTAL, 1),
                    w.progress.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false))
                WorkInfo.State.SUCCEEDED -> Progress.Done
                WorkInfo.State.FAILED -> Progress.Failed(w.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Download failed.")
                WorkInfo.State.CANCELLED -> Progress.Idle
            }
        }
}
