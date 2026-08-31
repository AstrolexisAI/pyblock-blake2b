package com.astrolexis.pyblock.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.astrolexis.pyblock.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.astrolexis.pyblock.data.crypto.Nip44
import com.astrolexis.pyblock.data.util.PaymentRequest
import com.astrolexis.pyblock.data.util.PaymentUri
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.data.nostr.DMMessage
import com.astrolexis.pyblock.data.nostr.Nostr
import com.astrolexis.pyblock.data.nostr.NostrClient
import com.astrolexis.pyblock.data.nostr.NostrUiState
import com.astrolexis.pyblock.ui.components.Identicon
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import com.astrolexis.pyblock.ui.Haptics

// MARK: Inbox

@Composable
fun DMInbox(client: NostrClient, state: NostrUiState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    // Native secp256k1 during composition — guard so it can't crash the inbox.
    val ok = remember { runCatching { Nip44.selfTest() }.getOrDefault(false) }
    LaunchedEffect(Unit) { client.scanAllPaynymLookahead() }   // catch payments whose DM was lost
    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
    Starfield()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim,
                modifier = Modifier.clickableNoRipple(onBack).padding(10.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.dm_private), style = PyType.mono(16f), color = PyTheme.magenta)
                Text(if (ok) stringResource(R.string.dm_encrypted_nip44) else stringResource(R.string.dm_crypto_selftest_failed),
                    style = PyType.mono(9f), color = if (ok) PyTheme.primaryDim else PyTheme.yellow)
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(42.dp))
        }
        HDivider()
        val peers = client.dmPeers()
        if (peers.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("🔒", style = PyType.mono(40f), color = PyTheme.primary)
                Text(stringResource(R.string.dm_no_messages_yet), style = PyType.mono(13f), color = PyTheme.primaryDim)
                Text(stringResource(R.string.dm_no_messages_hint),
                    style = PyType.mono(10f), color = PyTheme.primaryDim, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(peers, key = { it }) { peer ->
                    val last = state.conversations[peer]?.lastOrNull()
                    Row(Modifier.fillMaxWidth().clickableNoRipple { onOpen(peer) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Identicon(peer, 40)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(client.name(peer), style = PyType.mono(14f), color = PyTheme.cyan, maxLines = 1)
                            Text(last?.let { (if (it.mine) stringResource(R.string.dm_you_prefix) else "") + it.text } ?: "",
                                style = PyType.mono(11f), color = PyTheme.primaryDim, maxLines = 1)
                        }
                        Text("🔒", style = PyType.mono(12f), color = PyTheme.primaryDim)
                    }
                    HDivider()
                }
            }
        }
    }
    }
}

// MARK: Conversation

@Composable
fun DMConversation(
    client: NostrClient, thread: List<DMMessage>,
    peerName: String, peerKey: String, onBack: () -> Unit, onSend: (String) -> Unit,
    onPay: (String, Long?, String) -> Unit,
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { WalletStore.ensureLoaded(ctx) }
    // Look-ahead: catch a payment whose notification DM was lost.
    LaunchedEffect(peerKey) { client.scanPaynymLookahead(peerKey) }
    val wallets by WalletStore.wallets.collectAsState()
    val state by client.state.collectAsState()
    val myAddress = wallets.firstOrNull()?.address
    val peerAddress = client.addressFor(peerKey)
    // A PayNym gives a fresh unlinkable address per payment; prefer it.
    val peerPaynym = client.paynymFor(peerKey)
    val canPay = peerPaynym != null || peerAddress != null
    val readUpTo = state.readUpTo[peerKey] ?: 0L
    var draft by remember { mutableStateOf("") }
    var showRequest by remember { mutableStateOf(false) }
    var showPeerPaynym by remember { mutableStateOf(false) }
    var showPayJoin by remember { mutableStateOf(false) }
    var reqAmount by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<com.astrolexis.pyblock.data.util.PaymentReceipt?>(null) }
    val pin = rememberPinnedChat(itemCount = thread.size, onActivity = { client.markRead(peerKey) })
    // Client-side confirmations: whichever of my wallets tracked the tx.
    fun confFor(txid: String): com.astrolexis.pyblock.data.wallet.BdkNode.TxConf? {
        for (w in wallets) com.astrolexis.pyblock.data.wallet.WalletSyncManager.getNode(ctx, w).confStatus(txid)?.let { return it }
        return null
    }
    detail?.let { r -> TxDetailDialog(r, confFor(r.txid)) { detail = null } }
    if (showPayJoin)
        com.astrolexis.pyblock.ui.screens.payjoin.PayJoinSendSheet(peerKey, peerName) { showPayJoin = false }
    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
    Starfield()
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = PyType.mono(28f), color = PyTheme.primaryDim,
                modifier = Modifier.clickableNoRipple(onBack).padding(horizontal = 8.dp))
            Identicon(peerKey, 30)
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(peerName, style = PyType.mono(14f), color = PyTheme.cyan, maxLines = 1)
                    TierBadge(client.tierFor(peerKey))
                }
                Text(stringResource(R.string.dm_end_to_end_encrypted), style = PyType.mono(8f), color = PyTheme.primaryDim)
            }
            Spacer(Modifier.weight(1f))
            // The peer's PayNym (their cosmic identicon) → opens their code + cosmic name.
            peerPaynym?.let { pc ->
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickableNoRipple { showPeerPaynym = true }.padding(horizontal = 4.dp)) {
                    Identicon(pc, 26)
                    Text(stringResource(R.string.dm_paynym), style = PyType.mono(7f), color = PyTheme.cyan, letterSpacing = 1.sp)
                }
            }
        }
        if (showPeerPaynym) peerPaynym?.let { pc ->
            PeerPaynymDialog(peerName, pc) { showPeerPaynym = false }
        }
        HDivider()
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), state = pin.listState) {
                item {
                    Text(stringResource(R.string.dm_only_you_and_peer, peerName),
                        style = PyType.mono(9f), color = PyTheme.primaryDim,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), textAlign = TextAlign.Center)
                }
                items(thread, key = { it.id }) { m ->
                    val receipt = PaymentUri.parseReceipt(m.text)
                    val req = if (receipt == null) PaymentUri.parse(m.text) else null
                    when {
                        receipt != null -> PayReceiptBubble(m, receipt, confFor(receipt.txid)) { detail = receipt }
                        req != null -> PayReqBubble(m, req, canPay = !m.mine && myAddress != null) { onPay(req.address, req.amountSats, peerKey) }
                        else -> TextBubble(m, seen = m.mine && readUpTo >= m.createdAt)
                    }
                }
            }
            NewMessagesPill(pin)
        }
        // Private payment actions — only if you have a wallet to pay/receive from.
        if (myAddress != null) {
            if (showRequest) {
                RequestComposer(reqAmount, { reqAmount = it.filter { c -> c.isDigit() } },
                    onCancel = { showRequest = false; reqAmount = "" },
                    onSend = {
                        onSend(PaymentRequest(myAddress, reqAmount.toLongOrNull(), null).toUri())
                        showRequest = false; reqAmount = ""
                    })
            } else {
                // Fresh unlinkable address each payment. Index = receipts
                // already sent to this peer (matches the receiver's).
                fun startSend(amount: Long?) {
                    if (peerPaynym != null) {
                        val idx = thread.count { it.mine && it.text.startsWith("pyblock:paid?") }
                        com.astrolexis.pyblock.data.crypto.PaymentCode
                            .deriveSendAddress(ctx, peerPaynym, idx)?.let { onPay(it, amount, peerKey) }
                    } else peerAddress?.let { onPay(it, amount, peerKey) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayAction(stringResource(R.string.dm_send), enabled = canPay, Modifier.weight(1f)) { startSend(null) }
                    // One-tap preset amounts — sats pre-filled, wallet just confirms.
                    QuickChip("21K", enabled = canPay) { startSend(21_000L) }
                    QuickChip("100K", enabled = canPay) { startSend(100_000L) }
                    PayAction(stringResource(R.string.dm_request), enabled = true, Modifier.weight(1f)) { showRequest = true }
                }
                // Collaborative Send (PayJoin): you + the peer co-sign one tx. Needs only
                // their npub (peerKey) — the receive address is exchanged during the round-trip.
                if (com.astrolexis.pyblock.data.store.PayJoinFeature.enabled)
                    Text("⇄ COLLABORATIVE SEND",
                        style = PyType.mono(11f), color = PyTheme.cyan, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            .border(1.dp, PyTheme.cyan, RectangleShape)
                            .clickableNoRipple { Haptics.tap(); showPayJoin = true }.padding(vertical = 9.dp))
                if (peerPaynym != null)
                    Text(stringResource(R.string.dm_paynym_fresh_address),
                        style = PyType.mono(8f), color = PyTheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp))
                else if (peerAddress == null)
                    Text(stringResource(R.string.dm_no_receive_address, peerName),
                        style = PyType.mono(8f), color = PyTheme.primaryDim,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), textAlign = TextAlign.Center)
            }
        }
        InputBar(draft, stringResource(R.string.dm_encrypted_message_placeholder), { draft = it }) {
            if (draft.isNotBlank()) { onSend(draft); draft = "" }
        }
    }
    }
}

@Composable
private fun TextBubble(m: DMMessage, seen: Boolean = false) {
    val color = if (m.mine) PyTheme.magenta else PyTheme.cyan
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
            Box(Modifier.border(1.dp, color.copy(alpha = 0.5f), RectangleShape).padding(10.dp)) {
                Text(m.text, style = PyType.mono(13f), color = PyTheme.primary)
            }
            Text(time(m.createdAt) + (if (m.mine) (if (seen) stringResource(R.string.dm_seen) else stringResource(R.string.dm_sent)) else ""),
                style = PyType.mono(8f), color = if (m.mine && seen) PyTheme.cyan else PyTheme.primaryDim)
        }
    }
}

@Composable
private fun PayReqBubble(m: DMMessage, req: PaymentRequest, canPay: Boolean, onPay: () -> Unit) {
    val amt = req.amountSats
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Column(Modifier.border(1.dp, PyTheme.yellow, RectangleShape).padding(12.dp),
            horizontalAlignment = Alignment.Start) {
            Text(if (m.mine) stringResource(R.string.dm_you_requested) else stringResource(R.string.dm_payment_request), style = PyType.mono(10f), color = PyTheme.yellow)
            Spacer(Modifier.height(4.dp))
            Text(if (amt != null) stringResource(R.string.dm_btc_amount, PaymentUri.satsToBtc(amt)) else stringResource(R.string.dm_any_amount), style = PyType.mono(15f), color = PyTheme.cyan)
            Text(stringResource(R.string.dm_to_address, req.address.take(16)), style = PyType.mono(9f), color = PyTheme.primaryDim)
            if (canPay) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.dm_pay), style = PyType.mono(13f), color = PyTheme.bg, textAlign = TextAlign.Center,
                    modifier = Modifier.background(PyTheme.magenta).clickableNoRipple { Haptics.thock(); onPay() }
                        .padding(horizontal = 18.dp, vertical = 8.dp))
            }
            Text(time(m.createdAt), style = PyType.mono(8f), color = PyTheme.primaryDim, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun RequestComposer(amount: String, onAmount: (String) -> Unit, onCancel: () -> Unit, onSend: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        .border(1.dp, PyTheme.yellow, RectangleShape).padding(12.dp)) {
        Text(stringResource(R.string.dm_request_a_payment), style = PyType.mono(11f), color = PyTheme.yellow)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape).padding(10.dp)) {
            if (amount.isEmpty()) Text(stringResource(R.string.dm_amount_placeholder), style = PyType.mono(12f), color = PyTheme.primaryDim)
            BasicTextField(amount, onAmount, textStyle = PyType.mono(13f).copy(color = PyTheme.cyan),
                cursorBrush = SolidColor(PyTheme.cyan), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dm_cancel), style = PyType.mono(12f), color = PyTheme.primary, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).border(1.dp, PyTheme.primary, RectangleShape)
                    .clickableNoRipple { Haptics.tap(); onCancel() }.padding(vertical = 9.dp))
            Text(stringResource(R.string.dm_send_request), style = PyType.mono(12f), color = PyTheme.bg, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1.4f).background(PyTheme.yellow)
                    .clickableNoRipple { Haptics.thock(); onSend() }.padding(vertical = 9.dp))
        }
    }
}

@Composable
private fun QuickChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text("⚡$label", style = PyType.mono(11f), color = if (enabled) PyTheme.yellow else PyTheme.primaryDim,
        modifier = Modifier.border(1.dp, if (enabled) PyTheme.yellow else PyTheme.primaryDim, RectangleShape)
            .clickableNoRipple { if (enabled) { Haptics.coin(); onClick() } }.padding(horizontal = 8.dp, vertical = 10.dp))
}

@Composable
private fun PayAction(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(label, style = PyType.mono(12f), color = if (enabled) PyTheme.bg else PyTheme.primaryDim, textAlign = TextAlign.Center,
        modifier = modifier.background(if (enabled) PyTheme.magenta else Color.Transparent)
            .border(1.dp, if (enabled) PyTheme.magenta else PyTheme.primaryDim, RectangleShape)
            .clickableNoRipple { if (enabled) { Haptics.thock(); onClick() } }.padding(vertical = 10.dp))
}

private fun confLabel(conf: com.astrolexis.pyblock.data.wallet.BdkNode.TxConf?): String = when {
    conf == null -> "⏳ pending"
    !conf.confirmed -> "⏳ in mempool · 0-conf"
    else -> "✓ confirmed · block ${conf.height}"
}

@Composable
private fun PayReceiptBubble(m: DMMessage, r: com.astrolexis.pyblock.data.util.PaymentReceipt,
                            conf: com.astrolexis.pyblock.data.wallet.BdkNode.TxConf?, onTap: () -> Unit) {
    val cColor = if (conf?.confirmed == true) PyTheme.primary else PyTheme.yellow
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Column(Modifier.border(1.dp, PyTheme.primary, RectangleShape).clickableNoRipple(onTap).padding(12.dp)) {
            Text(if (m.mine) stringResource(R.string.dm_you_sent) else stringResource(R.string.dm_payment_received), style = PyType.mono(10f), color = PyTheme.primary)
            Text(r.amountSats?.let { stringResource(R.string.dm_btc_amount, com.astrolexis.pyblock.data.util.PaymentUri.satsToBtc(it)) } ?: stringResource(R.string.dm_payment),
                style = PyType.mono(15f), color = PyTheme.cyan)
            Text(confLabel(conf), style = PyType.mono(9f), color = cColor)
            Text(stringResource(R.string.dm_txid_tap_details, r.txid.take(16)), style = PyType.mono(7f), color = PyTheme.primaryDim)
            Text(time(m.createdAt), style = PyType.mono(8f), color = PyTheme.primaryDim)
        }
    }
}

@Composable
fun TxDetailDialog(r: com.astrolexis.pyblock.data.util.PaymentReceipt,
                   conf: com.astrolexis.pyblock.data.wallet.BdkNode.TxConf?, onDismiss: () -> Unit) {
    val clip = LocalClipboardManager.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.background(PyTheme.bg).border(1.dp, PyTheme.primary, RectangleShape).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.dm_payment_title), style = PyType.mono(16f), color = PyTheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(r.amountSats?.let { stringResource(R.string.dm_btc_amount, com.astrolexis.pyblock.data.util.PaymentUri.satsToBtc(it)) } ?: "—",
                style = PyType.mono(24f), color = PyTheme.cyan)
            r.amountSats?.let { Text(stringResource(R.string.dm_sats_amount, it), style = PyType.mono(11f), color = PyTheme.primaryDim) }
            Text(confLabel(conf), style = PyType.mono(13f), color = if (conf?.confirmed == true) PyTheme.primary else PyTheme.yellow,
                modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.dm_txid_label), style = PyType.mono(10f), color = PyTheme.primaryDim)
            Text(r.txid, style = PyType.mono(10f), color = PyTheme.primary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.dm_copy_txid), style = PyType.mono(12f), color = PyTheme.primary,
                modifier = Modifier.border(1.dp, PyTheme.primary, RectangleShape)
                    .clickableNoRipple { Haptics.tap(); clip.setText(AnnotatedString(r.txid)) }.padding(horizontal = 14.dp, vertical = 8.dp))
            Text(stringResource(R.string.dm_built_broadcast_ondevice),
                style = PyType.mono(8f), color = PyTheme.primaryDim, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

// MARK: Profile sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(client: NostrClient, pubkey: String, onDismiss: () -> Unit, onMessage: () -> Unit) {
    val clip = LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val npub = remember(pubkey) { Nostr.npub(pubkey) }
    val isMe = pubkey == client.myPubkey
    var revealNsec by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf(client.name(pubkey).removePrefix("@")) }
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = PyTheme.bg) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Identicon(pubkey, 84)
            Spacer(Modifier.size(6.dp))
            TierBadge(client.tierFor(pubkey), big = true)
            Spacer(Modifier.size(6.dp))
            if (isMe) {
                Text(stringResource(R.string.dm_your_name), style = PyType.mono(10f), color = PyTheme.primaryDim)
                Box(Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape).padding(10.dp)) {
                    if (nameDraft.isEmpty()) Text(stringResource(R.string.dm_display_name), style = PyType.mono(14f), color = PyTheme.primaryDim)
                    BasicTextField(nameDraft, { nameDraft = it }, textStyle = PyType.mono(14f).copy(color = PyTheme.cyan),
                        cursorBrush = SolidColor(PyTheme.cyan), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.size(8.dp))
                OutlineBtn(stringResource(R.string.dm_save_name), PyTheme.primary) { Haptics.thock(); client.setName(nameDraft); onDismiss() }
                Text(stringResource(R.string.dm_you), style = PyType.mono(10f), color = PyTheme.primaryDim, modifier = Modifier.padding(top = 6.dp))
                FlairPicker(client)
            } else {
                Text(client.name(pubkey), style = PyType.mono(18f), color = PyTheme.cyan)
            }
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.dm_npub), style = PyType.mono(10f), color = PyTheme.primaryDim)
            Text(npub, style = PyType.mono(12f), color = PyTheme.primary, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.size(10.dp))
            OutlineBtn(stringResource(R.string.dm_copy_npub), PyTheme.primary) { Haptics.tap(); clip.setText(AnnotatedString(npub)) }
            if (isMe) {
                Spacer(Modifier.size(12.dp))
                if (revealNsec) {
                    Text(stringResource(R.string.dm_nsec_warning), style = PyType.mono(10f), color = PyTheme.danger,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 14.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.dm_nsec_label), style = PyType.mono(9f), color = PyTheme.primaryDim)
                    Text(Nostr.myNsec(ctx), style = PyType.mono(11f), color = PyTheme.yellow,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlineBtn(stringResource(R.string.dm_copy_nsec), PyTheme.yellow) { Haptics.thock(); clip.setText(AnnotatedString(Nostr.myNsec(ctx))) }
                        OutlineBtn(stringResource(R.string.dm_hide_nsec), PyTheme.primary) { Haptics.tap(); revealNsec = false }
                    }
                } else {
                    OutlineBtn(stringResource(R.string.dm_reveal_nsec), PyTheme.danger) { Haptics.warning(); revealNsec = true }
                }
            }
            if (!isMe) {
                Spacer(Modifier.size(14.dp))
                Box(Modifier.background(PyTheme.magenta).clickableNoRipple { Haptics.thock(); onMessage() }
                    .padding(horizontal = 18.dp, vertical = 11.dp)) {
                    Text(stringResource(R.string.dm_message_privately), style = PyType.mono(13f), color = PyTheme.bg)
                }
                Text(stringResource(R.string.dm_end_to_end_encrypted_nip44), style = PyType.mono(9f), color = PyTheme.primaryDim,
                    modifier = Modifier.padding(top = 6.dp))
                // Moderation (UGC): block hides all their content instantly + reports to us.
                Spacer(Modifier.size(10.dp))
                if (client.isBlocked(pubkey)) {
                    Text(stringResource(R.string.dm_unblock_user), style = PyType.mono(12f), color = PyTheme.primaryDim,
                        modifier = Modifier.border(1.dp, PyTheme.primaryDim, RectangleShape)
                            .clickableNoRipple { Haptics.tap(); client.unblockUser(pubkey) }.padding(horizontal = 16.dp, vertical = 8.dp))
                } else {
                    Text(stringResource(R.string.dm_block_user), style = PyType.mono(12f), color = PyTheme.danger,
                        modifier = Modifier.border(1.dp, PyTheme.danger, RectangleShape)
                            .clickableNoRipple { Haptics.warning(); client.blockUser(pubkey); onDismiss() }.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

/** CHAT FLAIR — pick your name color. Unlocked with Pro+ (LN subscription). */
@Composable
private fun FlairPicker(client: NostrClient) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val unlocked = com.astrolexis.pyblock.data.store.EntitlementsStore.isPro
    var selected by remember { mutableStateOf(Nostr.flairColor(ctx) ?: "") }
    Spacer(Modifier.size(10.dp))
    Text(stringResource(R.string.dm_name_color), style = PyType.mono(9f), color = PyTheme.primaryDim)
    Spacer(Modifier.size(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FlairPalette.entries.forEach { (key, color) ->
            Box(
                Modifier.size(22.dp).background(color)
                    .border(2.dp, if (unlocked && selected == key) PyTheme.primary else Color.Transparent, RectangleShape)
                    .alpha(if (unlocked) 1f else 0.45f)
                    .clickableNoRipple {
                        Haptics.select()
                        if (!unlocked) {
                            com.astrolexis.pyblock.ui.screens.pro.PaywallBus.present(
                                com.astrolexis.pyblock.ui.screens.pro.PaywallContext.chatFlair)
                        } else {
                            selected = if (selected == key) "" else key
                            Nostr.setFlairColor(ctx, selected)
                            client.republishProfile()
                        }
                    },
            )
        }
    }
    if (!unlocked) {
        Text(stringResource(R.string.dm_free_with_pro), style = PyType.mono(9f), color = PyTheme.yellow,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun OutlineBtn(label: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.border(1.dp, color, RectangleShape).clickableNoRipple(onClick)
        .padding(horizontal = 16.dp, vertical = 9.dp)) {
        Text(label, style = PyType.mono(13f), color = color)
    }
}
