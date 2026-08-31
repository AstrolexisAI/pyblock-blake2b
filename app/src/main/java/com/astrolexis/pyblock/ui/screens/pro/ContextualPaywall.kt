package com.astrolexis.pyblock.ui.screens.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the paywall was triggered — drives the context headline shown above the plans. */
data class PaywallContext(val headline: String, val detail: String) {
    companion object {
        val academy = PaywallContext("Premium lesson", "WHALE 🐋 unlocks the whole Bitcoin Academy.")
        val premiumTheme = PaywallContext("Premium theme", "WHALE 🐋 unlocks every theme.")
        fun addressLimit(cur: Int, max: Int) =
            PaywallContext("Tracking $cur/$max addresses", "PRO raises the address limit.")
        val whaleLounge = PaywallContext("Whale Lounge 🐋", "The WHALES-only room — top tier only.")
        val chatFlair = PaywallContext("Chat flair", "PRO unlocks name colors in chat.")
    }
}

/** App-wide trigger for the contextual paywall — fire from any friction point. */
object PaywallBus {
    private val _ctx = MutableStateFlow<PaywallContext?>(null)
    val ctx: StateFlow<PaywallContext?> = _ctx.asStateFlow()
    fun present(c: PaywallContext) { _ctx.value = c }
    fun dismiss() { _ctx.value = null }
}

/** The contextual paywall: a framed upgrade screen shown at the moment of friction
 *  (not buried in Settings). Embeds the real Pro purchase flow (Lightning). */
@Composable
fun ContextualPaywall(context: PaywallContext, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
            Starfield()
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MarqueeTitle(text = stringResource(R.string.paywall_upgrade))
                    Spacer(Modifier.weight(1f))
                    Text("✕", style = PyType.mono(20f), color = PyTheme.primaryDim,
                        modifier = Modifier.clickableNoRipple {
                            Haptics.tap()
                            onDismiss()
                        }.padding(4.dp))
                }
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.cyan).padding(12.dp)) {
                    Text(context.headline, style = PyType.mono(15f), color = PyTheme.cyan)
                    Spacer(Modifier.height(4.dp))
                    Text(context.detail, style = PyType.mono(11f), color = PyTheme.primaryDim)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.paywall_sovereign_pool), style = PyType.mono(9f), color = PyTheme.primaryDim)
                }
                Spacer(Modifier.height(16.dp))
                ProSection()
            }
        }
    }
}
