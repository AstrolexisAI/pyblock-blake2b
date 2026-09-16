package com.astrolexis.pyblock.ui.blake

import com.astrolexis.pyblock.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.zIndex
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
private data class WizardResult(
    val txids: List<String>, val ricochet: Boolean,
    val amountSats: Long = 0, val sentMax: Boolean = false,
    val recipient: String = "",       // address actually paid (or resolved stealth address)
    val contactValue: String = "",    // what to save as a contact — PM code for a PayNym, else the address
)

@Composable
fun SendWizardSheet(
    coinKeys: Set<String> = emptySet(),
    prefillTo: String = "",
    prefillSats: Long? = null,
    /** A broadcast landed: (txid, sats). Used to post a receipt into the chat it was paid from. */
    onSent: ((String, Long) -> Unit)? = null,
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
    var amountText by remember { mutableStateOf(prefillSats?.toString() ?: "") }
    var unit by remember { mutableStateOf(if (prefillSats != null) SendUnit.SATS else SendUnit.BTC) }
    var sendMax by remember { mutableStateOf(false) }
    var ricochet by remember { mutableStateOf(false) }
    var hops by remember { mutableStateOf(2) }
    var feeRate by remember { mutableStateOf(2) }
    var customFeeText by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<WizardResult?>(null) }

    val spendable = BlakeBalanceStore.spendableSats()
    val locked = BlakeBalanceStore.lockedSats()
    val selectedSats = if (coinKeys.isEmpty()) 0L
        else BlakeBalanceStore.allUtxos().filter { it.id in coinKeys }.sumOf { it.value }
    val sweepSats = if (selectedSats > 0) selectedSats else spendable
    // Clamped: a typo in the custom field must not become a four-figure fee rate.
    val effectiveFee = (customFeeText.toIntOrNull() ?: feeRate).coerceIn(1, 500)

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
            var notifiedNow = false     // a first-time PayNym notification tx went out this attempt
            try {
                // A PayNym payment code (PM…) → derive a fresh BIP-47 send address (advances the per-code counter).
                var dest = toAddress.trim()
                val peerCode = if (com.astrolexis.pyblock.data.crypto.PaymentCode.looksLikePaymentCode(dest)) dest else null
                var paynymIdx: Int? = null
                if (peerCode != null) {
                    // BIP-47: the FIRST-EVER payment to a peer needs an on-chain notification tx so the
                    // recipient learns our payment code and can detect (and spend) the stealth payment.
                    // One per peer, ever — [hasNotified]/[markNotified] guard it.
                    if (!com.astrolexis.pyblock.data.crypto.PaymentCode.hasNotified(ctx, peerCode)) {
                        BlakeSpend.sendNotification(ctx, peerCode, effectiveFee.toLong(), only)
                        notifiedNow = true
                    }
                    val next = com.astrolexis.pyblock.data.crypto.PaymentCode.nextWalletSendAddress(ctx, peerCode)
                        ?: throw Exception(ctx.getString(R.string.blk_couldn_t_derive_a_paynym_address_from_th))
                    dest = next.first; paynymIdx = next.second
                }
                val contactValue = peerCode ?: dest
                if (ricochet) {
                    val outcome = BlakeSpend.ricochet(ctx, dest, amt, sendMax, hops, effectiveFee.toLong(), only)
                    com.astrolexis.pyblock.data.wallet.RicochetHistory.add(ctx, outcome, hops, recorded, dest, "mainnet")
                    BlakeSentStore.add(outcome.txids.lastOrNull() ?: "", recorded, dest, coinKeys, true)
                    result = WizardResult(outcome.txids, true, recorded, sendMax, dest, contactValue)
                    onSent?.invoke(outcome.txids.lastOrNull() ?: "", recorded.toLong())
                } else {
                    val txid = BlakeSpend.send(ctx, dest, amt, sendMax, effectiveFee.toLong(), only)
                    BlakeSentStore.add(txid, recorded, dest, coinKeys, false)
                    result = WizardResult(listOf(txid), false, recorded, sendMax, dest, contactValue)
                    onSent?.invoke(txid, recorded.toLong())
                }
                // Broadcast landed: only now does the PayNym index move on. An index burnt by a
                // cancelled send would eventually push a payment past the recipient's look-ahead
                // window, where their wallet stops looking for it.
                if (peerCode != null && paynymIdx != null)
                    com.astrolexis.pyblock.data.crypto.PaymentCode.didSendWallet(ctx, peerCode, paynymIdx)
                BlakeBalanceStore.refresh(ctx)
            } catch (e: Exception) {
                android.util.Log.e("BlakeSend", "send failed: ${e::class.java.simpleName}: ${e.message}", e)
                val raw = e.message ?: com.astrolexis.pyblock.data.store.AppStrings.get(R.string.blk_send_failed, e::class.java.simpleName)
                error = when {
                    // This exact send already reached the network (a prior attempt landed but looked
                    // like it failed). Not an error — refresh so the coin shows in-flight, tell the
                    // user to stop resending.
                    e is BlakeSpend.Err.AlreadyPending -> { runCatching { BlakeBalanceStore.refresh(ctx) }; e.message }
                    // The notification tx broadcast, but it consumed the only spendable coin so the
                    // payment itself couldn't be built. It's not a failure — the peer is now announced;
                    // the user just needs another coin (wait for the change to confirm, or receive more).
                    notifiedNow && (e is BlakeSpend.Err.InsufficientSpendable || e is BlakeSpend.Err.NoSpendable) ->
                        ctx.getString(R.string.blk_paynym_announced_on_chain_that_used_your)
                    raw.contains("min relay", ignoreCase = true) ->
                        ctx.getString(R.string.blk_fee_too_low_for_the_blake2b_network_pick)
                    else -> raw
                }
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
                QrScanner(title = stringResource(R.string.blk_scan_a_bitcoin_address),
                    onResult = { code -> toAddress = sanitizeAddress(code); scanning = false },
                    onClose = { scanning = false })
            } else {
                // Safe-area insets: header below the status bar, footer above the gesture nav bar,
                // and the whole wizard lifts above the keyboard (was clipping BACK/NEXT).
                Column(Modifier.fillMaxSize().padding(top = topInsetDp, bottom = botInsetDp).imePadding()) {
                    // Header
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (ricochet) stringResource(R.string.blk_ricochet) else stringResource(R.string.blk_send), style = Blake.mono(20f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 3.sp)
                        Spacer(Modifier.weight(1f))
                        Text("✕", style = Blake.mono(22f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple(onClose))
                    }
                    // Progress
                    val titles = listOf("TO", stringResource(R.string.blk_amount), stringResource(R.string.blk_privacy), stringResource(R.string.blk_review))
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
                                onScan = { scanning = true }, onPaste = { toAddress = sanitizeAddress(clip.getText()?.text ?: "") },
                                onContacts = { showContacts = true })
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
                            Text(stringResource(R.string.blk_back), style = Blake.mono(13f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).border(1.dp, Blake.line, RectangleShape).padding(vertical = 14.dp)
                                    .clickableNoRipple { if (!busy) { error = null; step-- } })
                        }
                        if (step < 4) {
                            Text(stringResource(R.string.blk_next), style = Blake.mono(14f, FontWeight.ExtraBold), color = if (stepValid) Blake.bg else Blake.faint,
                                letterSpacing = 1.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).then(if (stepValid) Modifier.background(Blake.pp) else Modifier.border(1.dp, Blake.line, RectangleShape))
                                    .padding(vertical = 14.dp).clickableNoRipple { if (stepValid) { error = null; step++ } })
                        } else {
                            Text(if (busy) stringResource(R.string.blk_broadcasting) else if (ricochet) "CONFIRM RICOCHET" else "CONFIRM SEND",
                                style = Blake.mono(14f, FontWeight.ExtraBold), color = Blake.bg, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).background(Blake.pp).padding(vertical = 14.dp)
                                    .graphicsLayer { scaleX = if (busy) 0.98f else 1f; scaleY = if (busy) 0.98f else 1f; alpha = if (busy) 0.85f else 1f }
                                    .clickableNoRipple { if (!busy) { com.astrolexis.pyblock.ui.Sfx.select(); submit() } })
                        }
                    }
                }
            }
        }
    }

    if (showContacts) {
        ContactsSheet(onPick = { picked -> toAddress = picked; showContacts = false }, onClose = { showContacts = false })
    }
}

// ---- Step 1 · TO ----
@Composable
private fun StepTo(
    addr: String, onAddr: (String) -> Unit, coinKeys: Set<String>, selectedSats: Long,
    spendable: Long, locked: Long, onScan: () -> Unit, onPaste: () -> Unit, onContacts: () -> Unit,
) {
    ReplayWarning()
    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth().blakeCard()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.blk_send_to), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.blk_contacts), style = Blake.mono(11f), color = Blake.pp, modifier = Modifier.clickableNoRipple(onContacts))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.blk_scan), style = Blake.mono(11f), color = Blake.pp, modifier = Modifier.clickableNoRipple(onScan))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.blk_paste), style = Blake.mono(11f), color = Blake.pp, modifier = Modifier.clickableNoRipple(onPaste))
        }
        Spacer(Modifier.height(10.dp))
        val ok = isPlausibleAddress(addr)
        BasicTextField(addr, onAddr, textStyle = Blake.mono(14f).copy(color = Blake.fg),
            cursorBrush = SolidColor(Blake.pp),
            modifier = Modifier.fillMaxWidth().border(1.dp, if (ok) Blake.pp.copy(alpha = 0.6f) else Blake.line, RectangleShape).padding(14.dp))
        val why = addressProblem(addr)
        if (why != null) {
            Spacer(Modifier.height(6.dp))
            Text(why, style = Blake.mono(8f), color = Blake.danger)
        } else if (ok && !com.astrolexis.pyblock.data.crypto.PaymentCode.looksLikePaymentCode(addr.trim())) {
            // Echo it back grouped, so a wrong-but-valid address still gets a second look before it
            // is paid: spot the typo before it costs you.
            Spacer(Modifier.height(6.dp))
            Text("✓ " + com.astrolexis.pyblock.data.blake.AddressCheck.grouped(addr), style = Blake.mono(8f), color = Blake.ok)
        } else {
            com.astrolexis.pyblock.data.wallet.BlakeContactsStore.labelFor(addr)?.let { name ->
                Spacer(Modifier.height(6.dp))
                Text("☰ $name", style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.ok)
            }
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
            Text(stringResource(R.string.blk_amount), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
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
            Text(stringResource(R.string.blk_max), style = Blake.mono(40f, FontWeight.ExtraBold), color = Blake.pp,
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
        Text(if (coinKeys.isEmpty()) stringResource(R.string.blk_spendable_2, Blake.btc(spendable), Blake.RUNE)
             else stringResource(R.string.blk_from_selected_coins_available_change_ret, coinKeys.size, Blake.btc(selectedSats), Blake.RUNE),
            style = Blake.mono(8f), color = if (coinKeys.isEmpty()) Blake.faint else Blake.pp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(16.dp))
    // Fee card
    Column(Modifier.fillMaxWidth().blakeCard()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.blk_network_fee), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.blk_sats, "%,d".format(estFee)), style = Blake.mono(9f), color = Blake.faint)
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
            Text(stringResource(R.string.blk_custom), style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
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
        Text(if (sendMax) (if (coinKeys.isEmpty()) stringResource(R.string.blk_sweeps_all_spendable_mined_coins) else "Sweeps the selected coin${if (coinKeys.size == 1) "" else "s"} (no change).")
             else stringResource(R.string.blk_change_returns_to_the_same_address_the_c),
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
    Text(stringResource(R.string.blk_how_to_send), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
    Spacer(Modifier.height(14.dp))
    PrivacyOption(stringResource(R.string.blk_direct), !ricochet, stringResource(R.string.blk_one_transaction_straight_to_the_recipien)) { onRicochet(false) }
    if (BlakeChains.RICOCHET_ENABLED) {
        Spacer(Modifier.height(14.dp))
        PrivacyOption("RICOCHET", ricochet, stringResource(R.string.blk_sweeps_through_throwaway_hops_first_coor)) { onRicochet(true) }
        if (ricochet) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().blakeCard(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.blk_hops), style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
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
        Text(stringResource(R.string.blk_review_confirm_to_broadcast), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.warn, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))
        val contactName = com.astrolexis.pyblock.data.wallet.BlakeContactsStore.labelFor(toAddress)
        if (contactName != null) {
            ReviewRow("TO", "☰ $contactName")
            ReviewRow("", toAddress, 9f, faint = true)
        } else {
            ReviewRow("TO", toAddress, 9f)
        }
        ReviewRow("AMOUNT", if (sendMax) stringResource(R.string.blk_max_4, Blake.btc(sweepSats), Blake.RUNE) else "${Blake.btc(amt)} ${Blake.RUNE}")
        ReviewRow("", stringResource(R.string.blk_sats_2, "%,d".format(if (sendMax) sweepSats else amt)), 9f, faint = true)
        BlakePrice.fiatLabel(if (sendMax) sweepSats else amt)?.let { ReviewRow(stringResource(R.string.blk_fiat), "$it $ccy") }
        ReviewRow(stringResource(R.string.blk_mode), if (ricochet) "ricochet · $hops hop${if (hops == 1) "" else "s"}" else "direct")
        ReviewRow(stringResource(R.string.blk_coins), if (coinKeys.isEmpty()) "auto-select" else "${coinKeys.size} selected · ${Blake.btc(selectedSats)} ${Blake.RUNE}")
        ReviewRow(stringResource(R.string.blk_fee_rate), "$effectiveFee sat/vB")
        ReviewRow(stringResource(R.string.blk_est_fee), "~${"%,d".format(estFee)} sats")
        Spacer(Modifier.height(10.dp))
        ReplayWarning()
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.blk_final_fee_is_computed_when_the_transacti),
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
        Text(stringResource(R.string.blk_same_address_two_chains_this_address_can),
            style = Blake.mono(8f), color = Blake.warn)
    }
}

@Composable
private fun SpendableCard(coinKeys: Set<String>, selectedSats: Long, spendable: Long, locked: Long) {
    val cc = coinKeys.isNotEmpty()
    Column(Modifier.fillMaxWidth().blakeCard()) {
        Text(if (cc) "SELECTED · ${coinKeys.size} COIN${if (coinKeys.size == 1) "" else "S"}" else stringResource(R.string.blk_spendable),
            style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Text("${Blake.btc(if (cc) selectedSats else spendable)} ${Blake.RUNE}", style = Blake.mono(18f, FontWeight.ExtraBold), color = Blake.ok)
        Spacer(Modifier.height(4.dp))
        Text(if (cc) stringResource(R.string.blk_only_the_selected_coins_is_used_the_rest, Blake.btc(spendable), Blake.RUNE)
             else stringResource(R.string.blk_only_mature_mined_coins_can_be_sent_lock, Blake.btc(locked), Blake.RUNE),
            style = Blake.mono(8f), color = if (cc) Blake.pp else Blake.faint)
    }
}

// ---- Result ----
@Composable
private fun SendResultScreen(r: WizardResult, onCopy: (String) -> Unit, onClose: () -> Unit) {
    var pop by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(if (pop) 1f else 0f, tween(550, delayMillis = 50), label = "ring")   // one ring, once
    val scale by animateFloatAsState(if (pop) 1f else 0.3f, tween(450), label = "pop")
    val alpha by animateFloatAsState(if (pop) 1f else 0f, tween(450), label = "popa")
    androidx.compose.runtime.LaunchedEffect(Unit) { pop = true; com.astrolexis.pyblock.ui.Haptics.tap(); com.astrolexis.pyblock.ui.Sfx.success() }

    val contacts by com.astrolexis.pyblock.data.wallet.BlakeContactsStore.contacts.collectAsState()
    val contactName = contacts.firstOrNull { it.value == r.contactValue }?.label
    val isPaymentCode = com.astrolexis.pyblock.data.wallet.BlakeContact.looksLikePaymentCode(r.contactValue)
    var copiedTxid by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var contactNameInput by remember { mutableStateOf("") }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                // One ring, opening once from the check and fading as it grows.
                Box(Modifier.size(30.dp).graphicsLayer { scaleX = 0.4f + 5.6f * ring; scaleY = 0.4f + 5.6f * ring; this.alpha = 0.9f * (1f - ring) }
                    .border(1.5.dp, Blake.ok, CircleShape))
                Text("✓", style = Blake.mono(26f, FontWeight.ExtraBold), color = Blake.ok,
                    modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha })
            }
            Spacer(Modifier.width(10.dp))
            Text(if (r.ricochet) stringResource(R.string.blk_ricochet_sent) else stringResource(R.string.blk_sent), style = Blake.mono(18f, FontWeight.ExtraBold), color = Blake.ok, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(12.dp))

        // Amount + fiat + recipient summary
        Column(Modifier.fillMaxWidth().blakeCard()) {
            Text("${if (r.sentMax) "" else "−"}${Blake.btc(r.amountSats)} ${Blake.RUNE}",
                style = Blake.mono(24f, FontWeight.ExtraBold), color = Blake.pp)
            BlakePrice.fiatLabel(r.amountSats)?.let {
                Text("≈ $it", style = Blake.mono(9f), color = Blake.faint)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("to ", style = Blake.mono(9f), color = Blake.faint)
                if (contactName != null) Text("☰ $contactName", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.ok)
                else Text(shortAddr(r.recipient), style = Blake.mono(10f), color = Blake.fg, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(if (r.ricochet) stringResource(R.string.blk_tx_chain_broadcast_to_blake2b, r.txids.size) else "Broadcast to BLAKE2b.",
            style = Blake.mono(10f), color = Blake.ppDim)
        Spacer(Modifier.height(12.dp))
        r.txids.forEachIndexed { i, t ->
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).blakeCard(10.dp)
                .clickableNoRipple { onCopy(t); copiedTxid = t }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (r.ricochet) (if (i == 0) "source" else if (i == r.txids.size - 1) stringResource(R.string.blk_recipient) else "hop $i") else "txid",
                        style = Blake.mono(8f), color = Blake.faint)
                    Spacer(Modifier.weight(1f))
                    Text(if (copiedTxid == t) stringResource(R.string.blk_copied_3) else "copy", style = Blake.mono(8f), color = if (copiedTxid == t) Blake.ok else Blake.pp)
                }
                Text(t, style = Blake.mono(10f), color = Blake.pp, maxLines = 1)
            }
        }

        // Save the recipient as a contact (skip if it already is one).
        if (contactName == null && r.contactValue.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            when {
                saved -> Text(stringResource(R.string.blk_saved_to_contacts), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.ok)
                saving -> Column(Modifier.fillMaxWidth().blakeCard()) {
                    Text(if (isPaymentCode) stringResource(R.string.blk_save_paynym_as_contact) else stringResource(R.string.blk_save_recipient_as_contact),
                        style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 1.sp)
                    Spacer(Modifier.height(8.dp))
                    BasicTextField(contactNameInput, { contactNameInput = it }, singleLine = true,
                        textStyle = Blake.mono(13f).copy(color = Blake.fg), cursorBrush = SolidColor(Blake.pp),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().border(1.dp, Blake.line, RectangleShape).padding(10.dp)) {
                                if (contactNameInput.isEmpty()) Text(stringResource(R.string.blk_name_e_g_stefa), style = Blake.mono(13f), color = Blake.faint)
                                inner()
                            }
                        })
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Text(stringResource(R.string.blk_save), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.bg, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).background(Blake.pp).padding(vertical = 10.dp)
                                .clickableNoRipple { com.astrolexis.pyblock.data.wallet.BlakeContactsStore.add(contactNameInput, r.contactValue); saved = true })
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.blk_cancel), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).border(1.dp, Blake.line, RectangleShape).padding(vertical = 10.dp).clickableNoRipple { saving = false })
                    }
                }
                else -> Text(if (isPaymentCode) stringResource(R.string.blk_save_paynym_as_contact_2) else stringResource(R.string.blk_save_recipient_as_contact_2),
                    style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().border(1.dp, Blake.pp, RectangleShape).padding(vertical = 11.dp).clickableNoRipple { saving = true })
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.blk_close), style = Blake.mono(13f, FontWeight.ExtraBold), color = Blake.bg, letterSpacing = 1.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().background(Blake.pp).padding(vertical = 12.dp).clickableNoRipple(onClose))
    }
    }
}

private fun shortAddr(s: String): String = if (s.length <= 22) s else s.take(12) + "…" + s.takeLast(6)

// ---- Helpers ----
@Composable
private fun amountSubtitle(unit: SendUnit, sendMax: Boolean, sats: Long, overspend: Boolean, ccy: String): String {
    if (overspend) return stringResource(R.string.blk_more_than_you_can_spend)
    if (sats <= 0) return if (unit == SendUnit.BTC) stringResource(R.string.blk_enter_an_amount_in) else stringResource(R.string.blk_enter_an_amount_in_sats)
    val other = if (unit == SendUnit.BTC) stringResource(R.string.blk_sats_2, "%,d".format(sats)) else "${Blake.btc(sats)} ᛒ"
    val fiat = BlakePrice.fiatLabel(sats)
    return if (fiat != null) "$other · $fiat $ccy" else other
}

/** Checksum-verified, not shape-guessed: a typo that keeps the length used to sail through here. */
private fun isPlausibleAddress(s: String): Boolean {
    val a = s.trim()
    // A BIP-47 PayNym payment code ("PM…") is a valid recipient — resolved to an address at send.
    if (com.astrolexis.pyblock.data.crypto.PaymentCode.looksLikePaymentCode(a)) return true
    return com.astrolexis.pyblock.data.blake.AddressCheck.isValid(a)
}

/** What's wrong with what they typed, in one sentence they can act on. */
private fun addressProblem(s: String): String? {
    val a = s.trim()
    if (a.isEmpty() || com.astrolexis.pyblock.data.crypto.PaymentCode.looksLikePaymentCode(a)) return null
    return com.astrolexis.pyblock.data.blake.AddressCheck.problem(a)
}

private fun sanitizeAddress(raw: String): String {
    var s = raw.trim()
    for (p in listOf("bitcoin:", "BITCOIN:")) if (s.startsWith(p)) s = s.removePrefix(p)
    s.indexOf('?').let { if (it >= 0) s = s.substring(0, it) }
    return s.trim()
}
