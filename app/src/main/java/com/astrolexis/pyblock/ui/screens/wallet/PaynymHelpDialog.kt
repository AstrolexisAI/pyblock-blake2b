package com.astrolexis.pyblock.ui.screens.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType

/** Text for the PayNym explainer, resolved by the caller inside the app's LocalizedApp
 *  (a Dialog's own composition doesn't follow the in-app language). */
data class PaynymHelpText(val title: String, val gotIt: String, val points: List<Pair<String, String>>)

/** One-time (and on-demand via ⓘ) explainer of how PayNym works, so a new user knows a
 *  received payment lands on a stealth address and is found once the sender is known — and
 *  doesn't panic that "it never arrived". Mirrors iOS PaynymHelpSheet. */
@Composable
fun PaynymHelpDialog(text: PaynymHelpText, onDismiss: () -> Unit) {
    val icons = listOf("◈", "⬇", "⬆", "🔒")
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(PyTheme.bg).border(1.dp, PyTheme.primary, RectangleShape).padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text.title, style = PyType.mono(14f), color = PyTheme.primary, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text("✕", style = PyType.mono(20f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { onDismiss() })
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                text.points.forEachIndexed { i, (title, body) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        .border(1.dp, PyTheme.primary.copy(alpha = 0.4f), RectangleShape).padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(icons.getOrElse(i) { "◈" }, style = PyType.mono(13f), color = PyTheme.cyan)
                            Spacer(Modifier.width(8.dp))
                            Text(title, style = PyType.mono(12f), color = PyTheme.yellow, letterSpacing = 1.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(body, style = PyType.mono(11f), color = PyTheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(text.gotIt, style = PyType.mono(13f), color = PyTheme.bg, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(PyTheme.primary)
                    .clickableNoRipple { Haptics.tap(); onDismiss() }.padding(vertical = 11.dp))
        }
    }
}
