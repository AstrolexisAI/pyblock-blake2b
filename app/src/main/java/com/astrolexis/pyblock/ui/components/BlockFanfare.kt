package com.astrolexis.pyblock.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.model.FoundBlock
import com.astrolexis.pyblock.data.store.ArcadeStore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.screens.game.DefenderSprite
import com.astrolexis.pyblock.ui.screens.game.DefenderSprite.drawSprite
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** JuiceOverlay / BLOCK-FOUND FANFARE — the loudest one-shot: a ring of BANK
 *  sprites bursts from center while a "STAGE CLEAR · BLOCK #N" banner blooms,
 *  over a dimming veil, then self-dismisses (~1.9s) + a stageClear haptic.
 *  [block] non-null = play; null = nothing. Gated: skips instantly (still calls
 *  [onDone]) when arcade visuals are off — the vault never mounts this. */
@Composable
fun BlockFanfare(block: FoundBlock?, onDone: () -> Unit) {
    if (block == null) return
    if (!ArcadeStore.visualsEnabled) {
        LaunchedEffect(block) { onDone() }
        return
    }
    val prog = remember(block) { Animatable(0f) }
    val burst = remember(block) {
        List(16) { i ->
            val a = i / 16f * 2f * Math.PI.toFloat()
            Triple(cos(a), sin(a), Random.nextFloat() * 1.3f + 1.7f)
        }
    }
    LaunchedEffect(block) {
        Haptics.stageClear()
        com.astrolexis.pyblock.ui.Sfx.stageClear()
        prog.animateTo(1f, tween(1900, easing = FastOutSlowInEasing))
        onDone()
    }
    // Capture theme colors here (composable context) — they can't be read inside
    // the Canvas draw lambda, which is not @Composable.
    val accent = PyTheme.primary
    val yellow = PyTheme.yellow
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val t = prog.value
            val fade = (1f - t).coerceIn(0f, 1f)
            // dim veil that lifts as the burst flies out
            drawRect(Color.Black.copy(alpha = 0.55f * fade))
            val r = t * size.minDimension * 0.55f
            burst.forEach { (dx, dy, sc) ->
                drawSprite(
                    DefenderSprite.BANK,
                    size.width / 2f + dx * r, size.height / 2f + dy * r, sc,
                    accent.copy(alpha = fade), Color.White.copy(alpha = fade),
                )
            }
        }
        val glow = (1f - prog.value)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                "⛏ STAGE CLEAR",
                style = PyType.header.copy(
                    letterSpacing = 5.sp,
                    shadow = Shadow(color = accent.copy(alpha = glow), blurRadius = 24f + 40f * glow),
                ),
                color = accent,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "BLOCK #${block.height}",
                style = PyType.mono(20f).copy(
                    shadow = Shadow(color = yellow.copy(alpha = glow * 0.8f), blurRadius = 18f),
                ),
                color = yellow,
            )
        }
    }
}
