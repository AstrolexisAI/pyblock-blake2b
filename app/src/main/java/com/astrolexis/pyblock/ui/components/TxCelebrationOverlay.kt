package com.astrolexis.pyblock.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.astrolexis.pyblock.R
import androidx.compose.ui.res.stringResource
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.random.Random

/**
 * A transient full-screen celebration when money moves — a color flash, a big
 * retro label, the amount, and a rain of ₿ particles. Non-interactive to the
 * layer below (it swallows taps so a send can't be double-fired), and calls
 * [onDone] after ~2s so the host can clear it / navigate home. Mirrors the iOS
 * `TxCelebrationOverlay` exactly (flash → spring title → ₿ rain, sent vs received).
 */
@Composable
fun TxCelebrationOverlay(sent: Boolean, sats: Long, onDone: () -> Unit) {
    val accent = if (sent) PyTheme.magenta else PyTheme.primary
    val title = stringResource(if (sent) R.string.wallet_celebrate_sent else R.string.wallet_celebrate_received)
    val amount = stringResource(
        if (sent) R.string.wallet_celebrate_amount_out else R.string.wallet_celebrate_amount_in,
        NumberFormat.getIntegerInstance().format(sats),
    )

    val flash = remember { Animatable(0f) }
    val titleScale = remember { Animatable(0.6f) }
    val titleAlpha = remember { Animatable(0f) }
    val progress = remember { Animatable(0f) }
    // Stable per-instance particles (x fraction, glyph size, start delay, fall duration).
    val particles = remember {
        List(18) {
            Particle(
                x = Random.nextFloat() * 0.9f + 0.05f,
                size = Random.nextFloat() * 18f + 16f,
                delay = Random.nextFloat() * 0.35f,
                duration = Random.nextFloat() * 0.7f + 0.9f,
            )
        }
    }

    LaunchedEffect(Unit) {
        launch { flash.animateTo(0.18f, tween(180)); flash.animateTo(0f, tween(900)) }
        launch { titleScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
        launch { titleAlpha.animateTo(1f, tween(200)) }
        launch { progress.animateTo(1f, tween(1600, easing = LinearEasing)) }
        delay(1700)
        titleAlpha.animateTo(0f, tween(300))
        onDone()
    }

    // The root swallows taps so the SEND button underneath can't fire again.
    BoxWithConstraints(Modifier.fillMaxSize().clickableNoRipple { }) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()

        Box(Modifier.fillMaxSize().background(accent.copy(alpha = flash.value)))

        particles.forEach { p ->
            val t = ((progress.value - p.delay) / p.duration).coerceIn(0f, 1f)
            val startY = if (sent) hPx + 40f else -40f
            val endY = if (sent) -40f else hPx + 40f
            val y = startY + (endY - startY) * t
            Text(
                "₿", style = PyType.mono(p.size), color = accent.copy(alpha = 0.9f * (1f - t)),
                modifier = Modifier.graphicsLayer {
                    translationX = p.x * wPx - p.size
                    translationY = y
                },
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).graphicsLayer {
                scaleX = titleScale.value; scaleY = titleScale.value; alpha = titleAlpha.value
            },
        ) {
            Text(title, style = PyType.mono(30f), color = accent, letterSpacing = 2.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(amount, style = PyType.mono(22f), color = PyTheme.yellow, textAlign = TextAlign.Center)
        }
    }
}

private data class Particle(val x: Float, val size: Float, val delay: Float, val duration: Float)
