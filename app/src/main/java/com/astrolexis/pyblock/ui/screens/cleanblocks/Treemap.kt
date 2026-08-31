package com.astrolexis.pyblock.ui.screens.cleanblocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.astrolexis.pyblock.data.model.CBTx
import kotlin.math.max
import kotlin.math.min

data class TmCell(val x: Float, val y: Float, val w: Float, val h: Float, val dirty: Boolean)

/** Squarified treemap — one cell per tx, area ∝ vsize, green=clean/red=parasite.
 *  Ports drawTreemap() from cleanblocks.php. Layout is frozen per [stableKey]
 *  (block height) so live tx-count refreshes don't reshuffle the picture;
 *  pass null to recompute whenever the tx set changes (static detail modal). */
@Composable
fun TreemapView(
    items: List<CBTx>,
    stableKey: Int?,
    aspect: Float = 16f / 9f,
    cornerRadius: Dp = 10.dp,
    modifier: Modifier = Modifier,
) {
    var cells by remember(stableKey) { mutableStateOf<List<TmCell>>(emptyList()) }
    LaunchedEffect(stableKey, items.size) {
        if (stableKey == null) {
            cells = squarify(items, aspect, 1f)
        } else if (cells.isEmpty() && items.isNotEmpty()) {
            cells = squarify(items, aspect, 1f)
        }
    }

    Canvas(
        modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color(0xFF050A05))
            .border(1.dp, Cb.line, RoundedCornerShape(cornerRadius)),
    ) {
        val scale = size.height
        for (c in cells) {
            val w = max(0f, c.w * scale - 0.6f)
            val h = max(0f, c.h * scale - 0.6f)
            drawRect(
                color = (if (c.dirty) Cb.red else Cb.green).copy(alpha = 0.85f),
                topLeft = Offset(c.x * scale, c.y * scale),
                size = Size(w, h),
            )
        }
    }
}

private class TmNode(val v: Double, val dirty: Boolean) {
    var a: Double = 0.0
}

fun squarify(items: List<CBTx>, W: Float, H: Float): List<TmCell> {
    if (W <= 0 || H <= 0 || items.isEmpty()) return emptyList()
    val nodes = items.map { TmNode(max(1, it.v).toDouble(), it.d != 0) }
        .sortedByDescending { it.v }
    val total = nodes.sumOf { it.v }
    if (total <= 0) return emptyList()
    val scale = (W * H) / total
    nodes.forEach { it.a = it.v * scale }

    fun worst(row: List<TmNode>, short: Float): Double {
        var s = 0.0; var mx = 0.0; var mn = 1e18
        for (n in row) { s += n.a; mx = max(mx, n.a); mn = min(mn, n.a) }
        if (s <= 0) return Double.MAX_VALUE
        val sd = short.toDouble()
        return max((sd * sd * mx) / (s * s), (s * s) / (sd * sd * mn))
    }

    val out = ArrayList<TmCell>(nodes.size)
    var x = 0f; var y = 0f; var w = W; var h = H; var i = 0
    while (i < nodes.size) {
        val short = min(w, h)
        val row = arrayListOf(nodes[i]); var i2 = i + 1
        while (i2 < nodes.size) {
            val cur = worst(row, short)
            row.add(nodes[i2])
            if (worst(row, short) > cur) { row.removeAt(row.size - 1); break }
            i2++
        }
        val s = row.sumOf { it.a }
        val thick = (s / short).toFloat()
        var off = 0f
        for (n in row) {
            val len = (n.a / thick).toFloat()
            out.add(if (w >= h) TmCell(x, y + off, thick, len, n.dirty) else TmCell(x + off, y, len, thick, n.dirty))
            off += len
        }
        if (w >= h) { x += thick; w -= thick } else { y += thick; h -= thick }
        i = i2
    }
    return out
}
