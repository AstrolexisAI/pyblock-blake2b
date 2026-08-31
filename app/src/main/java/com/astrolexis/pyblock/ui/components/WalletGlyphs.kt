package com.astrolexis.pyblock.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** On-brand chunky arrow (replaces ⬇/⬆) for receive (down) / send (up), with a
 *  subtle looping bob in the flow direction. Mirrors iOS WalletArrow. */
@Composable
fun WalletArrow(down: Boolean, color: Color, size: Dp, animated: Boolean = true) {
    val bob by if (animated) {
        rememberInfiniteTransition("arrow").animateFloat(
            0f, if (down) 0.14f else -0.14f,
            infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "bob",
        )
    } else remember0()
    Canvas(Modifier.size(size).offset(y = size * bob)) {
        val w = this.size.width; val h = this.size.height
        fun p(x: Float, y: Float) = Offset(x * w, (if (down) y else 1 - y) * h)
        val path = Path().apply {
            moveTo(p(0.34f, 0f).x, p(0.34f, 0f).y)
            lineTo(p(0.66f, 0f).x, p(0.66f, 0f).y)
            lineTo(p(0.66f, 0.5f).x, p(0.66f, 0.5f).y)
            lineTo(p(0.90f, 0.5f).x, p(0.90f, 0.5f).y)
            lineTo(p(0.50f, 1f).x, p(0.50f, 1f).y)
            lineTo(p(0.10f, 0.5f).x, p(0.10f, 0.5f).y)
            lineTo(p(0.34f, 0.5f).x, p(0.34f, 0.5f).y)
            close()
        }
        // Solid fill + permanent faint neon halo (iOS .shadow(color:0.6, radius:2)).
        drawGlowPath(path, color, color.copy(alpha = 0.6f), 2.dp.toPx())
    }
}

/** Custom lightning bolt (replaces ⚡): solid fill with a pulsing neon GLOW (the
 *  bolt itself never fades — only the halo pulses, matching iOS Glyphs.swift). */
@Composable
fun LightningGlyph(color: Color, size: Dp, animated: Boolean = true) {
    val t by if (animated) {
        rememberInfiniteTransition("bolt").animateFloat(
            0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
        )
    } else remember0()
    Canvas(Modifier.size(width = size * 0.72f, height = size)) {
        val w = this.size.width; val h = this.size.height
        fun p(x: Float, y: Float) = Offset(x * w, y * h)
        val path = Path().apply {
            moveTo(p(0.62f, 0f).x, p(0.62f, 0f).y)
            lineTo(p(0.05f, 0.55f).x, p(0.05f, 0.55f).y)
            lineTo(p(0.42f, 0.55f).x, p(0.42f, 0.55f).y)
            lineTo(p(0.30f, 1f).x, p(0.30f, 1f).y)
            lineTo(p(0.95f, 0.40f).x, p(0.95f, 0.40f).y)
            lineTo(p(0.55f, 0.40f).x, p(0.55f, 0.40f).y)
            close()
        }
        // Bolt stays solid; the glow pulses radius 1→3dp, alpha 0.3→0.9 (iOS).
        drawGlowPath(path, color, color.copy(alpha = 0.3f + t * 0.6f), (1f + t * 2f).dp.toPx())
    }
}

/** Custom DNA double-helix (replaces 🧬) for the node status — phase-rotates
 *  while syncing, still when synced. */
@Composable
fun DnaGlyph(color: Color, size: Dp, animated: Boolean = true) {
    val phase by if (animated) {
        rememberInfiniteTransition("dna").animateFloat(
            0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "phase",
        )
    } else remember0()
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val n = 22; val amp = w * 0.30f; val midX = w / 2
        val left = Path(); val right = Path()
        for (i in 0..n) {
            val t = i.toFloat() / n
            val y = t * h
            val ang = t * PI.toFloat() * 3 + phase
            val xl = midX + sin(ang) * amp
            val xr = midX + sin(ang + PI.toFloat()) * amp
            if (i == 0) { left.moveTo(xl, y); right.moveTo(xr, y) }
            else { left.lineTo(xl, y); right.lineTo(xr, y) }
            if (i % 4 == 2) drawLine(color.copy(alpha = 0.5f), Offset(xl, y), Offset(xr, y), strokeWidth = 1.dp.toPx())
        }
        drawPath(left, color, style = Stroke(width = 1.6.dp.toPx()))
        drawPath(right, color.copy(alpha = 0.65f), style = Stroke(width = 1.6.dp.toPx()))
    }
}

/** Fill [path] with [fill] plus a soft neon halo ([glow], blur [radiusPx]) —
 *  the Compose equivalent of iOS `.shadow(color:radius:)` on a filled shape. */
private fun DrawScope.drawGlowPath(path: Path, fill: Color, glow: Color, radiusPx: Float) {
    drawIntoCanvas { canvas ->
        val fp = android.graphics.Paint().apply {
            isAntiAlias = true
            color = fill.toArgb()
            setShadowLayer(radiusPx, 0f, 0f, glow.toArgb())
        }
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), fp)
    }
}

@Composable private fun remember0() = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
@Composable private fun remember1() = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(1f) }

// MARK: - Icon glyphs (custom-drawn, no emoji) — mirror of iOS Glyphs.swift

/** 🔑 → a key: ring bow + stem + teeth. */
@Composable
fun KeyGlyph(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val lw = h * 0.13f; val r = h * 0.26f
        drawCircle(color, r, Offset(lw + r, h / 2), style = Stroke(lw))
        drawRect(color, Offset(2 * r, h / 2 - lw / 2), androidx.compose.ui.geometry.Size(w - 2 * r - lw, lw))
        drawRect(color, Offset(w - lw * 1.4f, h / 2), androidx.compose.ui.geometry.Size(lw, h * 0.2f))
        drawRect(color, Offset(w - lw * 3f, h / 2), androidx.compose.ui.geometry.Size(lw, h * 0.15f))
    }
}

/** ⚙ → a gear: toothed ring + hub. */
@Composable
fun GearGlyph(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val c = Offset(w / 2, w / 2); val rOut = w * 0.46f; val rIn = w * 0.30f
        for (i in 0 until 8) {
            val deg = (i.toFloat() / 8 * 360f)
            rotate(deg, c) {
                drawRect(color, Offset(c.x - w * 0.07f, c.y - rOut), androidx.compose.ui.geometry.Size(w * 0.14f, w * 0.16f))
            }
        }
        drawCircle(color, rIn, c, style = Stroke(w * 0.14f))
    }
}

/** ⛏ → a pickaxe: diagonal handle + curved head. */
@Composable
fun PickGlyph(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val lw = h * 0.12f
        drawLine(color, Offset(w * 0.28f, h * 0.25f), Offset(w * 0.8f, h * 0.9f), strokeWidth = lw)
        val head = Path().apply { moveTo(w * 0.08f, h * 0.42f); quadraticBezierTo(w * 0.35f, h * 0.02f, w * 0.62f, h * 0.1f) }
        drawPath(head, color, style = Stroke(lw))
    }
}

/** 🔒 → a padlock: shackle + body. */
@Composable
fun LockGlyph(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val lw = h * 0.1f
        drawArc(color, 180f, 180f, false,
            Offset(w / 2 - w * 0.24f, h * 0.42f - w * 0.24f),
            androidx.compose.ui.geometry.Size(w * 0.48f, w * 0.48f), style = Stroke(lw))
        drawRoundRect(color, Offset(w * 0.18f, h * 0.42f), androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.48f),
            androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
    }
}

/** ⧉ → copy: two overlapping rects. */
@Composable
fun CopyGlyph(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val lw = h * 0.1f
        val cr = androidx.compose.ui.geometry.CornerRadius(w * 0.05f)
        drawRoundRect(color, Offset(w * 0.08f, h * 0.08f), androidx.compose.ui.geometry.Size(w * 0.55f, h * 0.55f), cr, style = Stroke(lw))
        drawRoundRect(color.copy(alpha = 0.25f), Offset(w * 0.37f, h * 0.37f), androidx.compose.ui.geometry.Size(w * 0.55f, h * 0.55f), cr)
        drawRoundRect(color, Offset(w * 0.37f, h * 0.37f), androidx.compose.ui.geometry.Size(w * 0.55f, h * 0.55f), cr, style = Stroke(lw))
    }
}
