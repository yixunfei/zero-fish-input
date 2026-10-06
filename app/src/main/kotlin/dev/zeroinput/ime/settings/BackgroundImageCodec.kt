package dev.zeroinput.ime.settings

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import java.io.ByteArrayOutputStream
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface

/** Bounded raster-only import. Re-encoding strips EXIF, names, and other source metadata. */
internal object BackgroundImageCodec {
    const val MAX_BYTES = 12 * 1024 * 1024
    const val MAX_PIXELS = 40_000_000L
    const val MAX_EDGE = 1280

    fun read(resolver: ContentResolver, uri: Uri, signal: CancellationSignal): ByteArray {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        val buffer = ByteArray(MAX_BYTES + 1)
        try {
            val descriptor = requireNotNull(resolver.openAssetFileDescriptor(uri, "r", signal))
            signal.setOnCancelListener { runCatching { descriptor.close() } }
            var size = 0
            descriptor.use { it.createInputStream().use { input ->
                while (size < buffer.size) {
                    signal.throwIfCanceled()
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    require(count > 0)
                    size += count
                }
            } }
            require(size in 1..MAX_BYTES)
            val bitmap = orient(decode(buffer, size), buffer, size)
            try {
                signal.throwIfCanceled()
                val output = WipingOutput()
                return try {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                } finally { output.wipe() }
            } finally { bitmap.recycle() }
        } finally { signal.setOnCancelListener(null); buffer.fill(0) }
    }

    fun decode(bytes: ByteArray, size: Int = bytes.size): Bitmap {
        require(size in 1..MAX_BYTES)
        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, size, info)
        require(info.outMimeType in setOf("image/jpeg", "image/png", "image/webp"))
        require(info.outWidth in 1..16384 && info.outHeight in 1..16384)
        require(info.outWidth.toLong() * info.outHeight <= MAX_PIXELS)
        var sample = 1
        while ((maxOf(info.outWidth, info.outHeight) + sample - 1) / sample > MAX_EDGE) sample *= 2
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }))
    }

    private fun orient(bitmap: Bitmap, bytes: ByteArray, size: Int): Bitmap {
        val orientation = try {
            ExifInterface(java.io.ByteArrayInputStream(bytes, 0, size))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        } catch (_: java.io.IOException) { 1 }
        val matrix = android.graphics.Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return try { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }
        finally { bitmap.recycle() }
    }

    /** A small separable box blur on a downsampled display copy; always runs on the worker. */
    fun blur(source: Bitmap, amount: Int): Bitmap {
        if (amount <= 0) return source
        val ratio = minOf(1f, 320f / maxOf(source.width, source.height))
        val small = source.scale((source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1), true)
        val width = small.width
        val height = small.height
        val pixels = IntArray(width * height)
        val work = IntArray(pixels.size)
        try {
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            val radius = (amount / 2).coerceIn(1, 10)
            repeat(2) {
                blurPass(pixels, work, width, height, radius, true)
                blurPass(work, pixels, width, height, radius, false)
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } finally {
            pixels.fill(0); work.fill(0)
            if (small !== source) small.recycle()
            source.recycle()
        }
    }

    private fun blurPass(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        for (y in 0 until height) for (x in 0 until width) {
            var a = 0; var r = 0; var g = 0; var b = 0
            for (offset in -radius..radius) {
                val xx = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
                val yy = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
                val color = input[yy * width + xx]
                a += color ushr 24; r += color ushr 16 and 255; g += color ushr 8 and 255; b += color and 255
            }
            val count = radius * 2 + 1
            output[y * width + x] = (a / count shl 24) or (r / count shl 16) or (g / count shl 8) or (b / count)
        }
    }

    private class WipingOutput : ByteArrayOutputStream() {
        fun wipe() { buf.fill(0); reset() }
    }
}
