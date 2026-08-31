package com.astrolexis.pyblock.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.astrolexis.pyblock.data.store.ArcadeStore

/** ODOMETER — a value that rolls (slides up) when it changes instead of hard-
 *  swapping. First render shows instantly (no roll). Degrades to a plain Text
 *  when arcade visuals are off, so it can never animate a balance. */
@Composable
fun OdometerText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (!ArcadeStore.visualsEnabled) {
        Text(text, modifier, color = color, style = style, maxLines = 1)
        return
    }
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(tween(360)) { it / 2 } + fadeIn(tween(360))) togetherWith
                (slideOutVertically(tween(360)) { -it / 2 } + fadeOut(tween(360)))
        },
        label = "odometer",
    ) { t ->
        Text(t, color = color, style = style, maxLines = 1)
    }
}
