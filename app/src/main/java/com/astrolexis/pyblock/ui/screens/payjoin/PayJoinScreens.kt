package com.astrolexis.pyblock.ui.screens.payjoin

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.nostr.Nostr
import com.astrolexis.pyblock.data.nostr.PayJoinCoordinator
import com.astrolexis.pyblock.data.store.PayJoinReservations
import com.astrolexis.pyblock.data.wallet.PayJoinTx
import com.astrolexis.pyblock.data.wallet.UtxoInfo
import com.astrolexis.pyblock.data.wallet.WalletSyncManager
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType

/**
 * Collaborative Send (PayJoin) UI — Android port of iOS `PayJoinUI.swift`. Sender sheet
 * (opened from a DM), receiver consent sheet (mounted globally in RootScaffold), and a
 * shared status strip. The money + protocol live in [PayJoinCoordinator] / [PayJoinTx];
 * these views only collect intent. Gated by PayJoinFeature at each entry point.
 */

/** Short, verifiable npub for a hex pubkey — a display name can collide (a stranger can
 *  register the same name), but the npub is the cryptographic identity. Shown in both PayJoin
 *  sheets so the user confirms WHO they're transacting with. */
fun payjoinNpubShort(hex: String): String {
    val n = Nostr.npub(hex)
    if (n.length <= 24) return n
    return n.take(16) + "…" + n.takeLast(6)
}

/** Non-frozen, non-reserved coins across all wallets — the pool both roles pick from. */
suspend fun loadPayJoinCoins(ctx: Context): List<UtxoInfo> {
    val all = ArrayList<UtxoInfo>()
    for ((_, node) in WalletSyncManager.nodesForAllWallets(ctx)) all.addAll(node.unspentOutputs())
    val reserved = PayJoinReservations.allReserved
    return all.filter { !it.frozen && it.key !in reserved }.sortedByDescending { it.valueSats }
}

// MARK: - Sender

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayJoinSendSheet(peer: String, peerName: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amountStr by remember { mutableStateOf("") }
    var feeStr by remember { mutableStateOf("5") }
    var coins by remember { mutableStateOf<List<UtxoInfo>>(emptyList()) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { coins = loadPayJoinCoins(ctx) }

    val selected = coins.filter { it.key in selectedKeys }
    val singleWallet = selected.map { it.walletId }.toSet().size <= 1
    val selTotal = selected.sumOf { it.valueSats }
    val amountSats = amountStr.toULongOrNull()
    val feeRate = feeStr.toULongOrNull() ?: 5uL
    val canSend = sessionId == null && selected.isNotEmpty() && singleWallet &&
        (amountSats ?: 0uL) > 0uL && selTotal.toULong() > (amountSats ?: 0uL)

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState, containerColor = PyTheme.bg) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("COLLABORATIVE SEND", style = PyType.mono(16f), color = PyTheme.yellow, letterSpacing = 2.sp)
            Text("Pay $peerName. You both add a coin to one transaction — a more private way to " +
                "pay a contact, still a normal on-chain payment.",
                style = PyType.mono(11f), color = PyTheme.primaryDim)
            // Confirm the cryptographic identity — a display name can be spoofed.
            Text("⚠ Verify this is your contact: ${payjoinNpubShort(peer)}",
                style = PyType.mono(9f), color = PyTheme.yellow)

            field("AMOUNT (sats)", amountStr) { amountStr = it.filter { c -> c.isDigit() } }
            field("FEE (sat/vB)", feeStr) { feeStr = it.filter { c -> c.isDigit() } }

            Text("CONTRIBUTE COIN(S)", style = PyType.mono(9f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
            if (coins.isEmpty()) {
                Text("No spendable coins.", style = PyType.mono(11f), color = PyTheme.primaryDim)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(coins, key = { it.key }) { u ->
                        coinRow(u, on = u.key in selectedKeys) {
                            Haptics.tap()
                            selectedKeys = if (u.key in selectedKeys) selectedKeys - u.key else selectedKeys + u.key
                        }
                    }
                }
            }
            if (selected.isNotEmpty())
                Text("${selected.size} coin(s) · $selTotal sat", style = PyType.mono(11f), color = PyTheme.cyan)
            if (!singleWallet)
                Text("⚠ Pick coins from a single address for a collaborative send.",
                    style = PyType.mono(10f), color = PyTheme.danger)
            error?.let { Text(it, style = PyType.mono(10f), color = PyTheme.danger) }

            val sid = sessionId
            if (sid != null) {
                PayJoinStatusStrip(sid, sender = true)
            } else {
                Text("SEND TOGETHER", style = PyType.mono(14f),
                    color = if (canSend) PyTheme.bg else PyTheme.primaryDim, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (canSend) PyTheme.yellow else PyTheme.bg)
                        .border(1.dp, if (canSend) PyTheme.yellow else PyTheme.primaryDim, RectangleShape)
                        .clickableNoRipple {
                            if (!canSend) return@clickableNoRipple
                            Haptics.thock()
                            val amt = amountSats ?: return@clickableNoRipple
                            val wid = selected.firstOrNull()?.walletId ?: return@clickableNoRipple
                            val id = PayJoinCoordinator.startSend(peer, amt, feeRate, selected, wid)
                            if (id != null) sessionId = id
                            else error = "Couldn't start — those coins may be in another send."
                        }
                        .padding(vertical = 12.dp))
            }
        }
    }
}

// MARK: - Receiver consent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayJoinConsentSheet(sessionId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sessions by PayJoinCoordinator.sessions.collectAsState()
    var coins by remember { mutableStateOf<List<UtxoInfo>>(emptyList()) }
    var pickedKey by remember { mutableStateOf<String?>(null) }
    var accepted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { coins = loadPayJoinCoins(ctx) }

    val live = sessions[sessionId]
    val peer = live?.session?.peerPubkey
    val peerName = peer?.let { PayJoinCoordinator.nameFor(it) } ?: "Someone"
    val amount = live?.session?.amountSats ?: 0uL

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState, containerColor = PyTheme.bg) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("COLLABORATIVE PAYMENT", style = PyType.mono(15f), color = PyTheme.yellow, letterSpacing = 2.sp)
            Text("$peerName wants to pay you $amount sats.", style = PyType.mono(13f), color = PyTheme.cyan)
            // Confirm the cryptographic identity — a display name can be spoofed.
            peer?.let {
                Text("⚠ Verify the payer: ${payjoinNpubShort(it)}", style = PyType.mono(9f), color = PyTheme.yellow)
            }
            Text("Add one of your coins to the payment. You receive the amount plus your coin back " +
                "to your address — a normal payment you make together, just more private.",
                style = PyType.mono(11f), color = PyTheme.primaryDim)

            if (accepted) {
                PayJoinStatusStrip(sessionId, sender = false)
            } else {
                Text("CONTRIBUTE A COIN", style = PyType.mono(9f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
                if (coins.isEmpty()) {
                    Text("No spendable coins to contribute.", style = PyType.mono(11f), color = PyTheme.primaryDim)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(coins, key = { it.key }) { u ->
                            coinRow(u, on = pickedKey == u.key) {
                                Haptics.tap()
                                pickedKey = if (pickedKey == u.key) null else u.key
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DECLINE", style = PyType.mono(12f), color = PyTheme.primaryDim, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).border(1.dp, PyTheme.primaryDim, RectangleShape)
                            .clickableNoRipple { Haptics.warning(); PayJoinCoordinator.declineInbound(sessionId); onClose() }
                            .padding(vertical = 11.dp))
                    val picked = coins.firstOrNull { it.key == pickedKey }
                    val on = picked != null
                    Text("CONTRIBUTE", style = PyType.mono(12f), color = if (on) PyTheme.bg else PyTheme.primaryDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                            .background(if (on) PyTheme.yellow else PyTheme.bg)
                            .border(1.dp, if (on) PyTheme.yellow else PyTheme.primaryDim, RectangleShape)
                            .clickableNoRipple {
                                val c = picked ?: return@clickableNoRipple
                                Haptics.thock()
                                PayJoinCoordinator.acceptInbound(sessionId, c.walletId,
                                    PayJoinTx.ReceiverCoin(c.key), c.address)
                                accepted = true
                            }
                            .padding(vertical = 11.dp))
                }
            }
        }
    }
}

// MARK: - Global consent host (mounted in RootScaffold)

/** Shows the consent sheet for the first inbound request awaiting the user's decision.
 *  Gated by PayJoinFeature at the mount site. */
@Composable
fun PayJoinConsentHost() {
    val pending by PayJoinCoordinator.pendingConsent.collectAsState()
    val sid = pending.firstOrNull() ?: return
    PayJoinConsentSheet(sessionId = sid) { PayJoinCoordinator.clear(sid) }
}

// MARK: - Status

@Composable
fun PayJoinStatusStrip(sessionId: String, sender: Boolean) {
    val sessions by PayJoinCoordinator.sessions.collectAsState()
    val statuses by PayJoinCoordinator.status.collectAsState()
    val state = sessions[sessionId]?.session?.state
    val guardFailed = state is com.astrolexis.pyblock.data.nostr.PayJoinState.Aborted && state.reason == "guard"

    Column(Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = 0.4f), RectangleShape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("▸", style = PyType.mono(12f), color = PyTheme.cyan)
            Text(statuses[sessionId] ?: "Working…", style = PyType.mono(11f), color = PyTheme.primary)
        }
        if (sender && guardFailed) {
            Text("SEND NORMALLY INSTEAD", style = PyType.mono(11f), color = PyTheme.bg,
                modifier = Modifier.background(PyTheme.yellow)
                    .clickableNoRipple { Haptics.tap(); PayJoinCoordinator.broadcastFallback(sessionId) }
                    .padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

// MARK: - shared bits

@Composable
private fun field(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = PyType.mono(9f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape).padding(10.dp)) {
            BasicTextField(value, onChange, textStyle = PyType.mono(16f).copy(color = PyTheme.cyan),
                cursorBrush = SolidColor(PyTheme.cyan), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun coinRow(u: UtxoInfo, on: Boolean, onTap: () -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, (if (on) PyTheme.yellow else PyTheme.primaryDim).copy(alpha = 0.6f), RectangleShape)
        .clickableNoRipple(onTap).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (on) "◉" else "○", style = PyType.mono(14f), color = if (on) PyTheme.yellow else PyTheme.primaryDim)
        Spacer(Modifier.width(8.dp))
        Text("${u.valueSats} sat", style = PyType.mono(13f), color = PyTheme.cyan)
        Spacer(Modifier.weight(1f))
        Text(u.address.take(8) + "…", style = PyType.mono(10f), color = PyTheme.primaryDim)
    }
}
