package com.astrolexis.pyblock.ui.blake

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.blake.BlakeApi
import com.astrolexis.pyblock.data.blake.BlakeBalanceStore
import com.astrolexis.pyblock.data.blake.BlakeChains
import com.astrolexis.pyblock.data.blake.BlakeFork
import com.astrolexis.pyblock.data.blake.BlakePrice
import com.astrolexis.pyblock.data.blake.BlakeStatus
import com.astrolexis.pyblock.data.wallet.VanityWallet
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** WALLET — clean overview: balance (spendable/locked), quick actions, recent activity.
 *  Faithful port of iOS WalletView — flat Blake aesthetic (black, ink cards, hairlines). */
@Composable
fun BlakeWalletScreen(onLaunchVanity: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    val wallets by WalletStore.wallets.collectAsState()
    BlakeBalanceStore.utxos.collectAsState().value      // recompose on utxo change
    val tip by BlakeBalanceStore.tip.collectAsState()
    val loading by BlakeBalanceStore.loading.collectAsState()
    val rates by BlakePrice.rates.collectAsState()
    val ccy by BlakePrice.currency.collectAsState()
    val live by BlakeBalanceStore.live.collectAsState()
    val sentRecords by com.astrolexis.pyblock.data.blake.BlakeSentStore.records.collectAsState()
    val operational by BlakeStatus.operational.collectAsState()
    val statusLoaded by BlakeStatus.loaded.collectAsState()
    val rc by BlakeStatus.rc.collectAsState()
    val statusHeight by BlakeStatus.blockHeight.collectAsState()

    var showDetails by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    LaunchedEffect(Unit) {
        WalletStore.ensureLoaded(ctx)
        BlakePrice.init(ctx)
        com.astrolexis.pyblock.data.blake.BlakeSentStore.init(ctx)
        BlakeBalanceStore.refresh(ctx)      // seed
        BlakeBalanceStore.startLive(ctx)    // live push (WebSocket) — no time-based polling
        // Slow safety refresh for price/status + a balance backstop if the socket drops.
        while (true) {
            BlakePrice.refresh(); BlakeStatus.refresh()
            if (!BlakeBalanceStore.live.value) BlakeBalanceStore.refresh(ctx)
            delay(30_000)
        }
    }
    // recompute derived off rates too
    rates.size

    val total = BlakeBalanceStore.totalSats()
    val spendable = BlakeBalanceStore.spendableSats()
    val locked = BlakeBalanceStore.lockedSats()

    Box(Modifier.fillMaxSize().background(Blake.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            if (statusLoaded && !operational) {
                Row(Modifier.fillMaxWidth().border(1.dp, Blake.warn.copy(alpha = 0.5f), RectangleShape).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(Blake.warn, CircleShape))
                    Spacer(Modifier.size(8.dp))
                    Text("${rc ?: "RC"} — TESTING ON MAINNET", style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.warn, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    Text("#$statusHeight", style = Blake.mono(9f), color = Blake.faint)
                }
                Spacer(Modifier.height(20.dp))
            }

            // Total balance card
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BLAKE2b BALANCE", style = Blake.mono(10f), color = Blake.ppDim, letterSpacing = 2.sp)
                    if (live) {
                        Spacer(Modifier.size(6.dp))
                        Box(Modifier.size(5.dp).background(Blake.ok, CircleShape))
                        Spacer(Modifier.size(3.dp))
                        Text("LIVE", style = Blake.mono(8f, FontWeight.ExtraBold), color = Blake.ok, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("⚙", style = Blake.mono(14f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple { sheet = Sheet.Settings })
                }
                Spacer(Modifier.height(6.dp))
                Text("${Blake.btc(total)} ${Blake.RUNE}", style = Blake.mono(26f, FontWeight.ExtraBold), color = Blake.pp, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickableNoRipple { sheet = Sheet.Currency }) {
                    BlakePrice.fiatLabel(total)?.let {
                        Text(it, style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.fg)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(ccy, style = Blake.mono(9f), color = Blake.pp,
                        modifier = Modifier.border(1.dp, Blake.line, RectangleShape).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text("${"%,d".format(total)} sats · ${wallets.size} address${if (wallets.size == 1) "" else "es"}",
                    style = Blake.mono(9f), color = Blake.faint)
                if (loading) Text("⟳ scanning…", style = Blake.mono(9f), color = Blake.pp)
            }

            // Send actions
            if ((BlakeChains.SEND_ENABLED || BlakeChains.RICOCHET_ENABLED) && wallets.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("↗ SEND", style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.bg, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).background(Blake.pp).padding(vertical = 12.dp)
                            .clickableNoRipple { if (spendable <= 0) toast(ctx, "No spendable coins yet.") else sheet = Sheet.Send })
                    if (BlakeChains.RICOCHET_ENABLED) {
                        Text("⟿ RICOCHET", style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).border(1.dp, Blake.pp, RectangleShape).padding(vertical = 12.dp)
                                .clickableNoRipple { if (spendable <= 0) toast(ctx, "No spendable coins yet.") else sheet = Sheet.Send })
                    }
                }
            }

            // Control buttons
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                navBtn(Modifier.weight(1f), "⌗ ADDRESS CONTROL", "${wallets.size}") { sheet = Sheet.Addresses }
                if (BlakeBalanceStore.allUtxos().isNotEmpty())
                    navBtn(Modifier.weight(1f), "◈ COIN CONTROL", "${BlakeBalanceStore.allUtxos().size}") { sheet = Sheet.Coins }
            }

            // PayNym entry
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().border(1.dp, Blake.line, RectangleShape).padding(horizontal = 14.dp, vertical = 11.dp)
                .clickableNoRipple { sheet = Sheet.Addresses }, verticalAlignment = Alignment.CenterVertically) {
                Text("᛭ PAYNYM", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text("share · receive", style = Blake.mono(9f), color = Blake.faint)
            }

            if (wallets.isEmpty()) {
                Spacer(Modifier.height(28.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Blake.RUNE, style = Blake.mono(40f, FontWeight.ExtraBold), color = Blake.pp.copy(alpha = 0.5f))
                    Spacer(Modifier.height(10.dp))
                    Text("No addresses yet", style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 1.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Open ADDRESS CONTROL to generate one, or import a WIF.",
                        style = Blake.mono(9f), color = Blake.faint, textAlign = TextAlign.Center)
                }
            } else {
                // DETAILS toggle
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth().border(1.dp, Blake.line, RectangleShape).padding(vertical = 10.dp, horizontal = 4.dp)
                    .clickableNoRipple { showDetails = !showDetails }, verticalAlignment = Alignment.CenterVertically) {
                    Text("DETAILS", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
                    Spacer(Modifier.size(6.dp))
                    Text(if (showDetails) "▲" else "▼", style = Blake.mono(9f), color = Blake.ppDim)
                }
                AnimatedVisibility(showDetails) {
                    Column {
                        Spacer(Modifier.height(14.dp))
                        Column(Modifier.fillMaxWidth().blakeCard()) {
                            breakdownRow("SPENDABLE", spendable, Blake.ok)
                            Spacer(Modifier.height(8.dp))
                            breakdownRow("LOCKED", locked, Blake.warn)
                            Spacer(Modifier.height(6.dp))
                            Text("Spendable = mature mined coins. Locked = immature or pre-fork (replay-exposed).",
                                style = Blake.mono(7f), color = Blake.faint)
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("ACTIVITY", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                        Spacer(Modifier.height(12.dp))
                        // Outgoing sends (locally recorded) — newest first, pending/confirmed.
                        val liveCoinIds = BlakeBalanceStore.allUtxos().map { it.id }.toSet()
                        sentRecords.take(20).forEach { r ->
                            val pending = com.astrolexis.pyblock.data.blake.BlakeSentStore.isPending(r, liveCoinIds)
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).blakeCard(12.dp)
                                .clickableNoRipple { clip.setText(AnnotatedString(r.id)); toast(ctx, "TXID copied") },
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("− ${Blake.btc(r.amountSats)} ${Blake.RUNE}${if (r.ricochet) " · ricochet" else ""}",
                                        style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.warn)
                                    Text("to ${r.toAddress.take(10)}…${r.toAddress.takeLast(8)}", style = Blake.mono(8f), color = Blake.faint)
                                }
                                Text(if (pending) "pending" else "sent", style = Blake.mono(9f), color = if (pending) Blake.warn else Blake.ppDim)
                            }
                        }
                        val rows = BlakeBalanceStore.allUtxos().sortedByDescending { it.height }.take(30)
                        if (rows.isEmpty() && sentRecords.isEmpty()) Text("No movements yet.", style = Blake.mono(10f), color = Blake.faint)
                        else rows.forEach { u ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).blakeCard(12.dp)
                                .clickableNoRipple { sheet = Sheet.Utxo(u) }, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("+ ${Blake.btc(u.value)} ${Blake.RUNE}", style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.ok)
                                    Text("${if (u.coinbase) "mined · " else ""}block #${u.height}", style = Blake.mono(8f), color = Blake.faint)
                                }
                                val conf = BlakeFork.confirmations(u, tip)
                                Text("$conf conf", style = Blake.mono(9f), color = if (BlakeFork.isSpendable(u, tip)) Blake.ok else Blake.warn)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    when (val s = sheet) {
        is Sheet.Utxo -> UtxoDetailSheet(s.utxo, tip, onCopy = { clip.setText(AnnotatedString(it)); toast(ctx, "Copied") }) { sheet = null }
        Sheet.Addresses -> AddressControlSheet(
            wallets = wallets,
            onGenerate = { onLaunchVanity(); sheet = null },
            onImport = { wif ->
                val ok = importWif(ctx, wif)
                toast(ctx, if (ok) "Imported" else "Invalid WIF")
                if (ok) scope.launch { BlakeBalanceStore.refresh(ctx) }
                ok
            },
            onCopy = { clip.setText(AnnotatedString(it)); toast(ctx, "Address copied") },
            balanceFor = { BlakeBalanceStore.balanceForAddress(it) },
            onClose = { sheet = null },
        )
        Sheet.Coins -> CoinsSheet(BlakeBalanceStore.allUtxos(), tip) { sheet = null }
        Sheet.Send -> SendSheet(
            onSend = { addr, amt, max, fee, ricochet ->
                scope.launch {
                    try {
                        val txid = if (ricochet) com.astrolexis.pyblock.data.blake.BlakeSpend.ricochet(ctx, addr, amt, max, 3, fee).txids.last()
                                   else com.astrolexis.pyblock.data.blake.BlakeSpend.send(ctx, addr, amt, max, fee)
                        com.astrolexis.pyblock.data.blake.BlakeSentStore.add(txid, if (max) total else amt, addr, emptySet(), ricochet)
                        toast(ctx, "Sent · ${txid.take(12)}…"); BlakeBalanceStore.refresh(ctx); sheet = null
                    } catch (e: Exception) { toast(ctx, e.message ?: "Send failed") }
                }
            },
            paste = { clip.getText()?.text ?: "" },
            onClose = { sheet = null },
        )
        Sheet.Currency -> CurrencyPickerSheet(BlakePrice.available()) { BlakePrice.setCurrency(it); sheet = null }
        Sheet.Settings -> SettingsSheet(operational, rc, statusHeight) { sheet = null }
        null -> {}
    }
}

private sealed class Sheet {
    data class Utxo(val utxo: BlakeApi.Utxo) : Sheet()
    object Addresses : Sheet()
    object Coins : Sheet()
    object Send : Sheet()
    object Currency : Sheet()
    object Settings : Sheet()
}

@Composable
private fun breakdownRow(label: String, sats: Long, accent: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(accent, CircleShape))
        Spacer(Modifier.size(6.dp))
        Text(label, style = Blake.mono(8f), color = Blake.faint, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text("${Blake.btc(sats)} ${Blake.RUNE}", style = Blake.mono(10f, FontWeight.ExtraBold), color = accent)
            BlakePrice.fiatLabel(sats)?.let { Text(it, style = Blake.mono(8f), color = Blake.faint) }
        }
    }
}

@Composable
private fun navBtn(modifier: Modifier, title: String, sub: String, onClick: () -> Unit) {
    Column(modifier.border(1.dp, Blake.line, RectangleShape).padding(12.dp).clickableNoRipple(onClick)) {
        Text(title, style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp)
        Text(sub, style = Blake.mono(9f), color = Blake.faint)
    }
}

private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
