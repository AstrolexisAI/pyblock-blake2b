package com.astrolexis.pyblock.ui.screens.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.wallet.RicochetHistory
import com.astrolexis.pyblock.data.wallet.RicochetRecord
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.components.copySensitiveToClipboard
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame

private fun mid(s: String, head: Int = 10, tail: Int = 8): String =
    if (s.length <= head + tail + 1) s else "${s.take(head)}…${s.takeLast(tail)}"

/** History of ricochet sends for a network (newest first). Tap → the tx-chain view. */
@Composable
fun RicochetHistoryScreen(network: String, onOpen: (RicochetRecord) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val records = remember(network) { RicochetHistory.records(ctx, network) }
    Column(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                MarqueeTitle(text = "RICOCHET HISTORY")
                Spacer(Modifier.weight(1f))
                Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { Haptics.tap(); onBack() })
            }
            Spacer(Modifier.height(16.dp))
            if (records.isEmpty()) {
                Text("No ricochet sends yet.", style = PyType.mono(12f), color = PyTheme.primaryDim,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp))
            } else {
                records.forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)
                        .moduleFrame(PyTheme.magenta).clickableNoRipple { Haptics.tap(); onOpen(r) }.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("↝ ${"%,d".format(r.amountSats)} sats", style = PyType.mono(14f), color = PyTheme.magenta)
                            Spacer(Modifier.weight(1f))
                            Text("${r.hops} hops", style = PyType.mono(10f), color = PyTheme.primaryDim)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("→ ${mid(r.toAddress)}", style = PyType.mono(10f), color = PyTheme.cyan)
                        Text(mid(r.finalTxid, 12, 10), style = PyType.mono(9f), color = PyTheme.primaryDim)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The full on-chain path of one ricochet: the SENDER (provenance) address a recipient/
 * exchange saw, an optional key reveal, the tx chain SOURCE → hops → RECIPIENT, and the
 * kept hop addresses. Mirrors iOS RicochetChainView.
 */
@Composable
fun RicochetChainScreen(record: RicochetRecord, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var revealKeys by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                MarqueeTitle(text = "RICOCHET ✓")
                Spacer(Modifier.weight(1f))
                Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { Haptics.tap(); onBack() })
            }
            Spacer(Modifier.height(6.dp))
            Text("${"%,d".format(record.amountSats)} sats · ${record.hops} hops · the payment took this on-chain path.",
                style = PyType.mono(10f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(14.dp))

            // SENDER (provenance) — the last hop, the address a recipient/exchange saw.
            record.senderAddress?.let { sender ->
                Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.yellow).padding(12.dp)) {
                    Text("SENDER ADDRESS (provenance)", style = PyType.mono(10f), color = PyTheme.yellow, letterSpacing = 1.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("If a recipient/exchange asks where these coins came from, THIS is the address they saw. It's yours — prove it with the address or its key.",
                        style = PyType.mono(8f), color = PyTheme.primaryDim)
                    Spacer(Modifier.height(8.dp))
                    Text(sender, style = PyType.mono(11f), color = PyTheme.cyan,
                        modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.primary, RectangleShape)
                            .clickableNoRipple { Haptics.tap(); clip.setText(AnnotatedString(sender)) }.padding(8.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(if (revealKeys) "HIDE HOP KEYS" else "REVEAL HOP KEYS",
                        style = PyType.mono(12f), color = if (revealKeys) PyTheme.primary else PyTheme.danger,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                            .border(1.dp, (if (revealKeys) PyTheme.primary else PyTheme.danger).copy(alpha = 0.6f), RectangleShape)
                            .clickableNoRipple { Haptics.warning(); revealKeys = !revealKeys }.padding(vertical = 10.dp))
                }
                Spacer(Modifier.height(14.dp))
            }

            // TX CHAIN — SOURCE → hop1 … → RECIPIENT.
            Text("TX CHAIN", style = PyType.mono(10f), color = PyTheme.primaryDim, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            record.txids.forEachIndexed { i, txid ->
                val label = when (i) {
                    0 -> "SOURCE"
                    record.txids.size - 1 -> "→ RECIPIENT"
                    else -> "HOP $i"
                }
                Text(label, style = PyType.mono(9f), color = PyTheme.magenta, letterSpacing = 1.sp)
                Spacer(Modifier.height(2.dp))
                Text(mid(txid, 12, 12), style = PyType.mono(10f), color = PyTheme.cyan,
                    modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = 0.4f), RectangleShape)
                        .clickableNoRipple { Haptics.tap(); clip.setText(AnnotatedString(txid)) }.padding(8.dp))
                if (i < record.txids.size - 1) {
                    Text("↓", style = PyType.mono(12f), color = PyTheme.primaryDim,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
                }
            }

            // HOP ADDRESSES (yours) — kept, non-ephemeral; key reveal copies via secure clipboard.
            Spacer(Modifier.height(16.dp))
            Text("HOP ADDRESSES (yours)", style = PyType.mono(10f), color = PyTheme.primaryDim, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            record.hopAddresses.forEachIndexed { i, addr ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("HOP ${i + 1}${if (i == record.hopAddresses.size - 1) " · sender" else ""}",
                        style = PyType.mono(9f), color = PyTheme.magenta, letterSpacing = 1.sp)
                    Text(addr, style = PyType.mono(10f), color = PyTheme.cyan,
                        modifier = Modifier.fillMaxWidth().clickableNoRipple { Haptics.tap(); clip.setText(AnnotatedString(addr)) })
                    if (revealKeys && i < record.hopWifs.size) {
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(record.hopWifs[i], style = PyType.mono(9f), color = PyTheme.danger,
                                modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            Text("COPY KEY", style = PyType.mono(9f), color = PyTheme.danger,
                                modifier = Modifier.border(1.dp, PyTheme.danger.copy(alpha = 0.6f), RectangleShape)
                                    .clickableNoRipple { Haptics.warning(); copySensitiveToClipboard(ctx, record.hopWifs[i], "private key") }
                                    .padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
