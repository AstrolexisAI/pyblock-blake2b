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
    val scope = rememberCoroutineScope()

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
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).blakeCard(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("#${b.height}", style = Blake.mono(13f, FontWeight.ExtraBold), color = Blake.pp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(b.stratum?.uppercase() ?: "—", style = Blake.mono(9f, FontWeight.ExtraBold),
                                    color = stratumColor(b.stratum), letterSpacing = 0.5.sp)
                                Text(" · ${b.finderMasked ?: "—"}", style = Blake.mono(9f), color = Blake.faint, maxLines = 1)
                            }
                        }
                        Text(b.reward?.let { "%.4f".format(it) } ?: "—", style = Blake.mono(12f), color = Blake.fg)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
      }
    }
}

/** Colour each block by the stratum that found it, so the mix reads at a glance. */
private fun stratumColor(s: String?): androidx.compose.ui.graphics.Color = when (s?.lowercase()) {
    "lotto" -> Blake.pp
    "chirp" -> Blake.ok
    "carousel" -> Blake.warn
    "datum" -> Blake.hero
    else -> Blake.faint
}

private fun hashrate(th: Double?): String {
    th ?: return "—"
    return if (th >= 1000) "%.2f PH/s".format(th / 1000) else "%.1f TH/s".format(th)
}
