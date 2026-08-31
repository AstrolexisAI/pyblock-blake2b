package com.astrolexis.pyblock.ui.blake

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.blake.BlakeApi
import com.astrolexis.pyblock.data.blake.BlakeBalanceStore
import com.astrolexis.pyblock.data.blake.BlakeChains
import com.astrolexis.pyblock.data.blake.BlakeFork
import com.astrolexis.pyblock.data.blake.BlakeSpend
import com.astrolexis.pyblock.data.crypto.VanityCrypto
import com.astrolexis.pyblock.data.crypto.toHex
import com.astrolexis.pyblock.data.wallet.VanityWallet
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.QrCode
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** WALLET — key-owning BLAKE2b wallet: balance (spendable vs locked), receive, generate/
 *  import, and coinbase-only send. Balances are server-read (Node B); keys live in the
 *  shared vault. Mirrors iOS WalletView. */
@Composable
fun BlakeWalletScreen(onLaunchVanity: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    val wallets by WalletStore.wallets.collectAsState()
    val utxos by BlakeBalanceStore.utxos.collectAsState()
    val tip by BlakeBalanceStore.tip.collectAsState()
    val loading by BlakeBalanceStore.loading.collectAsState()

    var sheet by remember { mutableStateOf<Sheet?>(null) }

    LaunchedEffect(Unit) {
        WalletStore.ensureLoaded(ctx)
        while (true) { BlakeBalanceStore.refresh(ctx); delay(20_000) }
    }

    val total = BlakeBalanceStore.totalSats()
    val spendable = BlakeBalanceStore.spendableSats()
    val locked = BlakeBalanceStore.lockedSats()

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarqueeTitle(text = "WALLET", accent = PyTheme.primary)
                Spacer(Modifier.weight(1f))
                Text(if (loading) "● SYNC" else "● LIVE",
                    style = PyType.mono(10f), color = if (loading) PyTheme.yellow else PyTheme.primary, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(16.dp))

            // Balance card
            Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fmt(total) + " sats", style = PyType.mono(30f), color = PyTheme.yellow, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    breakdown("SPENDABLE", spendable, PyTheme.primary)
                    breakdown("LOCKED", locked, PyTheme.primaryDim)
                }
                if (locked > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("locked = replay-exposed or immature coinbase", style = PyType.mono(8f), color = PyTheme.primaryDim)
                }
            }

            Spacer(Modifier.height(14.dp))
            if (wallets.isEmpty()) {
                Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No wallet yet.", style = PyType.mono(12f), color = PyTheme.primaryDim)
                    Spacer(Modifier.height(10.dp))
                    actionBtn("GENERATE", PyTheme.magenta) { onLaunchVanity() }
                    Spacer(Modifier.height(8.dp))
                    actionBtn("IMPORT KEY", PyTheme.cyan) { sheet = Sheet.Import }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { actionBtn("RECEIVE", PyTheme.primary) { sheet = Sheet.Receive(wallets.first()) } }
                    Box(Modifier.weight(1f)) {
                        actionBtn("SEND", PyTheme.magenta) {
                            if (spendable <= 0) toast(ctx, "No spendable coins yet (mature mined coinbase only).")
                            else sheet = Sheet.Send
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { actionBtn("GENERATE", PyTheme.cyan) { onLaunchVanity() } }
                    Box(Modifier.weight(1f)) { actionBtn("IMPORT", PyTheme.cyan) { sheet = Sheet.Import } }
                }

                Spacer(Modifier.height(18.dp))
                Text("MY WALLETS", style = PyType.mono(12f), color = PyTheme.yellow, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                wallets.forEach { w ->
                    val bal = BlakeBalanceStore.balanceForAddress(w.address)
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).moduleFrame(PyTheme.magenta).padding(12.dp)
                        .clickableNoRipple { Haptics.tap(); sheet = Sheet.Receive(w) }, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(w.label.ifEmpty { "wallet" }.uppercase(), style = PyType.mono(13f), color = PyTheme.cyan, letterSpacing = 1.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(mid(w.address), style = PyType.mono(11f), color = PyTheme.primaryDim)
                        }
                        Text(fmt(bal), style = PyType.mono(13f), color = PyTheme.yellow)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("COINS", style = PyType.mono(12f), color = PyTheme.yellow, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
                val allU = BlakeBalanceStore.allUtxos()
                if (allU.isEmpty()) Text("No coins.", style = PyType.mono(10f), color = PyTheme.primaryDim)
                else allU.sortedByDescending { it.value }.forEach { u ->
                    val reason = BlakeFork.lockReason(u, tip)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (reason == null) "🔓" else "🔒", style = PyType.mono(11f), color = PyTheme.primaryDim)
                        Spacer(Modifier.height(0.dp)); Spacer(Modifier.weight(0.02f))
                        Column(Modifier.weight(1f).padding(start = 6.dp)) {
                            Text(fmt(u.value) + " sats", style = PyType.mono(12f), color = if (reason == null) PyTheme.primary else PyTheme.primaryDim)
                            Text(reason ?: (if (u.coinbase) "mined · spendable" else "received"), style = PyType.mono(8f), color = PyTheme.primaryDim)
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    when (val s = sheet) {
        is Sheet.Receive -> ReceiveSheet(s.wallet, onCopy = { clip.setText(AnnotatedString(it)); toast(ctx, "Address copied") }) { sheet = null }
        Sheet.Import -> ImportSheet(
            onImport = { wif ->
                val ok = importWif(ctx, wif)
                toast(ctx, if (ok) "Wallet imported" else "Invalid WIF")
                if (ok) { scope.launch { BlakeBalanceStore.refresh(ctx) }; sheet = null }
            },
            onClose = { sheet = null },
        )
        Sheet.Send -> SendSheet(
            onSend = { addr, amt, max, fee ->
                scope.launch {
                    try {
                        val txid = BlakeSpend.send(ctx, addr, amt, max, fee)
                        toast(ctx, "Sent · ${txid.take(12)}…")
                        BlakeBalanceStore.refresh(ctx)
                        sheet = null
                    } catch (e: Exception) {
                        toast(ctx, e.message ?: "Send failed")
                    }
                }
            },
            pasteAddress = { clip.getText()?.text ?: "" },
            onClose = { sheet = null },
        )
        null -> {}
    }
}

private sealed class Sheet {
    data class Receive(val wallet: VanityWallet) : Sheet()
    object Import : Sheet()
    object Send : Sheet()
}

@Composable
private fun breakdown(label: String, sats: Long, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = PyType.mono(9f), color = PyTheme.primaryDim, letterSpacing = 1.sp)
        Text(fmt(sats), style = PyType.mono(13f), color = color)
    }
}

@Composable
private fun actionBtn(label: String, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(label, style = PyType.mono(13f), color = accent, textAlign = TextAlign.Center, letterSpacing = 2.sp,
        modifier = Modifier.fillMaxWidth().moduleFrame(accent).padding(vertical = 12.dp)
            .clickableNoRipple { Haptics.tap(); onClick() })
}

@Composable
private fun ReceiveSheet(wallet: VanityWallet, onCopy: (String) -> Unit, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(PyTheme.bg).moduleFrame(PyTheme.primary).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("RECEIVE", style = PyType.mono(16f), color = PyTheme.primary, letterSpacing = 3.sp)
            Spacer(Modifier.height(14.dp))
            QrCode(text = wallet.address, size = 220.dp)
            Spacer(Modifier.height(14.dp))
            Text(wallet.address, style = PyType.mono(11f), color = PyTheme.cyan, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            actionBtn("COPY", PyTheme.cyan) { onCopy(wallet.address) }
            Spacer(Modifier.height(8.dp))
            actionBtn("CLOSE", PyTheme.primaryDim) { onClose() }
        }
    }
}

@Composable
private fun ImportSheet(onImport: (String) -> Unit, onClose: () -> Unit) {
    var wif by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(PyTheme.bg).moduleFrame(PyTheme.cyan).padding(20.dp)) {
            Text("IMPORT KEY (WIF)", style = PyType.mono(15f), color = PyTheme.cyan, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            field(wif, "Paste WIF (K.../L.../5...)", KeyboardType.Password) { wif = it }
            Spacer(Modifier.height(12.dp))
            actionBtn("IMPORT", PyTheme.primary) { if (wif.isNotBlank()) onImport(wif.trim()) }
            Spacer(Modifier.height(8.dp))
            actionBtn("CANCEL", PyTheme.primaryDim) { onClose() }
        }
    }
}

@Composable
private fun SendSheet(onSend: (String, Long, Boolean, Long) -> Unit, pasteAddress: () -> String, onClose: () -> Unit) {
    var addr by remember { mutableStateOf("") }
    var amt by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("2") }
    var max by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(PyTheme.bg).moduleFrame(PyTheme.magenta).padding(20.dp)) {
            Text("SEND BLAKE2b", style = PyType.mono(15f), color = PyTheme.magenta, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Text("mature mined coinbase only", style = PyType.mono(9f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(12.dp))
            field(addr, "Recipient address", KeyboardType.Text) { addr = it }
            Spacer(Modifier.height(6.dp))
            Text("PASTE", style = PyType.mono(10f), color = PyTheme.cyan,
                modifier = Modifier.clickableNoRipple { addr = pasteAddress() })
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { field(if (max) "MAX" else amt, "Amount (sats)", KeyboardType.Number, enabled = !max) { amt = it.filter { c -> c.isDigit() } } }
                Spacer(Modifier.height(0.dp)); Spacer(Modifier.weight(0.02f))
                Text(if (max) "◉ MAX" else "○ MAX", style = PyType.mono(12f), color = PyTheme.yellow,
                    modifier = Modifier.padding(start = 10.dp).clickableNoRipple { max = !max })
            }
            Spacer(Modifier.height(8.dp))
            field(fee, "Fee (sat/vB)", KeyboardType.Number) { fee = it.filter { c -> c.isDigit() } }
            Spacer(Modifier.height(14.dp))
            actionBtn("REVIEW & SEND", PyTheme.magenta) {
                val a = if (max) 0L else amt.toLongOrNull() ?: 0L
                val f = fee.toLongOrNull() ?: 2L
                if (addr.isNotBlank() && (max || a > 0)) onSend(addr.trim(), a, max, f)
            }
            Spacer(Modifier.height(8.dp))
            actionBtn("CANCEL", PyTheme.primaryDim) { onClose() }
        }
    }
}

@Composable
private fun field(value: String, hint: String, keyboard: KeyboardType, enabled: Boolean = true, onChange: (String) -> Unit) {
    Column {
        Text(hint, style = PyType.mono(9f), color = PyTheme.primaryDim)
        BasicTextField(
            value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
            textStyle = PyType.mono(13f).copy(color = PyTheme.cyan),
            cursorBrush = SolidColor(PyTheme.primary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(10.dp),
        )
    }
}

// ---- helpers ----

private fun fmt(sats: Long): String = "%,d".format(sats)
private fun mid(s: String): String = if (s.length <= 20) s else "${s.take(12)}…${s.takeLast(8)}"
private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

/** Import a WIF as a new BLAKE2b wallet (own vault). Returns false on invalid key. */
private fun importWif(ctx: android.content.Context, wif: String): Boolean {
    val (priv, compressed) = VanityCrypto.decodeWif(wif) ?: return false
    val pub = if (compressed) VanityCrypto.compressedPubkey(priv) else VanityCrypto.uncompressedPubkey(priv)
    pub ?: return false
    val addr = VanityCrypto.p2pkhAddress(pub)
    val id = UUID.randomUUID().toString()
    return WalletStore.add(
        ctx,
        VanityWallet(id = id, label = "imported", address = addr, compressed = compressed,
            birthday = BlakeFork.FORK_HEIGHT, pubkeyHex = pub.toHex()),
        wif.trim(),
    )
}
