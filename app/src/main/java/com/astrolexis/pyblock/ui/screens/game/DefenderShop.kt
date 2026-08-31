package com.astrolexis.pyblock.ui.screens.game

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.model.Product
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.data.net.ProRepo
import com.astrolexis.pyblock.data.store.EntitlementsStore
import com.astrolexis.pyblock.data.store.LivesStore
import com.astrolexis.pyblock.data.util.BrandGuard
import com.astrolexis.pyblock.data.util.LnUri
import com.astrolexis.pyblock.ui.components.QrCode
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

private data class ShopBuy(
    val product: Product,
    val invoice: String? = null,
    val status: String? = null,
    val error: String? = null,
)

/** Node Defender shop — buy life packs + unlock ship skins, paid with
 *  Lightning (device account). Also the skin picker. */
@Composable
fun DefenderShop(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var products by remember { mutableStateOf<Map<String, Product>>(emptyMap()) }
    var buy by remember { mutableStateOf<ShopBuy?>(null) }
    val purchaseFailed = stringResource(R.string.shop_purchase_failed)

    LaunchedEffect(Unit) {
        runCatching { ApiClient.api.products() }.getOrNull()?.takeIf { it.ok }
            ?.let { r -> products = r.products.associateBy { it.id } }
    }

    fun startBuy(id: String) {
        val p = products[id] ?: Product(id = id)
        buy = ShopBuy(p)
        scope.launch {
            val r = ProRepo.purchase(id)
            buy = if (r?.ok == true && !r.invoice.isNullOrEmpty()) {
                val id2 = r.purchaseId
                scope.launch {
                    while (isActive && id2 != null) {
                        val st = runCatching { ApiClient.api.purchaseStatus(id2).status }.getOrNull()
                        if (st != null) buy = buy?.copy(status = st)
                        if (st == "paid") { EntitlementsStore.refresh(); break }
                        if (st == "expired") break
                        delay(2500)
                    }
                }
                ShopBuy(p, r.invoice, "pending")
            } else {
                ShopBuy(p, error = BrandGuard.clean(r?.errors?.firstOrNull(), purchaseFailed))
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f))) {
        Starfield()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MarqueeTitle(text = stringResource(R.string.shop_title))
            Spacer(Modifier.height(18.dp))

            // ---- Lives ----
            SectionHeader(stringResource(R.string.shop_section_lives), stringResource(R.string.shop_you_have, LivesStore.lives))
            LifeRow(products["lives10"], "lives10", stringResource(R.string.shop_plus10_lives), "$1.99") { startBuy("lives10") }
            Spacer(Modifier.height(8.dp))
            LifeRow(products["lives30"], "lives30", stringResource(R.string.shop_plus30_lives), "$4.99", best = true) { startBuy("lives30") }

            Spacer(Modifier.height(22.dp))

            // ---- Skins ----
            SectionHeader(stringResource(R.string.shop_section_ship_skins), if (LivesStore.skinsUnlocked) stringResource(R.string.shop_unlocked) else "")
            if (!LivesStore.skinsUnlocked) {
                val p = products["skins"]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .moduleFrame(PyTheme.magenta)
                        .clickableNoRipple { Haptics.coin(); startBuy("skins") }.padding(14.dp),
                ) {
                    Text(stringResource(R.string.shop_unlock_all_skins), style = PyType.mono(15f), color = PyTheme.magenta, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    Text(priceOf(p, "$2.99"), style = PyType.mono(15f), color = PyTheme.yellow)
                }
                Text(stringResource(R.string.shop_free_with_whale), style = PyType.mono(10f), color = PyTheme.primaryDim, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(12.dp))
            GameSkin.all.chunked(3).forEach { rowSkins ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowSkins.forEach { skin -> Box(Modifier.weight(1f)) { SkinCell(skin) } }
                    repeat(3 - rowSkins.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.shop_back), style = PyType.mono(15f), color = PyTheme.primaryDim, textAlign = TextAlign.Center,
                modifier = Modifier.width(200.dp).border(1.dp, PyTheme.primaryDim, RoundedCornerShape(4.dp)).clickableNoRipple { Haptics.tap(); onClose() }.padding(vertical = 12.dp))
            Spacer(Modifier.height(20.dp))
        }

        buy?.let { InvoiceModal(it, onClose = { buy = null }) }
    }
}

@Composable
private fun SectionHeader(title: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = PyType.mono(14f), color = PyTheme.cyan, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        if (right.isNotEmpty()) Text(right, style = PyType.mono(11f), color = PyTheme.primary)
    }
}

@Composable
private fun LifeRow(p: Product?, id: String, label: String, fallback: String, best: Boolean = false, onBuy: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .border(if (best) 2.dp else 1.dp, PyTheme.primary.copy(alpha = if (best) 0.9f else 0.4f), RoundedCornerShape(4.dp))
            .clickableNoRipple { Haptics.coin(); onBuy() }.padding(14.dp),
    ) {
        Text(label, style = PyType.mono(17f), color = PyTheme.primary, letterSpacing = 1.sp)
        if (best) {
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.shop_best), style = PyType.mono(9f), color = PyTheme.bg, modifier = Modifier.background(PyTheme.yellow).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(priceOf(p, fallback), style = PyType.mono(17f), color = PyTheme.yellow)
    }
}

@Composable
private fun SkinCell(skin: GameSkin) {
    val locked = !skin.free && !LivesStore.skinsUnlocked
    val selected = LivesStore.selectedSkinId == skin.id
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, if (selected) skin.stroke else PyTheme.primary.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            .clickableNoRipple { if (!locked) { Haptics.select(); LivesStore.selectSkin(skin.id) } }
            .padding(vertical = 12.dp),
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(if (locked) skin.fill.copy(alpha = 0.35f) else skin.fill), contentAlignment = Alignment.Center) {
            Text(if (locked) "🔒" else "₿", style = PyType.mono(15f), color = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(skin.name, style = PyType.mono(10f), color = if (locked) PyTheme.primaryDim else PyTheme.cyan)
    }
}

@Composable
private fun InvoiceModal(b: ShopBuy, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickableNoRipple(onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(300.dp).moduleFrame(PyTheme.magenta).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(shopName(ctx, b.product.id), style = PyType.mono(13f), color = PyTheme.magenta, letterSpacing = 2.sp)
            Spacer(Modifier.height(12.dp))
            when {
                b.error != null -> Text(b.error, style = PyType.mono(12f), color = PyTheme.danger, textAlign = TextAlign.Center)
                b.status == "paid" -> Text(stringResource(R.string.shop_paid_unlocked), style = PyType.mono(18f), color = PyTheme.primary)
                b.invoice == null -> CircularProgressIndicator(color = PyTheme.primary)
                else -> {
                    Text("${b.product.displayFiat.ifEmpty { "" }}${if (b.product.priceSats > 0) stringResource(R.string.shop_sats_suffix, num(b.product.priceSats)) else ""}", style = PyType.mono(13f), color = PyTheme.yellow)
                    Spacer(Modifier.height(12.dp))
                    QrCode(b.invoice, size = 210.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.shop_open_wallet), style = PyType.mono(13f), color = PyTheme.magenta, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.magenta, RoundedCornerShape(4.dp)).clickableNoRipple { Haptics.thock(); LnUri.open(ctx, b.invoice) }.padding(vertical = 10.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.shop_waiting_payment), style = PyType.mono(11f), color = PyTheme.primaryDim)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(if (b.status == "paid" || b.error != null) stringResource(R.string.shop_close) else stringResource(R.string.shop_cancel), style = PyType.mono(12f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple(onClose))
        }
    }
}

private fun priceOf(p: Product?, fallback: String): String =
    p?.displayFiat?.takeIf { it.isNotEmpty() } ?: fallback

private fun num(v: Int): String = String.format(Locale.US, "%,d", v)

private fun shopName(ctx: Context, id: String): String = when (id) {
    "lives10" -> ctx.getString(R.string.shop_plus10_lives)
    "lives30" -> ctx.getString(R.string.shop_plus30_lives)
    "skins" -> ctx.getString(R.string.shop_unlock_all_skins)
    else -> id.uppercase()
}
