package com.astrolexis.pyblock.ui.blake

import com.astrolexis.pyblock.R
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.blake.BlakeApi
import com.astrolexis.pyblock.data.blake.BlakeMiner
import com.astrolexis.pyblock.data.blake.BlakeRentals
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** CAROUSEL — rotating block templates. Mirrors the website's carousel page and iOS CarouselView:
 *  the lap (who is mined now, the next turns, the rhythm), where your own template stands, the
 *  blocks the rotation found, and how to connect — including CAROUSEL over DATUM (your own
 *  gateway). Read-only; every number comes from the server, the split included. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BlakeCarouselScreen() {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var lap by remember { mutableStateOf<BlakeApi.CarouselLap?>(null) }
    var me by remember { mutableStateOf<BlakeApi.CarouselMe?>(null) }
    var split by remember { mutableStateOf<BlakeApi.SplitProduct?>(null) }
    var prime by remember { mutableStateOf<BlakeApi.PrimeInfo?>(null) }
    var stats by remember { mutableStateOf<BlakeApi.PoolStats?>(null) }
    var blocks by remember { mutableStateOf<List<BlakeApi.Block>>(emptyList()) }
    var connect by remember { mutableStateOf<BlakeMiner.Connect?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    // The address MINER is keyed on: the template a node-runner publishes is paid to it.
    val myAddress = remember { ctx.getSharedPreferences("pyblockb.miner", android.content.Context.MODE_PRIVATE).getString("address", "") ?: "" }

    val load: suspend () -> Unit = {
        BlakeApi.carouselLap()?.let { lap = it }
        BlakeApi.split("carousel")?.let { split = it }
        BlakeApi.prime("carousel")?.let { prime = it }
        BlakeApi.poolStats()?.let { stats = it }
        BlakeApi.blocks().takeIf { it.isNotEmpty() }?.let { bl -> blocks = bl.filter { (it.stratum?.lowercase() ?: "") in setOf("carousel", "carousel_prime") } }
        if (connect == null) connect = BlakeMiner.connect()
        if (myAddress.isNotEmpty()) BlakeApi.carouselMe(myAddress)?.let { me = it }
        loaded = true
    }
    LaunchedEffect(Unit) { while (true) { load(); delay(20_000) } }
    val offline = loaded && lap == null

    Box(Modifier.fillMaxSize().background(Blake.bg)) {
      PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RuneGlyph(Rune.RAIDHO, ink = Blake.pp, size = 22.dp); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.blk_carousel), style = Blake.mono(24f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f)); BlakeTierBadge()
            }
            // The split is the gateway's, read live; a number typed here would be wrong within a week.
            Text(listOfNotNull(stringResource(R.string.blk_rotating_block_templates), split?.label).joinToString(" · "), style = Blake.mono(10f), color = Blake.ppDim)
            if (!loaded) Text(stringResource(R.string.blk_loading), style = Blake.mono(10f), color = Blake.pp)
            else if (offline) Text(stringResource(R.string.blk_can_t_reach_the_server_pull_to_retry), style = Blake.mono(10f), color = Blake.danger)

            // KPIs
            Spacer(Modifier.height(22.dp))
            val hr = (stats?.hashrateByStratum ?: emptyMap()).filterKeys { it in setOf("carousel", "lotto", "lotto_asic") }.values.sum()
            val hrText = if (hr > 0) BlakeRentals.th(hr) else lap?.hashrate?.let { BlakeRentals.th(it / 1e12) } ?: "—"
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Row(Modifier.fillMaxWidth()) {
                    BlakeStat(hrText, stringResource(R.string.blk_carousel_hashrate))
                    Spacer(Modifier.weight(1f))
                    BlakeStat(lap?.miners?.toString() ?: "—", stringResource(R.string.blk_miners), Blake.fg, alignEnd = true)
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    BlakeStat("${blocks.size}", stringResource(R.string.blk_blocks_found), Blake.fg)
                    Spacer(Modifier.weight(1f))
                    BlakeStat(lap?.ringN?.toString() ?: "—", stringResource(R.string.blk_templates_in_the_lap), Blake.ppDim, alignEnd = true)
                }
            }

            // THE LAP
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if (lap?.live == true) Blake.ok else Blake.faint, CircleShape)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.blk_the_lap), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                    Spacer(Modifier.weight(1f))
                    val n = lap?.ringN; val c = lap?.cycleS; val l = lap?.lapS
                    if (n != null && c != null && l != null)
                        Text(stringResource(R.string.blk_templates_a_turn_every_s_a_lap, n.toString(), c.toString(), dur(l)), style = Blake.mono(7f), color = Blake.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.blk_mining_now), style = Blake.mono(8f), color = Blake.faint, letterSpacing = 2.sp)
                Text(lap?.current ?: "—", style = Blake.mono(16f, FontWeight.ExtraBold), color = Blake.pp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val next = lap?.next.orEmpty()
                if (next.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.blk_next_up), style = Blake.mono(8f), color = Blake.faint, letterSpacing = 2.sp)
                    next.take(7).forEachIndexed { i, nx ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ordinal(ctx, i + 1), style = Blake.mono(8f, FontWeight.ExtraBold), color = Blake.ppDim, modifier = Modifier.width(30.dp))
                            Text(nx.name ?: "—", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.weight(1f))
                            Text(mid(nx.addr ?: ""), style = Blake.mono(8f), color = Blake.faint)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.blk_nobody_picks_this_order_it_comes_out_of_), style = Blake.mono(7f), color = Blake.faint)
            }

            // YOUR TEMPLATE
            me?.takeIf { it.inRing == true }?.let { m ->
                Spacer(Modifier.height(22.dp))
                Column(Modifier.fillMaxWidth().blakeCard()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(if (m.miningNow == true) Blake.ok else Blake.faint, CircleShape)); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.blk_your_template), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                        Spacer(Modifier.weight(1f))
                        if (m.miningNow == true) Text(stringResource(R.string.blk_mining_now), style = Blake.mono(8f, FontWeight.ExtraBold), color = Blake.ok, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        BlakeStat(m.position?.toString() ?: "—", stringResource(R.string.blk_position_in_the_lap))
                        Spacer(Modifier.weight(1f))
                        BlakeStat(m.turnsAhead?.toString() ?: "—", stringResource(R.string.blk_turns_ahead), Blake.fg)
                        Spacer(Modifier.weight(1f))
                        BlakeStat(m.etaS?.let { dur(it) } ?: "—", stringResource(R.string.blk_your_turn_in), Blake.ppDim, alignEnd = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.blk_picks_24h), style = Blake.mono(8f), color = Blake.faint, letterSpacing = 1.sp); Spacer(Modifier.width(6.dp))
                        Text(m.picks24h?.toString() ?: "—", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg)
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.blk_blocks_on_your_template), style = Blake.mono(8f), color = Blake.faint, letterSpacing = 1.sp); Spacer(Modifier.width(6.dp))
                        Text(m.blocks?.toString() ?: "—", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.blk_read_for_the_address_miner_is_set_to_pub), style = Blake.mono(7f), color = Blake.faint)
                }
            }

            // GATEWAYS
            Spacer(Modifier.height(22.dp))
            GatewaysCard(prime?.runners.orEmpty().map { GatewayRow.of(it) }, accent = Blake.pp, registered = prime?.nodeRunnersRegistered, primeHashrateGhs = prime?.hashrateGhs)

            // BLOCKS
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text(stringResource(R.string.blk_blocks_found_on_the_carousel), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                if (blocks.isEmpty()) Text(stringResource(R.string.blk_no_blocks_yet), style = Blake.mono(9f), color = Blake.faint)
                blocks.take(8).forEach { b ->
                    FoundBlockRow(b.height, b.finderMasked ?: "—", b.reward?.let { "%.4f ${Blake.RUNE}".format(java.util.Locale.US, it) } ?: "—",
                        b.timestamp?.let { relTimeSecs(ctx, it.toLong()) } ?: "", b.builtOnDatum, b.gatewayName)
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.blk_built_by_the_finder_s_own_gateway), style = Blake.mono(7f), color = Blake.faint)
            }

            // CONNECT
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text(stringResource(R.string.blk_connect), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                Spacer(Modifier.height(8.dp))
                val host = connect?.host ?: "b.pyblock.xyz"
                connect?.pools.orEmpty().filter { it.pool == "carousel" }.forEach { p ->
                    val target = "$host:${p.port ?: 0}"
                    Row(Modifier.fillMaxWidth().hairline().clickableNoRipple {
                        Haptics.tap(); clip.setText(AnnotatedString(target)); copied = target
                        scope.launch { delay(1500); if (copied == target) copied = null }
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.label ?: "CAROUSEL", style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp)
                                Spacer(Modifier.width(6.dp)); Text((p.kind ?: "").uppercase(), style = Blake.mono(7f), color = Blake.faint)
                            }
                            p.split?.let { Text(it, style = Blake.mono(7f), color = Blake.faint) }
                        }
                        Text(if (copied == target) stringResource(R.string.blk_copied_2) else target, style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.blk_username_your_blake2b_address_password_x), style = Blake.mono(7f), color = Blake.faint)
                // CAROUSEL over DATUM: the block is built and published by the miner's own node.
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    RuneGlyph(Rune.EHWAZ, ink = Blake.datum, size = 14.dp); Spacer(Modifier.width(8.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.blk_carousel_over_datum), style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.datum, letterSpacing = 1.sp)
                            prime?.connect?.port?.let { Spacer(Modifier.width(6.dp)); Text(":$it", style = Blake.mono(9f), color = Blake.faint) }
                        }
                        prime?.split?.let { s ->
                            // The middle clause only when someone other than the miner is paid for the node.
                            val parts = listOfNotNull(
                                s.minersBps?.let { stringResource(R.string.blk_you, pct(it)) },
                                s.nodeRunnerBps?.takeIf { it > 0 }?.let { stringResource(R.string.blk_node_runner, pct(it)) },
                                s.poolBps?.let { stringResource(R.string.blk_pybl_ck, pct(it)) })
                            Text(parts.joinToString(" · "), style = Blake.mono(8f), color = Blake.ppDim)
                        }
                        Text(stringResource(R.string.blk_your_node_builds_and_publishes_the_block_2), style = Blake.mono(7f), color = Blake.faint)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
      }
    }
}

private fun pct(bps: Int): String { val v = bps / 100.0; return if (v == Math.floor(v)) "%.0f".format(java.util.Locale.US, v) else "%.1f".format(java.util.Locale.US, v) }
private fun dur(s: Int): String = when {
    s < 3600 -> "${maxOf(1, s / 60)}m"
    s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"
    else -> "${s / 86400}d ${(s % 86400) / 3600}h"
}
private fun mid(s: String): String = if (s.length > 14) "${s.take(6)}…${s.takeLast(5)}" else s
private fun ordinal(ctx: android.content.Context, n: Int): String = when (n) {
    1 -> ctx.getString(R.string.blk_s_1st); 2 -> ctx.getString(R.string.blk_s_2nd); 3 -> ctx.getString(R.string.blk_s_3rd)
    else -> ctx.getString(R.string.blk_th, n.toString())
}
