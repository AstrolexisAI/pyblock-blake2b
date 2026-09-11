package com.astrolexis.pyblock.ui.blake

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.blake.BlakeMiner
import com.astrolexis.pyblock.data.blake.BlakeRentals
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** MINER — the user's own mining, keyed by the payout address used as stratum username.
 *  Pools hit, workers with live rate and last share, 1d/7d/30d line, blocks that paid this
 *  address, and connect info. Mirrors iOS MinerView. */
@Composable
fun BlakeMinerSheet(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("pyblockb.miner", android.content.Context.MODE_PRIVATE) }
    val wallets by WalletStore.wallets.collectAsState()

    var address by remember { mutableStateOf(prefs.getString("address", "") ?: "") }
    var manual by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var showPick by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<BlakeMiner.Stats?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf("1d") }
    var history by remember { mutableStateOf<List<BlakeMiner.Point>>(emptyList()) }
    var connect by remember { mutableStateOf<BlakeMiner.Connect?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    var copied by remember { mutableStateOf<String?>(null) }

    fun select(a: String) { address = a; stats = null; loaded = false; editing = false; showPick = false; prefs.edit().putString("address", a).apply() }
    val scanWallet: suspend () -> Unit = {
        scanning = true
        found = BlakeMiner.findMining(wallets.take(40).map { it.address })
        if (found.size == 1 && address.isEmpty()) select(found[0])
        scanning = false
    }

    LaunchedEffect(address) {
        if (address.isEmpty()) return@LaunchedEffect
        loaded = false
        while (true) {
            BlakeMiner.stats(address)?.let { s -> stats = s; s.connect?.let { connect = it } }
            history = BlakeMiner.history(address, range)
            loaded = true
            delay(30_000)   // server caches 20 s; samples every 3 min
        }
    }
    LaunchedEffect(range) { if (address.isNotEmpty()) history = BlakeMiner.history(address, range) }
    LaunchedEffect(Unit) {
        if (connect == null) connect = BlakeMiner.connect()
        if (address.isEmpty() && wallets.isNotEmpty()) scanWallet()
    }

    fullSheet("MINER", onClose) {
        // ---- Address ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            sectionTitle("ADDRESS")
            Spacer(Modifier.weight(1f))
            if (wallets.isNotEmpty()) Text(if (scanning) "SCANNING…" else "FIND MINE", style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp,
                modifier = Modifier.clickableNoRipple { if (!scanning) { Haptics.tap(); scope.launch { scanWallet() } } })
            Spacer(Modifier.width(12.dp))
            Text(if (editing) "DONE" else "CHANGE", style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp,
                modifier = Modifier.clickableNoRipple { Haptics.tap(); editing = !editing })
        }
        if (address.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(address, style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (editing || address.isEmpty()) {
            if (found.isNotEmpty()) {
                Spacer(Modifier.height(6.dp)); Text("mining from your wallet:", style = Blake.mono(8f), color = Blake.faint)
                found.forEach { a ->
                    Text(a, style = Blake.mono(9f), color = if (a == address) Blake.ok else Blake.pp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickableNoRipple { Haptics.tap(); select(a) })
                }
            }
            if (wallets.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("PICK FROM WALLET ›", style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp, modifier = Modifier.clickableNoRipple { showPick = !showPick })
                if (showPick) wallets.take(40).forEach { w ->
                    Text(w.address, style = Blake.mono(9f), color = Blake.pp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickableNoRipple { Haptics.tap(); select(w.address) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(value = manual, onValueChange = { manual = it }, singleLine = true,
                    textStyle = Blake.mono(10f).copy(color = Blake.fg), cursorBrush = SolidColor(Blake.pp),
                    modifier = Modifier.weight(1f).border(1.dp, Blake.line, RectangleShape).padding(9.dp),
                    decorationBox = { inner -> if (manual.isEmpty()) Text("other address", style = Blake.mono(10f), color = Blake.faint); inner() })
                Spacer(Modifier.width(8.dp))
                val ok = manual.trim().length >= 26
                Text("USE", style = Blake.mono(9f, FontWeight.ExtraBold), color = if (ok) Blake.pp else Blake.faint, letterSpacing = 1.sp,
                    modifier = Modifier.clickableNoRipple { if (ok) { Haptics.tap(); select(manual.trim()); manual = "" } })
            }
        }
        Spacer(Modifier.height(18.dp))

        val s = stats
        when {
            address.isEmpty() -> Text("Pick the payout address you mine to — it is the stratum username.", style = Blake.mono(9f), color = Blake.ppDim)
            !loaded -> Text("⟳ reading the gateways…", style = Blake.mono(10f), color = Blake.pp)
            s == null -> Text("⚠ can't reach the server.", style = Blake.mono(10f), color = Blake.danger)
            s.isMining -> {
                Column(Modifier.fillMaxWidth().blakeCard()) {
                    Row(Modifier.fillMaxWidth()) {
                        BlakeStat(BlakeRentals.th(s.totalTh1m), "hashrate now")
                        Spacer(Modifier.weight(1f))
                        BlakeStat("${s.workersOnline}", "workers online", Blake.fg, alignEnd = true)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth()) {
                        BlakeStat(BlakeRentals.th(s.totalTh1d), "24h average", Blake.ppDim)
                        Spacer(Modifier.weight(1f))
                        BlakeStat("${s.blocks.size}", "blocks paid", Blake.fg, alignEnd = true)
                    }
                }
                s.pools.forEach { p -> Spacer(Modifier.height(14.dp)); poolCard(p) }
                Spacer(Modifier.height(14.dp))
                // ---- Chart ----
                Column(Modifier.fillMaxWidth().blakeCard()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        sectionTitle("HASHRATE")
                        Spacer(Modifier.weight(1f))
                        listOf("1d", "7d", "30d").forEach { r ->
                            Text(r, style = Blake.mono(9f, FontWeight.ExtraBold), color = if (range == r) Blake.pp else Blake.faint, letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = 6.dp).clickableNoRipple { Haptics.tap(); range = r })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val v = history.map { it.hashrateTh ?: 0.0 }
                    val mx = v.maxOrNull() ?: 0.0
                    if (v.size > 1 && mx > 0) {
                        sparkline(v, Blake.pp, 90.dp, fill = true)
                        Spacer(Modifier.height(6.dp))
                        Row { Text("peak ${BlakeRentals.th(mx)}", style = Blake.mono(7f), color = Blake.faint); Spacer(Modifier.weight(1f)); Text("${v.size} samples · 3 min each", style = Blake.mono(7f), color = Blake.faint) }
                    } else Text("no samples in this range yet", style = Blake.mono(8f), color = Blake.faint)
                }
                Spacer(Modifier.height(18.dp))
                blocksList(s.blocks)
            }
            else -> {
                Text("No shares from this address yet. Point a miner here and it shows up within a few minutes.", style = Blake.mono(9f), color = Blake.ppDim)
                if (s.blocks.isNotEmpty()) { Spacer(Modifier.height(18.dp)); blocksList(s.blocks) }
            }
        }

        // ---- Connect ----
        Spacer(Modifier.height(22.dp))
        sectionTitle("CONNECT A MINER")
        Spacer(Modifier.height(6.dp))
        val c = connect
        if (c != null) {
            val host = c.host ?: "b.pyblock.xyz"
            val copyRow: (String, String) -> Unit = { _, v -> Haptics.tap(); clip.setText(AnnotatedString(v)) }
            Box(Modifier.clickableNoRipple { copyRow("HOST", host) }) { labelValue("HOST", host) }
            val user = if (address.isEmpty()) (c.username ?: "<payout address>[.worker]") else "$address.rig1"
            Box(Modifier.clickableNoRipple { copyRow("USERNAME", user) }) { labelValue("USERNAME", user) }
            labelValue("PASSWORD", c.password ?: "x")
            c.pools.forEach { p ->
                val target = "$host:${p.port ?: 0}"
                Row(Modifier.fillMaxWidth().hairline().clickableNoRipple {
                    Haptics.tap(); clip.setText(AnnotatedString(target)); copied = target
                    scope.launch { delay(1500); if (copied == target) copied = null }
                }.padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.label ?: (p.pool ?: "").uppercase(), style = Blake.mono(9f, FontWeight.ExtraBold), color = poolColor(p.pool ?: ""), letterSpacing = 1.sp)
                            Spacer(Modifier.width(6.dp)); Text((p.kind ?: "").uppercase(), style = Blake.mono(7f), color = Blake.faint)
                            p.minDiff?.let { Spacer(Modifier.width(6.dp)); Text("diff ${it.toInt()}", style = Blake.mono(7f), color = Blake.faint) }
                        }
                        p.split?.let { Text(it, style = Blake.mono(7f), color = Blake.faint) }
                    }
                    Text(if (copied == target) "COPIED" else target, style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp)
                }
            }
            c.vardiff?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Blake.mono(7f), color = Blake.faint) }
            c.miners.keys.sorted().forEach { name ->
                val url = c.miners[name] ?: return@forEach
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp).clickableNoRipple {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }, verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp)
                    Spacer(Modifier.weight(1f))
                    Text("↗", style = Blake.mono(10f), color = Blake.ppDim)
                }
            }
        } else Text("⟳", style = Blake.mono(9f), color = Blake.faint)
    }
}

@Composable
private fun poolCard(p: BlakeMiner.PoolEntry) {
    Column(Modifier.fillMaxWidth().blakeCard()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (p.live == true) Blake.ok else Blake.faint, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(p.label ?: p.pool.uppercase(), style = Blake.mono(11f, FontWeight.ExtraBold), color = poolColor(p.pool), letterSpacing = 2.sp)
            p.port?.takeIf { it > 0 }?.let { Spacer(Modifier.width(6.dp)); Text(":$it", style = Blake.mono(9f), color = Blake.faint) }
            Spacer(Modifier.weight(1f))
            p.sharePct?.takeIf { it > 0 }?.let { Text("%.1f%% of pool".format(java.util.Locale.US, it), style = Blake.mono(8f), color = Blake.ppDim) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            kpi("1m", p.hashrate1m); Spacer(Modifier.weight(1f)); kpi("5m", p.hashrate5m); Spacer(Modifier.weight(1f)); kpi("1h", p.hashrate1h); Spacer(Modifier.weight(1f)); kpi("1d", p.hashrate1d)
        }
        if (p.workers.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Blake.line))
            p.workers.forEach { w ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (w.name.isNullOrEmpty()) "(default worker)" else w.name, style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg)
                        val rej = (w.sharesDiffRej ?: 0.0).toLong()
                        Text("${shortAgent(w.agent)} · diff ${(w.vdiff ?: 0.0).toLong()} · acc ${(w.sharesDiffAcc ?: 0.0).toLong()}${if (rej > 0) " · rej $rej" else ""}",
                            style = Blake.mono(7f), color = Blake.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(BlakeRentals.th(w.hashrateTh), style = Blake.mono(10f, FontWeight.ExtraBold), color = if ((w.hashrateTh ?: 0.0) > 0) Blake.ok else Blake.faint)
                        Text(w.lastshare?.let { "share ${relTimeSecs(it)}" } ?: "no share yet", style = Blake.mono(7f), color = Blake.faint)
                    }
                }
            }
        }
    }
}

@Composable
private fun kpi(l: String, v: Double?) {
    Column {
        Text(BlakeRentals.th(v), style = Blake.mono(10f, FontWeight.ExtraBold), color = if ((v ?: 0.0) > 0) Blake.fg else Blake.faint)
        Text(l, style = Blake.mono(7f), color = Blake.faint)
    }
}

@Composable
private fun blocksList(list: List<BlakeMiner.MinerBlock>) {
    sectionTitle("BLOCKS PAID TO THIS ADDRESS")
    Spacer(Modifier.height(6.dp))
    if (list.isEmpty()) Text("none yet", style = Blake.mono(8f), color = Blake.faint)
    else list.sortedByDescending { it.height }.take(50).forEach { b ->
        Row(Modifier.fillMaxWidth().hairline().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#${b.height}", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg)
            Spacer(Modifier.width(10.dp))
            Text((b.pool ?: "").uppercase(), style = Blake.mono(8f, FontWeight.ExtraBold), color = poolColor(b.pool ?: ""), letterSpacing = 1.sp)
            if (b.role == "finder") { Spacer(Modifier.width(8.dp)); Text("FOUND", style = Blake.mono(7f, FontWeight.ExtraBold), color = Blake.ok, letterSpacing = 1.sp) }
            Spacer(Modifier.weight(1f))
            b.rewardSats?.let { Text("+${Blake.btc(it.coerceAtLeast(0))} ${Blake.RUNE}", style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp) }
            Spacer(Modifier.width(8.dp))
            Text(b.time?.let { relTimeSecs(it) } ?: "", style = Blake.mono(7f), color = Blake.faint)
        }
    }
}

private fun poolColor(p: String): Color = when (p) {
    "chirp" -> Blake.ok
    "wavicles" -> Blake.wave
    "carousel", "lotto", "lotto_asic" -> Blake.pp
    else -> Blake.ppDim
}
private fun shortAgent(a: String?): String = if (a.isNullOrEmpty()) "miner" else if (a.length > 26) a.take(26) + "…" else a
