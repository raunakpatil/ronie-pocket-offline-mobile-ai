package com.raunakpatil.roniepocket.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

object ImageImport {
    const val MAX_LONG_SIDE = 1536
    const val JPEG_QUALITY = 90

    fun imagesDir(context: Context): File = File(context.filesDir, "images").apply { mkdirs() }

    fun cameraDir(context: Context): File = File(context.cacheDir, "camera").apply { mkdirs() }

    /**
     * Calculates the target width and height keeping aspect ratio with long side <= [maxLongSide].
     */
    fun calculateTargetDimensions(width: Int, height: Int, maxLongSide: Int = MAX_LONG_SIDE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longSide = maxOf(width, height)
        if (longSide <= maxLongSide) return width to height
        val scale = maxLongSide.toFloat() / longSide.toFloat()
        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)
        return targetW to targetH
    }

    /** Computes hex SHA-1 of a byte array. */
    fun sha1(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Imports an image from [uri]: downscales long side <= 1536px, strips EXIF/GPS,
     * encodes as JPEG q90, and saves to files/images/<sha1>.jpg.
     */
    suspend fun importFromUri(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decodeAndDownscale(context, uri) ?: return@runCatching null
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            bitmap.recycle()

            val hash = sha1(bytes)
            val dir = imagesDir(context)
            val dest = File(dir, "$hash.jpg")
            if (!dest.exists()) {
                dest.writeBytes(bytes)
            }
            dest
        }.getOrNull()
    }

    private fun decodeAndDownscale(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(cr, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (targetW, targetH) = calculateTargetDimensions(info.size.width, info.size.height)
                decoder.setTargetSize(targetW, targetH)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            val original = MediaStore.Images.Media.getBitmap(cr, uri) ?: return null
            val (targetW, targetH) = calculateTargetDimensions(original.width, original.height)
            if (targetW == original.width && targetH == original.height) {
                original
            } else {
                Bitmap.createScaledBitmap(original, targetW, targetH, true).also {
                    if (it != original) original.recycle()
                }
            }
        }
    }
}
