package com.astrolexis.pyblock.ui.blake

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp

/** The DATUM rune ᚨ (Ansuz) drawn as a vector — the mark of running your own gateway: your node
 *  speaks the block. One stave with two branches falling to the right. Mirrors iOS BlakeAnsuz. */
@Composable
fun AnsuzRune(size: Dp, color: Color = Blake.datum, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size * 0.8f, size)) {
        val lw = (this.size.height * 0.17f).coerceAtLeast(2f)
        val h = this.size.height
        val x0 = lw / 2f + this.size.width * 0.32f; val x1 = this.size.width - lw / 2f
        drawLine(color, Offset(x0, 0f), Offset(x0, h), lw, StrokeCap.Round)
        drawLine(color, Offset(x0, h * 0.05f), Offset(x1, h * 0.30f), lw, StrokeCap.Round)
        drawLine(color, Offset(x0, h * 0.33f), Offset(x1, h * 0.58f), lw, StrokeCap.Round)
    }
}
