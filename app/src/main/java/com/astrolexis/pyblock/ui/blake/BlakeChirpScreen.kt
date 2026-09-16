package com.astrolexis.pyblock.ui.blake

import com.astrolexis.pyblock.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.rememberCoroutineScope
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.blake.BlakeApi
import kotlinx.coroutines.delay

/** CHIRP — the syndicate: weighted split, loyalty, eligibility + connect info.
 *  Mirrors iOS ChirpView. Sober, purple, flat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlakeChirpScreen() {
    var pool by remember { mutableStateOf<BlakeApi.ChirpPool?>(null) }
    var workers by remember { mutableStateOf<List<BlakeApi.ChirpWorker>>(emptyList()) }
    var showParticipants by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Keep last-good on a transient failure (never blanks stats/participants), one retry each.
    val load: suspend () -> Unit = {
        (BlakeApi.chirpPool() ?: BlakeApi.chirpPool())?.let { pool = it }
        (BlakeApi.chirpWorkers() ?: BlakeApi.chirpWorkers())?.let { workers = it }
        loaded = true
    }
    LaunchedEffect(Unit) { while (true) { load(); delay(20_000) } }

    Box(Modifier.fillMaxSize().background(Blake.bg)) {
      PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
          scope.launch { refreshing = true; load(); refreshing = false }
      }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.blk_chirp), style = Blake.mono(24f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 3.sp); Spacer(Modifier.weight(1f)); BlakeTierBadge() }
            Spacer(Modifier.height(6.dp))
            // From the gateway, never hardcoded: CHIRP is 1% + 1% on this chain, and this line used to
            // print the SHA-256 pool's 0.9%. No split from the server → say what it is, quote nothing.
            val sp = pool?.split?.pct
            Text(
                if (sp?.syndicate != null)
                    "Syndicate · ${pctText(sp.syndicate)} to members by weight · ${pctText(sp.supplier ?: 0.0)} node-runner · ${pctText(sp.pool ?: 0.0)} PyBLØCK"
                else stringResource(R.string.blk_syndicate_weighted_split),
                style = Blake.mono(10f), color = Blake.ppDim)

            Spacer(Modifier.height(22.dp))
            if (!loaded) { Text(stringResource(R.string.blk_loading), style = Blake.mono(10f), color = Blake.pp); Spacer(Modifier.height(14.dp)) }
            else if (pool == null) { Text(stringResource(R.string.blk_can_t_reach_the_server), style = Blake.mono(10f), color = Blake.danger); Spacer(Modifier.height(14.dp)) }

            Column(Modifier.fillMaxWidth().blakeCard()) {
                Row(Modifier.fillMaxWidth()) {
                    BlakeStat(hr(pool?.hashrate), stringResource(R.string.blk_syndicate_hashrate))
                    Spacer(Modifier.weight(1f))
                    BlakeStat("${pool?.workers ?: 0}", "workers", Blake.fg, alignEnd = true)
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    BlakeStat("${pool?.blocks ?: 0}", "blocks found", Blake.fg)
                    Spacer(Modifier.weight(1f))
                    BlakeStat("${pool?.candidates ?: 0}", "candidates", Blake.ppDim, alignEnd = true)
                }
            }

            // Connected miners, newest-active first (by last share, not by power).
            val online = workers.filter { it.connected }.sortedByDescending { it.lastShare ?: 0L }

            // BLOCK PARTICIPATION — eligible miners each hold a slice of the next block's
            // weighted reward split (white paper), sized by contribution. Eligibility is
            // server-authoritative when present, else approximated by the min-power floor.
            val minPowerMhs = pool?.minPower ?: 0.0
            fun eligibleOf(w: BlakeApi.ChirpWorker): Boolean =
                w.eligible ?: ((w.hashrateThs ?: 0.0) * 1_000_000.0 >= minPowerMhs)
            fun weightOf(w: BlakeApi.ChirpWorker): Double = w.share ?: (w.hashrateThs ?: 0.0)
            // The bar is ELIGIBLE miners only — those actually in the weighted reward split.
            // Miners that are connected but not yet eligible do NOT appear here.
            val eligible = online.filter { eligibleOf(it) && weightOf(it) > 0 }.sortedByDescending { weightOf(it) }
            val totalW = eligible.sumOf { weightOf(it) }
            if (online.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Column(Modifier.fillMaxWidth().blakeCard()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.blk_block_participation), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.ppDim,
                            letterSpacing = 1.5.sp, maxLines = 1, softWrap = false)
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text("${eligible.size} eligible", style = Blake.mono(9f), color = Blake.pp, maxLines = 1, softWrap = false)
                    }
                    if (eligible.isNotEmpty() && totalW > 0) {
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth().height(16.dp)) {
                            eligible.forEachIndexed { i, w ->
                                Box(Modifier.weight((weightOf(w) / totalW).toFloat()).fillMaxHeight().background(participationColor(i)))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Largest slice ${"%.0f".format(weightOf(eligible.first()) / totalW * 100)}% · each eligible miner shares the block reward in proportion to its contribution.",
                            style = Blake.mono(8f), color = Blake.faint)
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text(pool?.minDays?.let { "No miners are eligible for the split yet — it needs the $it-day loyalty floor. ${online.size} mining now; the bar fills in as they qualify." }
                            ?: "No miners are eligible for the split yet. ${online.size} mining now; the bar fills in as they qualify.",
                            style = Blake.mono(8f), color = Blake.faint)
                    }
                }
            }

            // PARTICIPANTS — connected miners only (disconnected hidden). Collapsible.
            if (online.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Column(Modifier.fillMaxWidth().blakeCard()) {
                    Row(Modifier.fillMaxWidth().clickableNoRipple { showParticipants = !showParticipants },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.blk_participants), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("${online.size}", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.pp)
                        Spacer(Modifier.weight(1f))
                        Text(if (showParticipants) "▲" else "▼", style = Blake.mono(9f), color = Blake.ppDim)
                    }
                    if (showParticipants) {
                        Spacer(Modifier.height(10.dp))
                        online.forEach { w ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(5.dp).background(Blake.ok, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(w.name ?: "anon", style = Blake.mono(10f), color = Blake.fg,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Text(hr(w.hashrateThs), style = Blake.mono(10f), color = Blake.ppDim, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text(stringResource(R.string.blk_eligibility), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                Spacer(Modifier.height(10.dp))
                rule(stringResource(R.string.blk_min_loyalty), pool?.minDays?.let { "$it days" } ?: "—")
                rule(stringResource(R.string.blk_min_power), powerStr(pool?.minPower))
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.blk_below_the_floor_you_still_mine_but_don_t),
                    style = Blake.mono(8f), color = Blake.faint)
            }

            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text(stringResource(R.string.blk_connect), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                Spacer(Modifier.height(10.dp))
                Text("pool.pyblock.xyz:5574", style = Blake.mono(13f), color = Blake.pp)
                Text(stringResource(R.string.blk_user_your_blake2b_address_pass_x), style = Blake.mono(9f), color = Blake.faint)
            }
            Spacer(Modifier.height(24.dp))
        }
      }
    }
}

@Composable
private fun rule(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text(value, style = Blake.mono(12f), color = Blake.fg)
    }
}

/** Sober purple palette for the participation-bar slices — cycles so adjacent slices differ. */
private val participationPalette = listOf(
    Color(0xFFB96BFF), Color(0xFF8F6FD0), Color(0xFF7A4FD0), Color(0xFFCBA6FF), Color(0xFF6A4A9A),
)
private fun participationColor(i: Int): Color = participationPalette[i % participationPalette.size]

private fun hr(h: Double?): String {
    h ?: return "—"
    return when { h >= 1000 -> "%.2f PH/s".format(h / 1000); h >= 1 -> "%.1f TH/s".format(h); else -> "%.0f GH/s".format(h * 1000) }
}
private fun powerStr(p: Double?): String {
    p ?: return "—"
    return if (p >= 1_000_000) "%.0f TH/s".format(p / 1_000_000) else "%.0f GH/s".format(p / 1000)
}

/** "1%" / "0.9%" — no trailing zero when it's whole. */
private fun pctText(v: Double): String =
    if (v == Math.rint(v)) String.format(java.util.Locale.US, "%.0f%%", v)
    else String.format(java.util.Locale.US, "%.1f%%", v)
