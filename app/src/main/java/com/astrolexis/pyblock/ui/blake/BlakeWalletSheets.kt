package com.astrolexis.pyblock.ui.blake

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.astrolexis.pyblock.data.blake.BlakeApi
import com.astrolexis.pyblock.data.blake.BlakeChains
import com.astrolexis.pyblock.data.blake.BlakeFork
import com.astrolexis.pyblock.data.crypto.VanityCrypto
import com.astrolexis.pyblock.data.crypto.toHex
import com.astrolexis.pyblock.data.wallet.VanityWallet
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.ui.components.QrCode
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import java.util.UUID

@Composable
internal fun sheetBox(title: String, accent: androidx.compose.ui.graphics.Color, onClose: () -> Unit, body: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().background(Blake.ink).border(1.dp, Blake.line, RectangleShape)
                .padding(20.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Blake.mono(15f, FontWeight.ExtraBold), color = accent, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text("✕", style = Blake.mono(18f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple(onClose))
            }
            Spacer(Modifier.height(14.dp))
            body()
        }
    }
}

@Composable
internal fun sheetBtn(label: String, accent: androidx.compose.ui.graphics.Color, filled: Boolean = false, onClick: () -> Unit) {
    Text(label, style = Blake.mono(12f, FontWeight.ExtraBold), color = if (filled) Blake.bg else accent, letterSpacing = 1.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
            .then(if (filled) Modifier.background(accent) else Modifier.border(1.dp, accent, RectangleShape))
            .padding(vertical = 12.dp).clickableNoRipple(onClick))
}

@Composable
internal fun sheetField(value: String, hint: String, keyboard: KeyboardType, enabled: Boolean = true, onChange: (String) -> Unit) {
    Column {
        Text(hint, style = Blake.mono(9f), color = Blake.faint)
        BasicTextField(
            value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
            textStyle = Blake.mono(13f).copy(color = Blake.fg),
            cursorBrush = SolidColor(Blake.pp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth().border(1.dp, Blake.line, RectangleShape).padding(10.dp),
        )
    }
}

// ---- UTXO detail ----
@Composable
fun UtxoDetailSheet(u: BlakeApi.Utxo, tip: Int, onCopy: (String) -> Unit, onClose: () -> Unit) {
    val unlockedIds by com.astrolexis.pyblock.data.blake.UnlockStore.ids.collectAsState()
    val unlocked = u.id in unlockedIds
    val replayLocked = BlakeFork.isReplayLocked(u, tip)
    var warn by remember { mutableStateOf(false) }
    sheetBox("COIN", Blake.pp, onClose) {
        Text("${Blake.btc(u.value)} ${Blake.RUNE}", style = Blake.mono(24f, FontWeight.ExtraBold), color = Blake.pp)
        Text("${"%,d".format(u.value)} sats", style = Blake.mono(10f), color = Blake.faint)
        Spacer(Modifier.height(12.dp))
        val reason = BlakeFork.lockReason(u, tip)
        val statusText = when {
            reason == null -> "spendable (mature mined)"
            unlocked -> "unlocked · replay-exposed (you accepted the risk)"
            else -> "locked · $reason"
        }
        kv("STATUS", statusText, if (reason == null) Blake.ok else if (unlocked) Blake.pp else Blake.warn)
        kv("CONFIRMATIONS", "${BlakeFork.confirmations(u, tip)}", Blake.fg)
        kv("HEIGHT", "#${u.height}", Blake.fg)
        kv("TYPE", if (u.coinbase) "coinbase (mined)" else "received", Blake.fg)
        Spacer(Modifier.height(10.dp))
        Text("TXID", style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
        Text("${u.txid}:${u.vout}", style = Blake.mono(10f), color = Blake.pp,
            modifier = Modifier.clickableNoRipple { onCopy("${u.txid}:${u.vout}") })
        Spacer(Modifier.height(14.dp))
        // Unlock / re-lock — only for replay-locked coins (immature coinbase can't be unlocked).
        if (replayLocked) {
            if (unlocked) {
                sheetBtn("🔒 RE-LOCK", Blake.warn) { com.astrolexis.pyblock.data.blake.UnlockStore.relock(u.id) }
            } else {
                sheetBtn("🔓 UNLOCK — REPLAY RISK", Blake.danger) { warn = true }
            }
            Spacer(Modifier.height(8.dp))
        }
        sheetBtn("CLOSE", Blake.ppDim) { onClose() }
    }
    if (warn) UnlockWarningDialog(
        onConfirm = { com.astrolexis.pyblock.data.blake.UnlockStore.unlock(u.id); warn = false },
        onDismiss = { warn = false },
    )
}

/** Strong replay-risk warning shown before unlocking a pre-fork/received coin. */
@Composable
private fun UnlockWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(Blake.ink).border(1.dp, Blake.danger, RectangleShape).padding(20.dp)) {
            Text("⚠ UNLOCK — REPLAY RISK", style = Blake.mono(14f, FontWeight.ExtraBold), color = Blake.danger, letterSpacing = 1.sp)
            Spacer(Modifier.height(12.dp))
            Text("This is a pre-fork or received coin. Bitcoin (SHA-256) and BLAKE2b share the same history before the fork, and this fork has NO replay protection.",
                style = Blake.mono(9f), color = Blake.fg)
            Spacer(Modifier.height(8.dp))
            Text("If you spend it here, the same transaction can be valid on BOTH chains — it can move or LOSE the matching coin on your Bitcoin (SHA-256) balance. This cannot be undone.",
                style = Blake.mono(9f), color = Blake.warn)
            Spacer(Modifier.height(8.dp))
            Text("Only unlock if you understand and accept this. Safer: move your Bitcoin coins with a Bitcoin wallet first, then these become yours alone on BLAKE2b.",
                style = Blake.mono(8f), color = Blake.faint)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { sheetBtn("CANCEL", Blake.ppDim) { onDismiss() } }
                Box(Modifier.weight(1f)) { sheetBtn("I UNDERSTAND", Blake.danger, filled = true) { onConfirm() } }
            }
        }
    }
}

@Composable
internal fun kv(k: String, v: String, accent: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text(v, style = Blake.mono(11f), color = accent)
    }
}

// ---- Address control (list + receive per address + generate/import) ----
@Composable
fun AddressControlSheet(
    wallets: List<VanityWallet>,
    onGenerate: () -> Unit,
    onCopy: (String) -> Unit,
    balanceFor: (String) -> Long,
    onSend: (Set<String>) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var receiveFor by remember { mutableStateOf<VanityWallet?>(null) }
    var detailFor by remember { mutableStateOf<VanityWallet?>(null) }
    var importing by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var wif by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var chooseTypeWif by remember { mutableStateOf<String?>(null) }   // set when neither/both funded → ask
    var newTypeAsk by remember { mutableStateOf(false) }              // "+ NEW" segwit/legacy prompt
    var dontAskAgain by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { com.astrolexis.pyblock.data.wallet.NewAddressPref.init(ctx) }

    val rf = receiveFor
    if (rf != null) { ReceiveSheet(rf, onCopy) { receiveFor = null }; return }
    val df = detailFor
    if (df != null) {
        AddressDetailSheet(df, balanceFor,
            onReceive = { detailFor = null; receiveFor = df },
            onSend = { keys -> detailFor = null; onSend(keys) },
            onCopy = onCopy) { detailFor = null }
        return
    }
    if (scanning) {
        com.astrolexis.pyblock.ui.components.QrScanner(title = "SCAN A PRIVATE KEY (WIF)",
            onResult = { code -> wif = code.trim(); importing = true; scanning = false },
            onClose = { scanning = false })
        return
    }

    sheetBox("ADDRESS CONTROL", Blake.pp, onClose) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                sheetBtn("+ NEW", Blake.pp, filled = true) {
                    when (com.astrolexis.pyblock.data.wallet.NewAddressPref.mode.value) {
                        com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.SEGWIT -> doCreateNew(ctx, true)
                        com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.LEGACY -> doCreateNew(ctx, false)
                        com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.ASK -> newTypeAsk = true
                    }
                }
            }
            Box(Modifier.weight(1f)) { sheetBtn("⛒ VANITY", Blake.pp) { onGenerate() } }
        }
        Spacer(Modifier.height(10.dp))
        sheetBtn(if (importing) "✕ IMPORT WIF" else "IMPORT WIF", Blake.pp) { importing = !importing }
        if (importing) {
            Spacer(Modifier.height(10.dp))
            sheetField(wif, "Paste WIF (K.../L.../5...)", KeyboardType.Password) { wif = it }
            Spacer(Modifier.height(6.dp))
            Text("⛶ SCAN A WIF QR", style = Blake.mono(10f), color = Blake.pp, modifier = Modifier.clickableNoRipple { scanning = true })
            Spacer(Modifier.height(8.dp))
            sheetBtn(if (probing) "CHECKING…" else "ADD KEY", Blake.ok) {
                val w = wif.trim()
                if (w.isBlank() || probing) return@sheetBtn
                probing = true
                scope.launch {
                    val p = probeWif(w)
                    probing = false
                    when {
                        p == null -> android.widget.Toast.makeText(ctx, "Invalid WIF", android.widget.Toast.LENGTH_SHORT).show()
                        // Only one side funded → import it automatically.
                        p.segwitFunded && !p.legacyFunded -> { importWifTyped(ctx, w, true); wif = ""; importing = false; android.widget.Toast.makeText(ctx, "Imported (segwit)", android.widget.Toast.LENGTH_SHORT).show() }
                        p.legacyFunded && !p.segwitFunded -> { importWifTyped(ctx, w, false); wif = ""; importing = false; android.widget.Toast.makeText(ctx, "Imported (legacy)", android.widget.Toast.LENGTH_SHORT).show() }
                        // Uncompressed WIF → legacy only (no segwit option).
                        p.segwitAddr == null -> { importWifTyped(ctx, w, false); wif = ""; importing = false; android.widget.Toast.makeText(ctx, "Imported", android.widget.Toast.LENGTH_SHORT).show() }
                        // Neither or both funded → ask the user which type.
                        else -> chooseTypeWif = w
                    }
                    if (wif.isEmpty()) com.astrolexis.pyblock.data.blake.BlakeBalanceStore.refresh(ctx)  // auto-imported → refresh
                }
            }
        }
        if (wallets.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            sheetBtn("⭳ EXPORT BACKUP PDF", Blake.warn) { exportBackup(ctx, wallets, balanceFor) }
        }
        Spacer(Modifier.height(16.dp))
        if (wallets.isEmpty()) Text("No addresses yet.", style = Blake.mono(10f), color = Blake.faint)
        else wallets.forEach { w ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, Blake.line, RectangleShape).padding(12.dp)
                .clickableNoRipple { detailFor = w }, verticalAlignment = Alignment.CenterVertically) {
                BlakeIdenticon(seed = w.address, dimen = 30.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.label.ifEmpty { "wallet" }.uppercase(), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.fg, letterSpacing = 1.sp)
                    Text(mid(w.address), style = Blake.mono(9f), color = Blake.faint)
                }
                Text("${Blake.btc(balanceFor(w.address))} ${Blake.RUNE}", style = Blake.mono(11f), color = Blake.pp)
            }
        }
    }

    // Neither (or both) address held a balance → let the user pick the type to import.
    chooseTypeWif?.let { w ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { chooseTypeWif = null }) {
            Column(Modifier.background(Blake.ink).border(1.dp, Blake.line, RectangleShape).padding(20.dp)) {
                Text("IMPORT AS", style = Blake.mono(14f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                Text("No balance found on either address. Pick the type to import.", style = Blake.mono(10f), color = Blake.faint)
                Spacer(Modifier.height(16.dp))
                sheetBtn("SEGWIT · bc1q", Blake.pp, filled = true) {
                    importWifTyped(ctx, w, true); chooseTypeWif = null; wif = ""; importing = false
                    scope.launch { com.astrolexis.pyblock.data.blake.BlakeBalanceStore.refresh(ctx) }
                    android.widget.Toast.makeText(ctx, "Imported (segwit)", android.widget.Toast.LENGTH_SHORT).show()
                }
                Spacer(Modifier.height(8.dp))
                sheetBtn("LEGACY · 1…", Blake.pp) {
                    importWifTyped(ctx, w, false); chooseTypeWif = null; wif = ""; importing = false
                    scope.launch { com.astrolexis.pyblock.data.blake.BlakeBalanceStore.refresh(ctx) }
                    android.widget.Toast.makeText(ctx, "Imported (legacy)", android.widget.Toast.LENGTH_SHORT).show()
                }
                Spacer(Modifier.height(8.dp))
                sheetBtn("CANCEL", Blake.ppDim) { chooseTypeWif = null }
            }
        }
    }

    // "+ NEW" → choose address type, with an optional "don't ask again" (reversible in Settings).
    if (newTypeAsk) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { newTypeAsk = false }) {
            Column(Modifier.background(Blake.ink).border(1.dp, Blake.line, RectangleShape).padding(20.dp)) {
                Text("NEW ADDRESS TYPE", style = Blake.mono(14f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                Text("SegWit (bc1q) is cheaper to spend and the modern default. Legacy (1…) is the classic format.",
                    style = Blake.mono(10f), color = Blake.faint)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().clickableNoRipple { dontAskAgain = !dontAskAgain }, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (dontAskAgain) "☑" else "☐", style = Blake.mono(14f), color = Blake.pp)
                    Spacer(Modifier.width(8.dp))
                    Text("Don't ask again (change in Settings)", style = Blake.mono(10f), color = Blake.ppDim)
                }
                Spacer(Modifier.height(14.dp))
                sheetBtn("SEGWIT · bc1q", Blake.pp, filled = true) {
                    if (dontAskAgain) com.astrolexis.pyblock.data.wallet.NewAddressPref.set(ctx, com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.SEGWIT)
                    doCreateNew(ctx, true); newTypeAsk = false
                }
                Spacer(Modifier.height(8.dp))
                sheetBtn("LEGACY · 1…", Blake.pp) {
                    if (dontAskAgain) com.astrolexis.pyblock.data.wallet.NewAddressPref.set(ctx, com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.LEGACY)
                    doCreateNew(ctx, false); newTypeAsk = false
                }
                Spacer(Modifier.height(8.dp))
                sheetBtn("CANCEL", Blake.ppDim) { newTypeAsk = false }
            }
        }
    }
}

@Composable
fun ReceiveSheet(wallet: VanityWallet, onCopy: (String) -> Unit, onClose: () -> Unit) {
    sheetBox("RECEIVE", Blake.pp, onClose) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.background(Blake.hero).padding(10.dp)) { QrCode(text = wallet.address, size = 220.dp) }
        }
        Spacer(Modifier.height(14.dp))
        Text(wallet.address, style = Blake.mono(11f), color = Blake.pp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        sheetBtn("COPY ADDRESS", Blake.pp, filled = true) { onCopy(wallet.address) }
        Spacer(Modifier.height(8.dp))
        sheetBtn("CLOSE", Blake.ppDim) { onClose() }
    }
}

/** Per-address wallet detail: this address' own balance + SEND (spends ONLY this address' coins) +
 *  RECEIVE. Each address behaves as an independent wallet. */
@Composable
fun AddressDetailSheet(
    wallet: VanityWallet,
    balanceFor: (String) -> Long,
    onReceive: () -> Unit,
    onSend: (Set<String>) -> Unit,
    onCopy: (String) -> Unit,
    onClose: () -> Unit,
) {
    val tip by com.astrolexis.pyblock.data.blake.BlakeBalanceStore.tip.collectAsState()
    com.astrolexis.pyblock.data.blake.BlakeBalanceStore.utxos.collectAsState().value   // recompose on UTXO change
    val mine = com.astrolexis.pyblock.data.blake.BlakeBalanceStore.allUtxos().filter { it.address == wallet.address }
    val spendable = mine.filter { BlakeFork.isEffectivelySpendable(it, tip) }
    val spendableSats = spendable.sumOf { it.value }
    val total = balanceFor(wallet.address)
    val canSend = spendableSats > 0 && BlakeChains.SEND_ENABLED
    sheetBox(wallet.label.ifEmpty { "WALLET" }.uppercase(), Blake.pp, onClose) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BlakeIdenticon(seed = wallet.address, dimen = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${Blake.btc(total)} ${Blake.RUNE}", style = Blake.mono(18f, FontWeight.ExtraBold), color = Blake.pp)
                if (spendableSats > 0) Text("${Blake.btc(spendableSats)} ${Blake.RUNE} spendable", style = Blake.mono(9f), color = Blake.ok)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(wallet.address, style = Blake.mono(10f), color = Blake.faint, modifier = Modifier.clickableNoRipple { onCopy(wallet.address) })
        Spacer(Modifier.height(16.dp))
        sheetBtn(if (canSend) "↗ SEND FROM THIS ADDRESS" else "↗ SEND", if (canSend) Blake.pp else Blake.faint, filled = canSend) {
            if (canSend) onSend(spendable.map { it.id }.toSet())
        }
        Spacer(Modifier.height(8.dp))
        sheetBtn("⭳ RECEIVE", Blake.pp) { onReceive() }
        if (spendableSats == 0L && total > 0L) {
            Spacer(Modifier.height(10.dp))
            Text("No spendable coins here — only mature mined coinbase is spendable; received/pre-fork coins are replay-locked (unlock in COIN CONTROL).",
                style = Blake.mono(8f), color = Blake.faint)
        }
        Spacer(Modifier.height(10.dp))
        sheetBtn("CLOSE", Blake.ppDim) { onClose() }
    }
}

// ---- Coins ----
@Composable
fun CoinsSheet(utxos: List<BlakeApi.Utxo>, tip: Int, onSpend: (Set<String>) -> Unit, onOpen: (BlakeApi.Utxo) -> Unit, onClose: () -> Unit) {
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val unlockedIds by com.astrolexis.pyblock.data.blake.UnlockStore.ids.collectAsState()
    sheetBox("COIN CONTROL", Blake.pp, onClose) {
        if (utxos.isEmpty()) { Text("No coins.", style = Blake.mono(10f), color = Blake.faint); return@sheetBox }
        val sorted = utxos.sortedByDescending { it.value }
        val spendableIds = sorted.filter { BlakeFork.isEffectivelySpendable(it, tip) }.map { it.id }.toSet()
        if (spendableIds.isNotEmpty() && BlakeChains.SEND_ENABLED) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Tap spendable coins to select, then SEND.", style = Blake.mono(9f), color = Blake.faint)
                Spacer(Modifier.weight(1f))
                val allOn = selected.containsAll(spendableIds)
                Text(if (allOn) "NONE" else "ALL", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.pp,
                    modifier = Modifier.clickableNoRipple { selected = if (allOn) emptySet() else spendableIds })
            }
        }
        Spacer(Modifier.height(10.dp))
        sorted.forEach { u ->
            val reason = BlakeFork.lockReason(u, tip)
            val unlocked = u.id in unlockedIds
            val spendable = BlakeFork.isEffectivelySpendable(u, tip)
            val on = u.id in selected
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                .border(1.dp, if (on) Blake.pp else Blake.line, RectangleShape).padding(12.dp)
                .then(when {
                    spendable && BlakeChains.SEND_ENABLED -> Modifier.clickableNoRipple {
                        selected = if (on) selected - u.id else selected + u.id
                    }
                    // Replay-locked (pre-fork/received) → tappable to open the detail + UNLOCK.
                    BlakeFork.isReplayLocked(u, tip) -> Modifier.clickableNoRipple { onOpen(u) }
                    else -> Modifier
                }),
                verticalAlignment = Alignment.CenterVertically) {
                Text(when { on -> "◉"; !spendable -> "🔒"; unlocked -> "🔓"; else -> "○" },
                    style = Blake.mono(12f), color = if (on) Blake.pp else Blake.faint)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${Blake.btc(u.value)} ${Blake.RUNE}", style = Blake.mono(12f, FontWeight.ExtraBold),
                        color = if (reason == null) Blake.ok else if (unlocked) Blake.pp else Blake.warn)
                    Text(if (unlocked) "unlocked · replay risk"
                         else if (!spendable && BlakeFork.isReplayLocked(u, tip)) "${reason ?: "received"} · tap to unlock"
                         else (reason ?: (if (u.coinbase) "mined · spendable" else "received")),
                        style = Blake.mono(8f), color = if (unlocked) Blake.pp else Blake.faint)
                }
                Text("#${u.height}", style = Blake.mono(9f), color = Blake.faint)
            }
        }
        if (selected.isNotEmpty() && BlakeChains.SEND_ENABLED) {
            val selSats = sorted.filter { it.id in selected }.sumOf { it.value }
            Spacer(Modifier.height(6.dp))
            sheetBtn("SEND ${selected.size} COIN${if (selected.size == 1) "" else "S"} · ${Blake.btc(selSats)} ${Blake.RUNE}",
                Blake.pp, filled = true) { onSpend(selected) }
        }
    }
}

// ---- Send / Ricochet ----
@Composable
fun SendSheet(onSend: (String, Long, Boolean, Long, Boolean) -> Unit, paste: () -> String, onClose: () -> Unit) {
    var addr by remember { mutableStateOf("") }
    var amt by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("2") }
    var max by remember { mutableStateOf(false) }
    var ricochet by remember { mutableStateOf(false) }
    sheetBox("SEND BLAKE2b", Blake.pp, onClose) {
        Text("mature mined coinbase only", style = Blake.mono(9f), color = Blake.faint)
        Spacer(Modifier.height(12.dp))
        sheetField(addr, "Recipient address", KeyboardType.Text) { addr = it }
        Spacer(Modifier.height(4.dp))
        Text("PASTE", style = Blake.mono(10f), color = Blake.pp, modifier = Modifier.clickableNoRipple { addr = paste() })
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { sheetField(if (max) "MAX" else amt, "Amount (sats)", KeyboardType.Number, enabled = !max) { amt = it.filter { c -> c.isDigit() } } }
            Spacer(Modifier.width(10.dp))
            Text(if (max) "◉ MAX" else "○ MAX", style = Blake.mono(12f), color = Blake.warn, modifier = Modifier.clickableNoRipple { max = !max })
        }
        Spacer(Modifier.height(10.dp))
        sheetField(fee, "Fee (sat/vB)", KeyboardType.Number) { fee = it.filter { c -> c.isDigit() } }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickableNoRipple { ricochet = !ricochet }) {
            Text(if (ricochet) "◉" else "○", style = Blake.mono(12f), color = Blake.pp)
            Spacer(Modifier.width(6.dp))
            Text("RICOCHET (3 hops · coordinator-free)", style = Blake.mono(10f), color = Blake.ppDim)
        }
        Spacer(Modifier.height(14.dp))
        sheetBtn(if (ricochet) "RICOCHET SEND" else "SEND", Blake.pp, filled = true) {
            val a = if (max) 0L else amt.toLongOrNull() ?: 0L
            val f = fee.toLongOrNull() ?: 2L
            if (addr.isNotBlank() && (max || a > 0)) onSend(addr.trim(), a, max, f, ricochet)
        }
    }
}

// ---- Currency picker ----
@Composable
fun CurrencyPickerSheet(currencies: List<String>, onPick: (String) -> Unit) {
    sheetBox("CURRENCY", Blake.pp, { onPick(currencies.firstOrNull() ?: "USD") }) {
        currencies.forEach { c ->
            Text(c, style = Blake.mono(14f), color = Blake.fg,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).clickableNoRipple { onPick(c) })
        }
    }
}

// ---- Settings ----
@Composable
fun SettingsSheet(operational: Boolean, rc: String?, height: Int, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { com.astrolexis.pyblock.data.wallet.NewAddressPref.init(ctx) }
    val newMode by com.astrolexis.pyblock.data.wallet.NewAddressPref.mode.collectAsState()
    sheetBox("SETTINGS", Blake.pp, onClose) {
        kv("NETWORK", if (operational) "operational" else "${rc ?: "RC"} · testing", if (operational) Blake.ok else Blake.warn)
        kv("TIMECHAIN", "#$height", Blake.fg)
        kv("APP", "PyBLØCK ${Blake.RUNE} 0.1.0", Blake.fg)
        Spacer(Modifier.height(6.dp))
        // Tap to cycle the "+ NEW" address type (reversible; "ask each time" re-enables the prompt).
        Row(Modifier.fillMaxWidth().clickableNoRipple {
            val next = when (newMode) {
                com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.ASK -> com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.SEGWIT
                com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.SEGWIT -> com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.LEGACY
                com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.LEGACY -> com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.ASK
            }
            com.astrolexis.pyblock.data.wallet.NewAddressPref.set(ctx, next)
        }, verticalAlignment = Alignment.CenterVertically) {
            Text("NEW ADDRESSES", style = Blake.mono(10f), color = Blake.faint, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(when (newMode) {
                com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.ASK -> "ask each time ▸"
                com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.SEGWIT -> "SegWit (bc1q) ▸"
                com.astrolexis.pyblock.data.wallet.NewAddressPref.Mode.LEGACY -> "Legacy (1…) ▸"
            }, style = Blake.mono(11f), color = Blake.pp)
        }
        Spacer(Modifier.height(14.dp))
        Text("BLAKE2b is Bitcoin under a BLAKE2b proof-of-work. Coins are read from the PyBLØCK node; only mature mined coinbase is spendable (non-replayable).",
            style = Blake.mono(9f), color = Blake.faint)
        Spacer(Modifier.height(14.dp))
        sheetBtn("CLOSE", Blake.ppDim) { onClose() }
    }
}

// ---- helpers ----
internal fun mid(s: String, h: Int = 12, t: Int = 8): String = if (s.length <= h + t + 1) s else "${s.take(h)}…${s.takeLast(t)}"

/** Build a paper-backup PDF of every wallet (address + WIF) and open it. */
private fun exportBackup(ctx: android.content.Context, wallets: List<VanityWallet>, balanceFor: (String) -> Long) {
    val entries = wallets.map { w ->
        com.astrolexis.pyblock.data.wallet.BackupPdf.Entry(
            label = w.label.ifEmpty { "wallet" },
            address = w.address,
            wif = WalletStore.wif(ctx, w.id) ?: "",
            balanceSats = balanceFor(w.address),
        )
    }.filter { it.wif.isNotBlank() }
    if (entries.isEmpty()) { android.widget.Toast.makeText(ctx, "Unlock the vault to export keys", android.widget.Toast.LENGTH_SHORT).show(); return }
    val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
    val paynym = runCatching { com.astrolexis.pyblock.data.crypto.PaymentCode.myCode(ctx) }.getOrNull()?.takeIf { it.isNotEmpty() }
    val file = com.astrolexis.pyblock.data.wallet.BackupPdf.generate(ctx, entries, paynym, null, f)
    if (file == null) { android.widget.Toast.makeText(ctx, "Export failed", android.widget.Toast.LENGTH_SHORT).show(); return }
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/pdf")
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }.onFailure { android.widget.Toast.makeText(ctx, "No PDF viewer", android.widget.Toast.LENGTH_SHORT).show() }
}

/** Generate a fresh random BLAKE2b wallet (own vault) — the "+ NEW" path, matching iOS
 *  `store.generate()`. Full-entropy CSPRNG key (VanityCrypto.hardenedRandom32), compressed
 *  K/L WIF, watch-only pubkey cached. Returns false only if the key math fails. */
private fun doCreateNew(ctx: android.content.Context, segwit: Boolean) {
    if (createRandomWallet(ctx, segwit))
        android.widget.Toast.makeText(ctx, if (segwit) "New SegWit address created" else "New legacy address created", android.widget.Toast.LENGTH_SHORT).show()
    else android.widget.Toast.makeText(ctx, "Couldn't create address", android.widget.Toast.LENGTH_SHORT).show()
}

fun createRandomWallet(ctx: android.content.Context, segwit: Boolean = true): Boolean {
    val priv = VanityCrypto.hardenedRandom32(ByteArray(0))
    val pub = VanityCrypto.compressedPubkey(priv) ?: return false
    val addr = if (segwit) VanityCrypto.p2wpkhAddress(pub) else VanityCrypto.p2pkhAddress(pub)
    val wif = VanityCrypto.wifCompressed(priv)
    return WalletStore.add(
        ctx,
        VanityWallet(id = UUID.randomUUID().toString(), label = "", address = addr,
            compressed = true, birthday = BlakeFork.FORK_HEIGHT, pubkeyHex = pub.toHex(), segwit = segwit),
        wif,
    )
}

/** Import a WIF with the caller-chosen address type. Returns false on invalid key. */
fun importWifTyped(ctx: android.content.Context, wif: String, segwit: Boolean): Boolean {
    val (priv, compressed) = VanityCrypto.decodeWif(wif) ?: return false
    val addr: String; val pubHex: String
    if (segwit) {
        val cpub = VanityCrypto.compressedPubkey(priv) ?: return false   // segwit = compressed only
        addr = VanityCrypto.p2wpkhAddress(cpub); pubHex = cpub.toHex()
    } else {
        val pub = (if (compressed) VanityCrypto.compressedPubkey(priv) else VanityCrypto.uncompressedPubkey(priv)) ?: return false
        addr = VanityCrypto.p2pkhAddress(pub); pubHex = pub.toHex()
    }
    return WalletStore.add(
        ctx,
        VanityWallet(id = UUID.randomUUID().toString(), label = "imported", address = addr,
            compressed = compressed, birthday = BlakeFork.FORK_HEIGHT, pubkeyHex = pubHex, segwit = segwit),
        wif.trim(),
    )
}

/** The candidate addresses (legacy + segwit) for a WIF and which of them currently hold a balance,
 *  so import can auto-pick the funded one (or ask if neither/both). segwitAddr is null for an
 *  uncompressed "5…" WIF (native SegWit is compressed-only). */
data class ImportProbe(val legacyAddr: String, val segwitAddr: String?, val legacyFunded: Boolean, val segwitFunded: Boolean)

suspend fun probeWif(wif: String): ImportProbe? {
    val (priv, compressed) = VanityCrypto.decodeWif(wif.trim()) ?: return null
    val legacyPub = (if (compressed) VanityCrypto.compressedPubkey(priv) else VanityCrypto.uncompressedPubkey(priv)) ?: return null
    val legacyAddr = VanityCrypto.p2pkhAddress(legacyPub)
    val segwitAddr = if (compressed) VanityCrypto.compressedPubkey(priv)?.let { VanityCrypto.p2wpkhAddress(it) } else null
    val legFunded = (com.astrolexis.pyblock.data.blake.BlakeApi.walletUtxos(legacyAddr)?.first?.sumOf { it.value } ?: 0L) > 0L
    val segFunded = segwitAddr != null && (com.astrolexis.pyblock.data.blake.BlakeApi.walletUtxos(segwitAddr)?.first?.sumOf { it.value } ?: 0L) > 0L
    return ImportProbe(legacyAddr, segwitAddr, legFunded, segFunded)
}
