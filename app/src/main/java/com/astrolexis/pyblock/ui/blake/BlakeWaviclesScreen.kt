package com.astrolexis.pyblock.ui.blake

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
                Text("WAVICLES", style = Blake.mono(24f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 3.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("DATUM · bring your own node · 0.4% fee", style = Blake.mono(10f), color = Blake.ppDim)
            Spacer(Modifier.height(22.dp))
            if (!loaded) { Text("⟳ loading…", style = Blake.mono(10f), color = Blake.wave); Spacer(Modifier.height(14.dp)) }
            else if (offline) { Text("⚠ pool offline — pull to retry.", style = Blake.mono(10f), color = Blake.danger); Spacer(Modifier.height(14.dp)) }

            // KPIs
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Row(Modifier.fillMaxWidth()) {
                    BlakeStat(hrGhs(stats?.hashrate?.poolGhs), "pool hashrate", Blake.wave)
                    Spacer(Modifier.weight(1f))
                    BlakeStat("${stats?.gateways ?: 0}", "gateways", Blake.fg, alignEnd = true)
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    BlakeStat("${w?.identities ?: 0}", "miners in window", Blake.fg)
                    Spacer(Modifier.weight(1f))
                    BlakeStat("${stats?.blocks?.size ?: 0}", "blocks found", Blake.ppDim, alignEnd = true)
                }
            }

            // "If a block is found right now"
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text("IF A BLOCK IS FOUND RIGHT NOW", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.ppDim,
                    letterSpacing = 1.5.sp, maxLines = 1, softWrap = false)
                Spacer(Modifier.height(12.dp))
                val value = w?.sampleValue ?: 0L
                val fee = w?.sampleFeeSats ?: 0L
                kvRow("BLOCK VALUE", "${Blake.btc(value)} ${Blake.RUNE}", Blake.fg)
                kvRow("TO THE WINDOW", "${Blake.btc(maxOf(0L, value - fee))} ${Blake.RUNE}", Blake.wave)
                kvRow("POOL FEE (0.4%)", "$fee sats", Blake.faint)
                Spacer(Modifier.height(8.dp))
                val fill = (w?.fillPercent ?: 0.0).coerceIn(0.0, 1.0)
                Row(Modifier.fillMaxWidth().height(10.dp).background(Blake.line)) {
                    Box(Modifier.fillMaxHeight().weight(fill.toFloat().coerceAtLeast(0.0001f)).background(Blake.wave))
                    Box(Modifier.fillMaxHeight().weight((1f - fill.toFloat()).coerceAtLeast(0.0001f)))
                }
                Spacer(Modifier.height(4.dp))
                Text("window ${"%.0f".format(fill * 100)}% full · 8× network difficulty (TIDES)",
                    style = Blake.mono(8f), color = Blake.faint)
                Spacer(Modifier.height(8.dp))
                val miners = w?.miners ?: emptyList()
                if (miners.isEmpty()) {
                    Text("Window empty — the first block pays the pool until work is credited.",
                        style = Blake.mono(8f), color = Blake.faint)
                } else {
                    miners.forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).background(if ((m.lastShareS ?: 999) < 120) Blake.ok else Blake.faint, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.identity ?: "anon", style = Blake.mono(9f), color = Blake.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                m.lastShareS?.let { Text("last share ${it}s ago", style = Blake.mono(7f), color = Blake.faint) }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${Blake.btc(maxOf(0L, m.payoutSats ?: 0L))} ${Blake.RUNE}",
                                    style = Blake.mono(9f, FontWeight.ExtraBold), color = if (m.payable == false) Blake.faint else Blake.wave)
                                Text(if (m.payable == false) "below min payout" else "%.1f%%".format(m.sharePercent ?: 0.0),
                                    style = Blake.mono(7f), color = Blake.faint)
                            }
                        }
                    }
                }
            }

            // Carry
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text("CARRY", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                Spacer(Modifier.height(6.dp))
                val carry = stats?.wavicles?.carryTotalSats ?: 0L
                Text("Owed to miners: $carry sats", style = Blake.mono(12f, FontWeight.ExtraBold), color = if (carry > 0) Blake.warn else Blake.fg)
                Text("Dust a coinbase couldn't place is carried and paid by the next coinbases.",
                    style = Blake.mono(8f), color = Blake.faint)
                stats?.wavicles?.lastSnapshot?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("last snapshot ${it.take(10)}…", style = Blake.mono(8f), color = Blake.wave,
                        modifier = Modifier.clickableNoRipple { clip.setText(AnnotatedString(it)) })
                }
            }

            // How it works
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text("HOW IT WORKS", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                Spacer(Modifier.height(8.dp))
                Text("99.6% of every block goes to the work window — split by share of work (TIDES), paid in that block's coinbase · 0.4% fee.",
                    style = Blake.mono(9f), color = Blake.fg)
                Spacer(Modifier.height(6.dp))
                Text("Not solo: every block found by anyone in the window pays everyone in the window.",
                    style = Blake.mono(9f), color = Blake.wave)
                Spacer(Modifier.height(6.dp))
                Text("Trustless: each coinbase commits BLAKE2b-256(snapshot) in an OP_RETURN (PYBLOCK-TON618); the split is public and verifiable.",
                    style = Blake.mono(8f), color = Blake.faint)
            }

            // Connect
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text("CONNECT", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                Spacer(Modifier.height(10.dp))
                Text("Not a stratum URL — point your DATUM gateway at the pool with this config:",
                    style = Blake.mono(9f), color = Blake.faint)
                Spacer(Modifier.height(8.dp))
                Text(gatewayJson, style = Blake.mono(9f), color = Blake.wave,
                    modifier = Modifier.fillMaxWidth().border(1.dp, Blake.line, RectangleShape).padding(10.dp))
                Spacer(Modifier.height(8.dp))
                Text(if (copied) "✓ COPIED" else "TAP TO COPY CONFIG", style = Blake.mono(10f, FontWeight.ExtraBold),
                    color = if (copied) Blake.ok else Blake.wave, letterSpacing = 1.sp,
                    modifier = Modifier.fillMaxWidth().border(1.dp, if (copied) Blake.ok else Blake.wave, RectangleShape).padding(vertical = 9.dp)
                        .clickableNoRipple { clip.setText(AnnotatedString(gatewayJson)); copied = true })
                Spacer(Modifier.height(8.dp))
                Text("• set mining.pool_address to YOUR BLAKE2b address\n• set mining.coinbase_tag_secondary to your name (shows after the slash in the block's scriptsig)\n• requires your own Bitcoin Knots BLAKE2b node + a DATUM gateway (CONVOY, OCEAN forks, StartOS)\n• tip: blockreservedweight=100000",
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
