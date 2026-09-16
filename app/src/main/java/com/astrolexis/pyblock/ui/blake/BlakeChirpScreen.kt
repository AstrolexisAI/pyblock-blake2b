package com.astrolexis.pyblock.ui.blake

import com.astrolexis.pyblock.data.blake.BlakeMiner
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
    var miners by remember { mutableStateOf<List<BlakeApi.ChirpMiner>>(emptyList()) }
    var prime by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var showParticipants by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Keep last-good on a transient failure (never blanks stats/participants), one retry each.
    val load: suspend () -> Unit = {
        (BlakeApi.chirpPool() ?: BlakeApi.chirpPool())?.let { pool = it }
        (BlakeApi.chirpWorkers() ?: BlakeApi.chirpWorkers())?.let { workers = it }
        BlakeApi.chirpMiners()?.let { miners = it }
        BlakeApi.chirpPrimeRunners()?.let { prime = it }
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
                    stringResource(R.string.blk_syndicate_to_members_by_weight_node_runn, pctText(sp.syndicate), pctText(sp.supplier ?: 0.0), pctText(sp.pool ?: 0.0))
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
            // Everyone mining now — on the house stratum or on CHIRP-PRIME — in the draw's order:
            // weight (tenure + power), then tenure, then power. The list used to be newest-share
            // first, which said nothing about who stands where in the syndicate.
            val reg = miners.mapNotNull { m -> m.address?.let { it to m } }.toMap()
            val online: List<Participant> = run {
                val seen = HashSet<String>(); val out = ArrayList<Participant>()
                workers.forEach { w ->
                    val name = w.name?.takeIf { it.isNotEmpty() } ?: return@forEach
                    seen.add(name)
                    val m = reg[name]; val pr = prime[BlakeMiner.masked(name)]
                    val live = (pr ?: Int.MAX_VALUE) <= 600
                    if (!w.connected && !live) return@forEach
                    out.add(Participant(name, w.hashrateThs, m?.days, m?.weight ?: w.share ?: 0.0,
                        w.eligible ?: m?.eligible ?: ((w.hashrateThs ?: 0.0) * 1_000_000.0 >= (pool?.minPower ?: 0.0)), pr != null, live, w.connected))
                }
                // On the Prime but not on the house stratum: the registry knows them (shared database).
                miners.forEach { m ->
                    val a = m.address ?: return@forEach
                    if (a in seen) return@forEach
                    val pr = prime[BlakeMiner.masked(a)] ?: return@forEach
                    if (pr <= 600) out.add(Participant(a, null, m.days, m.weight ?: 0.0, m.eligible ?: false, true, true, false))
                }
                out.sortedWith(compareByDescending<Participant> { it.weight }.thenByDescending { it.days ?: 0.0 }.thenByDescending { it.hashrateThs ?: 0.0 })
            }

            // BLOCK PARTICIPATION — eligible miners each hold a slice of the next block's
            // weighted reward split (white paper), sized by contribution. Eligibility is
            // server-authoritative when present, else approximated by the min-power floor.
            val minPowerMhs = pool?.minPower ?: 0.0
            fun weightOf(w: Participant): Double = w.weight
            // The bar is ELIGIBLE miners only — those actually in the weighted reward split.
            // Miners that are connected but not yet eligible do NOT appear here.
            val eligible = online.filter { it.eligible && it.weight > 0 }
            val totalW = eligible.sumOf { it.weight }
            if (online.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Column(Modifier.fillMaxWidth().blakeCard()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.blk_block_participation), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.ppDim,
                            letterSpacing = 1.5.sp, maxLines = 1, softWrap = false)
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.blk_eligible, eligible.size), style = Blake.mono(9f), color = Blake.pp, maxLines = 1, softWrap = false)
                    }
                    if (eligible.isNotEmpty() && totalW > 0) {
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth().height(16.dp)) {
                            eligible.forEachIndexed { i, w ->
                                Box(Modifier.weight((w.weight / totalW).toFloat()).fillMaxHeight().background(participationColor(i)))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.blk_largest_slice_each_eligible_miner_shares, "%.0f".format(weightOf(eligible.first()) / totalW * 100)),
                            style = Blake.mono(8f), color = Blake.faint)
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text(pool?.minDays?.let { stringResource(R.string.blk_no_miners_are_eligible_for_the_split_yet, it, online.size) }
                            ?: stringResource(R.string.blk_no_miners_are_eligible_for_the_split_yet_2, online.size),
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
                        Text(stringResource(R.string.blk_by_weight_tenure_power_on_chirp_prime_ow), style = Blake.mono(7f), color = Blake.faint)
                        Spacer(Modifier.height(4.dp))
                        online.forEach { w ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(5.dp).background(if (w.connected || w.primeLive) Blake.ok else Blake.faint, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                // The mark of running your own gateway: their node speaks the block.
                                if (w.onPrime) { AnsuzRune(11.dp, Blake.datum); Spacer(Modifier.width(4.dp)) }
                                Text(w.name, style = Blake.mono(10f), color = if (w.eligible) Blake.fg else Blake.ppDim,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                w.days?.let { Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.blk_d, it.toInt().toString()), style = Blake.mono(8f), color = Blake.faint, maxLines = 1, softWrap = false) }
                                Spacer(Modifier.width(8.dp))
                                Text(w.hashrateThs?.let { hr(it) } ?: "·", style = Blake.mono(10f), color = Blake.ppDim, maxLines = 1, softWrap = false)
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

/** One row of the syndicate: the house stratum's view (hashrate, connected), the registry's
 *  (tenure, weight, eligible) and the Prime's (on their own gateway), joined by address. */
private data class Participant(val name: String, val hashrateThs: Double?, val days: Double?, val weight: Double,
                               val eligible: Boolean, val onPrime: Boolean, val primeLive: Boolean, val connected: Boolean)
