package com.astrolexis.pyblock.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.astrolexis.pyblock.data.store.ArcadeStore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.theme.PyTheme

/** Non-interactive CRT scanline overlay. Draws faint horizontal lines over
 *  the whole area. Sits above content, below the nav bar. */
@Composable
fun CrtOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val gap = 3f
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = Color.Black.copy(alpha = 0.22f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            y += gap
        }
    }
}

/** CRT BOOT SWEEP — a one-shot phosphor power-on on cold launch: a bright band
 *  sweeps top→bottom over a black veil that fades as it goes, then clears and
 *  calls [onDone]. Fires a single power-up haptic. Skips instantly (still calls
 *  [onDone]) when arcade visuals are off, so it never gates the first frame. */
@Composable
fun CrtBootSweep(accent: Color = PyTheme.primary, onDone: () -> Unit) {
    if (!ArcadeStore.visualsEnabled) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val prog = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        Haptics.powerUp()
        com.astrolexis.pyblock.ui.Sfx.boot()
        prog.animateTo(1f, tween(460, easing = LinearEasing))
        onDone()
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val t = prog.value
        // black veil fades out as the sweep descends
        drawRect(Color.Black.copy(alpha = 1f - t))
        val y = t * size.height
        val h = 34f
        // bright sweep band (the phosphor line)
        drawRect(accent.copy(alpha = (1f - t) * 0.9f), topLeft = Offset(0f, y - h / 2f), size = Size(size.width, h))
        // faint trailing wash above the band
        if (y > 0f) drawRect(accent.copy(alpha = (1f - t) * 0.12f), topLeft = Offset(0f, 0f), size = Size(size.width, y))
    }
}
