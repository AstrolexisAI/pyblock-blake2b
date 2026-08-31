package com.astrolexis.pyblock.ui.theme

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import com.astrolexis.pyblock.data.store.ArcadeStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

// NEON GRID arcade kit (Compose). Mirrors the iOS ArcadeKit. Everything derives
// its bold color from the passed accent (default PyTheme.primary), so themes
// re-skin the cabinet. Battery-safe: Android disposes off-screen destinations,
// so only the visible screen's Starfield animates.

/** Per-subtree "keep it calm" flag. Money surfaces (the Wallet vault) provide
 *  `true` so amounts are read against a still, trustworthy background — no
 *  animated starfield, no bloom, no breathing title — regardless of the user's
 *  arcade preference. "Ruidoso en el lobby, silencioso en la bóveda." */
val LocalSober = staticCompositionLocalOf { false }

/** The single truth for whether arcade *glows* render here: the user preference
 *  AND not inside a sober (money) subtree. Thermal state does NOT gate this — the
 *  glows are cheap static and are the whole point; killing them when warm strips
 *  the look. Only continuous MOTION is thermal-gated (see [arcadeMotion]). */
val arcadeFx: Boolean
    @Composable get() = ArcadeStore.visualsEnabled && !LocalSober.current

/** Like [arcadeFx] but also pauses when the device is hot — for the continuous
 *  canvas animations only (Starfield / MinerSwarm). */
val arcadeMotion: Boolean
    @Composable get() = arcadeFx && !com.astrolexis.pyblock.data.store.ThermalStore.isHot

private data class StarSpec(val x: Float, val y0: Float, val size: Float, val speed: Float, val bright: Float, val accented: Boolean)

/** Galaga-style scrolling starfield. Stars drift downward and wrap. Silent when
 *  arcade visuals are off or inside a sober (money) subtree.
 *
 *  [speedMultiplier] warps the drift rate (HASHRATE WARP): 1.0 = resting; Stats
 *  passes >1 when pool hashrate is above its session baseline, <1 below. Same
 *  star count → battery-neutral. */
@Composable
fun Starfield(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    accent: Color = PyTheme.primary,
    speedMultiplier: Float = 1f,
) {
    if (!arcadeMotion) return
    val stars = remember {
        List(150) {
            StarSpec(Random.nextFloat(), Random.nextFloat(),
                Random.nextFloat() * 1.4f + 0.6f, Random.nextFloat() * 0.05f + 0.01f,
                Random.nextFloat() * 0.7f + 0.3f, Random.nextBoolean() && Random.nextBoolean())
        }
    }
    // Accumulate real time every frame (cheap) but only publish `t` (which drives
    // the Canvas redraw) at ~30fps — the slow drift reads identically while roughly
    // halving GPU/CPU vs a 60fps redraw. Battery + heat.
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        var lastEmit = 0L
        var acc = 0f
        while (true) withFrameNanos { now ->
            if (last != 0L) acc += (now - last) / 1_000_000_000f
            last = now
            if (now - lastEmit >= 33_000_000L) { t = acc; lastEmit = now }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        stars.forEach { s ->
            val y = ((s.y0 + t * s.speed * speedMultiplier) % 1f) * size.height
            // soft round dot (not a hard square) so it reads as a star, not a dead pixel
            drawCircle(if (s.accented) accent else tint, radius = s.size,
                center = Offset(s.x * size.width, y), alpha = s.bright)
        }
    }
}

/** A true gaussian neon bloom via BlurMaskFilter (hardware-accelerated since API 28)
 *  — a continuous soft falloff, not stacked strokes. Two stroked passes (wide soft +
 *  tighter bright) around the rect, drawn un-clipped so the light bleeds into black. */
private fun DrawScope.neonBloom(accent: Color, passes: List<Pair<Float, Float>>, strokePx: Float) {
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        passes.forEach { (blur, a) ->
            val p = Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = strokePx
                color = accent.copy(alpha = a).toArgb()
                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
            nc.drawRect(0f, 0f, size.width, size.height, p)
        }
    }
}

/** Machined neon "Module" card frame: a soft glowing tube around a bright thin
 *  border, over a faintly-lit black panel. In a sober subtree (or with arcade
 *  visuals off) it drops the bloom for a still, machined panel — same structure,
 *  no light show — so money reads calm. */
fun Modifier.moduleFrame(accent: Color): Modifier = composed {
    val panel = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF0B0B12), Color(0xFF050508)))
    if (arcadeFx) {
        // Wide multi-pass gaussian bloom (soft shoulder → bright core) matching the
        // iOS ~35px reach; big radius so the light washes into the black gutters.
        this.drawBehind { neonBloom(accent, passes = listOf(62f to 0.45f, 28f to 0.6f, 12f to 0.88f), strokePx = 7f) }
            .background(panel)
            .background(accent.copy(alpha = 0.07f))
            .border(1.5.dp, accent, RectangleShape)
    } else {
        // Sober: a quiet machined panel — flat fill + one steady hairline, no bloom.
        this.background(panel)
            .border(1.dp, accent.copy(alpha = 0.55f), RectangleShape)
    }
}

/** Delicate glyph-shaped neon glow for a text value — a soft Shadow that follows the
 *  letters (like iOS `.shadow` on Text), NOT a heavy rectangular blob behind the box.
 *  Apply to a Text style: `style = PyType.mono(20f).neonText(PyTheme.yellow)`. */
fun TextStyle.neonText(color: Color, blur: Float = 10f, alpha: Float = 0.6f): TextStyle =
    if (!ArcadeStore.visualsEnabled) this
    else copy(shadow = Shadow(color = color.copy(alpha = alpha), blurRadius = blur))

/** Arcade marquee title: breathing glow (glyph-shaped via text Shadow). Sits
 *  still (no pulse, no glow) when calm — a plain heading for the vault. */
@Composable
fun MarqueeTitle(text: String, modifier: Modifier = Modifier, accent: Color = PyYellow) {
    if (!arcadeFx) {
        androidx.compose.material3.Text(
            text, modifier, color = accent,
            style = PyType.header.copy(letterSpacing = 4.sp),
        )
        return
    }
    val tr = rememberInfiniteTransition(label = "marquee")
    val glow by tr.animateFloat(
        0.72f, 1.0f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    // FIXED-radius shadow (rasterized once) + a layer-alpha "breath" — cheap
    // compositing, no per-frame re-blur (which was cooking the GPU).
    androidx.compose.material3.Text(
        text,
        modifier.graphicsLayer { alpha = glow },
        color = accent,
        style = PyType.header.copy(
            letterSpacing = 4.sp,
            shadow = Shadow(color = accent.copy(alpha = 0.6f), blurRadius = 62f),
        ),
    )
}

/** Segmented pixel progress bar ("NEXT STAGE" style). */
@Composable
fun SegmentedBar(progress: Float, modifier: Modifier = Modifier, segments: Int = 16, accent: Color = PyTheme.primary) {
    val filled = (progress.coerceIn(0f, 1f) * segments).toInt()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(segments) { i ->
            Box(Modifier.weight(1f).height(7.dp).background(if (i < filled) accent else PyPanelEdge))
        }
    }
}
