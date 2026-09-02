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
    sheetBox("COIN", Blake.pp, onClose) {
        Text("${Blake.btc(u.value)} ${Blake.RUNE}", style = Blake.mono(24f, FontWeight.ExtraBold), color = Blake.pp)
        Text("${"%,d".format(u.value)} sats", style = Blake.mono(10f), color = Blake.faint)
        Spacer(Modifier.height(12.dp))
        val reason = BlakeFork.lockReason(u, tip)
        kv("STATUS", if (reason == null) "spendable (mature mined)" else "locked · $reason", if (reason == null) Blake.ok else Blake.warn)
        kv("CONFIRMATIONS", "${BlakeFork.confirmations(u, tip)}", Blake.fg)
        kv("HEIGHT", "#${u.height}", Blake.fg)
        kv("TYPE", if (u.coinbase) "coinbase (mined)" else "received", Blake.fg)
        Spacer(Modifier.height(10.dp))
        Text("TXID", style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
        Text("${u.txid}:${u.vout}", style = Blake.mono(10f), color = Blake.pp,
            modifier = Modifier.clickableNoRipple { onCopy("${u.txid}:${u.vout}") })
        Spacer(Modifier.height(14.dp))
        sheetBtn("CLOSE", Blake.ppDim) { onClose() }
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
    onImport: (String) -> Boolean,
    onCopy: (String) -> Unit,
    balanceFor: (String) -> Long,
    onClose: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var receiveFor by remember { mutableStateOf<VanityWallet?>(null) }
    var importing by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var wif by remember { mutableStateOf("") }

    val rf = receiveFor
    if (rf != null) { ReceiveSheet(rf, onCopy) { receiveFor = null }; return }
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
                    if (createRandomWallet(ctx)) android.widget.Toast.makeText(ctx, "New address created", android.widget.Toast.LENGTH_SHORT).show()
                    else android.widget.Toast.makeText(ctx, "Couldn't create address", android.widget.Toast.LENGTH_SHORT).show()
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
            sheetBtn("ADD KEY", Blake.ok) { if (wif.isNotBlank() && onImport(wif.trim())) { wif = ""; importing = false } }
        }
        if (wallets.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            sheetBtn("⭳ EXPORT BACKUP PDF", Blake.warn) { exportBackup(ctx, wallets, balanceFor) }
        }
        Spacer(Modifier.height(16.dp))
        if (wallets.isEmpty()) Text("No addresses yet.", style = Blake.mono(10f), color = Blake.faint)
        else wallets.forEach { w ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, Blake.line, RectangleShape).padding(12.dp)
                .clickableNoRipple { receiveFor = w }, verticalAlignment = Alignment.CenterVertically) {
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

// ---- Coins ----
@Composable
fun CoinsSheet(utxos: List<BlakeApi.Utxo>, tip: Int, onClose: () -> Unit) {
    sheetBox("COIN CONTROL", Blake.pp, onClose) {
        if (utxos.isEmpty()) Text("No coins.", style = Blake.mono(10f), color = Blake.faint)
        else utxos.sortedByDescending { it.value }.forEach { u ->
            val reason = BlakeFork.lockReason(u, tip)
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, Blake.line, RectangleShape).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (reason == null) "🔓" else "🔒", style = Blake.mono(11f), color = Blake.faint)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${Blake.btc(u.value)} ${Blake.RUNE}", style = Blake.mono(12f, FontWeight.ExtraBold), color = if (reason == null) Blake.ok else Blake.warn)
                    Text(reason ?: (if (u.coinbase) "mined · spendable" else "received"), style = Blake.mono(8f), color = Blake.faint)
                }
                Text("#${u.height}", style = Blake.mono(9f), color = Blake.faint)
            }
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
    sheetBox("SETTINGS", Blake.pp, onClose) {
        kv("NETWORK", if (operational) "operational" else "${rc ?: "RC"} · testing", if (operational) Blake.ok else Blake.warn)
        kv("TIMECHAIN", "#$height", Blake.fg)
        kv("APP", "PyBLØCK ${Blake.RUNE} 0.1.0", Blake.fg)
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
fun createRandomWallet(ctx: android.content.Context): Boolean {
    val priv = VanityCrypto.hardenedRandom32(ByteArray(0))
    val pub = VanityCrypto.compressedPubkey(priv) ?: return false
    val addr = VanityCrypto.p2pkhAddress(pub)
    val wif = VanityCrypto.wifCompressed(priv)
    return WalletStore.add(
        ctx,
        VanityWallet(id = UUID.randomUUID().toString(), label = "", address = addr,
            compressed = true, birthday = BlakeFork.FORK_HEIGHT, pubkeyHex = pub.toHex()),
        wif,
    )
}

/** Import a WIF as a new BLAKE2b wallet (own vault). Returns false on invalid key. */
fun importWif(ctx: android.content.Context, wif: String): Boolean {
    val (priv, compressed) = VanityCrypto.decodeWif(wif) ?: return false
    val pub = if (compressed) VanityCrypto.compressedPubkey(priv) else VanityCrypto.uncompressedPubkey(priv)
    pub ?: return false
    val addr = VanityCrypto.p2pkhAddress(pub)
    return WalletStore.add(
        ctx,
        VanityWallet(id = UUID.randomUUID().toString(), label = "imported", address = addr,
            compressed = compressed, birthday = BlakeFork.FORK_HEIGHT, pubkeyHex = pub.toHex()),
        wif.trim(),
    )
}
