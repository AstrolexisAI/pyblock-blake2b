package com.astrolexis.pyblock.ui.blake

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

/**
 * A deterministic 5×5 left-right-symmetric identicon derived from SHA-256 of the seed.
 * Purple-family (on-brand): the pattern differs per wallet while the hue stays in a tight
 * violet band. Same seed → same avatar everywhere; no network, no storage. Mirrors iOS
 * `BlakeIdenticon`.
 */
@Composable
fun BlakeIdenticon(seed: String, dimen: Dp = 30.dp) {
    val bytes = remember(seed) { MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()) }
    val cells = remember(seed) { pattern(bytes) }
    val color = remember(seed) {
        val h = 0.72f + (bytes.firstOrNull()?.toInt()?.and(0xff) ?: 0) / 255f * 0.10f
        Color.hsv(h * 360f, 0.55f, 0.95f)
    }
    Canvas(
        Modifier.size(dimen).background(Blake.pp.copy(alpha = 0.08f)).border(1.dp, Blake.pp.copy(alpha = 0.35f), RectangleShape),
    ) {
        val c = size.width / 5f
        for (row in 0 until 5) for (col in 0 until 5) if (cells[row * 5 + col]) {
            drawRect(color, topLeft = Offset(col * c, row * c), size = Size(c, c))
        }
    }
}

private fun pattern(bytes: ByteArray): BooleanArray {
    val cells = BooleanArray(25)
    var bit = 0
    fun on(): Boolean {
        val b = if (bytes.isEmpty()) 0 else bytes[(bit / 8) % bytes.size].toInt() and 0xff
        val v = (b shr (bit % 8)) and 1 == 1
        bit += 1
        return v
    }
    for (row in 0 until 5) {
        val c0 = on(); val c1 = on(); val c2 = on()
        cells[row * 5 + 0] = c0
        cells[row * 5 + 1] = c1
        cells[row * 5 + 2] = c2
        cells[row * 5 + 3] = c1   // mirror
        cells[row * 5 + 4] = c0
    }
    return cells
}
