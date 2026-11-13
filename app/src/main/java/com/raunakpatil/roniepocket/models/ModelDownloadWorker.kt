package com.raunakpatil.roniepocket.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the tier's model (and multimodal projection if present) in the background (survives
 * the app closing), resumes partial downloads with HTTP Range requests, and verifies the SHA-256
 * before the files are used.
 * Input: KEY_TIER. Progress: KEY_DONE / KEY_TOTAL bytes.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tier = runCatching { Tier.valueOf(inputData.getString(KEY_TIER)!!) }.getOrNull() ?: return@withContext Result.failure()
        val spec = tier.model
        val dir = ModelStore.dir(applicationContext)

        val downloadDraw = inputData.getBoolean(KEY_DOWNLOAD_DRAW, false)
        val drawTier = runCatching { ImageGenTier.valueOf(inputData.getString(KEY_DRAW_TIER)!!) }.getOrDefault(ImageGenTier.SD_TURBO)
        val drawFiles = if (downloadDraw) drawTier.files else emptyList()
        val drawDir = ModelStore.drawDir(applicationContext).let { if (drawTier.folder.isEmpty()) it else File(it, drawTier.folder).apply { mkdirs() } }
        val drawBytes = drawFiles.sumOf { it.sizeBytes }

        val totalBytes = spec.sizeBytes + (spec.mmproj?.sizeBytes ?: 0L) + drawBytes
        var cumulativeDone = 0L

        // 1. Download main model file
        val mainFinal = File(dir, spec.file)
        val mainPart = File(dir, spec.file + ".part")
        if (mainFinal.exists() && mainFinal.length() == spec.sizeBytes) {
            cumulativeDone += spec.sizeBytes
        } else {
            setForeground(foreground(cumulativeDone, totalBytes))
            try {
                downloadFile(spec.url, spec.sizeBytes, mainPart, cumulativeDone, totalBytes)
            } catch (e: IOException) {
                return@withContext if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf(KEY_ERROR to "Download failed: ${e.message}"))
            }
            setProgress(workDataOf(KEY_DONE to cumulativeDone + spec.sizeBytes, KEY_TOTAL to totalBytes, KEY_VERIFYING to true))
            if (sha256(mainPart) != spec.sha256) {
                mainPart.delete()
                return@withContext Result.failure(workDataOf(KEY_ERROR to "The download was corrupted. Please try again."))
            }
            if (!mainPart.renameTo(mainFinal)) return@withContext Result.failure(workDataOf(KEY_ERROR to "Couldn't save the model."))
            cumulativeDone += spec.sizeBytes
        }

        // 2. Download mmproj file if tier requires it
        val mmprojSpec = spec.mmproj
        if (mmprojSpec != null) {
            val mmprojFinal = File(dir, mmprojSpec.file)
            val mmprojPart = File(dir, mmprojSpec.file + ".part")
            if (!mmprojFinal.exists() || mmprojFinal.length() != mmprojSpec.sizeBytes) {
                setForeground(foreground(cumulativeDone, totalBytes))
                try {
                    downloadFile(mmprojSpec.url, mmprojSpec.sizeBytes, mmprojPart, cumulativeDone, totalBytes)
                } catch (e: IOException) {
                    return@withContext if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf(KEY_ERROR to "Download failed: ${e.message}"))
                }
                setProgress(workDataOf(KEY_DONE to cumulativeDone + mmprojSpec.sizeBytes, KEY_TOTAL to totalBytes, KEY_VERIFYING to true))
                if (sha256(mmprojPart) != mmprojSpec.sha256) {
                    mmprojPart.delete()
                    return@withContext Result.failure(workDataOf(KEY_ERROR to "The vision model download was corrupted. Please try again."))
                }
                if (!mmprojPart.renameTo(mmprojFinal)) return@withContext Result.failure(workDataOf(KEY_ERROR to "Couldn't save the vision model."))
            }
            cumulativeDone += mmprojSpec.sizeBytes
        }

        // 3. Download the drawing model's files, if one was asked for
        for (f in drawFiles) {
            val final = File(drawDir, f.name)
            val part = File(drawDir, f.name + ".part")
            if (!final.exists() || final.length() != f.sizeBytes) {
                setForeground(foreground(cumulativeDone, totalBytes))
                try {
                    downloadFile(f.url, f.sizeBytes, part, cumulativeDone, totalBytes)
                } catch (e: IOException) {
                    return@withContext if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf(KEY_ERROR to "Drawing model download failed: ${e.message}"))
                }
                setProgress(workDataOf(KEY_DONE to cumulativeDone + f.sizeBytes, KEY_TOTAL to totalBytes, KEY_VERIFYING to true))
                if (sha256(part) != f.sha256) {
                    part.delete()
                    return@withContext Result.failure(workDataOf(KEY_ERROR to "The drawing model download was corrupted. Please try again."))
                }
                if (!part.renameTo(final)) return@withContext Result.failure(workDataOf(KEY_ERROR to "Couldn't save the drawing model."))
            }
            cumulativeDone += f.sizeBytes
        }

        setProgress(workDataOf(KEY_DONE to totalBytes, KEY_TOTAL to totalBytes, KEY_VERIFYING to false))
        Result.success()
    }

    private suspend fun downloadFile(url: String, fileSizeBytes: Long, part: File, baseBytes: Long, totalOverallBytes: Long) {
        var done = if (part.exists()) part.length() else 0L
        if (done >= fileSizeBytes) return
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "RoniePocket/0.1 (Android)")
            if (done > 0) setRequestProperty("Range", "bytes=$done-")
        }
        val code = conn.responseCode
        if (code == 200 && done > 0) done = 0L          // server ignored Range: start over
        if (code != 200 && code != 206) throw IOException("HTTP $code")
        conn.inputStream.use { input ->
            FileOutputStream(part, /*append*/ done > 0).use { out ->
                val buf = ByteArray(1 shl 16)
                var lastReport = 0L
                while (true) {
                    if (isStopped) throw IOException("stopped")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 8_000_000) {
                        lastReport = done
                        val overallDone = baseBytes + done
                        setProgress(workDataOf(KEY_DONE to overallDone, KEY_TOTAL to totalOverallBytes))
                        setForeground(foreground(overallDone, totalOverallBytes))
                    }
                }
            }
        }
        if (done != fileSizeBytes) throw IOException("incomplete: $done of $fileSizeBytes")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun foreground(done: Long, total: Long): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Brain download", NotificationManager.IMPORTANCE_LOW))
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Ronie is moving in")
            .setContentText("Downloading Ronie's brain: $pct%")
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    companion object {
        const val KEY_TIER = "tier"
        const val KEY_DOWNLOAD_DRAW = "download_draw"
        const val KEY_DRAW_TIER = "draw_tier"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "model_download"
        private const val NOTIF_ID = 42
    }
}
