package com.astrolexis.pyblock.ui.blake

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val scope = rememberCoroutineScope()
    val tip = stats?.blockHeight ?: status?.blockHeight ?: 0

    val load: suspend () -> Unit = {
        BlakeApi.poolStats()?.let { stats = it }
        BlakeApi.status()?.let { status = it }
        BlakeApi.blocks().takeIf { it.isNotEmpty() }?.let { blocks = it }
        loaded = true
    }
    LaunchedEffect(Unit) { while (true) { load(); delay(20_000) } }

    val live = status?.operational == true

    Box(Modifier.fillMaxSize().background(Blake.bg)) {
      PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
          scope.launch { refreshing = true; load(); refreshing = false }
      }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            // header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(7.dp).background(if (live) Blake.ok else Blake.warn, CircleShape))
                Spacer(Modifier.size(5.dp))
                Text(if (live) "LIVE" else (status?.rc ?: "RC"), style = Blake.mono(10f, FontWeight.ExtraBold),
                    color = if (live) Blake.ok else Blake.warn, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("BLAKE2b · timechain ${status?.blockHeight?.let { "#$it" } ?: "—"}",
                style = Blake.mono(10f), color = Blake.ppDim, letterSpacing = 1.sp)

            Spacer(Modifier.height(22.dp))
            if (!loaded) Text("⟳ loading network…", style = Blake.mono(10f), color = Blake.pp)
            else if (stats == null) Text("⚠ can't reach the server.", style = Blake.mono(10f), color = Blake.danger)

            Spacer(Modifier.height(14.dp))
            // KPI card
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Row(Modifier.fillMaxWidth()) {
                    BlakeStat(hashrate(stats?.networkHashrateThs), "network hashrate")
                    Spacer(Modifier.weight(1f))
                    BlakeStat("${stats?.miners ?: 0}", "miners", Blake.fg, alignEnd = true)
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    BlakeStat(stats?.blockHeight?.toString() ?: "—", "block height", Blake.fg)
                    Spacer(Modifier.weight(1f))
                    BlakeStat("${stats?.connections ?: 0}", "connections", Blake.ppDim, alignEnd = true)
                }
            }

            Spacer(Modifier.height(22.dp))
            Text("MINED BLOCKS", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
            Spacer(Modifier.height(12.dp))
            if (blocks.isEmpty()) {
                Text("No blocks yet.", style = Blake.mono(10f), color = Blake.faint)
            } else {
                blocks.take(20).forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).blakeCard(12.dp)
                        .clickableNoRipple { selectedBlock = b }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("#${b.height}", style = Blake.mono(13f, FontWeight.ExtraBold), color = Blake.pp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(b.stratum?.uppercase() ?: "—", style = Blake.mono(9f, FontWeight.ExtraBold),
                                    color = stratumColor(b.stratum, tip), letterSpacing = 0.5.sp)
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

    selectedBlock?.let { b -> BlockDetailDialog(b, tip) { selectedBlock = null } }
}

/** Colour each block by the stratum that found it. The flagship (primary for the current era —
 *  LOTTO before block 970000, CAROUSEL after) is purple; the others keep a stable accent, so the
 *  list re-colours itself automatically once the timechain crosses the switch. */
private fun stratumColor(s: String?, tip: Int): androidx.compose.ui.graphics.Color {
    if (s != null && s.lowercase() == BlakeFork.primaryStratum(tip)) return Blake.pp
    return when (s?.lowercase()) {
        "chirp" -> Blake.ok
        "carousel", "lotto" -> Blake.warn
        "datum" -> Blake.hero
        else -> Blake.faint
    }
}

/** Tap a mined block → its details + the COINBASE SPLIT (how the reward was divided across
 *  miners) once the pool reports it. Mirrors iOS BlockDetailView. */
@Composable
private fun BlockDetailDialog(b: BlakeApi.Block, tip: Int, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var detail by remember { mutableStateOf<BlakeApi.BlockDetail?>(null) }
    var loadingSplit by remember { mutableStateOf(true) }
    LaunchedEffect(b.height) { detail = BlakeApi.blockDetail(b.height); loadingSplit = false }
    val confs = if (tip > 0) maxOf(0, tip - b.height + 1) else 0
    val isPrimary = b.stratum?.lowercase() == BlakeFork.primaryStratum(tip)
    val accent = stratumColor(b.stratum, tip)

    sheetBox("BLOCK #${b.height}", accent, onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(b.stratum?.uppercase() ?: "—", style = Blake.mono(10f, FontWeight.ExtraBold), color = accent, letterSpacing = 1.sp)
            if (isPrimary) { Spacer(Modifier.size(6.dp)); Text("· flagship", style = Blake.mono(8f), color = Blake.faint) }
        }
        Spacer(Modifier.height(12.dp))
        kv("REWARD", "${b.reward?.let { "%.8f".format(it) } ?: "—"} ${Blake.RUNE}", Blake.pp)
        kv("FINDER", b.finderMasked ?: "—", Blake.fg)
        kv("CONFIRMATIONS", if (confs > 0) "$confs" else "—", Blake.fg)
        kv("DIFFICULTY", b.difficulty?.let { fmtDiff(it) } ?: "—", Blake.fg)
        b.protocolName?.let { kv("PROTOCOL", it, Blake.fg) }
        kv("TIME", b.timestamp?.let { relTime(it) } ?: "—", Blake.fg)
        Spacer(Modifier.height(10.dp))
        Text("HASH", style = Blake.mono(8f), color = Blake.faint, letterSpacing = 1.sp)
        Text(b.hash, style = Blake.mono(9f), color = Blake.pp, modifier = Modifier.clickableNoRipple {
            clip.setText(androidx.compose.ui.text.AnnotatedString(b.hash))
            android.widget.Toast.makeText(ctx, "Hash copied", android.widget.Toast.LENGTH_SHORT).show()
        })
        Spacer(Modifier.height(14.dp))
        Text("COINBASE SPLIT", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        val outs = detail?.coinbase
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
            Text("${outs.size} output(s) · shared coinbase", style = Blake.mono(8f), color = Blake.faint)
        } else if (loadingSplit) {
            Text("Loading split…", style = Blake.mono(9f), color = Blake.faint)
        } else {
            Text("The pool hasn't published this block's coinbase breakdown yet.", style = Blake.mono(9f), color = Blake.faint)
        }
        Spacer(Modifier.height(14.dp))
        sheetBtn("CLOSE", Blake.ppDim) { onClose() }
    }
}

private fun fmtDiff(d: Double): String = when {
    d >= 1e12 -> "%.2fT".format(d / 1e12)
    d >= 1e9 -> "%.2fG".format(d / 1e9)
    d >= 1e6 -> "%.2fM".format(d / 1e6)
    else -> "%.0f".format(d)
}
private fun relTime(ts: Double): String {
    val s = System.currentTimeMillis() / 1000.0 - ts
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${(s / 60).toInt()}m ago"
        s < 86400 -> "${(s / 3600).toInt()}h ago"
        else -> "${(s / 86400).toInt()}d ago"
    }
}

private fun hashrate(th: Double?): String {
    th ?: return "—"
    return if (th >= 1000) "%.2f PH/s".format(th / 1000) else "%.1f TH/s".format(th)
}
