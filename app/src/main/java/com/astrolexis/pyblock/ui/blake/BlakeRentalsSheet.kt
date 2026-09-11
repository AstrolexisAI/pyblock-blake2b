package com.astrolexis.pyblock.ui.blake

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.astrolexis.pyblock.data.blake.BlakeRentals
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.Sfx
import com.astrolexis.pyblock.ui.components.QrCode
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** Full-screen sheet container shared by RENTALS and MINER (long content, own scroll). */
@Composable
internal fun fullSheet(title: String, onClose: () -> Unit, body: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Blake.bg).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Blake.mono(18f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text("✕", style = Blake.mono(22f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple(onClose))
            }
            Spacer(Modifier.height(14.dp))
            body()
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** A row with a hairline under it (the list idiom of the wallet). */
internal fun Modifier.hairline(): Modifier = drawBehind {
    drawRect(Blake.line, topLeft = Offset(0f, size.height - 1f), size = Size(size.width, 1f))
}

@Composable
internal fun sectionTitle(t: String) { Text(t, style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp) }

@Composable
internal fun labelValue(k: String, v: String, accent: Color = Blake.fg) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(k, style = Blake.mono(8f, FontWeight.ExtraBold), color = Blake.faint, letterSpacing = 1.sp, modifier = Modifier.width(84.dp))
        Text(v, style = Blake.mono(10f, FontWeight.ExtraBold), color = accent, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** RENTALS — rent BLAKE2b hash delivered to your own payout address. Mirrors iOS RentalsView. */
@Composable
fun BlakeRentalsSheet(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("pyblockb.rentals", android.content.Context.MODE_PRIVATE) }
    val wallets by WalletStore.wallets.collectAsState()

    var rigs by remember { mutableStateOf<BlakeRentals.Rigs?>(null) }
    var loadedRigs by remember { mutableStateOf(false) }
    var hours by remember { mutableStateOf(3) }
    var rig by remember { mutableStateOf<BlakeRentals.Rig?>(null) }
    var pool by remember { mutableStateOf("carousel") }
    var address by remember { mutableStateOf(prefs.getString("address", null) ?: wallets.firstOrNull()?.address ?: "") }
    var showWalletPick by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf<BlakeRentals.Quote?>(null) }
    var quoting by remember { mutableStateOf(false) }
    var ordering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var order by remember { mutableStateOf<BlakeRentals.Order?>(null) }
    var live by remember { mutableStateOf<BlakeRentals.OrderStatus?>(null) }
    var nonce by remember { mutableStateOf(UUID.randomUUID().toString().replace("-", "")) }
    var myOrders by remember { mutableStateOf<List<BlakeRentals.DeviceOrder>>(emptyList()) }
    var tracking by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    val loadRigs: suspend () -> Unit = {
        BlakeRentals.rigs()?.let { r ->
            rigs = r
            rig?.let { sel -> r.rigs.firstOrNull { it.id == sel.id }?.let { rig = it } }   // keep the price current
            if (r.durations.isNotEmpty() && hours !in r.durations) hours = r.durations.first()
        }
        loadedRigs = true
    }
    LaunchedEffect(Unit) {
        loadRigs(); myOrders = BlakeRentals.myOrders()
        while (true) { delay(60_000); if (tracking == null && order == null) loadRigs() }   // prices move
    }
    val trackId = tracking ?: order?.orderId
    LaunchedEffect(trackId) {
        val id = trackId ?: return@LaunchedEffect
        while (true) {
            BlakeRentals.status(id)?.let { s ->
                if (live?.status != s.status && s.status == "paid") { Haptics.success(); Sfx.coin() }
                if (live?.status != s.status && s.status == "active") Sfx.powerUp()
                live = s
                if (BlakeRentals.statusIsFinal(s.status)) { myOrders = BlakeRentals.myOrders(); return@LaunchedEffect }
            }
            delay(5_000)
        }
    }

    fullSheet("RENTALS", onClose) {
        Text("Rent BLAKE2b hash to your address. Live market price, paid over Lightning; delivered on the pool you pick.",
            style = Blake.mono(9f), color = Blake.ppDim)
        Spacer(Modifier.height(18.dp))

        if (trackId != null) {
            // ---- Tracker ----
            val s = live
            val status = s?.status ?: order?.status ?: "pending_payment"
            val invoice = s?.invoice ?: order?.invoice ?: myOrders.firstOrNull { it.id == trackId }?.lnInvoice
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹ BACK", style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp,
                    modifier = Modifier.clickableNoRipple { Haptics.tap(); tracking = null; order = null; live = null; quote = null; nonce = UUID.randomUUID().toString().replace("-", "") })
                Spacer(Modifier.weight(1f))
                Text(trackId.take(8), style = Blake.mono(8f), color = Blake.faint)
            }
            Spacer(Modifier.height(14.dp))
            val cur = BlakeRentals.lifecycle.indexOf(status)
            Row(Modifier.fillMaxWidth()) {
                BlakeRentals.lifecycle.forEachIndexed { i, _ ->
                    Box(Modifier.weight(1f).height(3.dp).background(if (i <= cur) Blake.pp else Blake.line))
                    if (i < BlakeRentals.lifecycle.size - 1) Spacer(Modifier.width(6.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(BlakeRentals.statusLabel(status), style = Blake.mono(12f, FontWeight.ExtraBold), color = statusColor(status), letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                if (status == "pending_payment" && (s?.secondsLeft ?: 0) > 0) {
                    val left = s!!.secondsLeft!!
                    Text("expires in ${left / 60}m ${left % 60}s", style = Blake.mono(8f), color = Blake.warn)
                }
            }
            s?.errorMsg?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(8.dp)); Text(it, style = Blake.mono(9f), color = Blake.danger) }
            if (status == "pending_payment" && invoice != null && s?.stillPayable != false) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.background(Blake.hero).padding(8.dp)) { QrCode(text = invoice.uppercase(), size = 220.dp) }
                }
                Spacer(Modifier.height(10.dp))
                Text(invoice, style = Blake.mono(8f), color = Blake.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Row {
                    Text(if (copied) "COPIED" else "COPY INVOICE", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).border(1.dp, Blake.pp, RectangleShape).padding(vertical = 10.dp).clickableNoRipple {
                            Haptics.tap(); clip.setText(AnnotatedString(invoice)); copied = true
                            scope.launch { delay(1500); copied = false }
                        })
                    Spacer(Modifier.width(10.dp))
                    Text("PAY WITH WALLET", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.bg, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).background(Blake.pp).padding(vertical = 10.dp).clickableNoRipple {
                            Haptics.tap()
                            // Chooser surfaces every installed Lightning wallet, not only the default handler.
                            val i = Intent(Intent.ACTION_VIEW, Uri.parse("lightning:" + invoice.lowercase()))
                            runCatching { ctx.startActivity(Intent.createChooser(i, "Pay with")) }
                                .onFailure { clip.setText(AnnotatedString(invoice)); android.widget.Toast.makeText(ctx, "No Lightning wallet found — invoice copied.", android.widget.Toast.LENGTH_SHORT).show() }
                        })
                }
                Spacer(Modifier.height(6.dp))
                Text("Pay from any Lightning wallet. The hash starts within minutes of payment; this screen follows it.", style = Blake.mono(7f), color = Blake.faint)
            }
            Spacer(Modifier.height(14.dp))
            if (s != null) {
                labelValue("PACKAGE", "${BlakeRentals.th((s.hashratePhs ?: 0.0) * 1000)} · ${(s.durationH ?: 0.0).toInt()}h")
                labelValue("POOL PORT", s.port?.let { ":$it" } ?: "—")
                labelValue("TO", s.btcAddress ?: "—")
                labelValue("TOTAL", BlakeRentals.sats(s.totalSats), Blake.pp)
                if (status == "active" || status == "completed") {
                    labelValue("LIVE HASH", BlakeRentals.th((s.liveHashratePhs ?: 0.0) * 1000), Blake.ok)
                    s.percentDone?.let { p ->
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp).background(Blake.line)) {
                            Box(Modifier.fillMaxWidth((p / 100.0).coerceIn(0.0, 1.0).toFloat()).height(5.dp).background(Blake.ok))
                        }
                    }
                    val series = s.seriesTh()
                    if (series.size > 1) { Spacer(Modifier.height(8.dp)); sparkline(series, Blake.ok, 44.dp) }
                }
                if (status == "completed") {
                    Spacer(Modifier.height(8.dp))
                    Text("Delivered. Rewards mined during the rental were paid to your address by the pool.", style = Blake.mono(8f), color = Blake.ppDim)
                }
            } else Text("⟳ checking…", style = Blake.mono(9f), color = Blake.pp)
        } else {
            // ---- Packages ----
            val durations = rigs?.durations?.takeIf { it.isNotEmpty() } ?: listOf(3, 6, 12, 24)
            Row(Modifier.fillMaxWidth().border(1.dp, Blake.line, RectangleShape)) {
                durations.forEach { h ->
                    val on = hours == h
                    Text("${h}h", style = Blake.mono(10f, FontWeight.ExtraBold), color = if (on) Blake.bg else Blake.ppDim, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).background(if (on) Blake.pp else Color.Transparent).padding(vertical = 9.dp)
                            .clickableNoRipple { Haptics.tap(); hours = h; if (rig?.hours != h) { rig = null; quote = null } })
                }
            }
            Spacer(Modifier.height(10.dp))
            when {
                !loadedRigs -> Text("⟳ loading packages…", style = Blake.mono(10f), color = Blake.pp)
                rigs == null -> Text("⚠ can't reach the server.", style = Blake.mono(10f), color = Blake.danger)
                rigs!!.rigs.isEmpty() -> Text(rigs!!.notice ?: "Rentals are paused right now.", style = Blake.mono(10f), color = Blake.warn)
                else -> {
                    rigs!!.rigs.filter { (it.hours ?: 0) == hours && it.available != false }.sortedBy { it.th ?: 0.0 }.forEach { r ->
                        val on = rig?.id == r.id
                        Row(Modifier.fillMaxWidth().hairline().clickableNoRipple { Haptics.tap(); rig = r; quote = null; error = null }.padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (on) "✓" else "·", style = Blake.mono(11f, FontWeight.ExtraBold), color = if (on) Blake.ok else Blake.faint, modifier = Modifier.width(14.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(BlakeRentals.th(r.th), style = Blake.mono(12f, FontWeight.ExtraBold), color = if (on) Blake.hero else Blake.fg)
                            Spacer(Modifier.width(6.dp))
                            Text("· ${r.hours ?: hours}h", style = Blake.mono(9f), color = Blake.ppDim)
                            Spacer(Modifier.weight(1f))
                            Text(BlakeRentals.sats(r.totalSats), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.pp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Price is the market right now; it is re-quoted when you pay. The fee is inside the total.", style = Blake.mono(7f), color = Blake.faint)
                }
            }

            if (rig != null) {
                // ---- Pool ----
                Spacer(Modifier.height(18.dp))
                sectionTitle("DELIVER TO")
                val pools = rigs?.pools ?: emptyMap()
                listOf("carousel", "lotto", "chirp", "wavicles").filter { pools[it] != null }.forEach { key ->
                    val p = pools[key]!!
                    val on = pool == key
                    Row(Modifier.fillMaxWidth().hairline().clickableNoRipple { Haptics.tap(); pool = key; quote = null; error = null }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.Top) {
                        Text(if (on) "✓" else "·", style = Blake.mono(11f, FontWeight.ExtraBold), color = if (on) Blake.ok else Blake.faint, modifier = Modifier.width(14.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.label ?: key.uppercase(), style = Blake.mono(11f, FontWeight.ExtraBold), color = if (on) Blake.hero else Blake.fg, letterSpacing = 1.sp)
                                p.port?.let { Spacer(Modifier.width(6.dp)); Text(":$it", style = Blake.mono(9f), color = Blake.faint) }
                            }
                            p.split?.let { Text(it, style = Blake.mono(8f), color = Blake.ppDim) }
                            p.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = Blake.mono(7f), color = Blake.faint) }
                        }
                    }
                }

                // ---- Address ----
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    sectionTitle("PAYOUT ADDRESS")
                    Spacer(Modifier.weight(1f))
                    if (wallets.isNotEmpty()) Text("MY WALLET ›", style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp,
                        modifier = Modifier.clickableNoRipple { showWalletPick = !showWalletPick })
                }
                if (showWalletPick) wallets.take(40).forEach { w ->
                    Text(w.address, style = Blake.mono(9f), color = if (w.address == address) Blake.ok else Blake.pp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickableNoRipple { Haptics.tap(); address = w.address; quote = null; showWalletPick = false })
                }
                Spacer(Modifier.height(8.dp))
                BasicTextField(value = address, onValueChange = { address = it; quote = null }, singleLine = true,
                    textStyle = Blake.mono(10f).copy(color = Blake.fg), cursorBrush = SolidColor(Blake.pp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Blake.line, RectangleShape).padding(10.dp))
                Text("The rented hash mines to this address on the pool above. Coins land like any other reward.", style = Blake.mono(7f), color = Blake.faint)

                // ---- Quote / pay ----
                Spacer(Modifier.height(14.dp))
                error?.let { Text(it, style = Blake.mono(9f), color = Blake.danger); Spacer(Modifier.height(8.dp)) }
                val q = quote
                val canQuote = address.trim().length >= 26
                if (q != null) {
                    labelValue("PACKAGE", "${BlakeRentals.th(q.th)} · ${q.hours ?: hours}h")
                    labelValue("POOL", "${pools[q.pool ?: pool]?.label ?: pool.uppercase()} :${q.port ?: 0}")
                    labelValue("TOTAL", BlakeRentals.sats(q.totalSats), Blake.pp)
                    q.feePct?.let { Text("fee ${it.toInt()}% included · re-quoted when you pay", style = Blake.mono(7f), color = Blake.faint) }
                    Spacer(Modifier.height(10.dp))
                    Text(if (ordering) "CREATING INVOICE…" else "PAY WITH LIGHTNING", style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.bg, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().background(Blake.pp.copy(alpha = if (ordering) 0.6f else 1f)).padding(vertical = 12.dp).clickableNoRipple {
                            if (ordering) return@clickableNoRipple
                            scope.launch {
                                ordering = true; error = null; Haptics.thock()
                                try {
                                    order = BlakeRentals.order(rig!!.id, address.trim(), pool, nonce); live = null; Sfx.select()
                                } catch (e: Exception) { error = e.message ?: "Order failed."; Haptics.error(); Sfx.error() }
                                ordering = false
                            }
                        })
                } else {
                    Text(if (quoting) "QUOTING…" else "GET QUOTE", style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.bg, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().background(if (canQuote) Blake.pp else Blake.faint).padding(vertical = 12.dp).clickableNoRipple {
                            if (!canQuote || quoting) return@clickableNoRipple
                            scope.launch {
                                quoting = true; error = null; Haptics.tap()
                                try {
                                    quote = BlakeRentals.quote(rig!!.id, address.trim(), pool)
                                    prefs.edit().putString("address", address.trim()).apply()
                                } catch (e: Exception) { error = e.message ?: "Quote failed."; Haptics.error() }
                                quoting = false
                            }
                        })
                }
            }

            // ---- My orders ----
            if (myOrders.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                sectionTitle("MY ORDERS")
                myOrders.take(20).forEach { o ->
                    Row(Modifier.fillMaxWidth().hairline().clickableNoRipple { Haptics.tap(); live = null; tracking = o.id }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(statusColor(o.status), CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${BlakeRentals.th(o.th ?: (o.hashratePhs ?: 0.0) * 1000)} · ${(o.durationH ?: 0.0).toInt()}h · ${(o.pool ?: "").uppercase()}",
                                style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg)
                            Text("${BlakeRentals.statusLabel(o.status)} · ${relTimeSecs(o.createdAt)}", style = Blake.mono(8f), color = Blake.faint)
                        }
                        Text(BlakeRentals.sats(o.totalSats), style = Blake.mono(9f), color = Blake.ppDim)
                        Spacer(Modifier.width(8.dp))
                        Text("›", style = Blake.mono(14f), color = Blake.ppDim)
                    }
                }
            }
        }
    }
}

@Composable
internal fun sparkline(v: List<Double>, color: Color, height: androidx.compose.ui.unit.Dp, fill: Boolean = false) {
    val mx = (v.maxOrNull() ?: 1.0).coerceAtLeast(1e-9)
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val path = Path()
        val area = Path()
        v.forEachIndexed { i, x ->
            val px = size.width * i / (v.size - 1).coerceAtLeast(1)
            val py = size.height - size.height * (x / mx).toFloat()
            if (i == 0) { path.moveTo(px, py); area.moveTo(px, size.height); area.lineTo(px, py) } else { path.lineTo(px, py); area.lineTo(px, py) }
        }
        area.lineTo(size.width, size.height); area.close()
        if (fill) drawPath(area, color.copy(alpha = 0.12f))
        drawPath(path, color, style = Stroke(width = 1.5f * density))
    }
}

internal fun statusColor(s: String?): Color = when (s) {
    "active", "completed", "paid" -> Blake.ok
    "pending_payment" -> Blake.warn
    null -> Blake.faint
    else -> Blake.danger
}

internal fun relTimeSecs(ts: Long?): String {
    if (ts == null) return "—"
    val d = System.currentTimeMillis() / 1000 - ts
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60}m ago"
        d < 86400 -> "${d / 3600}h ago"
        else -> "${d / 86400}d ago"
    }
}
