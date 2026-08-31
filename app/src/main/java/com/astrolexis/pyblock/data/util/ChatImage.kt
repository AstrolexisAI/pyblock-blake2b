package com.astrolexis.pyblock.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/** Prepare a picked gallery/camera image for chat upload: honor EXIF rotation,
 *  downscale to ≤1600px on the long edge, JPEG-compress at 0.7. Mirrors iOS
 *  `NostrChatView.downscaleJPEG` so both platforms upload comparable-sized media. */
object ChatImage {
    fun downscaleJpeg(ctx: Context, uri: Uri, maxDim: Int = 1600, quality: Int = 70): ByteArray? {
        val cr = ctx.contentResolver
        // Pass 1: bounds only, so a huge photo never fully decodes into memory.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth; val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        // Pass 2: decode near the target with inSampleSize (power-of-two subsample).
        var sample = 1
        while (w / (sample * 2) >= maxDim || h / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        // Precise scale to the long-edge cap.
        val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height))
        if (scale < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1), true)
        }

        // Apply EXIF orientation (camera photos are often stored rotated).
        bmp = applyExif(cr, uri, bmp)

        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }

    private fun applyExif(cr: android.content.ContentResolver, uri: Uri, bmp: Bitmap): Bitmap {
        val orientation = try {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return bmp
        }
        return try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (e: Exception) { bmp }
    }
}
