package com.astrolexis.pyblock.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.astrolexis.pyblock.ui.screens.game.DefenderSprite
import com.astrolexis.pyblock.ui.screens.game.DefenderSprite.drawSprite
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.arcadeMotion
import kotlin.random.Random

/** MINER SWARM — ambient BANK sprites drifting behind Stats, count scaled to the
 *  live active-worker count (4..14). A thin use of the existing DefenderSprite
 *  blitter (SpriteLayer). Silent when arcade visuals are off or inside a sober
 *  subtree. Battery-safe: the frame loop only runs while this is composed — i.e.
 *  only while Stats is on screen. */
@Composable
fun MinerSwarm(workers: Int, modifier: Modifier = Modifier, base: Color = PyTheme.primary) {
    if (!arcadeMotion) return
    val count = if (workers <= 0) 0 else workers.coerceIn(4, 8)
    if (count == 0) return

    data class Sp(val x0: Float, val y0: Float, val vx: Float, val vy: Float, val scale: Float)
    val sprites = remember(count) {
        List(count) {
            Sp(
                Random.nextFloat(), Random.nextFloat(),
                (Random.nextFloat() - 0.5f) * 0.015f, Random.nextFloat() * 0.02f + 0.008f,
                Random.nextFloat() * 1.1f + 1.7f,
            )
        }
    }
    // ~12fps: the sprite blitter is fill-heavy and a slow ambient drift doesn't
    // need 60fps. Accumulate every frame, publish `t` (redraw) only ~12×/sec.
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        var lastEmit = 0L
        var acc = 0f
        while (true) withFrameNanos { now ->
            if (last != 0L) acc += (now - last) / 1_000_000_000f
            last = now
            if (now - lastEmit >= 83_000_000L) { t = acc; lastEmit = now }
        }
    }
    val body = base.copy(alpha = 0.30f)
    val hi = Color.White.copy(alpha = 0.40f)
    Canvas(modifier.fillMaxSize()) {
        sprites.forEach { s ->
            val x = (((s.x0 + t * s.vx) % 1f + 1f) % 1f) * size.width
            val y = ((s.y0 + t * s.vy) % 1f) * size.height
            drawSprite(DefenderSprite.BANK, x, y, s.scale, body, hi)
        }
    }
}
