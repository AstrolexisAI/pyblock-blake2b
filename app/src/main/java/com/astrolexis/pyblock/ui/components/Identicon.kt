package com.astrolexis.pyblock.ui.components

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
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

/** Deterministic 5×5 symmetric pixel avatar from a seed. Byte-for-byte identical
 *  to iOS `Identicon`: hue from hash[0]; a 12%-tint background wash; a 1dp border
 *  at 45%; and the pattern derived by consuming SHA-256 bits SEQUENTIALLY (same
 *  order as iOS — row-major, columns 0,1,2 then mirror to 3,4) so the same seed
 *  renders the exact same avatar on both platforms. */
@Composable
fun Identicon(seed: String, size: Int) {
    val hash = remember(seed) { MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()) }
    val hue = (hash[0].toInt() and 0xff) / 255f * 360f
    val color = Color.hsv(hue, 0.7f, 0.95f)
    // Build the grid exactly like iOS: a running bit counter consumed left→right,
    // top→bottom for the 3 unique columns, then mirrored.
    val grid = remember(seed) {
        val g = BooleanArray(25)
        var bit = 0
        fun on(): Boolean {
            val b = hash[(bit / 8) % hash.size].toInt() and 0xff
            val v = (b shr (bit % 8)) and 1 == 1
            bit++
            return v
        }
        for (row in 0..4) {
            val c0 = on(); val c1 = on(); val c2 = on()
            g[row * 5 + 0] = c0; g[row * 5 + 1] = c1; g[row * 5 + 2] = c2
            g[row * 5 + 3] = c1; g[row * 5 + 4] = c0
        }
        g
    }
    Canvas(
        Modifier.size(size.dp)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.45f))
    ) {
        val cell = this.size.width / 5f
        for (row in 0..4) for (col in 0..4) {
            if (!grid[row * 5 + col]) continue
            drawRect(color, topLeft = Offset(col * cell, row * cell), size = Size(cell, cell))
        }
    }
}
