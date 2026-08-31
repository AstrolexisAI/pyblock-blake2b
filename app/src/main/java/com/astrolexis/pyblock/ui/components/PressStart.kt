package com.astrolexis.pyblock.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.store.ArcadeStore
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType

/** Arcade attract-screen empty state: a blinking headline (PRESS START / INSERT
 *  COIN) over the real informative message. Blinks only when arcade visuals are
 *  on; static otherwise. The message always reads clearly. */
@Composable
fun PressStart(headline: String, message: String, modifier: Modifier = Modifier) {
    val blink by if (ArcadeStore.visualsEnabled) {
        val tr = rememberInfiniteTransition(label = "pressstart")
        tr.animateFloat(
            0.2f, 1f,
            infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
            label = "blink",
        )
    } else {
        androidx.compose.runtime.mutableFloatStateOf(1f)
    }
    Column(
        modifier.fillMaxWidth().padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "▶ $headline ◀",
            style = PyType.header.copy(letterSpacing = 5.sp),
            color = PyTheme.primary.copy(alpha = blink),
        )
        Spacer(Modifier.height(12.dp))
        Text(message, style = PyType.mono(13f), color = PyTheme.primaryDim, textAlign = TextAlign.Center)
    }
}
