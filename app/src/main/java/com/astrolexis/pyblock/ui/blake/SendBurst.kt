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
