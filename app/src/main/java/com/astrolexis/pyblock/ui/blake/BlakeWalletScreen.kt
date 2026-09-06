package com.astrolexis.pyblock.ui.blake

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.astrolexis.pyblock.data.blake.UnlockStore
import com.astrolexis.pyblock.data.wallet.VanityWallet
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** WALLET — clean overview: balance (spendable/locked), quick actions, recent activity.
 *  Faithful port of iOS WalletView — flat Blake aesthetic (black, ink cards, hairlines). */
@OptIn(ExperimentalMaterial3Api::class)
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
    val live by BlakeBalanceStore.live.collectAsState()
    val sentRecords by com.astrolexis.pyblock.data.blake.BlakeSentStore.records.collectAsState()
    val pendingIn by BlakeBalanceStore.pendingIn.collectAsState()
    val pendingSpent by BlakeBalanceStore.pendingSpentIds.collectAsState()
    val pendingActivity by BlakeBalanceStore.pendingActivity.collectAsState()
    var sentDetail by remember { mutableStateOf<com.astrolexis.pyblock.data.blake.SentRecord?>(null) }
    val operational by BlakeStatus.operational.collectAsState()
    val statusLoaded by BlakeStatus.loaded.collectAsState()
    val rc by BlakeStatus.rc.collectAsState()
    val statusHeight by BlakeStatus.blockHeight.collectAsState()

    var showDetails by remember { mutableStateOf(false) }
    var spendExpanded by remember { mutableStateOf(false) }
    var lockedExpanded by remember { mutableStateOf(false) }
    var pendingUnlock by remember { mutableStateOf<BlakeApi.Utxo?>(null) }   // coin awaiting replay-risk confirm
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    // Live activity feedback: a heartbeat dot on the header + a one-shot slide-down/blink of
    // the list whenever it changes (new pending/sent/confirmed row, or a confirmation tick)
    // so a change is felt even while the user is staring at it — no manual pull-to-refresh.
    val actSig = "${pendingActivity.size}:${sentRecords.size}:${BlakeBalanceStore.allUtxos().size}:$tip"
    val slide = remember { Animatable(0f) }
    var actSeen by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(actSig) {
        if (actSeen == null) { actSeen = actSig } else if (actSeen != actSig) {
            actSeen = actSig
            slide.snapTo(1f)
            slide.animateTo(0f, tween(520))
        }
    }
    val beat by rememberInfiniteTransition(label = "beat").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "beatv")

    LaunchedEffect(Unit) {
        WalletStore.ensureLoaded(ctx)
        BlakePrice.init(ctx)
        com.astrolexis.pyblock.data.blake.BlakeSentStore.init(ctx)
        com.astrolexis.pyblock.data.blake.UnlockStore.init(ctx)
        com.astrolexis.pyblock.data.blake.BlakeLabelStore.init(ctx)
        com.astrolexis.pyblock.data.wallet.BlakeContactsStore.init(ctx)
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
    com.astrolexis.pyblock.data.blake.UnlockStore.ids.collectAsState().value   // recompose on unlock/relock
    val labels by com.astrolexis.pyblock.data.blake.BlakeLabelStore.labels.collectAsState()   // recompose on label edits

    val total = BlakeBalanceStore.totalSats()
    val spendable = BlakeBalanceStore.spendableSats()
    val locked = BlakeBalanceStore.lockedSats()
    val pendingSpentIds = BlakeBalanceStore.pendingSpentIds.collectAsState().value
    val spendableUtxos = BlakeBalanceStore.allUtxos()
        .filter { BlakeFork.isEffectivelySpendable(it, tip) && it.id !in pendingSpentIds }
        .sortedByDescending { it.value }
    val lockedUtxos = BlakeBalanceStore.allUtxos()
        .filter { !BlakeFork.isEffectivelySpendable(it, tip) }
        .sortedByDescending { it.value }

    Box(Modifier.fillMaxSize().background(Blake.bg)) {
      PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
          scope.launch { refreshing = true; BlakeBalanceStore.refresh(ctx); BlakePrice.refresh(); BlakeStatus.refresh(); refreshing = false }
      }, modifier = Modifier.fillMaxSize()) {
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
                    // Syncing spins INLINE next to LIVE (fixed-width slot) so it never adds a row
                    // and shifts the card while refreshing.
                    Spacer(Modifier.size(6.dp))
                    val spin by rememberInfiniteTransition(label = "sync").animateFloat(
                        0f, 360f, infiniteRepeatable(tween(1000, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "spin")
                    Box(Modifier.width(12.dp)) {
                        if (loading) Text("⟳", style = Blake.mono(9f), color = Blake.pp, modifier = Modifier.graphicsLayer { rotationZ = spin })
                    }
                    Spacer(Modifier.weight(1f))
                    Text("⚙", style = Blake.mono(14f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple { sheet = Sheet.Settings })
                }
                Spacer(Modifier.height(6.dp))
                Text("${Blake.btc(total)} ${Blake.RUNE}", style = Blake.mono(30f, FontWeight.ExtraBold), color = Blake.pp, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text("${"%,d".format(total)} sats · ${wallets.size} address${if (wallets.size == 1) "" else "es"}",
                    style = Blake.mono(9f), color = Blake.faint)
                val pendInTotal = pendingIn.values.sum()
                if (pendInTotal > 0 || pendingSpent.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).background(Blake.warn, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        val parts = buildList {
                            if (pendInTotal > 0) add("+${Blake.btc(pendInTotal)} ${Blake.RUNE} incoming")
                            if (pendingSpent.isNotEmpty()) add("send in flight")
                        }
                        Text(parts.joinToString(" · ") + " · pending", style = Blake.mono(9f), color = Blake.warn)
                    }
                }
            }

            // Send actions
            if ((BlakeChains.SEND_ENABLED || BlakeChains.RICOCHET_ENABLED) && wallets.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("↗ SEND", style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.bg, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).background(Blake.pp).padding(vertical = 12.dp)
                            .clickableNoRipple { if (spendable <= 0) toast(ctx, "No spendable coins yet.") else sheet = Sheet.Send() })
                    if (BlakeChains.RICOCHET_ENABLED) {
                        Text("⟿ RICOCHETS", style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).border(1.dp, Blake.pp, RectangleShape).padding(vertical = 12.dp)
                                .clickableNoRipple { sheet = Sheet.Ricochets })
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
                .clickableNoRipple { sheet = Sheet.Paynym }, verticalAlignment = Alignment.CenterVertically) {
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
                            // Tap a header to expand its coins inline — unlock a replay-locked coin
                            // (or re-lock one you unlocked) without leaving the wallet screen.
                            breakdownRow("SPENDABLE", spendable, spendableUtxos.size, Blake.ok, spendExpanded) { spendExpanded = !spendExpanded }
                            if (spendExpanded) {
                                if (spendableUtxos.isEmpty())
                                    Text("No mature mined coins yet.", style = Blake.mono(8f), color = Blake.faint, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                                else spendableUtxos.forEach { u ->
                                    coinBreakdownRow(u, tip, locked = false, onUnlock = {}, onRelock = { UnlockStore.relock(u.id) }, onInfo = { sheet = Sheet.Utxo(u) })
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Blake.line))
                            Spacer(Modifier.height(8.dp))
                            breakdownRow("LOCKED", locked, lockedUtxos.size, Blake.warn, lockedExpanded) { lockedExpanded = !lockedExpanded }
                            if (lockedExpanded) {
                                if (lockedUtxos.isEmpty())
                                    Text("Nothing locked.", style = Blake.mono(8f), color = Blake.faint, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                                else lockedUtxos.forEach { u ->
                                    coinBreakdownRow(u, tip, locked = true, onUnlock = { pendingUnlock = u }, onRelock = { UnlockStore.relock(u.id) }, onInfo = { sheet = Sheet.Utxo(u) })
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("Spendable = mature mined coins. Locked = immature or pre-fork (replay-exposed). Tap a row to unlock.",
                                style = Blake.mono(7f), color = Blake.faint)
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ACTIVITY", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                            if (live) {
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.size(6.dp).graphicsLayer { alpha = beat }.background(Blake.ok, CircleShape))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // ONE list, newest-first: pending (0-conf) → sent → confirmed. Confirmed
                        // recency is estimated from block depth (~40s/block, monotonic w/ height).
                        val liveCoinIds = BlakeBalanceStore.allUtxos().map { it.id }.toSet()
                        val now = System.currentTimeMillis() / 1000
                        data class Act(val ts: Long, val view: @Composable () -> Unit)
                        val acts = ArrayList<Act>()
                        pendingActivity.forEach { p ->
                            acts.add(Act(maxOf(now, p.seen)) {
                                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).blakeCard(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("+ ${Blake.btc(p.sats)} ${Blake.RUNE}", style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.warn)
                                        Text("incoming · ${p.address.take(10)}…", style = Blake.mono(8f), color = Blake.faint)
                                    }
                                    Text("pending", style = Blake.mono(9f), color = Blake.warn)
                                }
                            })
                        }
                        sentRecords.take(30).forEach { r ->
                            val pending = com.astrolexis.pyblock.data.blake.BlakeSentStore.isPending(r, liveCoinIds)
                            acts.add(Act(r.date) {
                                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).blakeCard(12.dp)
                                    .clickableNoRipple { sentDetail = r }, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("− ${Blake.btc(r.amountSats)} ${Blake.RUNE}${if (r.ricochet) " · ricochet" else ""}",
                                            style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.warn)
                                        val tag = labels[r.id]?.takeIf { it.isNotBlank() }
                                        val contactName = com.astrolexis.pyblock.data.wallet.BlakeContactsStore.labelFor(r.toAddress)
                                        if (tag != null) Text("🏷 $tag", style = Blake.mono(8f, FontWeight.ExtraBold), color = Blake.pp, maxLines = 1)
                                        else if (contactName != null) Text("to ☰ $contactName", style = Blake.mono(8f, FontWeight.ExtraBold), color = Blake.ok, maxLines = 1)
                                        Text("to ${r.toAddress.take(10)}…${r.toAddress.takeLast(8)}", style = Blake.mono(8f), color = Blake.faint)
                                    }
                                    Text(if (pending) "pending" else "sent", style = Blake.mono(9f), color = if (pending) Blake.warn else Blake.ppDim)
                                }
                            })
                        }
                        BlakeBalanceStore.allUtxos().forEach { u ->
                            acts.add(Act(now - maxOf(0, tip - u.height).toLong() * 40) {
                                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).blakeCard(12.dp)
                                    .clickableNoRipple { sheet = Sheet.Utxo(u) }, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("+ ${Blake.btc(u.value)} ${Blake.RUNE}", style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.ok)
                                        labels[u.id]?.takeIf { it.isNotBlank() }?.let {
                                            Text("🏷 $it", style = Blake.mono(8f, FontWeight.ExtraBold), color = Blake.pp, maxLines = 1)
                                        }
                                        Text("${if (u.coinbase) "mined · " else ""}block #${u.height}", style = Blake.mono(8f), color = Blake.faint)
                                    }
                                    val conf = BlakeFork.confirmations(u, tip)
                                    Text("$conf conf", style = Blake.mono(9f), color = if (BlakeFork.isSpendable(u, tip)) Blake.ok else Blake.warn)
                                }
                            })
                        }
                        if (acts.isEmpty()) Text("No movements yet.", style = Blake.mono(10f), color = Blake.faint)
                        else Column(Modifier.graphicsLayer {
                            translationY = slide.value * 16.dp.toPx()
                            alpha = 1f - slide.value * 0.55f
                        }) { acts.sortedByDescending { it.ts }.take(60).forEach { it.view() } }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
      }
    }

    when (val s = sheet) {
        is Sheet.Utxo -> UtxoDetailSheet(s.utxo, tip, onCopy = { clip.setText(AnnotatedString(it)); toast(ctx, "Copied") }) { sheet = null }
        Sheet.Addresses -> AddressControlSheet(
            wallets = wallets,
            onGenerate = { onLaunchVanity(); sheet = null },
            onCopy = { clip.setText(AnnotatedString(it)); toast(ctx, "Address copied") },
            balanceFor = { BlakeBalanceStore.balanceForAddress(it) },
            onSend = { keys -> sheet = Sheet.Send(keys) },
            onClose = { sheet = null },
        )
        Sheet.Coins -> CoinsSheet(BlakeBalanceStore.allUtxos(), tip, onSpend = { keys -> sheet = Sheet.Send(keys) }, onOpen = { u -> sheet = Sheet.Utxo(u) }) { sheet = null }
        is Sheet.Send -> SendWizardSheet(coinKeys = s.coinKeys, onClose = { sheet = null })
        Sheet.Currency -> CurrencyPickerSheet(BlakePrice.available()) { BlakePrice.setCurrency(it); sheet = null }
        Sheet.Settings -> SettingsSheet(operational, rc, statusHeight) { sheet = null }
        Sheet.Ricochets -> RicochetHistorySheet(onCopy = { clip.setText(AnnotatedString(it)); toast(ctx, "Copied") }) { sheet = null }
        Sheet.Paynym -> PaynymSheet(onCopy = { clip.setText(AnnotatedString(it)); toast(ctx, "Copied") }, paste = { clip.getText()?.text ?: "" }) { sheet = null }
        null -> {}
    }

    sentDetail?.let { r ->
        SentDetailDialog(
            r = r,
            pending = com.astrolexis.pyblock.data.blake.BlakeSentStore.isPending(r, BlakeBalanceStore.allUtxos().map { it.id }.toSet()),
            onCopy = { clip.setText(AnnotatedString(it)); toast(ctx, "Copied") },
            onClose = { sentDetail = null },
        )
    }

    // Replay-risk confirm before unlocking a locked coin (mirrors iOS confirmationDialog).
    pendingUnlock?.let { u ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { pendingUnlock = null }) {
            Column(Modifier.background(Blake.ink).border(1.dp, Blake.line, RectangleShape).padding(20.dp)) {
                Text("UNLOCK THIS COIN?", style = Blake.mono(14f, FontWeight.ExtraBold), color = Blake.danger, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                Text("Replay-exposed coins can also move on the Bitcoin (SHA-256) chain — spending here may affect/lose that balance. Only unlock if you understand this.",
                    style = Blake.mono(10f), color = Blake.faint)
                Spacer(Modifier.height(14.dp))
                sheetBtn("🔓 UNLOCK — I ACCEPT THE RISK", Blake.danger, filled = true) { UnlockStore.unlock(u.id); pendingUnlock = null }
                Spacer(Modifier.height(8.dp))
                sheetBtn("CANCEL", Blake.ppDim) { pendingUnlock = null }
            }
        }
    }
}

private sealed class Sheet {
    data class Utxo(val utxo: BlakeApi.Utxo) : Sheet()
    object Addresses : Sheet()
    object Coins : Sheet()
    data class Send(val coinKeys: Set<String> = emptySet()) : Sheet()
    object Currency : Sheet()
    object Settings : Sheet()
    object Ricochets : Sheet()
    object Paynym : Sheet()
}

@Composable
private fun breakdownRow(label: String, sats: Long, count: Int, accent: androidx.compose.ui.graphics.Color,
                        expanded: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickableNoRipple(onToggle), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(accent, CircleShape))
        Spacer(Modifier.size(6.dp))
        Text(label, style = Blake.mono(8f), color = Blake.faint, letterSpacing = 1.sp)
        Spacer(Modifier.size(4.dp))
        Text("($count)", style = Blake.mono(8f), color = Blake.faint)
        Spacer(Modifier.size(4.dp))
        Text(if (expanded) "▲" else "▼", style = Blake.mono(7f), color = Blake.ppDim)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text("${Blake.btc(sats)} ${Blake.RUNE}", style = Blake.mono(10f, FontWeight.ExtraBold), color = accent)
            BlakePrice.fiatLabel(sats)?.let { Text(it, style = Blake.mono(8f), color = Blake.faint) }
        }
    }
}

/** One coin inside an expanded SPENDABLE/LOCKED bucket. Replay-locked → UNLOCK; unlocked → LOCK. */
@Composable
private fun coinBreakdownRow(u: BlakeApi.Utxo, tip: Int, locked: Boolean,
                            onUnlock: () -> Unit, onRelock: () -> Unit, onInfo: () -> Unit) {
    val unlocked = UnlockStore.isUnlocked(u.id)
    val replayLocked = BlakeFork.isReplayLocked(u, tip)
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("◈", style = Blake.mono(9f), color = if (locked) Blake.warn else Blake.ok)
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text("${Blake.btc(u.value)} ${Blake.RUNE}", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg)
            Text(if (locked) (BlakeFork.lockReason(u, tip) ?: "locked")
                 else if (unlocked) "unlocked · replay risk accepted" else "mature mined",
                 style = Blake.mono(7f), color = Blake.faint)
        }
        if (replayLocked) {
            if (unlocked) miniBtn("🔒 LOCK", Blake.warn, onRelock)
            else miniBtn("🔓 UNLOCK", Blake.danger, onUnlock)
            Spacer(Modifier.size(8.dp))
        }
        Text("ⓘ", style = Blake.mono(11f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple(onInfo))
    }
}

@Composable
private fun miniBtn(title: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(title, style = Blake.mono(8f, FontWeight.ExtraBold), color = color, letterSpacing = 0.5.sp,
        modifier = Modifier.border(1.dp, color, RectangleShape).padding(horizontal = 7.dp, vertical = 4.dp).clickableNoRipple(onClick))
}

@Composable
private fun navBtn(modifier: Modifier, title: String, sub: String, onClick: () -> Unit) {
    Column(modifier.fillMaxHeight().border(1.dp, Blake.line, RectangleShape).padding(12.dp).clickableNoRipple(onClick)) {
        Text(title, style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 0.5.sp,
            maxLines = 1, softWrap = false)
        Text(sub, style = Blake.mono(9f), color = Blake.faint)
    }
}

/** Tap a sent row → full detail with copyable txid + live PENDING/CONFIRMED status.
 *  Mirrors iOS SentDetailSheet. */
@Composable
private fun SentDetailDialog(
    r: com.astrolexis.pyblock.data.blake.SentRecord,
    pending: Boolean,
    onCopy: (String) -> Unit,
    onClose: () -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    var labelText by remember(r.id) { mutableStateOf(com.astrolexis.pyblock.data.blake.BlakeLabelStore.labelFor(r.id) ?: "") }
    sheetBox(if (r.ricochet) "RICOCHET SENT" else "SENT", Blake.pp, onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if (pending) Blake.warn else Blake.ok, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(if (pending) "PENDING · waiting for a block" else "CONFIRMED",
                style = Blake.mono(10f, FontWeight.ExtraBold), color = if (pending) Blake.warn else Blake.ok, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(14.dp))
        Text("− ${Blake.btc(r.amountSats)} ${Blake.RUNE}", style = Blake.mono(24f, FontWeight.ExtraBold), color = Blake.warn)
        Text("${"%,d".format(r.amountSats)} sats", style = Blake.mono(10f), color = Blake.faint)
        Spacer(Modifier.height(14.dp))
        Text("LABEL", style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.text.BasicTextField(labelText, {
            labelText = it; com.astrolexis.pyblock.data.blake.BlakeLabelStore.set(r.id, it)
        }, singleLine = true, textStyle = Blake.mono(12f).copy(color = Blake.fg),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Blake.pp),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().border(1.dp, Blake.line, RectangleShape).padding(10.dp)) {
                    if (labelText.isEmpty()) Text("Name this transaction (e.g. pago Stefa)", style = Blake.mono(12f), color = Blake.faint)
                    inner()
                }
            })
        Spacer(Modifier.height(14.dp))
        Text("TO", style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
        Text(r.toAddress, style = Blake.mono(10f), color = Blake.fg)
        Spacer(Modifier.height(12.dp))
        Text("TXID", style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
        Text(r.id, style = Blake.mono(10f), color = Blake.pp)
        Spacer(Modifier.height(8.dp))
        sheetBtn(if (copied) "✓ COPIED" else "TAP TO COPY TXID", if (copied) Blake.ok else Blake.pp) { onCopy(r.id); copied = true }
        if (r.ricochet) {
            Spacer(Modifier.height(10.dp))
            Text("Open RICOCHETS in the wallet to see the full hop chain + provable keys.",
                style = Blake.mono(8f), color = Blake.faint)
        }
        Spacer(Modifier.height(10.dp))
        Text("BLAKE2b balances update once the transaction is mined — the sent coins are still counted as spendable until then.",
            style = Blake.mono(7f), color = Blake.faint)
        Spacer(Modifier.height(12.dp))
        sheetBtn("CLOSE", Blake.ppDim) { onClose() }
    }
}

private fun toast(ctx: android.content.Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
