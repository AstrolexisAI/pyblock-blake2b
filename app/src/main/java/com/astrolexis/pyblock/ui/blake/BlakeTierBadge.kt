package com.astrolexis.pyblock.ui.blake

import com.astrolexis.pyblock.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Text
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.data.net.ProRepo
import com.astrolexis.pyblock.data.store.EntitlementsStore
import com.astrolexis.pyblock.ui.components.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * FREE · PRO · WHALE, in every tab's header. The one place membership is always reachable from:
 * tapping it opens the membership sheet, which is also where upgrading starts. Mirrors iOS; here
 * the way up is a Lightning invoice, since this build lives outside the Play Store.
 */
@Composable
fun BlakeTierBadge(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var tier by remember { mutableStateOf(EntitlementsStore.tierTag ?: "free") }
    LaunchedEffect(open) { if (!open) tier = EntitlementsStore.tierTag ?: "free" }
    val label = when (tier) { "whale" -> "WHALE"; "pro" -> "PRO"; else -> "FREE" }
    val accent = when (tier) { "whale" -> Blake.hero; "pro" -> Blake.pp; else -> Blake.faint }
    val free = tier == "free"
    Row(modifier
            .then(if (free) Modifier else Modifier.background(accent, Blake.shape))
            .border(1.dp, if (free) Blake.line else accent, Blake.shape)
            .padding(horizontal = 5.dp, vertical = 3.dp)
            .clickableNoRipple { com.astrolexis.pyblock.ui.Haptics.tap(); open = true },
        verticalAlignment = Alignment.CenterVertically) {
        if (!free) {
            RuneGlyph(if (tier == "whale") Rune.LAGUZ else Rune.FEHU, forge = if (tier == "whale") RuneForge.TEMPERED else RuneForge.CAST, ink = Blake.bg, size = 8.dp)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, style = Blake.mono(7f, FontWeight.ExtraBold), color = if (free) Blake.faint else Blake.bg, letterSpacing = 1.sp, maxLines = 1, softWrap = false)
    }
    if (open) BlakeMembershipSheet(onClose = { open = false })
}

private data class Plan(val id: String, val tier: String, val fiat: String, val sats: Int, val days: Int)

/** Where you stand, and the way up: pick a plan, pay the invoice, the tier lands on this account. */
@Composable
fun BlakeMembershipSheet(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var tier by remember { mutableStateOf(EntitlementsStore.tierTag ?: "free") }
    var plans by remember { mutableStateOf<List<Plan>>(emptyList()) }
    var annual by remember { mutableStateOf(false) }
    var invoice by remember { mutableStateOf<String?>(null) }
    var purchaseId by remember { mutableStateOf<String?>(null) }
    var paid by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        plans = withContext(Dispatchers.IO) {
            runCatching {
                val body = OkHttpClient().newCall(Request.Builder().url(ApiClient.BASE_URL + "api/app/products.php").build()).execute().use { it.body?.string() } ?: ""
                val arr = JSONObject(body).optJSONArray("products") ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    if (o.optString("kind") != "subscription") null
                    else Plan(o.optString("id"), o.optString("grants_tier"), o.optString("display_fiat"), o.optInt("price_sats"), o.optInt("period_days"))
                }
            }.getOrDefault(emptyList())
        }
    }
    // Poll the purchase until the invoice is paid, then reload the tier.
    LaunchedEffect(purchaseId) {
        val id = purchaseId ?: return@LaunchedEffect
        while (!paid) {
            delay(3_000)
            val st = runCatching { ApiClient.api.purchaseStatus(id).status }.getOrNull()
            if (st == "paid" || st == "credited") {
                paid = true
                EntitlementsStore.refresh()
                tier = EntitlementsStore.tierTag ?: "free"
                com.astrolexis.pyblock.ui.Haptics.success()
            }
        }
    }

    fun plan(t: String) = plans.firstOrNull { it.tier == t && (if (annual) it.days >= 365 else it.days < 365) }

    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(Blake.ink, Blake.shape).border(1.dp, Blake.line, Blake.shape).padding(20.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.blk_membership), style = Blake.mono(16f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f))
                Text("✕", style = Blake.mono(18f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple(onClose))
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RuneGlyph(when (tier) { "whale" -> Rune.LAGUZ; "pro" -> Rune.FEHU; else -> Rune.ISA },
                    forge = if (tier == "whale") RuneForge.TEMPERED else RuneForge.CAST,
                    ink = if (tier == "free") Blake.faint else Blake.pp, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(when (tier) { "whale" -> "WHALE"; "pro" -> "PRO"; else -> "FREE" }, style = Blake.mono(18f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 2.sp)
                    Text(if (tier == "free") stringResource(R.string.blk_the_runes_you_earn_are_yours_on_any_tier)
                         else stringResource(R.string.blk_held_by_your_account_paid_over_lightning), style = Blake.mono(9f), color = Blake.ppDim)
                }
            }
            Spacer(Modifier.height(12.dp))
            listOf("Runes you earned mining" to true, "Every ink for your runes" to EntitlementsStore.isPro, "Bindrune, your sigil" to EntitlementsStore.isPro,
                   "Rig alerts · a year of history" to EntitlementsStore.isPro, "The tempered forge · the lounge" to EntitlementsStore.isWhale).forEach { (t, on) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    if (on) RuneGlyph(Rune.ISA, ink = Blake.ok, size = 10.dp) else Text("·", style = Blake.mono(10f), color = Blake.faint, modifier = Modifier.width(10.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t, style = Blake.mono(9f), color = if (on) Blake.fg else Blake.faint)
                }
            }
            if (tier != "whale") {
                Spacer(Modifier.height(14.dp))
                if (invoice == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(false to stringResource(R.string.blk_monthly), true to stringResource(R.string.blk_annual)).forEach { (a, l) ->
                            Text(l, style = Blake.mono(9f, FontWeight.ExtraBold), color = if (annual == a) Blake.bg else Blake.ppDim, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).then(if (annual == a) Modifier.background(Blake.pp, Blake.shape) else Modifier.border(1.dp, Blake.line, Blake.shape))
                                    .padding(vertical = 7.dp).clickableNoRipple { annual = a })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    listOf("pro" to "PRO", "whale" to "WHALE").forEach { (t, name) ->
                        val p = plan(t)
                        if (p != null && (t == "whale" || tier == "free")) {
                            Row(Modifier.fillMaxWidth().border(1.dp, if (t == "whale") Blake.hero.copy(alpha = 0.5f) else Blake.pp.copy(alpha = 0.5f), Blake.shape).padding(12.dp)
                                    .clickableNoRipple {
                                        if (busy) return@clickableNoRipple
                                        busy = true; error = null
                                        scope.launch {
                                            val r = ProRepo.purchase(p.id)
                                            if (r?.ok == true && !r.invoice.isNullOrBlank()) { invoice = r.invoice; purchaseId = r.purchaseId }
                                            else error = r?.errors?.joinToString() ?: ctx.getString(R.string.blk_couldn_t_get_an_invoice_try_again)
                                            busy = false
                                        }
                                    }, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = Blake.mono(13f, FontWeight.ExtraBold), color = if (t == "whale") Blake.hero else Blake.pp, letterSpacing = 2.sp)
                                    Text("${p.sats} sats · ${p.fiat} ${if (annual) stringResource(R.string.blk_a_year) else stringResource(R.string.blk_a_month)}", style = Blake.mono(9f), color = Blake.ppDim)
                                }
                                Text(if (busy) "…" else stringResource(R.string.blk_pay), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.pp)
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (plans.isEmpty()) Text(stringResource(R.string.blk_loading_prices), style = Blake.mono(9f), color = Blake.faint)
                } else if (!paid) {
                    Text(stringResource(R.string.blk_pay_with_lightning), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.background(Blake.hero).padding(8.dp).align(Alignment.CenterHorizontally)) { QrCode(text = invoice!!.uppercase(), size = 220.dp) }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.blk_copy_invoice), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().border(1.dp, Blake.pp.copy(alpha = 0.5f), Blake.shape).padding(vertical = 9.dp)
                            .clickableNoRipple { clip.setText(AnnotatedString(invoice!!)); com.astrolexis.pyblock.ui.Haptics.tap() })
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.blk_waiting_for_the_payment_the_tier_lands_o), style = Blake.mono(8f), color = Blake.faint)
                } else {
                    Text(stringResource(R.string.blk_paid_welcome), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ok)
                }
                error?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Blake.mono(9f), color = Blake.danger) }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.blk_refresh), style = Blake.mono(8f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 1.sp,
                modifier = Modifier.clickableNoRipple { scope.launch { EntitlementsStore.refresh(); tier = EntitlementsStore.tierTag ?: "free" } })
        }
    }
}
