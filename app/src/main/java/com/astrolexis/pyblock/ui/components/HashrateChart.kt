package com.astrolexis.pyblock.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.model.HashratePoint
import com.astrolexis.pyblock.data.model.HistoryRange
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType

/** Canvas line+area chart for a hashrate series (TH/s). Mirrors the iOS
 *  Swift-Charts version with a monotone-ish polyline + gradient fill. */
@Composable
fun HashrateChart(points: List<HashratePoint>, color: Color, modifier: Modifier = Modifier) {
    if (points.isEmpty()) {
        Box(
            modifier.fillMaxWidth().height(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("no data", style = PyType.mono(12f), color = PyTheme.primaryDim)
        }
        return
    }
    val maxH = remember(points) { points.maxOf { it.hashrate1m }.coerceAtLeast(1e-9) }
    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        val n = points.size
        val w = size.width
        val h = size.height
        fun px(i: Int) = if (n <= 1) 0f else w * i / (n - 1)
        fun py(v: Double) = h - (v / maxH * h).toFloat()

        for (g in 1..3) {
            val gy = h * g / 4f
            drawLine(color.copy(alpha = 0.12f), Offset(0f, gy), Offset(w, gy), 1f)
        }

        val line = Path()
        val area = Path()
        points.forEachIndexed { i, p ->
            val x = px(i)
            val y = py(p.hashrate1m)
            if (i == 0) {
                line.moveTo(x, y)
                area.moveTo(x, h)
                area.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(px(n - 1), h)
        area.close()
        drawPath(
            area,
            brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0f))),
        )
        drawPath(line, color = color, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
fun RangePicker(
    range: HistoryRange,
    allowed: List<HistoryRange>,
    onSelect: (HistoryRange) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        allowed.forEach { r ->
            val sel = r == range
            Text(
                r.label.uppercase(),
                style = PyType.mono(11f),
                color = if (sel) PyTheme.yellow else PyTheme.cyan,
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .weight(1f)
                    .background(if (sel) PyTheme.yellow.copy(alpha = 0.1f) else Color.Transparent)
                    .border(1.dp, if (sel) PyTheme.yellow else PyTheme.primary.copy(alpha = 0.4f), RectangleShape)
                    .clickableNoRipple { onSelect(r) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}
