package com.astrolexis.pyblock.ui.screens.buy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.astrolexis.pyblock.data.model.Stratum
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.astrolexis.pyblock.R
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.astrolexis.pyblock.data.model.NHPackage
import com.astrolexis.pyblock.data.model.NHQuote
import com.astrolexis.pyblock.data.model.OrderCreateResponse
import com.astrolexis.pyblock.data.model.poolNameForPort
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.data.net.BuyRepo
import com.astrolexis.pyblock.data.store.AddressStore
import com.astrolexis.pyblock.data.util.BrandGuard
import com.astrolexis.pyblock.data.util.LnUri
import com.astrolexis.pyblock.ui.components.QrCode
import com.astrolexis.pyblock.ui.components.RetroTextField
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

data class BuyUiState(
    val address: String = "",
    val port: Int = 4444,
    val selectedPackageId: String? = "p1",
    val customMode: Boolean = false,
    val customPh: Double = 1.0,
    val customHours: Double = 48.0,
    val pkgPrices: Map<String, Int?> = emptyMap(),
    val quote: NHQuote? = null,
    val quoting: Boolean = false,
    val quoteError: String? = null,
    val creating: Boolean = false,
    val order: OrderCreateResponse? = null,
    val orderError: String? = null,
    val status: String? = null,
)

class BuyViewModel : ViewModel() {
    private val _state = MutableStateFlow(
        BuyUiState(address = AddressStore.addresses.value.firstOrNull()?.address ?: ""),
    )
    val state = _state.asStateFlow()
    private var pollJob: Job? = null

    init { loadPackages() }

    private fun curPh(s: BuyUiState) = if (s.customMode) s.customPh else NHPackage.all.first { it.id == s.selectedPackageId }.ph
    private fun curHours(s: BuyUiState) = if (s.customMode) s.customHours else NHPackage.all.first { it.id == s.selectedPackageId }.hours

    private fun loadPackages() {
        viewModelScope.launch {
            val prices = coroutineScope {
                NHPackage.all.map { pkg ->
                    async { pkg.id to runCatching { ApiClient.api.packageQuote(pkg.ph, pkg.hours) }.getOrNull()?.quote?.totalSats }
                }.awaitAll()
            }.toMap()
            _state.update { it.copy(pkgPrices = prices) }
            loadQuote()
        }
    }

    fun selectPackage(pkg: NHPackage) {
        _state.update { it.copy(customMode = false, selectedPackageId = pkg.id) }
        loadQuote()
    }

    fun enableCustom() {
        _state.update { it.copy(customMode = true) }
        loadQuote()
    }

    fun adjustCustom(dPh: Double = 0.0, dHours: Double = 0.0) {
        _state.update {
            it.copy(customPh = max(0.1, it.customPh + dPh), customHours = max(1.0, it.customHours + dHours))
        }
        loadQuote()
    }

    fun setPool(port: Int) {
        _state.update { it.copy(port = port) }
    }

    fun setAddress(s: String) = _state.update { it.copy(address = s) }

    private fun loadQuote() {
        val s = _state.value
        val ph = curPh(s); val hours = curHours(s)
        _state.update { it.copy(quoting = true, quoteError = null, quote = null) }
        viewModelScope.launch {
            val r = runCatching { ApiClient.api.packageQuote(ph, hours) }.getOrNull()
            if (r?.ok == true && r.quote != null) {
                _state.update { it.copy(quoting = false, quote = r.quote) }
            } else {
                _state.update { it.copy(quoting = false, quoteError = clean(r?.errors?.firstOrNull())) }
            }
        }
    }

    fun buy() {
        val s = _state.value
        val addr = s.address.trim()
        if (addr.isEmpty()) {
            _state.update { it.copy(orderError = "Enter your destination BTC address.") }
            return
        }
        // Validate format+checksum (BDK) so a mistyped address can't send hashrate
        // to nowhere — the invoice is real money, the destination must be valid.
        if (runCatching { org.bitcoindevkit.Address(addr, org.bitcoindevkit.Network.BITCOIN) }.isFailure) {
            _state.update { it.copy(orderError = "That doesn't look like a valid Bitcoin address.") }
            return
        }
        _state.update { it.copy(creating = true, orderError = null) }
        viewModelScope.launch {
            val r = BuyRepo.createOrder(addr, s.port, curPh(s), curHours(s))
            if (r.ok && !r.invoice.isNullOrEmpty()) {
                _state.update { it.copy(creating = false, order = r, status = r.status) }
                r.orderId?.let { startPolling(it) }
            } else {
                _state.update { it.copy(creating = false, orderError = clean(r.errors?.firstOrNull())) }
            }
        }
    }

    private fun startPolling(orderId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val st = runCatching { ApiClient.api.orderStatus(orderId).order?.status }.getOrNull()
                if (st != null) _state.update { it.copy(status = st) }
                if (st in listOf("active", "settled", "expired", "failed")) break
                delay(3000)
            }
        }
    }

    fun reset() {
        pollJob?.cancel()
        _state.update { it.copy(order = null, status = null, orderError = null) }
    }

    /** Never surface a provider name / order number. */
    private fun clean(s: String?): String =
        BrandGuard.clean(s, "That order is too large right now — lower the package or hours.")
}

@Composable
fun BuyScreen(onPayOnchain: (String, Int?) -> Unit = { _, _ -> }, onOpenChat: () -> Unit = {}) {
    val vm: BuyViewModel = viewModel()
    val state by vm.state.collectAsState()
    // Shared activity-scoped chat client — for the post-purchase brag card.
    val chat: com.astrolexis.pyblock.data.nostr.NostrClient = viewModel()

    // LOTTO Official Rules (App Review 5.3.2 — reachable from the LOTTO/buy surface). Mirrors iOS.
    var showRules by remember { mutableStateOf(false) }
    if (showRules) {
        androidx.activity.compose.BackHandler { showRules = false }
        com.astrolexis.pyblock.ui.screens.academy.OfficialRulesScreen(onBack = { showRules = false })
        return
    }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        MarqueeTitle(text = stringResource(R.string.buy_title))
        Spacer(Modifier.height(10.dp))

        if (state.order != null) {
            InvoiceCard(state, onPayOnchain, onBrag = {
                val phs = state.quote?.hashratePhs ?: 0.0
                if (phs > 0) {
                    chat.post(String.format(java.util.Locale.US, "pyblock:stack?phs=%.2f", phs))
                    onOpenChat()
                }
            }) { vm.reset() }
        } else {
            Text(
                stringResource(R.string.buy_intro),
                style = PyType.mono(13f), color = PyTheme.cyan,
            )
            Spacer(Modifier.height(14.dp))
            PackageGrid(state, vm)
            if (state.customMode) {
                Spacer(Modifier.height(10.dp))
                CustomFields(state, vm)
            }
            Spacer(Modifier.height(14.dp))
            PoolPicker(state.port) { vm.setPool(it) }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.buy_destination_address_label), style = PyType.mono(11f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            RetroTextField(state.address, vm::setAddress, "bc1q...")
            Spacer(Modifier.height(14.dp))
            QuoteSummary(state)
            Spacer(Modifier.height(14.dp))
            Text(
                if (state.creating) "…" else stringResource(R.string.buy_generate_invoice, poolNameForPort(state.port)),
                style = PyType.mono(15f), color = PyTheme.magenta, textAlign = TextAlign.Center, letterSpacing = 2.sp,
                modifier = Modifier.fillMaxWidth().moduleFrame(PyTheme.magenta)
                    .clickableNoRipple { Haptics.thock(); vm.buy() }.padding(vertical = 12.dp),
            )
            state.orderError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = PyType.mono(11f), color = PyTheme.danger)
            }
        }
        Spacer(Modifier.height(16.dp))
        // LOTTO Official Rules — App Review 5.3.2 (reachable from the buy/LOTTO surface).
        Text(stringResource(R.string.settings_lotto_official_rules), style = PyType.mono(12f), color = PyTheme.cyan,
            textAlign = TextAlign.Center, letterSpacing = 1.sp,
            modifier = Modifier.fillMaxWidth().clickableNoRipple { Haptics.tap(); showRules = true }.padding(vertical = 6.dp))
        Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PackageGrid(state: BuyUiState, vm: BuyViewModel) {
    Text(stringResource(R.string.buy_choose_package), style = PyType.mono(11f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
    Spacer(Modifier.height(8.dp))
    val cards: List<@Composable (Modifier) -> Unit> = buildList {
        NHPackage.all.forEach { pkg ->
            add { m -> PackageCard(m, pkg, active = !state.customMode && state.selectedPackageId == pkg.id, price = state.pkgPrices[pkg.id]) { Haptics.select(); vm.selectPackage(pkg) } }
        }
        add { m -> CustomCard(m, active = state.customMode, custom = "${fmtPh(state.customPh)} PH/s · ${state.customHours.toInt()}h") { Haptics.select(); vm.enableCustom() } }
    }
    cards.chunked(2).forEach { rowCards ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowCards.forEach { card -> card(Modifier.weight(1f)) }
            if (rowCards.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PackageCard(modifier: Modifier, pkg: NHPackage, active: Boolean, price: Int?, onClick: () -> Unit) {
    Column(
        modifier.background(if (active) PyTheme.yellow.copy(alpha = 0.1f) else Color.Transparent)
            .border(if (active) 2.dp else 1.dp, if (active) PyTheme.yellow else PyTheme.primary.copy(alpha = 0.4f), RectangleShape)
            .clickableNoRipple(onClick).padding(10.dp),
    ) {
        Text(pkg.name, style = PyType.mono(15f), color = if (active) PyTheme.yellow else PyTheme.primary)
        Text("${fmtPh(pkg.ph)} PH/s · ${pkg.hours.toInt()}h", style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f))
        Text(price?.let { "${num(it)} sats" } ?: "…", style = PyType.mono(12f), color = if (active) PyTheme.yellow else PyTheme.primaryDim)
    }
}

@Composable
private fun CustomCard(modifier: Modifier, active: Boolean, custom: String, onClick: () -> Unit) {
    Column(
        modifier.background(if (active) PyTheme.magenta.copy(alpha = 0.1f) else Color.Transparent)
            .border(if (active) 2.dp else 1.dp, if (active) PyTheme.magenta else PyTheme.primary.copy(alpha = 0.4f), RectangleShape)
            .clickableNoRipple(onClick).padding(10.dp),
    ) {
        Text(stringResource(R.string.buy_custom), style = PyType.mono(15f), color = if (active) PyTheme.magenta else PyTheme.primary)
        Text(stringResource(R.string.buy_custom_subtitle), style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f))
        Text(if (active) custom else stringResource(R.string.buy_custom_set_your_own), style = PyType.mono(12f), color = if (active) PyTheme.magenta else PyTheme.primaryDim)
    }
}

@Composable
private fun CustomFields(state: BuyUiState, vm: BuyViewModel) {
    Column(Modifier.fillMaxWidth().border(1.dp, PyTheme.magenta.copy(alpha = 0.4f), RectangleShape).padding(12.dp)) {
        Stepper(stringResource(R.string.buy_power), "${fmtPh(state.customPh)} PH/s", { vm.adjustCustom(dPh = -1.0) }, { vm.adjustCustom(dPh = 1.0) })
        Spacer(Modifier.height(8.dp))
        Stepper(stringResource(R.string.buy_hours), "${state.customHours.toInt()}", { vm.adjustCustom(dHours = -6.0) }, { vm.adjustCustom(dHours = 6.0) })
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.buy_min_order), style = PyType.mono(10f), color = PyTheme.primaryDim)
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PyType.mono(11f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        StepBtn("−", onMinus)
        Text(value, style = PyType.mono(16f), color = PyTheme.yellow,
            modifier = Modifier.padding(horizontal = 12.dp), textAlign = TextAlign.Center)
        StepBtn("+", onPlus)
    }
}

@Composable
private fun StepBtn(s: String, onClick: () -> Unit) {
    Text(s, style = PyType.mono(20f), color = PyTheme.cyan, textAlign = TextAlign.Center,
        modifier = Modifier.size(36.dp).border(1.dp, PyTheme.cyan.copy(alpha = 0.6f), RectangleShape).clickableNoRipple { Haptics.tap(); onClick() }.padding(top = 4.dp))
}

@Composable
private fun PoolPicker(port: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PoolBtn(Modifier.weight(1f).fillMaxHeight(), "LOTTO", stringResource(R.string.buy_pool_lotto_subtitle), Stratum.Pool.LOTTO.activePort, PyTheme.primary, port, onSelect)
            PoolBtn(Modifier.weight(1f).fillMaxHeight(), "DATUM", stringResource(R.string.buy_pool_datum_subtitle), Stratum.Pool.DATUM.activePort, PyTheme.magenta, port, onSelect)
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PoolBtn(Modifier.weight(1f).fillMaxHeight(), "SV2", stringResource(R.string.buy_pool_sv2_subtitle), Stratum.Pool.SV2.activePort, PyTheme.cyan, port, onSelect)
            PoolBtn(Modifier.weight(1f).fillMaxHeight(), "CHIRP", stringResource(R.string.buy_pool_chirp_subtitle), Stratum.Pool.CHIRP.activePort, PyTheme.yellow, port, onSelect)
        }
        PoolBtn(Modifier.fillMaxWidth(), "🎠 CAROUSEL", stringResource(R.string.buy_pool_carousel_subtitle), Stratum.Pool.CAROUSEL.activePort, Color.White, port, onSelect)
    }
}

@Composable
private fun PoolBtn(modifier: Modifier, name: String, subtitle: String, value: Int, color: Color, current: Int, onSelect: (Int) -> Unit) {
    val active = current == value
    Column(
        modifier.background(if (active) color.copy(alpha = 0.12f) else Color.Transparent)
            .border(if (active) 2.dp else 1.dp, color, RectangleShape).clickableNoRipple { Haptics.select(); onSelect(value) }.padding(10.dp),
    ) {
        Text(name, style = PyType.mono(16f), color = if (active) color else color.copy(alpha = 0.55f))
        Text(subtitle, style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f))
    }
}

@Composable
private fun QuoteSummary(state: BuyUiState) {
    when {
        state.quoting -> Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(color = PyTheme.primary, modifier = Modifier.size(22.dp))
        }
        state.quote != null -> Column(
            Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(12.dp),
        ) {
            QRow(stringResource(R.string.buy_quote_power), "${fmtPh(state.quote.hashratePhs)} PH/s")
            QRow(stringResource(R.string.buy_quote_duration), "${state.quote.durationH.toInt()} h")
            HorizontalDivider(color = PyTheme.primary.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
            QRow(stringResource(R.string.buy_quote_total), "${num(state.quote.totalSats)} sats")
            val q = state.quote
            if ((q.discountSats ?: 0) > 0) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (q.tier == "whale") "🐋 WHALE −2%" else "⭐ PRO −1%", style = PyType.mono(10f), color = PyTheme.primary)
                    Text("−${num(q.discountSats ?: 0)} sats", style = PyType.mono(11f), color = PyTheme.yellow)
                }
            } else if (!com.astrolexis.pyblock.data.store.EntitlementsStore.isPro && (q.whaleSaveSats ?: 0) > 0) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.buy_pro_whale_savings, num(q.proSaveSats ?: 0), num(q.whaleSaveSats ?: 0)),
                    style = PyType.mono(10f), color = PyTheme.yellow)
                Text(stringResource(R.string.buy_subscribe_hint),
                    style = PyType.mono(8f), color = PyTheme.primaryDim)
            }
        }
        state.quoteError != null -> Text(state.quoteError, style = PyType.mono(11f), color = PyTheme.danger)
    }
}

@Composable
private fun QRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, style = PyType.mono(13f), color = PyTheme.cyan)
        Spacer(Modifier.weight(1f))
        Text(v, style = PyType.mono(13f), color = PyTheme.yellow)
    }
}

@Composable
private fun InvoiceCard(state: BuyUiState, onPayOnchain: (String, Int?) -> Unit, onBrag: () -> Unit = {}, onReset: () -> Unit) {
    val order = state.order ?: return
    val invoice = order.invoice.orEmpty()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().moduleFrame(PyTheme.magenta).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.buy_lightning_invoice), style = PyType.mono(13f), color = PyTheme.magenta, letterSpacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        Text("${order.totalSats ?: 0} sats", style = PyType.mono(22f), color = PyTheme.yellow)
        Spacer(Modifier.height(12.dp))
        QrCode(invoice, size = 220.dp)
        Spacer(Modifier.height(12.dp))
        StatusRow(state.status)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InvBtn(stringResource(R.string.buy_copy), PyTheme.cyan, Modifier.weight(1f)) { clipboard.setText(AnnotatedString(invoice)) }
            InvBtn(stringResource(R.string.buy_open_wallet), PyTheme.magenta, Modifier.weight(1f)) { LnUri.open(ctx, invoice) }
        }
        // F1: pay on-chain from the PyBLØCK wallet — only once the server offers it.
        order.onchainAddress?.takeIf { it.isNotEmpty() }?.let { addr ->
            val remaining = order.onchainExpiresAt?.let { it - (System.currentTimeMillis() / 1000).toInt() }
            val expired = (remaining ?: 1) <= 0
            val c = if (expired) PyTheme.primaryDim else PyTheme.magenta
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.buy_or), style = PyType.mono(10f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.buy_pay_onchain), style = PyType.mono(12f), color = c,
                textAlign = TextAlign.Center, letterSpacing = 1.sp,
                modifier = Modifier.fillMaxWidth().border(1.dp, c, RectangleShape)
                    .clickableNoRipple { if (!expired) { Haptics.thock(); onPayOnchain(addr, order.onchainAmountSats ?: order.totalSats) } }.padding(vertical = 11.dp))
            remaining?.let { r ->
                Text(if (r > 0) stringResource(R.string.buy_pay_within, maxOf(1, r / 60)) else stringResource(R.string.buy_price_window_expired),
                    style = PyType.mono(8f), color = if (r > 0) PyTheme.yellow else PyTheme.danger)
            }
        }
        // Social proof loop: paid order → brag card in the community chat.
        if (state.status !in listOf(null, "pending_payment", "expired", "failed")) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.buy_brag_in_chat), style = PyType.mono(13f), color = PyTheme.yellow,
                textAlign = TextAlign.Center, letterSpacing = 1.sp,
                modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.yellow, RectangleShape)
                    .clickableNoRipple { Haptics.tap(); onBrag() }.padding(vertical = 10.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.buy_new_order), style = PyType.mono(12f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { Haptics.tap(); onReset() })
    }
}

@Composable
private fun StatusRow(status: String?) {
    val (label, color) = when (status) {
        "pending_payment" -> stringResource(R.string.buy_status_pending_payment) to PyTheme.magenta
        "paid" -> stringResource(R.string.buy_status_paid) to PyTheme.cyan
        "submitted" -> stringResource(R.string.buy_status_submitted) to PyTheme.cyan
        "active" -> stringResource(R.string.buy_status_active) to PyTheme.primary
        "settled" -> stringResource(R.string.buy_status_settled) to PyTheme.primary
        "expired" -> stringResource(R.string.buy_status_expired) to PyTheme.danger
        "failed" -> stringResource(R.string.buy_status_failed) to PyTheme.danger
        else -> stringResource(R.string.buy_status_waiting) to PyTheme.primaryDim
    }
    Text(label, style = PyType.mono(13f), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun InvBtn(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Text(text, style = PyType.mono(14f), color = color, textAlign = TextAlign.Center, letterSpacing = 1.sp,
        modifier = modifier.fillMaxWidth().border(1.dp, color, RectangleShape).clickableNoRipple { Haptics.tap(); onClick() }.padding(vertical = 10.dp))
}

private fun num(v: Int): String = String.format(Locale.US, "%,d", v)
private fun fmtPh(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
