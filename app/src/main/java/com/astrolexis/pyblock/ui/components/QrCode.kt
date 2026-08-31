package com.astrolexis.pyblock.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Renders [text] as a matrix-green-on-black QR (matches iOS QRCodeView: fg
 *  #00FF41 on black). Still scans fine; callers add the primary-green border. */
@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier) {
    val bmp = remember(text) { generateQr(text, 512) }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "QR code",
            modifier = modifier.size(size),
        )
    }
}

private fun generateQr(text: String, px: Int): Bitmap? = try {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px)
    val pixels = IntArray(px * px)
    for (y in 0 until px) {
        val offset = y * px
        for (x in 0 until px) {
            pixels[offset + x] = if (matrix.get(x, y)) 0xFF00FF41.toInt() else AndroidColor.BLACK
        }
    }
    Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, px, 0, 0, px, px)
    }
} catch (e: Exception) {
    null
}
