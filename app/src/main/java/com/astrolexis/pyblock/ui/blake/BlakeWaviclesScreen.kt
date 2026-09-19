package com.astrolexis.pyblock.ui.blake

import com.astrolexis.pyblock.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.blake.BlakeApi
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** WAVICLES — PyBLØCK's DATUM BLAKE2b pool (bring your own node). Mirrors iOS WaviclesView +
 *  BlakeChirpScreen: KPIs, the live "if a block is found right now" window (TIDES), carry,
 *  how-it-works copy, and the connect gateway config. Read-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlakeWaviclesScreen() {
    var stats by remember { mutableStateOf<BlakeApi.WaviclesStats?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val load: suspend () -> Unit = {
        (BlakeApi.wavicles() ?: BlakeApi.wavicles())?.let { stats = it }
        loaded = true
    }
    LaunchedEffect(Unit) { while (true) { load(); delay(20_000) } }

    val offline = loaded && (stats == null || stats?.ok == false)
    val w = stats?.window
    val gatewayJson = run {
        val d = stats?.pool?.datum
        "{\"pool_host\":\"${d?.host ?: "b.pyblock.xyz"}\",\"pool_port\":${d?.port ?: 28915}," +
            "\"pool_pubkey\":\"${d?.pubkey ?: "<pull to refresh>"}\"," +
            "\"pool_pass_workers\":true,\"pool_pass_full_users\":true,\"pooled_mining_only\":true}"
    }

    Box(Modifier.fillMaxSize().background(Blake.bg)) {
      PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
          scope.launch { refreshing = true; load(); refreshing = false }
      }, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DagazRune(24.dp, Blake.wave)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.blk_wavicles), style = Blake.mono(24f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f))
                BlakeTierBadge()
            }
            Spacer(Modifier.height(6.dp))
            // Both fees come from the server: one rate when the block comes from your own node
            // (DATUM), another when it comes through PyBLØCK's stratum. This line used to claim the
            // first for every case, and before that it fell back to two literals — so a pool that
            // was slow or offline still printed two confident percentages nobody had checked. The
            // split has moved three times in two days. When we don't know it, we say nothing.
            val ownPct = stats?.pool?.feeBps?.let { it / 100.0 }
            val stratumPct = stats?.pool?.stratumFeeBps?.let { it / 100.0 }
            Text(
                if (ownPct != null && stratumPct != null)
                    stringResource(R.string.blk_datum_bring_your_own_node_fee_via_our_st, wpct(ownPct), wpct(stratumPct))
                else stringResource(R.string.blk_datum_bring_your_own_node),
                style = Blake.mono(10f), color = Blake.ppDim)
            Spacer(Modifier.height(22.dp))
            if (!loaded) { Text(stringResource(R.string.blk_loading), style = Blake.mono(10f), color = Blake.wave); Spacer(Modifier.height(14.dp)) }
            else if (offline) { Text(stringResource(R.string.blk_pool_offline_pull_to_retry), style = Blake.mono(10f), color = Blake.danger); Spacer(Modifier.height(14.dp)) }

            // KPIs
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Row(Modifier.fillMaxWidth()) {
                    BlakeStat(hrGhs(stats?.hashrate?.poolGhs), stringResource(R.string.blk_pool_hashrate), Blake.wave)
                    Spacer(Modifier.weight(1f))
                    BlakeStat("${stats?.gateways ?: 0}", stringResource(R.string.blk_gateways), Blake.fg, alignEnd = true)
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    BlakeStat("${w?.identities ?: 0}", stringResource(R.string.blk_miners_in_window), Blake.fg)
                    Spacer(Modifier.weight(1f))
                    BlakeStat("${stats?.blocks?.size ?: 0}", stringResource(R.string.blk_blocks_found), Blake.ppDim, alignEnd = true)
                }
            }

            // "If a block is found right now"
            Spacer(Modifier.height(22.dp))
            GatewaysCard(stats?.clients.orEmpty().map { GatewayRow.of(it) }, accent = Blake.wave, primeHashrateGhs = stats?.hashrate?.poolGhs, allDatum = true)
            Spacer(Modifier.height(22.dp))
            // The blocks WAVICLES found, Ehwaz when the finder's own gateway built them. Until the feed
            // says so itself (asked for), the finder is matched against the window's DATUM identities.
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text(stringResource(R.string.blk_blocks_found_on_wavicles), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                val datumIds = (stats?.clients.orEmpty().filter { it.onDatum }.mapNotNull { it.identity } + w?.miners.orEmpty().filter { it.onDatum }.mapNotNull { it.identity }).toSet()
                val list = stats?.blocks.orEmpty().sortedByDescending { it.height ?: 0 }
                if (list.isEmpty()) Text(stringResource(R.string.blk_no_blocks_yet), style = Blake.mono(9f), color = Blake.faint)
                list.take(8).forEach { b ->
                    val finder = b.finder ?: "—"
                    val datum = b.via?.let { it == "datum" } ?: (com.astrolexis.pyblock.data.blake.BlakeMiner.masked(finder) in datumIds)
                    FoundBlockRow(b.height ?: 0, com.astrolexis.pyblock.data.blake.BlakeMiner.masked(finder),
                        b.coinbaseValue?.let { "${Blake.btc(maxOf(0L, it))} ${Blake.RUNE}" } ?: "—",
                        b.ts?.let { relTimeSecs(ctx, it.toLong()) } ?: "", datum, b.gatewayName)
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.blk_built_by_the_finder_s_own_gateway), style = Blake.mono(7f), color = Blake.faint)
            }
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text(stringResource(R.string.blk_if_a_block_is_found_right_now), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.ppDim,
                    letterSpacing = 1.5.sp, maxLines = 1, softWrap = false)
                Spacer(Modifier.height(12.dp))
                val value = w?.sampleValue ?: 0L
                val fee = w?.sampleFeeSats ?: 0L
                kvRow("BLOCK VALUE", "${Blake.btc(value)} ${Blake.RUNE}", Blake.fg)
                kvRow("TO THE WINDOW", "${Blake.btc(maxOf(0L, value - fee))} ${Blake.RUNE}", Blake.wave)
                // Measured from THIS sample rather than assumed: the blend of the two fee paths moves.
                kvRow(if (value > 0) "POOL FEE (${String.format(java.util.Locale.US, "%.2f%%", fee * 100.0 / value)})" else "POOL FEE",
                    stringResource(R.string.blk_sats_2, fee), Blake.faint)
                Spacer(Modifier.height(8.dp))
                val fill = (w?.fillPercent ?: 0.0).coerceIn(0.0, 1.0)
                Row(Modifier.fillMaxWidth().height(10.dp).background(Blake.line)) {
                    Box(Modifier.fillMaxHeight().weight(fill.toFloat().coerceAtLeast(0.0001f)).background(Blake.wave))
                    Box(Modifier.fillMaxHeight().weight((1f - fill.toFloat()).coerceAtLeast(0.0001f)))
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.blk_window_full_8_network_difficulty_tides, "%.0f".format(fill * 100)),
                    style = Blake.mono(8f), color = Blake.faint)
                Spacer(Modifier.height(8.dp))
                // The table is keyed by the stratum username, and some rigs are pointed here with a
                // machine name ("sc184", "HS-BOX…") instead of a payout address. Those can never be
                // paid, and mixed into the payout table they read as peers earning nothing. Show
                // only identities that could receive a coinbase, and count the rest in one line.
                val allMiners = w?.miners ?: emptyList()
                val miners = allMiners.filter { isPayoutIdentity(it.identity) }
                val unpayable = allMiners.size - miners.size
                if (miners.isEmpty()) {
                    Text(stringResource(R.string.blk_window_empty_the_first_block_pays_the_po),
                        style = Blake.mono(8f), color = Blake.faint)
                } else {
                    miners.forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).background(if ((m.lastShareS ?: 999) < 120) Blake.ok else Blake.faint, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            if (m.onDatum) { RuneGlyph(Rune.EHWAZ, ink = Blake.datum, size = 11.dp); Spacer(Modifier.width(6.dp)) }   // its own node builds the block
                            Column(Modifier.weight(1f)) {
                                Text(m.identity ?: "anon", style = Blake.mono(9f), color = if (m.onDatum) Blake.datum else Blake.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                m.lastShareS?.let { Text(stringResource(R.string.blk_last_share_s_ago, it), style = Blake.mono(7f), color = Blake.faint) }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${Blake.btc(maxOf(0L, m.payoutSats ?: 0L))} ${Blake.RUNE}",
                                    style = Blake.mono(9f, FontWeight.ExtraBold), color = if (m.payable == false) Blake.faint else Blake.wave)
                                Text(if (m.payable == false) stringResource(R.string.blk_below_min_payout) else "%.1f%%".format(m.sharePercent ?: 0.0),
                                    style = Blake.mono(7f), color = Blake.faint)
                            }
                        }
                    }
                    if (unpayable > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.blk_miners_hidden_their_stratum_username_isn, unpayable),
                            style = Blake.mono(7f), color = Blake.faint)
                    }
                }
            }

            // The pool used to carry dust a coinbase couldn't place and pay it in the next one. That
            // rule is OFF (carry_forward: false): what doesn't fit isn't owed — it stays in the split
            // as BelowCut, visible in the snapshot committed to the OP_RETURN. So this no longer says
            // "Owed to miners", which read as if we were holding somebody's coins.
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text(stringResource(R.string.blk_proof), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                Spacer(Modifier.height(6.dp))
                val carry = stats?.wavicles?.carryTotalSats ?: 0L
                if (stats?.wavicles?.carryForward == true && carry > 0) {
                    Text(stringResource(R.string.blk_legacy_carry_from_the_earlier_rule_sats, carry),
                        style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.warn)
                }
                Text(stringResource(R.string.blk_nothing_is_held_back_what_a_coinbase_can),
                    style = Blake.mono(8f), color = Blake.faint)
                stats?.wavicles?.lastSnapshot?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.blk_last_snapshot, it.take(10)), style = Blake.mono(8f), color = Blake.wave,
                        modifier = Modifier.clickableNoRipple { clip.setText(AnnotatedString(it)) })
                }
            }

            // How it works
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text(stringResource(R.string.blk_how_it_works), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                Spacer(Modifier.height(8.dp))
                val own = stats?.pool?.feeBps?.let { it / 100.0 }
                val strat = stats?.pool?.stratumFeeBps?.let { it / 100.0 }
                Text(
                    if (own != null && strat != null)
                        stringResource(R.string.blk_of_every_block_goes_to_the_work_window_s, wpct(100 - own), wpct(own), wpct(strat))
                    else stringResource(R.string.blk_every_block_goes_to_the_work_window_spli),
                    style = Blake.mono(9f), color = Blake.fg)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.blk_not_solo_every_block_found_by_anyone_in_),
                    style = Blake.mono(9f), color = Blake.wave)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.blk_trustless_each_coinbase_commits_blake2b_),
                    style = Blake.mono(8f), color = Blake.faint)
            }

            // Connect
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text(stringResource(R.string.blk_connect), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.blk_not_a_stratum_url_point_your_datum_gatew),
                    style = Blake.mono(9f), color = Blake.faint)
                Spacer(Modifier.height(8.dp))
                Text(gatewayJson, style = Blake.mono(9f), color = Blake.wave,
                    modifier = Modifier.fillMaxWidth().border(1.dp, Blake.line, RectangleShape).padding(10.dp))
                Spacer(Modifier.height(8.dp))
                Text(if (copied) stringResource(R.string.blk_copied_4) else stringResource(R.string.blk_tap_to_copy_config), style = Blake.mono(10f, FontWeight.ExtraBold),
                    color = if (copied) Blake.ok else Blake.wave, letterSpacing = 1.sp,
                    modifier = Modifier.fillMaxWidth().border(1.dp, if (copied) Blake.ok else Blake.wave, RectangleShape).padding(vertical = 9.dp)
                        .clickableNoRipple { clip.setText(AnnotatedString(gatewayJson)); copied = true })
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.blk_set_mining_pool_address_to_your_blake2b_),
                    style = Blake.mono(8f), color = Blake.faint)
                Spacer(Modifier.height(6.dp))
                Text("If your gateway logs \"decryption failed\", the pool_pubkey is truncated — copy the full key.",
                    style = Blake.mono(8f), color = Blake.warn)
            }
            Spacer(Modifier.height(24.dp))
        }
      }
    }
}

@Composable
private fun kvRow(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text(value, style = Blake.mono(12f, FontWeight.ExtraBold), color = color)
    }
}

/** The WAVICLES rune ᛞ (Dagaz — day↔night duality → wave↔particle) drawn as a vector: two
 *  vertical staves joined by a crossing X. Our own mark, no emoji. Mirrors iOS BlakeDagaz. */
@Composable
fun DagazRune(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val lw = (this.size.minDimension * 0.13f).coerceAtLeast(2f)
        val x0 = lw / 2f; val x1 = this.size.width - lw / 2f
        val h = this.size.height
        drawLine(color, Offset(x0, 0f), Offset(x0, h), lw, StrokeCap.Round)   // left stave
        drawLine(color, Offset(x1, 0f), Offset(x1, h), lw, StrokeCap.Round)   // right stave
        drawLine(color, Offset(x0, 0f), Offset(x1, h), lw, StrokeCap.Round)   // ╲
        drawLine(color, Offset(x0, h), Offset(x1, 0f), lw, StrokeCap.Round)   // ╱
    }
}

private fun hrGhs(ghs: Double?): String {
    ghs ?: return "—"
    val ths = ghs / 1000
    return when { ths >= 1000 -> "%.2f PH/s".format(ths / 1000); ths >= 1 -> "%.1f TH/s".format(ths); else -> "%.0f GH/s".format(ghs) }
}

/** Could this identity ever receive a coinbase payment? Mainnet P2PKH/P2SH/bech32 only — the same
 *  shape the send screen accepts. Anything else is a rig name typed into the stratum username.
 *  A worker suffix ("addr.rig1") is normal and still pays the address before the dot. */
private fun isPayoutIdentity(raw: String?): Boolean {
    val a = raw?.trim() ?: return false
    if (a.length < 26) return false
    val base = a.substringBefore('.')
    if (base.length < 26) return false
    return when {
        base.startsWith("1") || base.startsWith("3") -> base.length <= 35
        base.lowercase().startsWith("bc1") -> base.length <= 62
        else -> false
    }
}

/** "0.4%" / "3%" — no trailing zero when it's whole. */
private fun wpct(v: Double): String =
    if (v == Math.rint(v)) String.format(java.util.Locale.US, "%.0f%%", v)
    else String.format(java.util.Locale.US, "%.1f%%", v)
