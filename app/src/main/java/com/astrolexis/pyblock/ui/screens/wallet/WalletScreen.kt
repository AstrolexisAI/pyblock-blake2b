package com.astrolexis.pyblock.ui.screens.wallet

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.astrolexis.pyblock.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import com.astrolexis.pyblock.data.wallet.BdkNode
import com.astrolexis.pyblock.data.wallet.VanityWallet
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.data.wallet.WalletSyncManager
import com.astrolexis.pyblock.ui.components.QrCode
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WalletViewModel(app: Application) : AndroidViewModel(app) {
    val wallets = WalletStore.wallets
    var sendResult by mutableStateOf<String?>(null)
    var sending by mutableStateOf(false)
    var sentSats by mutableStateOf(0L)      // amount of the last successful send (for the celebration)
    // The record of the last successful ricochet — drives the full tx-chain view (no
    // auto-dismiss celebration; a ricochet is inspectable). Mirrors iOS ricochetResult.
    var ricochetResult by mutableStateOf<com.astrolexis.pyblock.data.wallet.RicochetRecord?>(null)

    init { WalletStore.ensureLoaded(app) }

    fun node(meta: VanityWallet): BdkNode = WalletSyncManager.getNode(getApplication(), meta)
    fun focus(meta: VanityWallet) = WalletSyncManager.focus(getApplication(), meta)
    fun unfocus() = WalletSyncManager.unfocus()
    fun rotateAll() = WalletSyncManager.scheduleAll(getApplication(), wallets.value)
    fun remove(id: String) = WalletStore.remove(getApplication(), id)

    fun send(meta: VanityWallet, to: String, sats: Long, feeSatVb: Long, sendMax: Boolean = false, notifyPeer: String? = null, paynymCode: String? = null, selectedKeys: List<String>? = null) {
        if (sending) return
        sending = true; sendResult = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bal = node(meta).balanceSats
                // First cold PayNym payment to this peer: announce my payment code on-chain
                // (BIP-47 notification) so they can discover this and future stealth payments.
                // One-time per peer; must land before the payment so it's discoverable.
                val ctx = getApplication<Application>()
                if (paynymCode != null && !com.astrolexis.pyblock.data.crypto.PaymentCode.hasNotified(ctx, paynymCode)) {
                    node(meta).sendNotification(paynymCode, feeSatVb)
                    com.astrolexis.pyblock.data.crypto.PaymentCode.markNotified(ctx, paynymCode)
                }
                val txid = node(meta).send(to, sats, feeSatVb, sendMax, selectedKeys)
                sentSats = if (sendMax) bal else sats
                sendResult = getApplication<Application>().getString(R.string.wallet_send_result_ok, txid.take(20))
                // Hand a receipt back to the chat, if this pay came from a DM.
                notifyPeer?.let { com.astrolexis.pyblock.data.wallet.PendingReceipt.set(it, txid, sats) }
            } catch (e: Exception) {
                val m = e.message.orEmpty()
                sendResult = if (m.contains("insufficient", ignoreCase = true))
                    getApplication<Application>().getString(R.string.wallet_send_result_insufficient, fmtSats(node(meta).balanceSats))
                else getApplication<Application>().getString(R.string.wallet_send_result_error, m.take(60))
            }
            sending = false
        }
    }

    /** RICOCHET: sweep/pay the selected coins forward through N self-spend hops, then
     *  persist the outcome (tx chain + hop keys) to history and surface the chain view. */
    fun ricochet(meta: VanityWallet, to: String, sats: Long, feeSatVb: Long, sendMax: Boolean, hops: Int, selectedKeys: List<String>?) {
        if (sending) return
        sending = true; sendResult = null; ricochetResult = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bal = node(meta).balanceSats
                val outcome = node(meta).ricochet(to, sats, sendMax, hops, feeSatVb, selectedKeys)
                sentSats = if (sendMax) bal else sats
                ricochetResult = com.astrolexis.pyblock.data.wallet.RicochetHistory.add(
                    getApplication(), outcome, hops = hops, amountSats = sentSats, toAddress = to, network = "mainnet",
                )
            } catch (e: Exception) {
                sendResult = getApplication<Application>().getString(R.string.wallet_send_result_error, e.message.orEmpty().take(80))
            }
            sending = false
        }
    }

    override fun onCleared() { WalletSyncManager.stopAll() }
}

@Composable
fun WalletScreen(onLaunchVanity: () -> Unit, onLaunchMining: () -> Unit = {}) {
    val vm: WalletViewModel = viewModel()
    val wallets by vm.wallets.collectAsState()
    var openId by remember { mutableStateOf<String?>(null) }
    var startTab by remember { mutableStateOf("receive") }
    var addressControl by remember { mutableStateOf(false) }
    var coinsOpen by remember { mutableStateOf(false) }
    var receiveOpen by remember { mutableStateOf(false) }
    var sendOpen by remember { mutableStateOf(false) }
    var nodeDashOpen by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf<com.astrolexis.pyblock.data.wallet.PendingPayment.Request?>(null) }

    LaunchedEffect(wallets.size) { if (openId == null) vm.rotateAll() }

    // A pending payment handed off from the chat → open SEND pre-filled with the
    // recipient (SEND auto-sources the richest wallet, like iOS).
    LaunchedEffect(wallets.size) {
        if (prefill == null && wallets.isNotEmpty()) {
            com.astrolexis.pyblock.data.wallet.PendingPayment.consume()?.let { p -> prefill = p; sendOpen = true }
        }
    }

    val open = wallets.firstOrNull { it.id == openId }
    // The whole wallet tab is the vault — force it calm (no starfield / bloom /
    // breathing title) so amounts read against a still, trustworthy background,
    // regardless of the user's arcade preference.
    androidx.compose.runtime.CompositionLocalProvider(
        com.astrolexis.pyblock.ui.theme.LocalSober provides true,
    ) {
    when {
        open != null -> {
            BackHandler { vm.unfocus(); openId = null }
            WalletDetail(vm, open, startTab = startTab, onBack = { vm.unfocus(); openId = null })
        }
        sendOpen -> {
            BackHandler { sendOpen = false; prefill = null }
            SendScreen(vm, wallets, prefill = prefill, onBack = { sendOpen = false; prefill = null })
        }
        receiveOpen -> {
            BackHandler { receiveOpen = false }
            ReceiveScreen(vm, wallets, onBack = { receiveOpen = false })
        }
        nodeDashOpen -> {
            BackHandler { nodeDashOpen = false }
            NodeDashboardScreen(vm, wallets, onBack = { nodeDashOpen = false })
        }
        coinsOpen -> {
            // Checked before addressControl so COINS (opened from within Address Control)
            // drills in; Back returns to Address Control (iOS nav).
            BackHandler { coinsOpen = false }
            CoinsScreen(vm, wallets, onBack = { coinsOpen = false })
        }
        addressControl -> {
            BackHandler { addressControl = false }
            AddressControl(vm, wallets, onOpen = { openId = it; startTab = "receive" },
                onLaunchVanity = onLaunchVanity, onCoins = { coinsOpen = true }, onBack = { addressControl = false })
        }
        else -> WalletHome(vm, wallets,
            onReceive = { receiveOpen = true },
            onSend = { if (wallets.isEmpty()) onLaunchVanity() else sendOpen = true },
            onGen = onLaunchVanity, onAddressControl = { addressControl = true }, onMining = onLaunchMining,
            onNodeDash = { nodeDashOpen = true })
    }
    }
}

// MARK: Home — unified balance + actions + hub (mirrors iOS MyAddressView.walletCard)

@Composable
private fun WalletHome(vm: WalletViewModel, wallets: List<VanityWallet>,
                       onReceive: () -> Unit, onSend: () -> Unit, onGen: () -> Unit,
                       onAddressControl: () -> Unit, onMining: () -> Unit, onNodeDash: () -> Unit) {
    val nodes = wallets.map { vm.node(it) }
    val total = nodes.sumOf { it.balanceSats }
    val synced = wallets.isNotEmpty() && nodes.all { it.synced }
    val syncedCount = nodes.count { it.synced }
    val tip = nodes.maxOfOrNull { it.tipHeight } ?: 0
    val statusColor = when {
        wallets.isEmpty() -> PyTheme.primaryDim
        synced -> PyTheme.primary
        else -> PyTheme.yellow
    }
    // Reflect the recovering node's real status (e.g. "syncing 93%") instead of a
    // generic count, so progress is visible.
    val statusLine = when {
        wallets.isEmpty() -> stringResource(R.string.wallet_home_no_wallets)
        synced -> stringResource(R.string.wallet_home_synced_tip, tip)
        else -> nodes.firstOrNull { !it.synced }?.status
            ?.takeIf { it.isNotBlank() && it != "idle" } ?: stringResource(R.string.wallet_home_syncing, syncedCount, wallets.size)
    }
    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
    Starfield()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        MarqueeTitle(text = stringResource(R.string.wallet_home_title))
        Spacer(Modifier.height(18.dp))
        // walletCard: GEN + node status, aggregate balance, RECEIVE / SEND
        Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.border(1.dp, PyTheme.magenta.copy(alpha = 0.6f), RectangleShape)
                    .clickableNoRipple { Haptics.thock(); onGen() }.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    com.astrolexis.pyblock.ui.components.LightningGlyph(PyTheme.magenta, 12.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.wallet_home_gen), style = PyType.mono(11f), color = PyTheme.magenta)
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickableNoRipple { Haptics.tap(); onNodeDash() }) {
                    com.astrolexis.pyblock.ui.components.DnaGlyph(statusColor, 15.dp, animated = !synced && wallets.isNotEmpty())
                    Spacer(Modifier.width(7.dp))
                    Text(statusLine, style = PyType.mono(11f), color = PyTheme.cyan)
                    Spacer(Modifier.width(4.dp))
                    Text("›", style = PyType.mono(12f), color = PyTheme.primaryDim)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(fmtSats(total), style = PyType.mono(30f), color = PyTheme.yellow, maxLines = 1,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            // iOS parity (MyAddressView): the slice of the total still at 0-conf.
            val totalPending = nodes.sumOf { it.pendingSats }
            if (totalPending > 0) Text(stringResource(R.string.wallet_home_pending, fmtSats(totalPending)),
                style = PyType.mono(11f), color = PyTheme.yellow,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WalletActionButton(stringResource(R.string.wallet_action_receive), down = true, PyTheme.primary, Modifier.weight(1f)) { Haptics.tap(); onReceive() }
                WalletActionButton(stringResource(R.string.wallet_action_send), down = false, PyTheme.magenta, Modifier.weight(1f)) { Haptics.tap(); onSend() }
            }
        }
        Spacer(Modifier.height(18.dp))
        // hub buttons → address control / mining
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HubButton(stringResource(R.string.wallet_hub_address_control), Modifier.weight(1f), { Haptics.tap(); onAddressControl() }) { com.astrolexis.pyblock.ui.components.GearGlyph(PyTheme.cyan, 15.dp) }
            HubButton(stringResource(R.string.wallet_hub_mining), Modifier.weight(1f), { Haptics.tap(); onMining() }) { com.astrolexis.pyblock.ui.components.PickGlyph(PyTheme.cyan, 15.dp) }
        }
        Spacer(Modifier.height(18.dp))
        WalletActivity(vm)
        Spacer(Modifier.height(24.dp))
    }
    }
}

// MARK: Unified RECEIVE — PayNym (account) + a picker over every vanity address
// (mirrors iOS ReceiveSheet).

@Composable
private fun ReceiveScreen(vm: WalletViewModel, wallets: List<VanityWallet>, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clip = LocalClipboardManager.current
    val code = remember { com.astrolexis.pyblock.data.crypto.PaymentCode.myCode(ctx) }
    var picked by remember { mutableStateOf<VanityWallet?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        com.astrolexis.pyblock.data.crypto.PaynymNotifications.scan(ctx)
        // Explain PayNym once, the first time — so a new user knows how receiving works
        // and doesn't think a payment "never arrived".
        val p = ctx.getSharedPreferences("pyblock_prefs", android.content.Context.MODE_PRIVATE)
        if (!p.getBoolean("paynym_help_seen", false)) { p.edit().putBoolean("paynym_help_seen", true).apply(); showHelp = true }
    }
    if (showHelp) {
        // Resolve strings here (inside the app's LocalizedApp) — a Dialog's own composition
        // doesn't follow the in-app language, so we pass the localized text in.
        val help = PaynymHelpText(
            title = stringResource(R.string.pnym_help_title),
            gotIt = stringResource(R.string.pnym_help_got_it),
            points = listOf(
                stringResource(R.string.pnym_help_what_title) to stringResource(R.string.pnym_help_what),
                stringResource(R.string.pnym_help_receive_title) to stringResource(R.string.pnym_help_receive),
                stringResource(R.string.pnym_help_send_title) to stringResource(R.string.pnym_help_send),
                stringResource(R.string.pnym_help_safe_title) to stringResource(R.string.pnym_help_safe),
            ),
        )
        PaynymHelpDialog(help) { showHelp = false }
    }
    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
    Starfield()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MarqueeTitle(text = stringResource(R.string.wallet_receive_title))
            Spacer(Modifier.weight(1f))
            Text("ⓘ", style = PyType.mono(18f), color = PyTheme.cyan, modifier = Modifier.clickableNoRipple { Haptics.tap(); showHelp = true })
            Spacer(Modifier.width(14.dp))
            Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { Haptics.tap(); onBack() })
        }
        Spacer(Modifier.height(16.dp))
        // Featured PayNym (account-level).
        Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.astrolexis.pyblock.ui.components.LockGlyph(PyTheme.primary, 13.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.wallet_receive_your_paynym), style = PyType.mono(13f), color = PyTheme.primary, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(8.dp))
            com.astrolexis.pyblock.ui.components.Identicon(code, 44)
            Spacer(Modifier.height(6.dp))
            Text(com.astrolexis.pyblock.data.crypto.PaynymName.cosmic(code), style = PyType.mono(13f), color = PyTheme.yellow, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.border(2.dp, PyTheme.primary, RectangleShape)) { QrCode(code, 220.dp) }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.wallet_receive_private_hint),
                style = PyType.mono(9f), color = PyTheme.primaryDim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            CopyChip(stringResource(R.string.wallet_receive_copy_paynym)) { Haptics.tap(); clip.setText(AnnotatedString(code)) }
            Text(stringResource(R.string.wallet_receive_bip47_hint),
                style = PyType.mono(8f), color = PyTheme.primaryDim, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(16.dp))
        // Fallback: a specific vanity address — a picker over all wallets.
        Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(14.dp)) {
            Text(stringResource(R.string.wallet_receive_vanity_header), style = PyType.mono(10f), color = PyTheme.yellow, letterSpacing = 1.sp)
            Spacer(Modifier.height(10.dp))
            val sel = picked
            when {
                sel != null -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    com.astrolexis.pyblock.ui.components.Identicon(sel.address, 36)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.border(2.dp, PyTheme.primary, RectangleShape)) { QrCode(sel.address, 180.dp) }
                    Spacer(Modifier.height(6.dp))
                    Text(sel.address, style = PyType.mono(10f), color = PyTheme.cyan, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    CopyChip(stringResource(R.string.wallet_receive_copy_label, sel.label.uppercase())) { Haptics.tap(); clip.setText(AnnotatedString(sel.address)) }
                    Text(stringResource(R.string.wallet_receive_pick_another), style = PyType.mono(11f), color = PyTheme.primaryDim,
                        modifier = Modifier.fillMaxWidth().clickableNoRipple { Haptics.select(); picked = null }.padding(top = 6.dp), textAlign = TextAlign.Center)
                }
                wallets.isEmpty() -> Text(stringResource(R.string.wallet_receive_empty),
                    style = PyType.mono(9f), color = PyTheme.primaryDim)
                else -> wallets.forEach { w ->
                    Row(Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape)
                        .clickableNoRipple { Haptics.select(); picked = w }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(w.label.ifEmpty { stringResource(R.string.wallet_default_label) }, style = PyType.mono(13f), color = PyTheme.cyan)
                        Spacer(Modifier.weight(1f))
                        Text("${w.address.take(12)}…", style = PyType.mono(9f), color = PyTheme.primaryDim)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
private fun WalletActionButton(label: String, down: Boolean, c: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.border(2.dp, c, RectangleShape).clickableNoRipple(onClick).padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        com.astrolexis.pyblock.ui.components.WalletArrow(down, c, 16.dp, animated = false)
        Spacer(Modifier.width(8.dp))
        Text(label, style = PyType.mono(15f), color = c, letterSpacing = 1.sp)
    }
}

@Composable
private fun HubButton(label: String, modifier: Modifier, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(modifier.border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape)
        .clickableNoRipple(onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(7.dp))
        Text(label, style = PyType.mono(12f), color = PyTheme.cyan, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text("›", style = PyType.mono(14f), color = PyTheme.primaryDim)
    }
}

// MARK: List

@Composable
private fun AddressControl(vm: WalletViewModel, wallets: List<VanityWallet>, onOpen: (String) -> Unit,
                          onLaunchVanity: () -> Unit, onCoins: () -> Unit, onBack: () -> Unit) {
    var importOpen by remember { mutableStateOf(false) }
    var signOpen by remember { mutableStateOf(false) }
    var passwordOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var airGapOpen by remember { mutableStateOf(false) }
    var ricochetHistoryOpen by remember { mutableStateOf(false) }
    var openRicochet by remember { mutableStateOf<com.astrolexis.pyblock.data.wallet.RicochetRecord?>(null) }
    val vaultEnabled by com.astrolexis.pyblock.data.wallet.WalletVault.enabled.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val exportScope = androidx.compose.runtime.rememberCoroutineScope()

    // Air-gap hub (sign + broadcast) — offline-signing tools, full-screen.
    if (airGapOpen) {
        androidx.activity.compose.BackHandler { airGapOpen = false }
        com.astrolexis.pyblock.ui.screens.airgap.AirGapHubScreen(onBack = { airGapOpen = false })
        return
    }

    // Ricochet history (tx chain + hop keys) — full-screen, drills into one record.
    val ricRecord = openRicochet
    if (ricRecord != null) {
        androidx.activity.compose.BackHandler { openRicochet = null }
        RicochetChainScreen(record = ricRecord, onBack = { openRicochet = null })
        return
    }
    if (ricochetHistoryOpen) {
        androidx.activity.compose.BackHandler { ricochetHistoryOpen = false }
        RicochetHistoryScreen(network = "mainnet", onOpen = { openRicochet = it }, onBack = { ricochetHistoryOpen = false })
        return
    }

    // Key-management surface (WIF export, sign, import, backup) — block
    // screenshots / screen recording / recents thumbnail while it's open.
    com.astrolexis.pyblock.ui.components.SecureFlag()
    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
    Starfield()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MarqueeTitle(text = stringResource(R.string.wallet_ac_title))
            Spacer(Modifier.weight(1f))
            Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { Haptics.tap(); onBack() })
        }
        Spacer(Modifier.height(18.dp))
        AcRow(stringResource(R.string.wallet_ac_generate), PyTheme.magenta, { Haptics.thock(); onLaunchVanity() }) { com.astrolexis.pyblock.ui.components.LightningGlyph(PyTheme.magenta, 14.dp) }
        Spacer(Modifier.height(10.dp))
        AcRow(stringResource(R.string.wallet_ac_import), PyTheme.primary, { Haptics.tap(); importOpen = true }) { com.astrolexis.pyblock.ui.components.WalletArrow(true, PyTheme.primary, 13.dp, animated = false) }
        Spacer(Modifier.height(10.dp))
        AcRow(stringResource(R.string.wallet_hub_coins), PyTheme.cyan, { Haptics.tap(); onCoins() }) { Text("❄", style = PyType.mono(14f), color = PyTheme.cyan) }
        Spacer(Modifier.height(10.dp))
        AcRow("RICOCHET HISTORY", PyTheme.magenta, { Haptics.tap(); ricochetHistoryOpen = true }) { Text("↝", style = PyType.mono(15f), color = PyTheme.magenta) }
        Spacer(Modifier.height(10.dp))
        AcRow(if (exporting) stringResource(R.string.wallet_ac_exporting) else stringResource(R.string.wallet_ac_export_pdf), PyTheme.cyan, {
            if (!exporting) {
                Haptics.thock()
                exporting = true
                exportScope.launch(Dispatchers.IO) {
                    val entries = wallets.map { w ->
                        com.astrolexis.pyblock.data.wallet.BackupPdf.Entry(w.label.ifEmpty { ctx.getString(R.string.wallet_default_label) }, w.address,
                            WalletStore.wif(ctx, w.id) ?: "", vm.node(w).balanceSats)
                    }
                    val code = com.astrolexis.pyblock.data.crypto.PaymentCode.myCode(ctx)
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    val file = com.astrolexis.pyblock.data.wallet.BackupPdf.generate(ctx, entries, code, com.astrolexis.pyblock.data.crypto.PaynymName.cosmic(code), date)
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        exporting = false
                        if (file != null) {
                            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            val chooser = android.content.Intent.createChooser(send, ctx.getString(R.string.wallet_ac_chooser_title))
                            val act = com.astrolexis.pyblock.util.findActivity(ctx)
                            if (act != null) act.startActivity(chooser)
                            else ctx.startActivity(chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
            }
        }) { com.astrolexis.pyblock.ui.components.LockGlyph(PyTheme.cyan, 13.dp) }
        Text(stringResource(R.string.wallet_ac_export_hint),
            style = PyType.mono(8f), color = PyTheme.primaryDim, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        AcRow(stringResource(R.string.wallet_ac_sign_verify), PyTheme.primary, { Haptics.tap(); signOpen = true }) { com.astrolexis.pyblock.ui.components.LockGlyph(PyTheme.primary, 13.dp) }
        Spacer(Modifier.height(10.dp))
        AcRow(stringResource(R.string.wallet_ac_airgap), PyTheme.cyan, { Haptics.tap(); airGapOpen = true }) { Text("⛓", style = PyType.mono(14f), color = PyTheme.cyan) }
        Spacer(Modifier.height(10.dp))
        val pwLabel = if (vaultEnabled) stringResource(R.string.wallet_ac_lock_now) else stringResource(R.string.wallet_ac_enable_password)
        AcRow(pwLabel, PyTheme.magenta, {
            if (vaultEnabled) { Haptics.warning(); com.astrolexis.pyblock.data.wallet.WalletVault.lock() } else { Haptics.tap(); passwordOpen = true }
        }) { com.astrolexis.pyblock.ui.components.LockGlyph(PyTheme.magenta, 13.dp) }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.wallet_ac_your_wallets), style = PyType.mono(13f), color = PyTheme.cyan, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        if (wallets.isEmpty()) {
            Text(stringResource(R.string.wallet_ac_empty),
                style = PyType.mono(10f), color = PyTheme.primaryDim)
        } else {
            wallets.forEach { w -> WalletRow(vm.node(w), w) { Haptics.tap(); onOpen(w.id) } }
        }
        Spacer(Modifier.height(24.dp))
    }
    }
    if (importOpen) ImportKeyDialog(onDone = { importOpen = false })
    if (signOpen) MessageSignDialog(onClose = { signOpen = false })
    if (passwordOpen) androidx.compose.ui.window.Dialog(
        onDismissRequest = { passwordOpen = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) { com.astrolexis.pyblock.ui.screens.vault.SetPasswordScreen(onClose = { passwordOpen = false }) }
}

/** A bordered address-control action row: icon + label + › (mirrors iOS). */
@Composable
private fun AcRow(label: String, color: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, color.copy(alpha = 0.6f), RectangleShape)
        .clickableNoRipple(onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(label, style = PyType.mono(13f), color = color, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text("›", style = PyType.mono(16f), color = PyTheme.primaryDim)
    }
}

/** Import an existing wallet from a WIF private key (any P2PKH "1…" key). The
 *  key is validated by re-deriving its address before it's stored. */
@Composable
private fun ImportKeyDialog(onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var wif by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDone) {
        // User's private key is entered/visible here — secure the dialog window.
        com.astrolexis.pyblock.ui.components.SecureFlag()
        Column(Modifier.fillMaxWidth().background(PyTheme.bg).border(1.dp, PyTheme.primary, RectangleShape).padding(20.dp)) {
            Text(stringResource(R.string.wallet_import_title), style = PyType.mono(15f), color = PyTheme.yellow, letterSpacing = 2.sp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.wallet_import_hint),
                style = PyType.mono(9f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(12.dp))
            if (done != null) {
                Text(stringResource(R.string.wallet_import_done_ok), style = PyType.mono(14f), color = PyTheme.primary)
                Text("$done", style = PyType.mono(10f), color = PyTheme.cyan)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.wallet_import_done_button), style = PyType.mono(13f), color = PyTheme.primary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.primary, RectangleShape).clickableNoRipple(onDone).padding(vertical = 10.dp))
            } else {
                Field(stringResource(R.string.wallet_import_field_placeholder), wif, KeyboardType.Ascii) { wif = it.trim(); error = null }
                Row(Modifier.clickableNoRipple { clip.getText()?.let { wif = it.text.trim() } }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    com.astrolexis.pyblock.ui.components.CopyGlyph(PyTheme.cyan, 11.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.wallet_paste), style = PyType.mono(11f), color = PyTheme.cyan)
                }
                error?.let { Text(it, style = PyType.mono(10f), color = PyTheme.danger) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.wallet_cancel), style = PyType.mono(13f), color = PyTheme.primary, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).border(1.dp, PyTheme.primary, RectangleShape).clickableNoRipple(onDone).padding(vertical = 10.dp))
                    Text(stringResource(R.string.wallet_import_confirm), style = PyType.mono(13f), color = PyTheme.bg, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).background(PyTheme.magenta)
                            .clickableNoRipple {
                                Haptics.thock()
                                val addr = importFromWif(ctx, wif)
                                if (addr != null) { Haptics.success(); done = addr } else { Haptics.error(); error = ctx.getString(R.string.wallet_import_invalid) }
                            }.padding(vertical = 10.dp))
                }
            }
        }
    }
}

/** Validate + store a WIF-imported wallet; returns the derived address or null. */
private fun importFromWif(ctx: android.content.Context, wif: String): String? {
    val vc = com.astrolexis.pyblock.data.crypto.VanityCrypto
    val (priv, compressed) = vc.decodeWif(wif.trim()) ?: return null
    val pub = (if (compressed) vc.compressedPubkey(priv) else vc.uncompressedPubkey(priv)) ?: return null
    val address = vc.p2pkhAddress(pub)
    val w = VanityWallet(java.util.UUID.randomUUID().toString(), "imported",
        address, compressed, com.astrolexis.pyblock.data.crypto.PaymentCode.RECEIVE_BIRTHDAY)
    return if (WalletStore.add(ctx, w, wif.trim())) address else null
}

@Composable
private fun WalletRow(node: BdkNode, w: VanityWallet, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)
        .border(1.dp, PyTheme.magenta.copy(alpha = 0.5f), RectangleShape)
        .clickableNoRipple(onOpen).padding(12.dp), verticalAlignment = Alignment.Top) {
        com.astrolexis.pyblock.ui.components.KeyGlyph(PyTheme.yellow, 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(w.label.ifEmpty { stringResource(R.string.wallet_default_label) }.uppercase(), style = PyType.mono(13f), color = PyTheme.cyan, letterSpacing = 1.sp)
            Spacer(Modifier.height(4.dp))
            Text(midTruncate(w.address), style = PyType.mono(11f), color = PyTheme.primaryDim)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtSats(node.balanceSats), style = PyType.mono(13f), color = PyTheme.yellow)
            Text(if (node.synced) stringResource(R.string.wallet_row_synced) else if (node.status == "idle") stringResource(R.string.wallet_row_tap_to_sync) else stringResource(R.string.wallet_row_syncing),
                style = PyType.mono(9f), color = if (node.synced) PyTheme.primary else PyTheme.primaryDim)
        }
    }
}

/** Middle-truncate an address like iOS `.truncationMode(.middle)`. */
private fun midTruncate(s: String, head: Int = 12, tail: Int = 8): String =
    if (s.length <= head + tail + 1) s else "${s.take(head)}…${s.takeLast(tail)}"

// MARK: Detail

@Composable
private fun WalletDetail(vm: WalletViewModel, w: VanityWallet,
                         prefill: com.astrolexis.pyblock.data.wallet.PendingPayment.Request? = null,
                         startTab: String = "receive",
                         onBack: () -> Unit) {
    val node = vm.node(w)
    var tab by remember { mutableStateOf(if (prefill != null) "send" else startTab) }
    DisposableEffect(w.id) { vm.focus(w); onDispose { vm.unfocus() } }
    val clip = LocalClipboardManager.current

    // Effects: haptic + visual celebration on receive (balance up) and on send.
    val haptic = LocalHapticFeedback.current
    var celebrate by remember { mutableStateOf<String?>(null) }   // "received" | "sent"
    var celebrateSats by remember { mutableStateOf(0L) }
    var prevBalance by remember { mutableStateOf(node.balanceSats) }
    LaunchedEffect(node.balanceSats) {
        if (node.balanceSats > prevBalance) {
            celebrateSats = node.balanceSats - prevBalance
            celebrate = "received"; haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        prevBalance = node.balanceSats
    }
    LaunchedEffect(vm.sendResult) {
        if (vm.sendResult?.startsWith("✓") == true) {
            celebrateSats = vm.sentSats
            celebrate = "sent"; Haptics.sent()
        }
    }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
    Starfield()
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = PyType.mono(28f), color = PyTheme.primaryDim,
                modifier = Modifier.clickableNoRipple { Haptics.tap(); onBack() }.padding(horizontal = 10.dp))
            Text(w.label.ifEmpty { stringResource(R.string.wallet_default_label) }, style = PyType.mono(16f), color = PyTheme.yellow)
            Spacer(Modifier.weight(1f))
            Text(if (node.synced) stringResource(R.string.wallet_detail_synced) else stringResource(R.string.wallet_detail_status, node.status), style = PyType.mono(10f),
                color = if (node.synced) PyTheme.primary else PyTheme.primaryDim, modifier = Modifier.padding(end = 12.dp))
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Column(Modifier.fillMaxWidth()
                .moduleFrame(PyTheme.primary).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.wallet_detail_balance), style = PyType.mono(12f), color = PyTheme.primary, letterSpacing = 3.sp)
                Spacer(Modifier.height(6.dp))
                Text(fmtSats(node.balanceSats), style = PyType.mono(30f), color = PyTheme.yellow, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TabChip(stringResource(R.string.wallet_action_receive), tab == "receive", Modifier.weight(1f), down = true, accent = PyTheme.primary) { Haptics.select(); tab = "receive" }
            TabChip(stringResource(R.string.wallet_action_send), tab == "send", Modifier.weight(1f), down = false, accent = PyTheme.magenta) { Haptics.select(); tab = "send" }
        }
        Spacer(Modifier.height(16.dp))
        if (tab == "receive") ReceivePane(w, clip) else SendPane(vm, w, node, prefill)
        Spacer(Modifier.height(20.dp))
        // Reveal the WIF so any wallet (incl. PayNym-received ones) can be paper-backed up.
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var confirmReveal by remember { mutableStateOf(false) }
        var revealed by remember { mutableStateOf<String?>(null) }
        Text(stringResource(R.string.wallet_detail_reveal_key), style = PyType.mono(11f), color = PyTheme.magenta,
            textAlign = TextAlign.Center, letterSpacing = 1.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .border(1.dp, PyTheme.magenta.copy(alpha = 0.6f), RectangleShape)
                .clickableNoRipple { Haptics.warning(); confirmReveal = true }.padding(vertical = 10.dp))
        if (confirmReveal) RevealConfirmDialog(
            onCancel = { confirmReveal = false },
            onReveal = { confirmReveal = false; revealed = com.astrolexis.pyblock.data.wallet.WalletStore.wif(ctx, w.id) })
        revealed?.let { RevealKeyDialog(it, clip) { revealed = null } }
        Spacer(Modifier.height(24.dp))
    }
        celebrate?.let { kind ->
            val sent = kind == "sent"
            com.astrolexis.pyblock.ui.components.TxCelebrationOverlay(sent = sent, sats = celebrateSats) {
                celebrate = null
                if (sent) { vm.sendResult = null; onBack() }   // sent → return to wallet home
            }
        }
    }
}

@Composable
private fun RevealConfirmDialog(onCancel: () -> Unit, onReveal: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().background(PyTheme.bg).border(1.dp, PyTheme.magenta, RectangleShape).padding(20.dp)) {
            Text(stringResource(R.string.wallet_reveal_confirm_title), style = PyType.mono(15f), color = PyTheme.magenta, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.wallet_reveal_confirm_warning),
                style = PyType.mono(10f), color = PyTheme.primary)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.wallet_cancel), style = PyType.mono(12f), color = PyTheme.primary, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).border(1.dp, PyTheme.primary, RectangleShape).clickableNoRipple(onCancel).padding(vertical = 10.dp))
                Text(stringResource(R.string.wallet_reveal_confirm_button), style = PyType.mono(12f), color = PyTheme.bg, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).background(PyTheme.magenta).clickableNoRipple { Haptics.warning(); onReveal() }.padding(vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun RevealKeyDialog(wif: String, clip: androidx.compose.ui.platform.ClipboardManager, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        // The dialog has its own window — secure it (screenshots/recording) and
        // copy the WIF as a sensitive clip.
        com.astrolexis.pyblock.ui.components.SecureFlag()
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Column(Modifier.fillMaxWidth().background(PyTheme.bg).border(1.dp, PyTheme.primary, RectangleShape).padding(20.dp)) {
            Text(stringResource(R.string.wallet_revealkey_title), style = PyType.mono(13f), color = PyTheme.yellow, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            Text(wif, style = PyType.mono(13f), color = PyTheme.cyan,
                modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.cyan.copy(alpha = 0.5f), RectangleShape).padding(12.dp))
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.wallet_revealkey_copy), style = PyType.mono(12f), color = PyTheme.primary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.primary, RectangleShape)
                    .clickableNoRipple { com.astrolexis.pyblock.ui.components.copySensitiveToClipboard(ctx, wif, "private key") }.padding(vertical = 10.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.wallet_import_done_button), style = PyType.mono(12f), color = PyTheme.primaryDim, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickableNoRipple(onClose).padding(vertical = 8.dp))
        }
    }
}

// MARK: Unified SEND — dedicated screen; auto-sources the richest wallet (iOS SendSheet).

@Composable
private fun SendScreen(vm: WalletViewModel, wallets: List<VanityWallet>,
                       prefill: com.astrolexis.pyblock.data.wallet.PendingPayment.Request?, onBack: () -> Unit) {
    val w = remember(wallets) {
        wallets.filter { vm.node(it).balanceSats > 0 }.maxByOrNull { vm.node(it).balanceSats } ?: wallets.firstOrNull()
    }
    if (w != null) DisposableEffect(w.id) { vm.focus(w); onDispose { vm.unfocus() } }
    // Full-screen confirmation the moment the tx broadcasts, then auto-returns to
    // the wallet home. Same UX as iOS SendSheet.
    var celebrate by remember { mutableStateOf(false) }
    LaunchedEffect(vm.sendResult) {
        if (vm.sendResult?.startsWith("✓") == true) { celebrate = true; Haptics.sent() }
    }
    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
    Starfield()
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            com.astrolexis.pyblock.ui.components.WalletArrow(false, PyTheme.magenta, 15.dp, animated = false)
            Spacer(Modifier.width(8.dp))
            MarqueeTitle(text = stringResource(R.string.wallet_action_send))
            Spacer(Modifier.weight(1f))
            Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { Haptics.tap(); onBack() })
        }
        Spacer(Modifier.height(16.dp))
        if (w == null) {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                com.astrolexis.pyblock.ui.components.KeyGlyph(PyTheme.primaryDim, 40.dp)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.wallet_send_no_funds), style = PyType.mono(13f), color = PyTheme.primaryDim)
            }
        } else {
            SendPane(vm, w, vm.node(w), prefill)
        }
        Spacer(Modifier.height(24.dp))
    }
        if (celebrate) {
            com.astrolexis.pyblock.ui.components.TxCelebrationOverlay(sent = true, sats = vm.sentSats) {
                celebrate = false
                vm.sendResult = null   // clear so a stale "✓" can't re-fire elsewhere
                onBack()               // return to the wallet home
            }
        }
    }
}

@Composable
private fun ReceivePane(w: VanityWallet, clip: androidx.compose.ui.platform.ClipboardManager) {
    // Per-address receive: ONLY this wallet's specific address. The account PayNym
    // is shown once, in the main RECEIVE — never repeated per address.
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.wallet_receivepane_header), style = PyType.mono(10f), color = PyTheme.yellow, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            com.astrolexis.pyblock.ui.components.Identicon(w.address, 36)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.border(2.dp, PyTheme.primary, RectangleShape)) { QrCode(w.address, 180.dp) }
            Spacer(Modifier.height(8.dp))
            Text(w.address, style = PyType.mono(11f), color = PyTheme.cyan, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            CopyChip(stringResource(R.string.wallet_receivepane_copy_address)) { Haptics.tap(); clip.setText(AnnotatedString(w.address)) }
        }
    }
}

/** A bordered copy button with the custom CopyGlyph (no ⧉ emoji). */
@Composable
private fun CopyChip(label: String, onClick: () -> Unit) {
    Row(
        Modifier.border(1.dp, PyTheme.primary, RectangleShape).clickableNoRipple(onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.astrolexis.pyblock.ui.components.CopyGlyph(PyTheme.primary, 12.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, style = PyType.mono(12f), color = PyTheme.primary)
    }
}

@Composable
private fun SendPane(vm: WalletViewModel, w: VanityWallet, node: BdkNode,
                     prefill: com.astrolexis.pyblock.data.wallet.PendingPayment.Request? = null) {
    var to by remember { mutableStateOf(prefill?.address ?: "") }
    var amount by remember { mutableStateOf(prefill?.amountSats?.toString() ?: "") }
    var fee by remember { mutableStateOf("2") }
    var confirming by remember { mutableStateOf(false) }
    var showNyms by remember { mutableStateOf(false) }
    var sendMax by remember { mutableStateOf(false) }
    var peerCode by remember { mutableStateOf<String?>(null) }   // PM8T… when paying a PayNym (for the BIP-47 notification)
    var showBook by remember { mutableStateOf(false) }
    var showSaveBook by remember { mutableStateOf(false) }
    // Coin control: manual UTXO selection (frozen coins are excluded from the picker).
    var showCoins by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    // Ricochet: sweep/pay the selected coins forward through N self-spend hops (coordinator-
    // free). Requires a coin selection (like iOS). hops default 4.
    var ricochet by remember { mutableStateOf(false) }
    var hops by remember { mutableStateOf(4) }
    // Air-gap: export an UNSIGNED PSBT (watch-only build) as a QR to sign offline.
    var exportPsbt by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val airGapScope = androidx.compose.runtime.rememberCoroutineScope()
    var coins by remember { mutableStateOf<List<com.astrolexis.pyblock.data.wallet.UtxoInfo>>(emptyList()) }
    LaunchedEffect(node, node.balanceSats) {
        coins = node.unspentOutputs().filter { !it.frozen }
        selectedKeys = selectedKeys.filter { key -> coins.any { it.key == key } }   // drop spent/stale
    }
    val usingSelection = selectedKeys.isNotEmpty()
    val selectedTotal = coins.filter { it.key in selectedKeys }.sumOf { it.valueSats }
    val clip = LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isPaynym = com.astrolexis.pyblock.data.crypto.PaymentCode.looksLikePaymentCode(to)
    val payingName = if (isPaynym) com.astrolexis.pyblock.data.crypto.PaynymBook.contactForCode(ctx, to)?.displayName
        ?: com.astrolexis.pyblock.data.crypto.PaynymName.cosmic(to) else null
    val wallets by vm.wallets.collectAsState()
    val total = wallets.sumOf { vm.node(it).balanceSats }
    // A completed ricochet shows the full tx-chain view (no auto-dismiss celebration).
    val ricochetDone = vm.ricochetResult
    if (ricochetDone != null) {
        androidx.activity.compose.BackHandler { vm.ricochetResult = null }
        RicochetChainScreen(record = ricochetDone, onBack = { vm.ricochetResult = null })
        return
    }
    // Air-gap export shows the unsigned PSBT full-screen (animated UR QR).
    val exportShown = exportPsbt
    if (exportShown != null) {
        androidx.activity.compose.BackHandler { exportPsbt = null }
        com.astrolexis.pyblock.ui.screens.airgap.AirGapExportScreen(psbtB64 = exportShown, onBack = { exportPsbt = null })
        return
    }
    // Camera QR scanner (full-screen overlay) → set the recipient, stripping a
    // bitcoin: URI to the address like PASTE.
    var showScanner by remember { mutableStateOf(false) }
    if (showScanner) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showScanner = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
          // A Dialog is a fresh composition/window — re-apply the in-app language so
          // the scanner text follows the app, not the device locale.
          com.astrolexis.pyblock.ui.theme.LocalizedApp {
            com.astrolexis.pyblock.ui.components.QrScanner(
                title = stringResource(R.string.wallet_scan_title),
                onResult = { raw ->
                    val p = raw.trim()
                    to = com.astrolexis.pyblock.data.util.PaymentUri.parse(p)?.address ?: p
                    confirming = false
                    showScanner = false
                },
                onClose = { showScanner = false },
            )
          }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(stringResource(R.string.wallet_send_available, fmtSats(total)) + if (wallets.size > 1) stringResource(R.string.wallet_send_available_addresses, wallets.size) else "",
            style = PyType.mono(11f), color = PyTheme.primary)
        Text(stringResource(R.string.wallet_send_from_hint),
            style = PyType.mono(8f), color = PyTheme.primaryDim)
        Spacer(Modifier.height(12.dp))
        // TO — label + NYMS / SCAN / PASTE
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wallet_send_to_label), style = PyType.mono(10f), color = PyTheme.primaryDim, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.wallet_send_nyms), style = PyType.mono(11f), color = PyTheme.cyan, modifier = Modifier.clickableNoRipple { Haptics.tap(); showNyms = true })
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.wallet_send_book), style = PyType.mono(11f), color = PyTheme.cyan, modifier = Modifier.clickableNoRipple { Haptics.tap(); showBook = true })
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.wallet_scan), style = PyType.mono(11f), color = PyTheme.cyan, modifier = Modifier.clickableNoRipple { Haptics.tap(); showScanner = true })
            Spacer(Modifier.width(14.dp))
            Row(Modifier.clickableNoRipple { clip.getText()?.let { val p = it.text.trim(); to = com.astrolexis.pyblock.data.util.PaymentUri.parse(p)?.address ?: p; confirming = false } }, verticalAlignment = Alignment.CenterVertically) {
                com.astrolexis.pyblock.ui.components.CopyGlyph(PyTheme.cyan, 11.dp)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.wallet_paste), style = PyType.mono(11f), color = PyTheme.cyan)
            }
        }
        Spacer(Modifier.height(6.dp))
        Field(stringResource(R.string.wallet_send_to_placeholder), to, KeyboardType.Ascii) { to = it; confirming = false }
        if (isPaynym) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            com.astrolexis.pyblock.ui.components.LockGlyph(PyTheme.primary, 10.dp)
            Spacer(Modifier.width(4.dp))
            Text(payingName?.let { stringResource(R.string.wallet_send_paying_named, it) } ?: stringResource(R.string.wallet_send_paying_paynym),
                style = PyType.mono(8f), color = PyTheme.primary)
        }
        // Saved-name display, or a just-in-time "save to address book" shortcut (non-PayNym).
        if (!isPaynym && to.isNotBlank()) {
            val bookEntry = com.astrolexis.pyblock.data.crypto.AddressBook.entry(ctx, to)
            when {
                bookEntry != null && bookEntry.name.isNotBlank() -> Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("◈", style = PyType.mono(10f), color = PyTheme.cyan)
                    Spacer(Modifier.width(4.dp))
                    Text(bookEntry.name, style = PyType.mono(9f), color = PyTheme.cyan)
                }
                com.astrolexis.pyblock.data.crypto.AddressBook.isValid(to) -> Row(
                    Modifier.padding(top = 4.dp).clickableNoRipple { Haptics.tap(); showSaveBook = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("＋", style = PyType.mono(10f), color = PyTheme.primaryDim)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.wallet_send_save_to_book), style = PyType.mono(9f), color = PyTheme.primaryDim)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // AMOUNT — label + MAX toggle
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wallet_send_amount_label), style = PyType.mono(10f), color = PyTheme.primaryDim, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(if (sendMax) stringResource(R.string.wallet_send_max_on) else stringResource(R.string.wallet_send_max_off), style = PyType.mono(11f), color = PyTheme.yellow,
                modifier = Modifier.clickableNoRipple { Haptics.select(); sendMax = !sendMax; if (sendMax) amount = ""; confirming = false })
        }
        Spacer(Modifier.height(6.dp))
        Field(if (sendMax) stringResource(R.string.wallet_send_amount_max_placeholder) else "0", amount, KeyboardType.Number, enabled = !sendMax, dim = sendMax) { amount = it.filter { c -> c.isDigit() }; confirming = false }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.wallet_send_fee_label), style = PyType.mono(10f), color = PyTheme.primaryDim, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Field("2", fee, KeyboardType.Number) { fee = it.filter { c -> c.isDigit() }; confirming = false }
        // COINS — auto (all spendable) vs a manual UTXO selection.
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (usingSelection)
                    stringResource(R.string.wallet_send_coins_n, selectedKeys.size)
                 else stringResource(R.string.wallet_send_coins_auto),
                style = PyType.mono(10f), color = if (usingSelection) PyTheme.cyan else PyTheme.primaryDim)
            Spacer(Modifier.weight(1f))
            Text(stringResource(if (usingSelection) R.string.wallet_send_coins_edit else R.string.wallet_send_coins_select),
                style = PyType.mono(11f), color = PyTheme.cyan,
                modifier = Modifier.clickableNoRipple { Haptics.tap(); showCoins = true })
        }
        val amt = amount.toLongOrNull() ?: 0
        val feeVb = fee.toLongOrNull() ?: 0
        // RICOCHET — sweep/pay the selected coins forward through N self-spend hops. Requires
        // a coin selection (the source set must be explicit). The cost of N+1 chained txs can
        // be large, so it's shown BIG. Mirrors iOS SendSheet.ricochetRow.
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().border(1.dp, (if (ricochet) PyTheme.magenta else PyTheme.primary).copy(alpha = 0.4f), RectangleShape).padding(10.dp)) {
            Row(Modifier.fillMaxWidth().clickableNoRipple {
                    if (usingSelection) { Haptics.select(); ricochet = !ricochet; confirming = false }
                }, verticalAlignment = Alignment.CenterVertically) {
                Text(if (ricochet) "◉" else "○", style = PyType.mono(13f), color = if (ricochet) PyTheme.magenta else PyTheme.primaryDim)
                Spacer(Modifier.width(8.dp))
                Text("RICOCHET", style = PyType.mono(11f), color = if (ricochet) PyTheme.magenta else PyTheme.primaryDim, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                if (ricochet) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("HOPS", style = PyType.mono(9f), color = PyTheme.primaryDim)
                    Spacer(Modifier.width(8.dp))
                    listOf(1, 2, 3, 4).forEach { h ->
                        Text("$h", style = PyType.mono(13f), color = if (h == hops) PyTheme.cyan else PyTheme.primaryDim,
                            modifier = Modifier.clickableNoRipple { Haptics.tap(); hops = h; confirming = false }.padding(horizontal = 6.dp))
                    }
                }
            }
            if (ricochet) {
                val ricFee = feeVb * (selectedKeys.size.toLong() * 148 + 80 + hops.toLong() * 110)
                val eatsAmount = !sendMax && amt > 0 && ricFee >= amt
                val ricColor = when {
                    eatsAmount -> PyTheme.danger
                    !sendMax && amt > 0 && ricFee * 4 >= amt -> PyTheme.yellow
                    else -> PyTheme.magenta
                }
                Spacer(Modifier.height(8.dp))
                Text("FEE ≈ ${"%,d".format(ricFee)} sats", style = PyType.mono(22f), color = ricColor)
                Text("${hops + 1} transactions at $feeVb sat/vB" +
                        (if (!sendMax && amt > 0) " · ≈${minOf(999L, ricFee * 100 / maxOf(1L, amt))}% of the amount" else "") +
                        " — more hops/congestion = more fees.",
                    style = PyType.mono(8f), color = ricColor)
                if (eatsAmount) Text("⚠ Fees would eat the whole payment. Use fewer hops or a lower fee.",
                    style = PyType.mono(9f), color = PyTheme.danger)
            } else if (!usingSelection) {
                Text("Select the coins to ricochet (COINS above) first.", style = PyType.mono(9f), color = PyTheme.primaryDim)
            }
        }
        // When a manual selection is active, the cap is the selected total; otherwise the
        // wallet balance. ~226 vB = legacy P2PKH 1-in-2-out, close enough to warn BEFORE the
        // builder fails with a cryptic InsufficientFunds. Overflow-safe (fail closed).
        val sourceCap = if (usingSelection) selectedTotal else node.balanceSats
        val estFee = runCatching { Math.multiplyExact(feeVb, 226L) }.getOrElse { Long.MAX_VALUE }
        val exceeds = !sendMax && amt > 0 &&
            runCatching { Math.addExact(amt, estFee) }.getOrElse { Long.MAX_VALUE } > sourceCap
        if (exceeds) Text(
            if (amt <= sourceCap)
                stringResource(R.string.wallet_send_exceeds_fee, fmtSats(estFee), fmtSats(sourceCap))
            else
                stringResource(R.string.wallet_send_exceeds_largest, fmtSats(sourceCap)),
            style = PyType.mono(9f), color = PyTheme.yellow)
        val ready = to.isNotBlank() && (sendMax || amt > 0) && feeVb > 0 && !vm.sending && !exceeds
        // Pre-send privacy meter (Boltzmann-lite) — reads the pending send's shape and
        // scores its on-chain footprint so it can be improved before broadcast. Never
        // touches funds. Mirrors iOS PrivacyBar in SendSheet.
        val validInputs = to.isNotBlank() && (sendMax || amt > 0)
        if (validInputs) {
            Spacer(Modifier.height(12.dp))
            val addressCount = coins.filter { it.key in selectedKeys }.map { it.address }.distinct().size
            com.astrolexis.pyblock.ui.components.PrivacyBar(
                com.astrolexis.pyblock.data.wallet.PrivacyScore.evaluate(
                    amountSats = amt, sendMax = sendMax, spendable = sourceCap,
                    recipientIsPaynym = isPaynym, usingSelection = usingSelection,
                    addressCount = addressCount, inputCount = selectedKeys.size,
                )
            )
        }
        Spacer(Modifier.height(16.dp))
        if (!confirming) {
            Row(Modifier.fillMaxWidth().background(if (ready) PyTheme.magenta else Color.Transparent)
                .border(2.dp, if (ready) PyTheme.magenta else PyTheme.primaryDim, RectangleShape)
                .clickableNoRipple {
                    if (ready) {
                        Haptics.thock()
                        // Resolve a PayNym code to a fresh stealth address before review.
                        // Capture the PM code FIRST (it's overwritten) for the BIP-47 notification.
                        if (isPaynym) {
                            peerCode = to
                            com.astrolexis.pyblock.data.crypto.PaymentCode.nextWalletSendAddress(ctx, to)?.let { to = it }
                        }
                        confirming = true
                    }
                }.padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (!vm.sending) {
                    com.astrolexis.pyblock.ui.components.WalletArrow(false, if (ready) PyTheme.bg else PyTheme.primaryDim, 15.dp, animated = ready)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (vm.sending) stringResource(R.string.wallet_send_broadcasting) else stringResource(R.string.wallet_send_review), style = PyType.mono(15f), letterSpacing = 1.sp,
                    color = if (ready) PyTheme.bg else PyTheme.primaryDim)
            }
        } else {
            // Second step: review the irreversible tx before broadcasting.
            Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.yellow).padding(14.dp)) {
                Text(stringResource(R.string.wallet_send_confirm_title), style = PyType.mono(12f), color = PyTheme.yellow, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                Text(if (sendMax) stringResource(R.string.wallet_send_confirm_all) else stringResource(R.string.wallet_send_confirm_amount, fmtSats(amt)), style = PyType.mono(12f), color = PyTheme.cyan)
                Text(stringResource(R.string.wallet_send_confirm_to, to.take(24)), style = PyType.mono(11f), color = PyTheme.primaryDim)
                Text(stringResource(R.string.wallet_send_confirm_fee, feeVb), style = PyType.mono(11f),
                    color = if (feeVb > 100) PyTheme.danger else PyTheme.primaryDim)
                if (feeVb > 100) Text(stringResource(R.string.wallet_send_confirm_high_fee), style = PyType.mono(10f), color = PyTheme.danger)
                if (ricochet && usingSelection) {
                    val ricTotal = feeVb * (selectedKeys.size.toLong() * 148 + 80 + hops.toLong() * 110)
                    Text("↝ RICOCHET · ${hops + 1} chained txs · est. total fee ≈ ${"%,d".format(ricTotal)} sats",
                        style = PyType.mono(10f), color = PyTheme.magenta)
                }
                // Which wallet funds this send (chat hand-off clarity). Mirrors iOS sendConfirmFooter.
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.send_confirm_from, w.label, "${w.address.take(10)}…${w.address.takeLast(6)}"),
                    style = PyType.mono(11f), color = PyTheme.primary)
                peerCode?.let { pc ->
                    if (!com.astrolexis.pyblock.data.crypto.PaymentCode.hasNotified(ctx, pc)) {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.wallet_send_notify_note), style = PyType.mono(10f), color = PyTheme.primaryDim)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.wallet_cancel), style = PyType.mono(13f), color = PyTheme.primary, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).border(1.dp, PyTheme.primary, RectangleShape)
                            .clickableNoRipple { confirming = false }.padding(vertical = 11.dp))
                    Text(stringResource(R.string.wallet_send_confirm_button), style = PyType.mono(13f), color = PyTheme.bg, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1.4f).background(PyTheme.magenta)
                            .clickableNoRipple {
                                Haptics.coin(); confirming = false
                                if (ricochet && usingSelection) vm.ricochet(w, to, amt, feeVb, sendMax, hops, selectedKeys)
                                else vm.send(w, to, amt, feeVb, sendMax, prefill?.peer, paynymCode = peerCode, selectedKeys = if (usingSelection) selectedKeys else null)
                            }.padding(vertical = 11.dp))
                }
            }
        }
        // Air-gap: export the pending payment UNSIGNED (no key, nothing broadcast).
        // Disabled during coin-control (the watch-only builder takes no selection).
        if (validInputs) {
            Spacer(Modifier.height(10.dp))
            val canExport = !usingSelection && !vm.sending
            Text(if (usingSelection) stringResource(R.string.wallet_send_export_needs_no_selection) else stringResource(R.string.wallet_send_export_unsigned),
                style = PyType.mono(12f), color = if (canExport) PyTheme.cyan else PyTheme.primaryDim, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, (if (canExport) PyTheme.cyan else PyTheme.primaryDim).copy(alpha = 0.6f), RectangleShape)
                    .clickableNoRipple {
                        if (!canExport) return@clickableNoRipple
                        Haptics.tap(); exportError = null
                        airGapScope.launch {
                            try { exportPsbt = node.buildUnsignedPSBT(to.trim(), amt, sendMax, feeVb) }
                            catch (e: Exception) { Haptics.error(); exportError = e.message ?: "Couldn't build the PSBT." }
                        }
                    }.padding(vertical = 11.dp))
            exportError?.let { Spacer(Modifier.height(6.dp)); Text(it, style = PyType.mono(10f), color = PyTheme.danger) }
        }
        vm.sendResult?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = PyType.mono(11f), color = if (it.startsWith("✓")) PyTheme.primary else PyTheme.danger,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.wallet_send_privacy_hint),
            style = PyType.mono(9f), color = PyTheme.primaryDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    if (showNyms) com.astrolexis.pyblock.ui.screens.chat.PaynymContactsDialog(
        onPick = { c -> to = c.code; confirming = false }, onDismiss = { showNyms = false })
    if (showBook) AddressBookDialog(
        onPick = { e -> to = e.address; confirming = false }, onDismiss = { showBook = false })
    if (showSaveBook) SaveToBookDialog(address = to,
        onSave = { nm -> com.astrolexis.pyblock.data.crypto.AddressBook.upsert(ctx, nm, to) },
        onDismiss = { showSaveBook = false })
    if (showCoins) CoinSelectDialog(
        coins = coins, selectedKeys = selectedKeys,
        onToggle = { k -> selectedKeys = if (k in selectedKeys) selectedKeys - k else selectedKeys + k },
        onClear = { selectedKeys = emptyList() },
        onDismiss = { showCoins = false })
}

@Composable
private fun Field(placeholder: String, value: String, kb: KeyboardType, enabled: Boolean = true, dim: Boolean = false, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = if (dim) 0.2f else 0.5f), RectangleShape).padding(12.dp)) {
        if (value.isEmpty()) Text(placeholder, style = PyType.mono(13f), color = PyTheme.primaryDim)
        BasicTextField(value, onChange, enabled = enabled, textStyle = PyType.mono(13f).copy(color = PyTheme.cyan),
            cursorBrush = SolidColor(PyTheme.cyan), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = kb), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, modifier: Modifier, down: Boolean? = null,
                    accent: Color = PyTheme.primary, onClick: () -> Unit) {
    val c = if (selected) PyTheme.bg else accent
    Row(
        modifier.background(if (selected) accent else Color.Transparent)
            .border(1.dp, accent, RectangleShape).clickableNoRipple(onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (down != null) {
            com.astrolexis.pyblock.ui.components.WalletArrow(down, c, 14.dp, animated = false)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = PyType.mono(14f), color = c)
    }
}

// Match iOS: all amounts render in sats with comma grouping (e.g. "8,193 sats").
private fun fmtSats(v: Long): String = "%,d sats".format(v)
