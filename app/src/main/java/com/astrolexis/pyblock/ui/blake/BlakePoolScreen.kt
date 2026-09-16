package com.astrolexis.pyblock.ui.blake

import com.astrolexis.pyblock.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.blake.BlakeApi
import com.astrolexis.pyblock.data.blake.BlakeFork
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** HOME / POOL — the BLAKE2b network at a glance. Mirrors iOS PoolView + b.pyblock.xyz:
 *  hero title, LIVE/RC badge, big mono KPIs, recent mined blocks. Sober, purple, spacious. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlakePoolScreen() {
    var stats by remember { mutableStateOf<BlakeApi.PoolStats?>(null) }
    var status by remember { mutableStateOf<BlakeApi.Status?>(null) }
    var blocks by remember { mutableStateOf<List<BlakeApi.Block>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var selectedBlock by remember { mutableStateOf<BlakeApi.Block?>(null) }
    var showRentals by remember { mutableStateOf(false) }
    var showMiner by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tip = stats?.blockHeight ?: status?.blockHeight ?: 0

    var blocksHeight by remember { mutableStateOf(-1) }   // height the block feed was last fetched at
    var topBlock by remember { mutableStateOf(-1) }        // newest block shown; a higher one is "found"
    var sweepKey by remember { mutableStateOf(0) }         // bumps once per found block
    val sweep = remember { androidx.compose.animation.core.Animatable(-0.3f) }
    LaunchedEffect(sweepKey) { if (sweepKey > 0) { sweep.snapTo(-0.3f); sweep.animateTo(1.3f, tween(900)) } }
    /** Returns true when the stats call succeeded (the signal the poll loop backs off on). */
    val load: suspend () -> Boolean = {
        val fresh = BlakeApi.poolStats()
        fresh?.let { stats = it }
        BlakeApi.status()?.let { status = it }
        // The block feed is the heavy call (the whole history) and changes once per block, so it is
        // fetched only when the height moved, not on every 20 s tick.
        val h = fresh?.blockHeight ?: status?.blockHeight ?: 0
        if (blocks.isEmpty() || h != blocksHeight) {
            BlakeApi.blocks().takeIf { it.isNotEmpty() }?.let { bb ->
                blocks = bb; blocksHeight = h
                val newest = bb.maxOfOrNull { it.height } ?: -1
                // Only a block that arrived while watching counts: the first load just sets the mark.
                if (topBlock > 0 && newest > topBlock) {
                    val b = bb.first { it.height == newest }
                    com.astrolexis.pyblock.ui.Haptics.tap(); com.astrolexis.pyblock.ui.Sfx.stageClear()
                    WalletEvents.post(WalletEvents.Kind.Block(newest, b.stratum ?: "pool"))
                    sweepKey++
                }
                topBlock = maxOf(topBlock, newest)
            }
        }
        loaded = true
        fresh != null
    }
    // Back off while the server is unreachable: a fleet of open POOL screens hammering every 20 s is
    // exactly what kept php-fpm from recovering on 2026-09-08. Snap back on the first success.
    LaunchedEffect(Unit) {
        var d = 20_000L
        while (true) { val ok = load(); d = if (ok) 20_000L else minOf(d * 2, 300_000L); delay(d) }
    }

    val live = status?.operational == true

    Box(Modifier.fillMaxSize().background(Blake.bg)) {
      PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
          scope.launch { refreshing = true; load(); refreshing = false }
      }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            // header
            Row(verticalAlignment = Alignment.CenterVertically) {
                BlakeTierBadge()
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(7.dp).background(if (live) Blake.ok else Blake.warn, CircleShape))
                Spacer(Modifier.size(5.dp))
                Text(if (live) stringResource(R.string.blk_live) else (status?.rc ?: "RC"), style = Blake.mono(10f, FontWeight.ExtraBold),
                    color = if (live) Blake.ok else Blake.warn, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(8.dp))
            val poolEvent by WalletEvents.current.collectAsState()
            // A found block borrows this line for a few seconds; otherwise it states the timechain.
            Text(poolEvent?.let { "${it.glyph} ${it.text}" } ?: stringResource(R.string.blk_blake2b_timechain, status?.blockHeight?.let { "#$it" } ?: "—"),
                style = Blake.mono(10f), color = Blake.ppDim, letterSpacing = 1.sp)

            Spacer(Modifier.height(22.dp))
            if (!loaded) Text(stringResource(R.string.blk_loading_network), style = Blake.mono(10f), color = Blake.pp)
            else if (stats == null) Text(stringResource(R.string.blk_can_t_reach_the_server), style = Blake.mono(10f), color = Blake.danger)

            Spacer(Modifier.height(14.dp))
            // KPI card — with the block-found sweep: a thin light bar crossing it once, left to right.
            Column(Modifier.fillMaxWidth().blakeCard().drawWithContent {
                drawContent()
                val x = sweep.value
                if (x > -0.3f && x < 1.3f) {
                    val w = size.width * 0.3f
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Blake.pp.copy(alpha = 0f), Blake.pp.copy(alpha = 0.45f), Blake.pp.copy(alpha = 0f)),
                            startX = size.width * x, endX = size.width * x + w),
                        topLeft = androidx.compose.ui.geometry.Offset(size.width * x, 0f),
                        size = androidx.compose.ui.geometry.Size(w, size.height))
                }
            }) {
                Row(Modifier.fillMaxWidth()) {
                    BlakeStat(hashrate(stats?.poolHashrateThs), stringResource(R.string.blk_pool_hashrate))
                    Spacer(Modifier.weight(1f))
                    BlakeStat("${stats?.miners ?: 0}", "miners", Blake.fg, alignEnd = true)
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    BlakeStat(hashrate(stats?.chainHashrateThs), stringResource(R.string.blk_network_hashrate), Blake.ppDim)
                    Spacer(Modifier.weight(1f))
                    BlakeStat(stats?.blockHeight?.toString() ?: "—", stringResource(R.string.blk_block_height), Blake.fg, alignEnd = true)
                }
            }

            // Hashrate by stratum
            val byStratum = stats?.hashrateByStratum
            if (!byStratum.isNullOrEmpty()) {
                Spacer(Modifier.height(22.dp))
                Column(Modifier.fillMaxWidth().blakeCard()) {
                    Text(stringResource(R.string.blk_hashrate_by_stratum), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
                    Spacer(Modifier.height(10.dp))
                    val labels = mapOf("lotto" to stringResource(R.string.blk_lotto), "lotto_asic" to stringResource(R.string.blk_lotto_asic),
                        "chirp" to stringResource(R.string.blk_chirp), "carousel" to stringResource(R.string.blk_carousel), "wavicles" to stringResource(R.string.blk_wavicles),
                        "datum" to stringResource(R.string.blk_datum))
                    // Flagship first (LOTTO before 970000, CAROUSEL after — lotto's ASIC tier tags along
                    // while lotto is flagship), then the rest by hashrate desc. Leads with the flagship
                    // through the swap regardless of its size.
                    val primary = BlakeFork.primaryStratum(tip, stats?.flagship)
                    val flagshipKeys = if (primary == "lotto") listOf("lotto", "lotto_asic") else listOf(primary)
                    val present = byStratum.keys.filter { labels.containsKey(it) }
                    val flag = flagshipKeys.filter { it in present }.sortedByDescending { byStratum[it] ?: 0.0 }
                    val rest = present.filter { it !in flag }.sortedByDescending { byStratum[it] ?: 0.0 }
                    val rows = (flag + rest).map { Triple(it, labels[it] ?: it.uppercase(), byStratum[it] ?: 0.0) }
                    val total = rows.sumOf { it.third }
                    rows.forEach { (k, label, ths) ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // The two products with a rune of their own carry it beside the name.
                                ProductRune.runeFor(k)?.let { RuneGlyph(it, ink = stratumColor(k, tip, stats?.flagship), size = 10.dp); Spacer(Modifier.width(5.dp)) }
                                Text(label, style = Blake.mono(9f, FontWeight.ExtraBold), color = stratumColor(k, tip, stats?.flagship), letterSpacing = 0.5.sp)
                                Spacer(Modifier.weight(1f))
                                Text(hashrate(ths), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg)
                            }
                            Spacer(Modifier.height(3.dp))
                            Row(Modifier.fillMaxWidth().height(5.dp).background(Blake.line)) {
                                val frac = if (total > 0) (ths / total).toFloat() else 0f
                                Box(Modifier.fillMaxHeight().weight(frac.coerceAtLeast(0.0001f)).background(stratumColor(k, tip, stats?.flagship)))
                                Box(Modifier.fillMaxHeight().weight((1f - frac).coerceAtLeast(0.0001f)))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.blk_pool_total_across_all_stratums_network_i),
                        style = Blake.mono(7f), color = Blake.faint)
                }
            }

            // Places to go from the pool: rent hash to your address, or see your own miners. Same
            // rows as the wallet's command list — glyph, name, hint, chevron, hairline.
            Spacer(Modifier.height(22.dp))
            poolCommandRow("ᚱ", "RENTALS", stringResource(R.string.blk_rent_hash_to_your_address)) { showRentals = true }
            poolCommandRow("ᛗ", "MINER", stringResource(R.string.blk_your_workers_connect)) { showMiner = true }

            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.blk_mined_blocks), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
            Spacer(Modifier.height(12.dp))
            if (blocks.isEmpty()) {
                Text(stringResource(R.string.blk_no_blocks_yet), style = Blake.mono(10f), color = Blake.faint)
            } else {
                blocks.take(20).forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).blakeCard(12.dp)
                        .clickableNoRipple { selectedBlock = b }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("#${b.height}", style = Blake.mono(13f, FontWeight.ExtraBold), color = Blake.pp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(b.stratum?.uppercase() ?: "—", style = Blake.mono(9f, FontWeight.ExtraBold),
                                    color = stratumColor(b.stratum, tip, stats?.flagship), letterSpacing = 0.5.sp)
                                Text(" · ${b.finderMasked ?: "—"}", style = Blake.mono(9f), color = Blake.faint, maxLines = 1)
                            }
                        }
                        Text(b.reward?.let { "%.4f".format(it) } ?: "—", style = Blake.mono(12f), color = Blake.fg)
                        Spacer(Modifier.size(8.dp))
                        Text("›", style = Blake.mono(14f), color = Blake.ppDim)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
      }
    }

    if (showRentals) BlakeRentalsSheet { showRentals = false }
    if (showMiner) BlakeMinerSheet { showMiner = false }
    selectedBlock?.let { b -> BlockDetailDialog(b, tip, stats?.flagship) { selectedBlock = null } }
}

/** Colour each block by the stratum that found it. The flagship (primary for the current era —
 *  LOTTO before block 970000, CAROUSEL after) is purple; the others keep a stable accent, so the
 *  list re-colours itself automatically once the timechain crosses the switch. */
private fun stratumColor(s: String?, tip: Int, serverFlagship: String? = null): androidx.compose.ui.graphics.Color {
    // lotto_asic is the same product as lotto (ASIC vs GPU) — colour it in the lotto family.
    val key = if (s?.lowercase() == "lotto_asic") "lotto" else s?.lowercase()
    if (key != null && key == BlakeFork.primaryStratum(tip, serverFlagship)) return Blake.pp
    return when (key) {
        "chirp" -> Blake.ok
        "carousel", "lotto" -> Blake.warn
        "wavicles" -> Blake.wave
        "datum" -> Blake.datum
        else -> Blake.faint
    }
}

/** Tap a mined block → its details + the COINBASE SPLIT (how the reward was divided across
 *  miners) once the pool reports it. Mirrors iOS BlockDetailView. */
@Composable
private fun BlockDetailDialog(b: BlakeApi.Block, tip: Int, serverFlagship: String?, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var detail by remember { mutableStateOf<BlakeApi.BlockDetail?>(null) }
    var loadingSplit by remember { mutableStateOf(true) }
    LaunchedEffect(b.height) { detail = BlakeApi.blockDetail(b.height); loadingSplit = false }
    val confs = if (tip > 0) maxOf(0, tip - b.height + 1) else 0
    val isPrimary = b.stratum?.lowercase() == BlakeFork.primaryStratum(tip, serverFlagship)
    val accent = stratumColor(b.stratum, tip, serverFlagship)

    sheetBox(stringResource(R.string.blk_block, b.height), accent, onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(b.stratum?.uppercase() ?: "—", style = Blake.mono(10f, FontWeight.ExtraBold), color = accent, letterSpacing = 1.sp)
            if (isPrimary) { Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.blk_flagship), style = Blake.mono(8f), color = Blake.faint) }
        }
        Spacer(Modifier.height(12.dp))
        kv(stringResource(R.string.blk_reward), "${b.reward?.let { "%.8f".format(it) } ?: "—"} ${Blake.RUNE}", Blake.pp)
        kv(stringResource(R.string.blk_finder), b.finderMasked ?: "—", Blake.fg)
        kv(stringResource(R.string.blk_confirmations), if (confs > 0) "$confs" else "—", Blake.fg)
        kv(stringResource(R.string.blk_difficulty), b.difficulty?.let { fmtDiff(it) } ?: "—", Blake.fg)
        // Since the swap the feed's protocol is the stratum name itself; showing it twice says nothing.
        b.protocolName?.takeIf { it.lowercase() != b.stratum?.lowercase() }?.let { kv(stringResource(R.string.blk_protocol), it, Blake.fg) }
        detail?.architect?.takeIf { it.isNotBlank() }?.let {
            // WAVICLES and DATUM blocks are built by the finder's own node.
            val ownNode = b.stratum?.lowercase() in setOf("wavicles", "datum")
            kv(if (ownNode) stringResource(R.string.blk_built_by) else stringResource(R.string.blk_architect),
               if (ownNode) stringResource(R.string.blk_s_node, it) else it, Blake.fg)
        }
        kv(stringResource(R.string.blk_time), b.timestamp?.let { relTime(ctx, it) } ?: "—", Blake.fg)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.blk_hash), style = Blake.mono(8f), color = Blake.faint, letterSpacing = 1.sp)
        Text(b.hash, style = Blake.mono(9f), color = Blake.pp, modifier = Modifier.clickableNoRipple {
            clip.setText(androidx.compose.ui.text.AnnotatedString(b.hash))
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.blk_hash_copied), android.widget.Toast.LENGTH_SHORT).show()
        })
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.blk_coinbase_split), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        val outs = detail?.coinbase?.filter { (it.sats ?: 0L) > 0L }
        if (!outs.isNullOrEmpty()) {
            val total = outs.sumOf { it.sats ?: 0L }
            outs.forEach { o ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(o.address ?: "—", style = Blake.mono(9f), color = Blake.fg, maxLines = 1, modifier = Modifier.weight(1f))
                    Spacer(Modifier.size(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${Blake.btc(o.sats ?: 0L)} ${Blake.RUNE}", style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp)
                        val pct = o.share ?: (if (total > 0) (o.sats ?: 0L).toDouble() / total else 0.0)
                        Text("%.1f%%".format(pct * 100), style = Blake.mono(8f), color = Blake.faint)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Blake.line))
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.blk_output_s_shared_coinbase, outs.size), style = Blake.mono(8f), color = Blake.faint)
        } else if (loadingSplit) {
            Text(stringResource(R.string.blk_loading_split), style = Blake.mono(9f), color = Blake.faint)
        } else {
            Text(stringResource(R.string.blk_the_pool_hasn_t_published_this_block_s_c), style = Blake.mono(9f), color = Blake.faint)
        }
        Spacer(Modifier.height(14.dp))
        sheetBtn(stringResource(R.string.blk_close), Blake.ppDim) { onClose() }
    }
}

private fun fmtDiff(d: Double): String = when {
    d >= 1e12 -> "%.2fT".format(d / 1e12)
    d >= 1e9 -> "%.2fG".format(d / 1e9)
    d >= 1e6 -> "%.2fM".format(d / 1e6)
    else -> "%.0f".format(d)
}
private fun relTime(ctx: android.content.Context, ts: Double): String {
    val s = System.currentTimeMillis() / 1000.0 - ts
    return when {
        s < 60 -> ctx.getString(R.string.blk_just_now)
        s < 3600 -> com.astrolexis.pyblock.data.store.AppStrings.get(R.string.blk_m_ago, (s / 60).toInt())
        s < 86400 -> com.astrolexis.pyblock.data.store.AppStrings.get(R.string.blk_h_ago, (s / 3600).toInt())
        else -> com.astrolexis.pyblock.data.store.AppStrings.get(R.string.blk_d_ago, (s / 86400).toInt())
    }
}

private fun hashrate(th: Double?): String {
    th ?: return "—"
    return if (th >= 1000) "%.2f PH/s".format(th / 1000) else "%.1f TH/s".format(th)
}

@Composable
private fun poolCommandRow(glyph: String, name: String, trailing: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().hairline().clickableNoRipple { com.astrolexis.pyblock.ui.Haptics.tap(); onClick() }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(glyph, style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.pp, modifier = Modifier.width(14.dp))
        Spacer(Modifier.width(10.dp))
        Text(name, style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.fg, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        Text(trailing, style = Blake.mono(9f), color = Blake.faint)
        Spacer(Modifier.width(10.dp))
        Text("›", style = Blake.mono(14f), color = Blake.ppDim)
    }
}
