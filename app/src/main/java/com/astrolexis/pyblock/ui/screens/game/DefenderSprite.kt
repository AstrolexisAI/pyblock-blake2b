package com.astrolexis.pyblock.ui.screens.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/** Pixel-matrix sprite blitter (ports the iOS FxDrawSprite / site pixSprite).
 *  0 = empty, 1 = base colour, 2 = highlight. */
object DefenderSprite {
    /** The bank sprite — the GALAGA-style enemy, verbatim from iOS FxSim.bank. */
    val BANK: Array<IntArray> = arrayOf(
        intArrayOf(0, 0, 0, 0, 2, 0, 0, 0, 0),
        intArrayOf(0, 1, 1, 1, 1, 1, 1, 1, 0),
        intArrayOf(1, 1, 0, 0, 2, 0, 0, 1, 1),
        intArrayOf(1, 0, 0, 0, 2, 0, 0, 0, 0),
        intArrayOf(0, 1, 1, 1, 2, 1, 1, 1, 0),
        intArrayOf(0, 0, 0, 0, 2, 0, 0, 0, 1),
        intArrayOf(1, 1, 0, 0, 2, 0, 0, 1, 1),
        intArrayOf(0, 1, 1, 1, 1, 1, 1, 1, 0),
    )

    fun DrawScope.drawSprite(
        rows: Array<IntArray>, cx: Float, cy: Float, scale: Float, base: Color, hi: Color,
    ) {
        val h = rows.size
        val w = rows[0].size
        val ox = cx - w * scale / 2f
        val oy = cy - h * scale / 2f
        for (y in rows.indices) {
            val row = rows[y]
            for (x in row.indices) {
                val v = row[x]
                if (v == 0) continue
                drawRect(if (v == 2) hi else base, Offset(ox + x * scale, oy + y * scale), Size(scale, scale))
            }
        }
    }
}
