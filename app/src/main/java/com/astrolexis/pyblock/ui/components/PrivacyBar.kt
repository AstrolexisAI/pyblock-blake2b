package com.astrolexis.pyblock.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.wallet.PrivacyScore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import kotlin.math.roundToInt

/**
 * Pre-send privacy meter — a segmented arcade bar + score, tappable to reveal the
 * specific factors (what helps / what leaks). Driven by [PrivacyScore]; it only reflects
 * the pending send, never changes it. Android mirror of iOS PrivacyBar.swift.
 */
@Composable
fun PrivacyBar(score: PrivacyScore) {
    var expanded by remember { mutableStateOf(false) }
    val color = when (score.band) {
        PrivacyScore.Band.GOOD -> PyTheme.primary
        PrivacyScore.Band.FAIR -> PyTheme.yellow
        PrivacyScore.Band.POOR -> PyTheme.danger
    }

    Column(
        Modifier.fillMaxWidth().border(1.dp, color.copy(alpha = 0.5f), RectangleShape)
            .animateContentSize().padding(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickableNoRipple { Haptics.tap(); expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PRIVACY", style = PyType.mono(10f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
            Spacer(Modifier.width(8.dp))
            // 10-segment meter, filled proportional to the score.
            val filled = (score.score / 10.0).roundToInt()
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(10) { i ->
                    Box(Modifier.size(8.dp).background(if (i < filled) color else PyTheme.primaryDim.copy(alpha = 0.25f)))
                }
            }
            Spacer(Modifier.weight(1f))
            Text("${score.score}", style = PyType.mono(13f), color = color)
            Spacer(Modifier.width(6.dp))
            Text(score.label, style = PyType.mono(9f), color = color, letterSpacing = 1.sp)
            Spacer(Modifier.width(6.dp))
            Text(if (expanded) "▾" else "▸", style = PyType.mono(11f), color = PyTheme.primaryDim)
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            score.factors.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.Top) {
                    Text(if (f.good) "✓" else "⚠", style = PyType.mono(10f), color = if (f.good) PyTheme.primary else PyTheme.yellow)
                    Spacer(Modifier.width(6.dp))
                    Text(f.text, style = PyType.mono(9f), color = PyTheme.primaryDim)
                }
            }
        }
    }
}
