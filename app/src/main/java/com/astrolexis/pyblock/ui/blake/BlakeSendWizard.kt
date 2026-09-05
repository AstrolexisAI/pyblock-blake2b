package com.astrolexis.pyblock.ui.blake

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.astrolexis.pyblock.data.blake.BlakeApi
import com.astrolexis.pyblock.data.blake.BlakeBalanceStore
import com.astrolexis.pyblock.data.blake.BlakeChains
import com.astrolexis.pyblock.data.blake.BlakeFork
import com.astrolexis.pyblock.data.blake.BlakePrice
import com.astrolexis.pyblock.data.blake.BlakeSentStore
import com.astrolexis.pyblock.data.blake.BlakeSpend
import com.astrolexis.pyblock.ui.components.QrScanner
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.launch

/**
 * SEND / RICOCHET of mature mined BLAKE2b coins, as a 4-step wizard — a faithful port of iOS
 * `SendRicochetView`: 1) TO · 2) AMOUNT + FEE · 3) PRIVACY · 4) REVIEW. Works from BOTH the
 * general Send (auto-select) and Coin Control ([coinKeys] set). Gated behind [BlakeChains].
 */
private enum class SendUnit { BTC, SATS }
private data class WizardResult(val txids: List<String>, val ricochet: Boolean)

@Composable
fun SendWizardSheet(
    coinKeys: Set<String> = emptySet(),
    prefillTo: String = "",
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    // Compose WindowInsets (and the Dialog's own view) read 0 under gesture nav, so read the REAL
    // system-bar insets from the ACTIVITY's decorView (auto-detects per device) and apply a floor.
    fun activityInsets(): androidx.core.graphics.Insets? {
        var c: android.content.Context? = ctx
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) {
                return ViewCompat.getRootWindowInsets(c.window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.systemBars())
            }
            c = c.baseContext
        }
        return null
    }
    val topInsetDp = remember {
        with(density) { maxOf(activityInsets()?.top ?: 0, 28.dp.roundToPx()).toDp() }
    }
    val botInsetDp = remember {
        // 48dp floor clears the gesture pill / nav bar on devices that report 0 inside the dialog.
        with(density) { maxOf(activityInsets()?.bottom ?: 0, 48.dp.roundToPx()).toDp() }
    }
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val tip by BlakeBalanceStore.tip.collectAsState()
    val ccy by BlakePrice.currency.collectAsState()
    BlakePrice.rates.collectAsState().value            // recompose on rate load
    BlakeBalanceStore.utxos.collectAsState().value            // recompose when balances/UTXOs change
    BlakeBalanceStore.pendingSpentIds.collectAsState().value  // recompose when a send goes in-flight

    var step by remember { mutableStateOf(1) }
    var toAddress by remember { mutableStateOf(prefillTo) }
    var amountText by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(SendUnit.BTC) }
    var sendMax by remember { mutableStateOf(false) }
    var ricochet by remember { mutableStateOf(false) }
    var hops by remember { mutableStateOf(2) }
    var feeRate by remember { mutableStateOf(2) }
    var customFeeText by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<WizardResult?>(null) }

    val spendable = BlakeBalanceStore.spendableSats()
    val locked = BlakeBalanceStore.lockedSats()
    val selectedSats = if (coinKeys.isEmpty()) 0L
        else BlakeBalanceStore.allUtxos().filter { it.id in coinKeys }.sumOf { it.value }
    val sweepSats = if (selectedSats > 0) selectedSats else spendable
    val effectiveFee = maxOf(1, customFeeText.toIntOrNull() ?: feeRate)

    fun amountSats(): Long {
        if (sendMax) return sweepSats
        return when (unit) {
            SendUnit.SATS -> amountText.filter { it.isDigit() }.toLongOrNull() ?: 0L
            SendUnit.BTC -> {
                val v = amountText.replace(",", ".").toDoubleOrNull() ?: return 0L
                if (v <= 0) 0L else (v * 100_000_000).toLong()
            }
        }
    }
    val amt = amountSats()
    val overspend = !sendMax && amt > (if (selectedSats > 0) selectedSats else spendable)
    val stepValid = when (step) {
        1 -> isPlausibleAddress(toAddress)
        2 -> (sendMax || amt > 0) && !overspend && spendable > 0
        else -> true
    }
    fun estFee(): Long {
        val nIn = if (coinKeys.isEmpty()) maxOf(1, BlakeBalanceStore.allUtxos().count { BlakeFork.isSpendable(it, tip) }) else coinKeys.size
        val base = nIn.toLong() * 148 + 2 * 34 + 10
        val hopExtra = if (ricochet) maxOf(1, minOf(4, hops)).toLong() * 115 else 0L
        return effectiveFee.toLong() * (base + hopExtra)
    }

    fun submit() {
        error = null; busy = true
        val only = coinKeys.ifEmpty { null }
        val recorded = if (sendMax) sweepSats else amt
        // Run OFF the main thread: the BDK tx build/sign (esp. SegWit) is CPU-heavy and was blocking
        // the UI thread → ANR (button stuck on BROADCASTING). Compose state writes are thread-safe.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // A PayNym payment code (PM…) → derive a fresh BIP-47 send address (advances the per-code counter).
                var dest = toAddress.trim()
                if (com.astrolexis.pyblock.data.crypto.PaymentCode.looksLikePaymentCode(dest)) {
                    dest = com.astrolexis.pyblock.data.crypto.PaymentCode.nextWalletSendAddress(ctx, dest)
                        ?: throw Exception("Couldn't derive a PayNym address from that code.")
                }
                if (ricochet) {
                    val outcome = BlakeSpend.ricochet(ctx, dest, amt, sendMax, hops, effectiveFee.toLong(), only)
                    com.astrolexis.pyblock.data.wallet.RicochetHistory.add(ctx, outcome, hops, recorded, dest, "mainnet")
                    BlakeSentStore.add(outcome.txids.lastOrNull() ?: "", recorded, dest, coinKeys, true)
                    result = WizardResult(outcome.txids, true)
                } else {
                    val txid = BlakeSpend.send(ctx, dest, amt, sendMax, effectiveFee.toLong(), only)
                    BlakeSentStore.add(txid, recorded, dest, coinKeys, false)
                    result = WizardResult(listOf(txid), false)
                }
                BlakeBalanceStore.refresh(ctx)
            } catch (e: Exception) {
                android.util.Log.e("BlakeSend", "send failed: ${e::class.java.simpleName}: ${e.message}", e)
                val raw = e.message ?: "Send failed (${e::class.java.simpleName})"
                error = if (raw.contains("min relay", ignoreCase = true))
                    "Fee too low for the BLAKE2b network — pick a higher fee (2 sat/vB or more) and try again." else raw
            } finally { busy = false }
        }
    }

    // decorFitsSystemWindows=false → the dialog draws edge-to-edge so the statusBars/navigationBars/
    // ime padding modifiers below actually take effect (otherwise the dialog consumes the insets and
    // the footer stays clipped under the gesture bar).
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Blake.bg)) {
            val r = result
            if (r != null) {
                SendResultScreen(r, onCopy = { clip.setText(AnnotatedString(it)) }, onClose = onClose)
            } else if (scanning) {
                QrScanner(title = "SCAN A BITCOIN ADDRESS",
                    onResult = { code -> toAddress = sanitizeAddress(code); scanning = false },
                    onClose = { scanning = false })
            } else {
                // Safe-area insets: header below the status bar, footer above the gesture nav bar,
                // and the whole wizard lifts above the keyboard (was clipping BACK/NEXT).
                Column(Modifier.fillMaxSize().padding(top = topInsetDp, bottom = botInsetDp).imePadding()) {
                    // Header
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (ricochet) "RICOCHET" else "SEND", style = Blake.mono(20f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 3.sp)
                        Spacer(Modifier.weight(1f))
                        Text("✕", style = Blake.mono(22f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple(onClose))
                    }
                    // Progress
                    val titles = listOf("TO", "AMOUNT", "PRIVACY", "REVIEW")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        titles.forEachIndexed { i, t ->
                            Column(Modifier.weight(1f)) {
                                Box(Modifier.fillMaxWidth().height(3.dp).background(if (i + 1 <= step) Blake.pp else Blake.line))
                                Spacer(Modifier.height(4.dp))
                                Text("${i + 1} $t", style = Blake.mono(7f, if (i + 1 == step) FontWeight.ExtraBold else FontWeight.Normal),
                                    color = if (i + 1 == step) Blake.pp else Blake.faint, letterSpacing = 1.sp)
                            }
                        }
                    }
                    // Step body
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
                        when (step) {
                            1 -> StepTo(toAddress, { toAddress = it }, coinKeys, selectedSats, spendable, locked,
                                onScan = { scanning = true }, onPaste = { toAddress = sanitizeAddress(clip.getText()?.text ?: "") })
                            2 -> StepAmount(amountText, { amountText = it }, unit, { u ->
                                    if (u != unit) { val s = amt; unit = u; if (s > 0 && !sendMax) amountText = if (u == SendUnit.BTC) Blake.btc(s) else "$s" } },
                                    sendMax, { sendMax = it }, amt, sweepSats, overspend, coinKeys, selectedSats, spendable, ccy,
                                    feeRate, { feeRate = it }, customFeeText, { customFeeText = it }, effectiveFee, estFee())
                            3 -> StepPrivacy(ricochet, { ricochet = it }, hops, { hops = it })
                            else -> StepReview(toAddress, sendMax, amt, sweepSats, ricochet, hops, coinKeys, selectedSats, effectiveFee, estFee(), ccy)
                        }
                        error?.let { Spacer(Modifier.height(10.dp)); Text(it, style = Blake.mono(9f), color = Blake.danger) }
                    }
                    // Nav bar
                    Row(Modifier.fillMaxWidth().background(Blake.bg).padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (step > 1) {
                            Text("‹ BACK", style = Blake.mono(13f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).border(1.dp, Blake.line, RectangleShape).padding(vertical = 14.dp)
                                    .clickableNoRipple { if (!busy) { error = null; step-- } })
                        }
                        if (step < 4) {
                            Text("NEXT ›", style = Blake.mono(14f, FontWeight.ExtraBold), color = if (stepValid) Blake.bg else Blake.faint,
                                letterSpacing = 1.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).then(if (stepValid) Modifier.background(Blake.pp) else Modifier.border(1.dp, Blake.line, RectangleShape))
                                    .padding(vertical = 14.dp).clickableNoRipple { if (stepValid) { error = null; step++ } })
                        } else {
                            Text(if (busy) "BROADCASTING…" else if (ricochet) "CONFIRM RICOCHET" else "CONFIRM SEND",
                                style = Blake.mono(14f, FontWeight.ExtraBold), color = Blake.bg, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).background(Blake.pp).padding(vertical = 14.dp)
                                    .clickableNoRipple { if (!busy) submit() })
                        }
                    }
                }
            }
        }
    }
}

// ---- Step 1 · TO ----
@Composable
private fun StepTo(
    addr: String, onAddr: (String) -> Unit, coinKeys: Set<String>, selectedSats: Long,
    spendable: Long, locked: Long, onScan: () -> Unit, onPaste: () -> Unit,
) {
    ReplayWarning()
    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth().blakeCard()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SEND TO", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text("⛶ SCAN", style = Blake.mono(11f), color = Blake.pp, modifier = Modifier.clickableNoRipple(onScan))
            Spacer(Modifier.width(12.dp))
            Text("PASTE", style = Blake.mono(11f), color = Blake.pp, modifier = Modifier.clickableNoRipple(onPaste))
        }
        Spacer(Modifier.height(10.dp))
        val ok = isPlausibleAddress(addr)
        BasicTextField(addr, onAddr, textStyle = Blake.mono(14f).copy(color = Blake.fg),
            cursorBrush = SolidColor(Blake.pp),
            modifier = Modifier.fillMaxWidth().border(1.dp, if (ok) Blake.pp.copy(alpha = 0.6f) else Blake.line, RectangleShape).padding(14.dp))
        if (addr.isNotEmpty() && !ok) {
            Spacer(Modifier.height(6.dp))
            Text("That doesn't look like a valid address.", style = Blake.mono(8f), color = Blake.danger)
        }
    }
    Spacer(Modifier.height(16.dp))
    SpendableCard(coinKeys, selectedSats, spendable, locked)
}

// ---- Step 2 · AMOUNT + FEE ----
@Composable
private fun StepAmount(
    amountText: String, onAmount: (String) -> Unit, unit: SendUnit, onUnit: (SendUnit) -> Unit,
    sendMax: Boolean, onMax: (Boolean) -> Unit, amt: Long, sweepSats: Long, overspend: Boolean,
    coinKeys: Set<String>, selectedSats: Long, spendable: Long, ccy: String,
    feeRate: Int, onFeeRate: (Int) -> Unit, customFeeText: String, onCustomFee: (String) -> Unit,
    effectiveFee: Int, estFee: Long,
) {
    Column(Modifier.fillMaxWidth().blakeCard()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AMOUNT", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            // unit toggle
            Row(Modifier.border(1.dp, Blake.line, RectangleShape)) {
                UnitTab("ᛒ", unit == SendUnit.BTC) { onUnit(SendUnit.BTC) }
                UnitTab("sats", unit == SendUnit.SATS) { onUnit(SendUnit.SATS) }
            }
            Spacer(Modifier.width(8.dp))
            Text(if (sendMax) "MAX ✓" else "MAX", style = Blake.mono(10f, FontWeight.ExtraBold), color = if (sendMax) Blake.bg else Blake.pp,
                modifier = Modifier.then(if (sendMax) Modifier.background(Blake.pp) else Modifier.border(1.dp, Blake.pp, RectangleShape))
                    .padding(horizontal = 10.dp, vertical = 5.dp).clickableNoRipple { onMax(!sendMax) })
        }
        Spacer(Modifier.height(12.dp))
        if (sendMax) {
            Text("MAX", style = Blake.mono(40f, FontWeight.ExtraBold), color = Blake.pp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp))
        } else {
            Row(Modifier.fillMaxWidth().border(1.dp, if (overspend) Blake.danger else Blake.line, RectangleShape).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                BasicTextField(amountText, { s -> onAmount(if (unit == SendUnit.SATS) s.filter { it.isDigit() } else s.filter { it.isDigit() || it == '.' || it == ',' }) },
                    textStyle = Blake.mono(40f, FontWeight.ExtraBold).copy(color = Blake.pp, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(Blake.pp), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (unit == SendUnit.BTC) KeyboardType.Decimal else KeyboardType.Number),
                    decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { if (amountText.isEmpty()) Text("0", style = Blake.mono(40f, FontWeight.ExtraBold).copy(color = Blake.faint)); inner() } })
                Spacer(Modifier.width(8.dp))
                Text(if (unit == SendUnit.BTC) "ᛒ" else "sats", style = Blake.mono(16f, FontWeight.ExtraBold), color = Blake.ppDim)
            }
        }
        Spacer(Modifier.height(8.dp))
        val sub = amountSubtitle(unit, sendMax, if (sendMax) sweepSats else amt, overspend, ccy)
        Text(sub, style = Blake.mono(10f), color = if (overspend) Blake.danger else Blake.ppDim,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(if (coinKeys.isEmpty()) "Spendable ${Blake.btc(spendable)} ${Blake.RUNE}"
             else "From ${coinKeys.size} selected coin${if (coinKeys.size == 1) "" else "s"} · ${Blake.btc(selectedSats)} ${Blake.RUNE} available · change returns",
            style = Blake.mono(8f), color = if (coinKeys.isEmpty()) Blake.faint else Blake.pp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(16.dp))
    // Fee card
    Column(Modifier.fillMaxWidth().blakeCard()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NETWORK FEE", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text("~${"%,d".format(estFee)} sats", style = Blake.mono(9f), color = Blake.faint)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 5, 10, 20).forEach { rt ->
                val on = customFeeText.isEmpty() && feeRate == rt
                Text(if (rt == 2) "2·cheap" else "$rt", style = Blake.mono(10f, FontWeight.ExtraBold), color = if (on) Blake.bg else Blake.ppDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).then(if (on) Modifier.background(Blake.pp) else Modifier.border(1.dp, Blake.line, RectangleShape))
                        .padding(vertical = 9.dp).clickableNoRipple { onFeeRate(rt); onCustomFee("") })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CUSTOM", style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).border(1.dp, if (customFeeText.isEmpty()) Blake.line else Blake.pp, RectangleShape).padding(8.dp)) {
                BasicTextField(customFeeText, { onCustomFee(it.filter { c -> c.isDigit() }) }, textStyle = Blake.mono(11f).copy(color = Blake.fg),
                    cursorBrush = SolidColor(Blake.pp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    decorationBox = { inner -> if (customFeeText.isEmpty()) Text("sat/vB", style = Blake.mono(11f).copy(color = Blake.faint)); inner() })
            }
            Spacer(Modifier.width(6.dp))
            Text("sat/vB", style = Blake.mono(8f), color = Blake.faint)
        }
        Spacer(Modifier.height(8.dp))
        Text(if (sendMax) (if (coinKeys.isEmpty()) "Sweeps all spendable mined coins." else "Sweeps the selected coin${if (coinKeys.size == 1) "" else "s"} (no change).")
             else "Change returns to the same address the coin came from.",
            style = Blake.mono(7f), color = Blake.faint)
    }
}

@Composable
private fun UnitTab(t: String, on: Boolean, onClick: () -> Unit) {
    Text(t, style = Blake.mono(10f, FontWeight.ExtraBold), color = if (on) Blake.bg else Blake.ppDim,
        modifier = Modifier.then(if (on) Modifier.background(Blake.pp) else Modifier).padding(horizontal = 10.dp, vertical = 5.dp).clickableNoRipple(onClick))
}

// ---- Step 3 · PRIVACY ----
@Composable
private fun StepPrivacy(ricochet: Boolean, onRicochet: (Boolean) -> Unit, hops: Int, onHops: (Int) -> Unit) {
    Text("HOW TO SEND", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
    Spacer(Modifier.height(14.dp))
    PrivacyOption("DIRECT", !ricochet, "One transaction straight to the recipient. Cheapest and fastest.") { onRicochet(false) }
    if (BlakeChains.RICOCHET_ENABLED) {
        Spacer(Modifier.height(14.dp))
        PrivacyOption("RICOCHET", ricochet, "Sweeps through throwaway hops first — coordinator-free on-chain distance. Costs more fee. You keep every hop key (provenance).") { onRicochet(true) }
        if (ricochet) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().blakeCard(), verticalAlignment = Alignment.CenterVertically) {
                Text("HOPS", style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                (1..4).forEach { h ->
                    Text("$h", style = Blake.mono(12f, FontWeight.ExtraBold), color = if (hops == h) Blake.bg else Blake.ppDim, textAlign = TextAlign.Center,
                        modifier = Modifier.width(40.dp).then(if (hops == h) Modifier.background(Blake.pp) else Modifier.border(1.dp, Blake.line, RectangleShape))
                            .padding(vertical = 8.dp).clickableNoRipple { onHops(h) })
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

@Composable
private fun PrivacyOption(title: String, on: Boolean, note: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().blakeCard().border(1.dp, if (on) Blake.pp.copy(alpha = 0.6f) else Color.Transparent, RectangleShape)
        .clickableNoRipple(onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (on) "◉" else "○", style = Blake.mono(14f, FontWeight.ExtraBold), color = if (on) Blake.pp else Blake.faint)
            Spacer(Modifier.width(8.dp))
            Text(title, style = Blake.mono(13f, FontWeight.ExtraBold), color = if (on) Blake.hero else Blake.ppDim, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(note, style = Blake.mono(8f), color = Blake.faint)
    }
}

// ---- Step 4 · REVIEW ----
@Composable
private fun StepReview(
    toAddress: String, sendMax: Boolean, amt: Long, sweepSats: Long, ricochet: Boolean, hops: Int,
    coinKeys: Set<String>, selectedSats: Long, effectiveFee: Int, estFee: Long, ccy: String,
) {
    Column(Modifier.fillMaxWidth().blakeCard()) {
        Text("REVIEW — CONFIRM TO BROADCAST", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.warn, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))
        ReviewRow("TO", toAddress, 9f)
        ReviewRow("AMOUNT", if (sendMax) "MAX · ${Blake.btc(sweepSats)} ${Blake.RUNE}" else "${Blake.btc(amt)} ${Blake.RUNE}")
        ReviewRow("", "${"%,d".format(if (sendMax) sweepSats else amt)} sats", 9f, faint = true)
        BlakePrice.fiatLabel(if (sendMax) sweepSats else amt)?.let { ReviewRow("≈ FIAT", "$it $ccy") }
        ReviewRow("MODE", if (ricochet) "ricochet · $hops hop${if (hops == 1) "" else "s"}" else "direct")
        ReviewRow("COINS", if (coinKeys.isEmpty()) "auto-select" else "${coinKeys.size} selected · ${Blake.btc(selectedSats)} ${Blake.RUNE}")
        ReviewRow("FEE RATE", "$effectiveFee sat/vB")
        ReviewRow("EST. FEE", "~${"%,d".format(estFee)} sats")
        Spacer(Modifier.height(10.dp))
        ReplayWarning()
        Spacer(Modifier.height(8.dp))
        Text("Final fee is computed when the transaction is built. Broadcasts to BLAKE2b (Node B) — this can't be undone.",
            style = Blake.mono(7f), color = Blake.faint)
    }
}

@Composable
private fun ReviewRow(label: String, value: String, mono: Float = 11f, faint: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp, modifier = Modifier.width(78.dp))
        Text(value, style = Blake.mono(mono), color = if (faint) Blake.faint else Blake.fg, modifier = Modifier.weight(1f))
    }
}

// ---- Shared bits ----
@Composable
private fun ReplayWarning() {
    Row(Modifier.fillMaxWidth().border(1.dp, Blake.warn.copy(alpha = 0.5f), RectangleShape).padding(10.dp)) {
        Text("⚠", style = Blake.mono(14f, FontWeight.ExtraBold), color = Blake.warn)
        Spacer(Modifier.width(8.dp))
        Text("Same address, two chains. This address can also hold Bitcoin (SHA-256) coins. The fork has NO replay protection — moving funds using a shared address can affect or lose your balance on the other chain.",
            style = Blake.mono(8f), color = Blake.warn)
    }
}

@Composable
private fun SpendableCard(coinKeys: Set<String>, selectedSats: Long, spendable: Long, locked: Long) {
    val cc = coinKeys.isNotEmpty()
    Column(Modifier.fillMaxWidth().blakeCard()) {
        Text(if (cc) "SELECTED · ${coinKeys.size} COIN${if (coinKeys.size == 1) "" else "S"}" else "SPENDABLE",
            style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Text("${Blake.btc(if (cc) selectedSats else spendable)} ${Blake.RUNE}", style = Blake.mono(18f, FontWeight.ExtraBold), color = Blake.ok)
        Spacer(Modifier.height(4.dp))
        Text(if (cc) "Only the selected coin${if (coinKeys.size == 1) "" else "s"} is used — the rest of your wallet (${Blake.btc(spendable)} ${Blake.RUNE}) stays untouched. Any change returns to the same address."
             else "Only mature mined coins can be sent. Locked ${Blake.btc(locked)} ${Blake.RUNE} stays put.",
            style = Blake.mono(8f), color = if (cc) Blake.pp else Blake.faint)
    }
}

// ---- Result ----
@Composable
private fun SendResultScreen(r: WizardResult, onCopy: (String) -> Unit, onClose: () -> Unit) {
    var pop by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pop) 1f else 0.3f, tween(450), label = "pop")
    val alpha by animateFloatAsState(if (pop) 1f else 0f, tween(450), label = "popa")
    androidx.compose.runtime.LaunchedEffect(Unit) { pop = true; com.astrolexis.pyblock.ui.Haptics.tap() }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✓", style = Blake.mono(26f, FontWeight.ExtraBold), color = Blake.ok,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha })
            Spacer(Modifier.width(10.dp))
            Text(if (r.ricochet) "RICOCHET SENT" else "SENT", style = Blake.mono(18f, FontWeight.ExtraBold), color = Blake.ok, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(if (r.ricochet) "${r.txids.size}-tx chain broadcast to BLAKE2b." else "Broadcast to BLAKE2b.",
            style = Blake.mono(10f), color = Blake.ppDim)
        Spacer(Modifier.height(12.dp))
        r.txids.forEachIndexed { i, t ->
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).blakeCard(10.dp).clickableNoRipple { onCopy(t) }) {
                Text(if (r.ricochet) (if (i == 0) "source" else if (i == r.txids.size - 1) "→ recipient" else "hop $i") else "txid",
                    style = Blake.mono(8f), color = Blake.faint)
                Text(t, style = Blake.mono(10f), color = Blake.pp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("CLOSE", style = Blake.mono(13f, FontWeight.ExtraBold), color = Blake.bg, letterSpacing = 1.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().background(Blake.pp).padding(vertical = 12.dp).clickableNoRipple(onClose))
    }
}

// ---- Helpers ----
private fun amountSubtitle(unit: SendUnit, sendMax: Boolean, sats: Long, overspend: Boolean, ccy: String): String {
    if (overspend) return "More than you can spend"
    if (sats <= 0) return if (unit == SendUnit.BTC) "enter an amount in ᛒ" else "enter an amount in sats"
    val other = if (unit == SendUnit.BTC) "${"%,d".format(sats)} sats" else "${Blake.btc(sats)} ᛒ"
    val fiat = BlakePrice.fiatLabel(sats)
    return if (fiat != null) "$other · $fiat $ccy" else other
}

/** Light client-side plausibility (base58 P2PKH / bech32) — full validation is at build. */
private fun isPlausibleAddress(s: String): Boolean {
    val a = s.trim()
    // A BIP-47 PayNym payment code ("PM…") is a valid recipient — resolved to an address at send.
    if (com.astrolexis.pyblock.data.crypto.PaymentCode.looksLikePaymentCode(a)) return true
    if (a.length < 26) return false
    if (a.startsWith("1") || a.startsWith("3")) return a.length <= 35
    if (a.lowercase().startsWith("bc1")) return a.length <= 62
    return false
}

private fun sanitizeAddress(raw: String): String {
    var s = raw.trim()
    for (p in listOf("bitcoin:", "BITCOIN:")) if (s.startsWith(p)) s = s.removePrefix(p)
    s.indexOf('?').let { if (it >= 0) s = s.substring(0, it) }
    return s.trim()
}
