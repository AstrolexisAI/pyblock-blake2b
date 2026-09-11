package com.astrolexis.pyblock.ui.blake

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The moment a send is on the network. The rune punches out from the centre with two expanding
 * rings, and a cone of sparks (purple with a few green) rises and fades. About 1.2 s, drawn on a
 * Canvas with no layout work. Purely decorative: never intercepts touches. Mirrors iOS SendBurstView.
 */
private class Spark(val angle: Float, val speed: Float, val life: Float, val size: Float, val green: Boolean) {
    companion object {
        fun random(r: Random) = Spark(
            angle = (-Math.PI / 2 + r.nextDouble(-0.75, 0.75)).toFloat(),
            speed = r.nextDouble(140.0, 420.0).toFloat(),
            life = r.nextDouble(0.75, 1.25).toFloat(),
            size = r.nextDouble(2.5, 6.0).toFloat(),
            green = r.nextDouble() < 0.15,
        )
    }
}

@Composable
fun SendBurst(onDone: () -> Unit) {
    val sparks = remember { val r = Random(System.nanoTime()); List(56) { Spark.random(r) } }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (t < 1.4f) { withFrameNanos { now -> t = (now - start) / 1e9f } }
        onDone()
    }
    val ru = minOf(1f, t / 0.45f)
    val back = 1f + 2.2f * (ru - 1f).pow(3) + 1.2f * (ru - 1f).pow(2)          // ease-out-back
    val scale = 0.35f + 0.65f * back
    val fade = if (t < 1.0f) 1f else maxOf(0f, 1f - (t - 1.0f) / 0.35f)
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height * 0.40f)
            for (delay in floatArrayOf(0f, 0.16f)) {
                val u = (t - delay) / 0.85f
                if (u <= 0f || u >= 1f) continue
                val r = (18f + u * 170f) * density
                drawCircle(Blake.pp.copy(alpha = (1f - u) * 0.65f), radius = r, center = c, style = Stroke(width = (2.2f - 1.6f * u) * density))
            }
            for (s in sparks) {
                val u = t / s.life
                if (u <= 0f || u >= 1f) continue
                val d = s.speed * (1f - (1f - u).pow(2)) * s.life * 0.9f * density
                val x = c.x + cos(s.angle) * d
                val y = c.y + sin(s.angle) * d + 90f * t * t * density
                val a = (1f - u) * (if (u < 0.1f) u / 0.1f else 1f)
                drawCircle((if (s.green) Blake.ok else Blake.pp).copy(alpha = a), radius = s.size * (1f - 0.5f * u) * density, center = Offset(x, y))
            }
        }
        Text(Blake.RUNE, style = Blake.mono(76f, FontWeight.ExtraBold), color = Blake.pp,
            modifier = Modifier.align(Alignment.TopCenter).graphicsLayer {
                translationY = 0.40f * size.height - 0.5f * size.height   // centre on the ring origin
                scaleX = scale; scaleY = scale; alpha = fade
            })
    }
}

/**
 * The moment the user confirms a send, before the network answers: the rune lifts off from the
 * confirm button like a ship leaving the pad, three fading ghosts trailing, gone by the time the
 * result lands. About 0.8 s; never intercepts touches. Mirrors iOS TakeoffView.
 */
@Composable
fun Takeoff(onDone: () -> Unit) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (t < 0.9f) { withFrameNanos { now -> t = (now - start) / 1e9f } }
        onDone()
    }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.MONOSPACE; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val u = minOf(1f, t / 0.8f)
        fun ss(v: Float) = v * v * (3f - 2f * v)
        val y0 = size.height - 92f * density; val y1 = size.height * 0.30f; val x = size.width / 2f
        drawIntoCanvas { c ->
            for (g in 3 downTo 1) {
                val ug = maxOf(0f, u - g * 0.08f); val eg = ss(ug)
                paint.textSize = 34f * density * (1f - 0.4f * eg)
                paint.color = android.graphics.Color.argb((((1f - u) * (0.35f - g * 0.08f)).coerceIn(0f, 1f) * 255).toInt(), 0xB9, 0x6B, 0xFF)
                c.nativeCanvas.drawText(Blake.RUNE, x, y0 + (y1 - y0) * eg + paint.textSize * 0.35f, paint)
            }
            val e = ss(u)
            paint.textSize = 34f * density * (1f - 0.4f * e)
            paint.color = android.graphics.Color.argb(((if (u < 0.8f) 1f else (1f - u) / 0.2f) * 255).toInt(), 0xB9, 0x6B, 0xFF)
            val y = y0 + (y1 - y0) * e
            c.nativeCanvas.drawText(Blake.RUNE, x, y + paint.textSize * 0.35f, paint)
            paint.textSize = 9f * density
            paint.color = android.graphics.Color.argb(((if (u < 0.7f) 1f else (1f - u) / 0.3f) * 255).toInt(), 0x8F, 0x6F, 0xD0)
            c.nativeCanvas.drawText("SENDING", x, y + 30f * density, paint)
        }
    }
}

/** Coinbase maturity as a bar, not a sentence: how far a mined coin is through its 100 blocks.
 *  Hairline track, amber fill, only while immature. Mirrors iOS MaturityBar. */
@Composable
fun MaturityBar(u: com.astrolexis.pyblock.data.blake.BlakeApi.Utxo, tip: Int, height: androidx.compose.ui.unit.Dp = 2.dp) {
    if (!u.coinbase || tip <= 0 || com.astrolexis.pyblock.data.blake.BlakeFork.isSpendable(u, tip)) return
    val frac = (com.astrolexis.pyblock.data.blake.BlakeFork.confirmations(u, tip).toFloat() /
        com.astrolexis.pyblock.data.blake.BlakeFork.COINBASE_MATURITY).coerceIn(0f, 1f)
    val shown by androidx.compose.animation.core.animateFloatAsState(frac, androidx.compose.animation.core.tween(600), label = "maturity")
    Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(height).background(Blake.line2)) {
        Box(Modifier.fillMaxWidth(shown).height(height).background(Blake.warn))
    }
}
