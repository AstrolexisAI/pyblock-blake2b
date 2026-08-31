package com.astrolexis.pyblock.ui.screens.pro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.astrolexis.pyblock.data.model.Entitlements
import com.astrolexis.pyblock.data.model.Product
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.data.net.ProRepo
import com.astrolexis.pyblock.data.util.BrandGuard
import com.astrolexis.pyblock.data.util.LnUri
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.store.AuthStore
import com.astrolexis.pyblock.data.store.DeviceStore
import com.astrolexis.pyblock.data.store.EntitlementsStore
import com.astrolexis.pyblock.data.store.ThemeStore
import com.astrolexis.pyblock.ui.components.QrCode
import com.astrolexis.pyblock.ui.components.RetroTextField
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.moduleFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PurchaseFlow(
    val product: Product,
    val invoice: String? = null,
    val amount: Int? = null,
    val status: String? = null,
    val error: String? = null,
)

data class ProUiState(
    val loggedIn: Boolean = false,
    val ent: Entitlements? = null,
    val products: List<Product> = emptyList(),
    val loginLnurl: String? = null,
    val loginError: String? = null,
    val purchase: PurchaseFlow? = null,
    val backup: String? = null,       // account backup string when the sheet is open
    val restoreOpen: Boolean = false,
)

class ProViewModel : ViewModel() {
    private val _state = MutableStateFlow(ProUiState(loggedIn = AuthStore.isLoggedIn))
    val state = _state.asStateFlow()
    private var loginPoll: Job? = null
    private var purchasePoll: Job? = null

    init {
        loadProducts()
        refreshEntitlements()   // device account works even without a linked wallet
    }

    val tier: String get() = _state.value.ent?.tier ?: "free"

    private fun loadProducts() {
        viewModelScope.launch {
            val r = runCatching { ApiClient.api.products() }.getOrNull()
            if (r?.ok == true) _state.update { it.copy(products = r.products) }
        }
    }

    fun refreshEntitlements() {
        viewModelScope.launch {
            val e = ProRepo.entitlements() ?: return@launch
            _state.update { it.copy(ent = e) }
            EntitlementsStore.set(e)
            ThemeStore.enforce(e.tier == "whale")
        }
    }

    // ---- Buy: no login required. The anonymous device account pays; any wallet works. ----
    fun buy(p: Product) {
        _state.update { it.copy(purchase = PurchaseFlow(product = p)) }
        viewModelScope.launch {
            val r = ProRepo.purchase(p.id)
            if (r?.ok == true && !r.invoice.isNullOrEmpty()) {
                _state.update { it.copy(purchase = PurchaseFlow(p, r.invoice, r.amountSats, "pending")) }
                purchasePoll?.cancel()
                val id = r.purchaseId ?: return@launch
                purchasePoll = viewModelScope.launch {
                    while (isActive) {
                        val st = runCatching { ApiClient.api.purchaseStatus(id).status }.getOrNull()
                        if (st != null) _state.update { s -> s.copy(purchase = s.purchase?.copy(status = st)) }
                        if (st == "paid") { refreshEntitlements(); break }
                        if (st == "expired") break
                        delay(2500)
                    }
                }
            } else {
                _state.update { it.copy(purchase = PurchaseFlow(p, error = BrandGuard.clean(r?.errors?.firstOrNull(), "Purchase failed — try again."))) }
            }
        }
    }

    fun closePurchase() {
        purchasePoll?.cancel()
        _state.update { it.copy(purchase = null) }
    }

    // ---- Optional: link a wallet (LNURL-auth) for cross-device restore ----
    fun startLogin() {
        viewModelScope.launch {
            val la = runCatching { ApiClient.api.authLnurl() }.getOrNull()
            if (la?.ok != true || la.lnurl.isEmpty()) {
                _state.update { it.copy(loginError = "Couldn't start — try again.") }
                return@launch
            }
            _state.update { it.copy(loginLnurl = la.lnurl, loginError = null) }
            loginPoll?.cancel()
            loginPoll = viewModelScope.launch {
                while (isActive) {
                    val st = runCatching { ApiClient.api.authStatus(la.k1) }.getOrNull()
                    if (st?.authenticated == true && !st.sessionToken.isNullOrEmpty()) {
                        AuthStore.save(st.sessionToken, st.pubkey ?: "")
                        _state.update { it.copy(loggedIn = true, loginLnurl = null) }
                        refreshEntitlements()
                        break
                    }
                    delay(2000)
                }
            }
        }
    }

    fun cancelLogin() {
        loginPoll?.cancel()
        _state.update { it.copy(loginLnurl = null) }
    }

    fun unlink() {
        AuthStore.logout()
        _state.update { it.copy(loggedIn = false) }
        refreshEntitlements()   // fall back to the device account
    }

    // ---- Account backup / restore (universal, works with any wallet) ----
    fun showBackup() { _state.update { it.copy(backup = DeviceStore.backupString()) } }
    fun closeBackup() { _state.update { it.copy(backup = null) } }
    fun openRestore() { _state.update { it.copy(restoreOpen = true) } }
    fun closeRestore() { _state.update { it.copy(restoreOpen = false) } }

    /** Returns null on success, or an error message. */
    fun restore(raw: String, onDone: () -> Unit) {
        val parsed = DeviceStore.parseBackup(raw)
        if (parsed == null) { _state.update { it.copy() }; return }
        DeviceStore.import(parsed.first, parsed.second)
        AuthStore.logout()   // switching accounts — drop any linked-wallet session
        _state.update { it.copy(loggedIn = false, restoreOpen = false) }
        viewModelScope.launch { refreshEntitlements(); onDone() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProSection() {
    val vm: ProViewModel = viewModel()
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxWidth()) {
        Text("PYBLØCK PRO", style = PyType.mono(11f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        StatusCard(
            state,
            onLink = { vm.startLogin() },
            onUnlink = { vm.unlink() },
            onBackup = { vm.showBackup() },
            onRestore = { vm.openRestore() },
            onRenew = {
                // 1-tap renewal: re-buy the current tier (annual = best value).
                // Lightning is additive — paying again extends the expiry.
                val tier = state.ent?.tier
                state.products.firstOrNull { it.id == "$tier.annual" }?.let { vm.buy(it) }
            },
        )
        Spacer(Modifier.height(10.dp))

        // Subscription paywall (mirrors iOS): period toggle + Pro/Whale plan
        // cards + a single GET button, instead of a flat product list.
        val subs = state.products.filter { it.id.startsWith("pro.") || it.id.startsWith("whale.") }
        if (subs.isNotEmpty()) {
            var annual by remember { mutableStateOf(true) }
            var selTier by remember { mutableStateOf(if (state.ent?.tier == "pro") "whale" else "whale") }
            val suffix = if (annual) ".annual" else ".monthly"
            fun product(tier: String): Product? = subs.firstOrNull { it.id == "$tier$suffix" }
            val selected = product(selTier)

            Column(
                Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(14.dp),
            ) {
                Text(
                    if (state.ent?.tier == "pro") stringResource(R.string.pro_upgrade_prompt)
                    else stringResource(R.string.pro_become_power_user),
                    style = PyType.mono(12f), color = PyTheme.cyan.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PeriodPill(stringResource(R.string.pro_period_monthly), on = !annual, Modifier.weight(1f)) { annual = false }
                    PeriodPill(stringResource(R.string.pro_period_annual_save), on = annual, Modifier.weight(1f)) { annual = true }
                }
                Spacer(Modifier.height(12.dp))
                PlanCard("PRO", PyTheme.yellow, product("pro"), annual, selTier == "pro",
                    stringResource(R.string.pro_plan_pro_perks)) { selTier = "pro" }
                Spacer(Modifier.height(8.dp))
                PlanCard("🐋 WHALE", PyTheme.magenta, product("whale"), annual, selTier == "whale",
                    stringResource(R.string.pro_plan_whale_perks)) { selTier = "whale" }
                Spacer(Modifier.height(12.dp))
                val label = selected?.let {
                    stringResource(R.string.pro_get_button, if (selTier == "whale") "WHALE" else "PRO", num(it.priceSats))
                } ?: stringResource(R.string.pro_price_unavailable)
                Text(
                    label, style = PyType.mono(16f), color = PyTheme.magenta, letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(PyTheme.magenta.copy(alpha = 0.12f))
                        .border(2.dp, PyTheme.magenta, RectangleShape)
                        .clickableNoRipple { Haptics.coin(); selected?.let { vm.buy(it) } }.padding(vertical = 12.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.pro_one_time_note),
                    style = PyType.mono(9f), color = PyTheme.primaryDim, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            }
        }

        // Arcade extras (lives / skins) — not subscriptions, kept apart.
        val extras = state.products.filter { !it.id.startsWith("pro.") && !it.id.startsWith("whale.") }
        if (extras.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.pro_arcade_extras), style = PyType.mono(11f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            extras.forEach { p ->
                ProductRow(p, owned = isOwned(p, state), onBuy = { vm.buy(p) })
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    state.loginLnurl?.let { lnurl ->
        ModalBottomSheet(onDismissRequest = { vm.cancelLogin() }, sheetState = rememberModalBottomSheetState(true), containerColor = PyTheme.bg) {
            LoginSheet(lnurl)
        }
    }
    state.purchase?.let { pf ->
        ModalBottomSheet(onDismissRequest = { vm.closePurchase() }, sheetState = rememberModalBottomSheetState(true), containerColor = PyTheme.bg) {
            PurchaseSheet(pf)
        }
    }
    state.backup?.let { b ->
        ModalBottomSheet(onDismissRequest = { vm.closeBackup() }, sheetState = rememberModalBottomSheetState(true), containerColor = PyTheme.bg) {
            BackupSheet(b)
        }
    }
    if (state.restoreOpen) {
        ModalBottomSheet(onDismissRequest = { vm.closeRestore() }, sheetState = rememberModalBottomSheetState(true), containerColor = PyTheme.bg) {
            RestoreSheet(onRestore = { txt -> vm.restore(txt) {} }, onCancel = { vm.closeRestore() })
        }
    }
}

private fun isOwned(p: Product, s: ProUiState): Boolean {
    val ent = s.ent ?: return false
    return when (p.kind) {
        "subscription" -> (p.grantsTier == "whale" && ent.tier == "whale") ||
            (p.grantsTier == "pro" && (ent.tier == "pro" || ent.tier == "whale"))
        "nonconsumable" -> ent.skins.isNotEmpty() || ent.tier == "whale"
        else -> false
    }
}

@Composable
private fun StatusCard(
    state: ProUiState,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onRenew: () -> Unit = {},
) {
    val tier = state.ent?.tier ?: "free"
    val tierColor = when (tier) { "whale" -> PyTheme.magenta; "pro" -> PyTheme.yellow; else -> PyTheme.primaryDim }
    val tierLabel = when (tier) { "whale" -> "🐋 WHALE"; "pro" -> "PRO"; else -> "FREE" }

    Column(Modifier.fillMaxWidth().moduleFrame(tierColor).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tierLabel, style = PyType.mono(18f), color = tierColor)
            Spacer(Modifier.weight(1f))
            if (state.loggedIn) {
                Text(stringResource(R.string.pro_linked), style = PyType.mono(11f), color = PyTheme.cyan.copy(alpha = 0.8f))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.pro_unlink), style = PyType.mono(11f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { Haptics.warning(); onUnlink() })
            }
        }
        state.ent?.let { e ->
            if (e.tier != "free" && e.tierExpiresAt != null) {
                Text(stringResource(R.string.pro_active_until, fmtDate(e.tierExpiresAt)), style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f))
                // Lightning is pay-per-period: nothing auto-renews, so there is
                // no subscription to "cancel" — it just lapses on the date above.
                // Spell that out so users aren't left hunting for a cancel button.
                Text(stringResource(R.string.pro_no_autorenew_note),
                    style = PyType.mono(9f), color = PyTheme.primaryDim, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(8.dp))
                // 1-tap renewal — Lightning is additive, paying extends the expiry.
                Text(stringResource(R.string.pro_renew_extend), style = PyType.mono(12f), color = tierColor, textAlign = TextAlign.Center, letterSpacing = 1.sp,
                    modifier = Modifier.fillMaxWidth().border(1.dp, tierColor, RectangleShape).clickableNoRipple { Haptics.coin(); onRenew() }.padding(vertical = 9.dp))
            }
            Text(stringResource(R.string.pro_lives_skins, e.livesBalance, e.skins.size), style = PyType.mono(11f), color = PyTheme.cyan.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        // Account actions — link wallet (optional) + backup/restore (universal)
        if (!state.loggedIn) {
            AccountBtn(stringResource(R.string.pro_link_wallet), PyTheme.magenta, onLink)
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountBtn(stringResource(R.string.pro_backup), PyTheme.cyan, onBackup, Modifier.weight(1f))
            AccountBtn(stringResource(R.string.pro_restore), PyTheme.cyan, onRestore, Modifier.weight(1f))
        }
        state.loginError?.let { Text(it, style = PyType.mono(10f), color = PyTheme.danger, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun AccountBtn(label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        label, style = PyType.mono(12f), color = color, textAlign = TextAlign.Center, letterSpacing = 1.sp,
        modifier = modifier.fillMaxWidth().border(1.dp, color, RectangleShape).clickableNoRipple { Haptics.tap(); onClick() }.padding(vertical = 9.dp),
    )
}

@Composable
private fun PeriodPill(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, style = PyType.mono(11f), letterSpacing = 1.sp, textAlign = TextAlign.Center,
        color = if (on) PyTheme.bg else PyTheme.cyan,
        modifier = modifier.background(if (on) PyTheme.cyan else Color.Transparent)
            .border(1.dp, PyTheme.cyan.copy(alpha = if (on) 0f else 0.5f), RectangleShape)
            .clickableNoRipple { Haptics.select(); onClick() }.padding(vertical = 7.dp),
    )
}

@Composable
private fun PlanCard(name: String, accent: Color, product: Product?, annual: Boolean, on: Boolean, perk: String, onSelect: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .moduleFrame(accent)
            .clickableNoRipple { Haptics.select(); onSelect() }.padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = PyType.mono(18f), color = accent, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(product?.let { stringResource(R.string.pro_sats_amount, num(it.priceSats)) } ?: "—", style = PyType.mono(16f), color = PyTheme.yellow)
                Text(stringResource(R.string.pro_fiat_period, product?.displayFiat ?: "", if (annual) stringResource(R.string.pro_period_yr) else stringResource(R.string.pro_period_mo)), style = PyType.mono(9f), color = PyTheme.primaryDim)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(perk, style = PyType.mono(11f), color = PyTheme.cyan.copy(alpha = 0.8f))
    }
}

@Composable
private fun ProductRow(p: Product, owned: Boolean, onBuy: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = 0.3f), RectangleShape).padding(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(productName(p.id), style = PyType.mono(14f), color = PyTheme.primary)
            Text(stringResource(R.string.pro_fiat_sats, p.displayFiat, num(p.priceSats)), style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f))
        }
        if (owned) {
            Text(stringResource(R.string.pro_owned), style = PyType.mono(12f), color = PyTheme.primary)
        } else {
            Text(
                stringResource(R.string.pro_buy), style = PyType.mono(13f), color = PyTheme.magenta, letterSpacing = 1.sp,
                modifier = Modifier.border(1.dp, PyTheme.magenta, RectangleShape).clickableNoRipple { Haptics.coin(); onBuy() }.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun LoginSheet(lnurl: String) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.pro_link_lightning_wallet), style = PyType.mono(14f), color = PyTheme.magenta, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.pro_link_wallet_desc), style = PyType.mono(11f), color = PyTheme.cyan.copy(alpha = 0.8f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        QrCode(lnurl, size = 240.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.pro_open_wallet), style = PyType.mono(13f), color = PyTheme.magenta, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.magenta, RectangleShape)
                .clickableNoRipple { Haptics.tap(); LnUri.open(ctx, lnurl) }
                .padding(vertical = 10.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = PyTheme.primary, modifier = Modifier.height(16.dp).padding(end = 8.dp))
            Text(stringResource(R.string.pro_waiting_wallet), style = PyType.mono(11f), color = PyTheme.primaryDim)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun BackupSheet(backup: String) {
    val ctx = LocalContext.current
    // Renders the account secret (QR + text) that fully controls the account —
    // block screenshots / screen recording / recents thumbnail while shown.
    com.astrolexis.pyblock.ui.components.SecureFlag()
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.pro_backup_account), style = PyType.mono(14f), color = PyTheme.cyan, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.pro_backup_desc), style = PyType.mono(11f), color = PyTheme.yellow.copy(alpha = 0.9f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        QrCode(backup, size = 220.dp)
        Spacer(Modifier.height(12.dp))
        Text(backup, style = PyType.mono(10f), color = PyTheme.cyan, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = 0.3f), RectangleShape).padding(10.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.pro_copy_key), style = PyType.mono(13f), color = PyTheme.cyan, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.cyan, RectangleShape)
                .clickableNoRipple { Haptics.tap(); com.astrolexis.pyblock.ui.components.copySensitiveToClipboard(ctx, backup, ctx.getString(R.string.pro_account_key_label)) }.padding(vertical = 10.dp),
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun RestoreSheet(onRestore: (String) -> Unit, onCancel: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidKeyMsg = stringResource(R.string.pro_invalid_key)
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.pro_restore_account), style = PyType.mono(14f), color = PyTheme.cyan, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.pro_restore_desc), style = PyType.mono(11f), color = PyTheme.cyan.copy(alpha = 0.8f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        RetroTextField(value = text, onValueChange = { text = it; error = null }, placeholder = "pyblock-acct:v1:…", color = PyTheme.cyan)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.pro_paste), style = PyType.mono(11f), color = PyTheme.primaryDim,
            modifier = Modifier.clickableNoRipple { clipboard.getText()?.text?.let { text = it; error = null } },
        )
        error?.let { Text(it, style = PyType.mono(10f), color = PyTheme.danger, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.pro_restore_confirm), style = PyType.mono(13f), color = PyTheme.magenta, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.magenta, RectangleShape)
                .clickableNoRipple {
                    Haptics.thock()
                    if (DeviceStore.parseBackup(text) == null) error = invalidKeyMsg
                    else onRestore(text)
                }.padding(vertical = 10.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.pro_cancel), style = PyType.mono(11f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple(onCancel))
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PurchaseSheet(pf: PurchaseFlow) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(productName(pf.product.id).uppercase(), style = PyType.mono(13f), color = PyTheme.magenta, letterSpacing = 2.sp)
        when {
            pf.error != null -> {
                Spacer(Modifier.height(12.dp))
                Text(pf.error, style = PyType.mono(12f), color = PyTheme.danger)
            }
            pf.invoice == null -> {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(color = PyTheme.primary)
            }
            pf.status == "paid" -> {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.pro_paid_unlocked), style = PyType.mono(18f), color = PyTheme.primary)
            }
            else -> {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.pro_amount_fiat, pf.amount ?: pf.product.priceSats, pf.product.displayFiat), style = PyType.mono(16f), color = PyTheme.yellow)
                Spacer(Modifier.height(12.dp))
                QrCode(pf.invoice, size = 220.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.pro_open_wallet), style = PyType.mono(13f), color = PyTheme.magenta, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.magenta, RectangleShape)
                        .clickableNoRipple { Haptics.tap(); LnUri.open(ctx, pf.invoice) }
                        .padding(vertical = 10.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.pro_waiting_payment), style = PyType.mono(11f), color = PyTheme.primaryDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun productName(id: String): String = when (id) {
    "pro.monthly" -> "PRO · Monthly"
    "pro.annual" -> "PRO · Annual"
    "whale.monthly" -> "🐋 WHALE · Monthly"
    "whale.annual" -> "🐋 WHALE · Annual"
    "lives10" -> "+10 Lives"
    "lives30" -> "+30 Lives"
    "skins" -> "Unlock All Skins"
    else -> id
}

private fun num(v: Int): String = String.format(Locale.US, "%,d", v)

private val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private fun fmtDate(epochSec: Long): String =
    Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(dateFmt)
