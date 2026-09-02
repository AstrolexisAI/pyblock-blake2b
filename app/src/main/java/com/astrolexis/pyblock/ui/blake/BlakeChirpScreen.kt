package com.astrolexis.pyblock.ui.blake

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
import androidx.compose.material3.Text
import com.astrolexis.pyblock.ui.components.clickableNoRipple
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.blake.BlakeApi
import kotlinx.coroutines.delay

/** CHIRP — the syndicate: weighted split, loyalty, eligibility + connect info.
 *  Mirrors iOS ChirpView. Sober, purple, flat. */
@Composable
fun BlakeChirpScreen() {
    var pool by remember { mutableStateOf<BlakeApi.ChirpPool?>(null) }
    var workers by remember { mutableStateOf<List<BlakeApi.ChirpWorker>>(emptyList()) }
    var showParticipants by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            BlakeApi.chirpPool()?.let { pool = it }
            workers = BlakeApi.chirpWorkers()
            loaded = true; delay(20_000)
        }
    }

    Box(Modifier.fillMaxSize().background(Blake.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("CHIRP", style = Blake.mono(24f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 3.sp)
            Spacer(Modifier.height(6.dp))
            Text("Syndicate · weighted split · 0.9% fee", style = Blake.mono(10f), color = Blake.ppDim)

            Spacer(Modifier.height(22.dp))
            if (!loaded) { Text("⟳ loading…", style = Blake.mono(10f), color = Blake.pp); Spacer(Modifier.height(14.dp)) }
            else if (pool == null) { Text("⚠ can't reach the server.", style = Blake.mono(10f), color = Blake.danger); Spacer(Modifier.height(14.dp)) }

            Column(Modifier.fillMaxWidth().blakeCard()) {
                Row(Modifier.fillMaxWidth()) {
                    BlakeStat(hr(pool?.hashrate), "syndicate hashrate")
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
            val eligible = online.filter { eligibleOf(it) && weightOf(it) > 0 }.sortedByDescending { weightOf(it) }
            val totalW = eligible.sumOf { weightOf(it) }
            if (eligible.isNotEmpty() && totalW > 0) {
                Spacer(Modifier.height(22.dp))
                Column(Modifier.fillMaxWidth().blakeCard()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("BLOCK PARTICIPATION", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${eligible.size} eligible", style = Blake.mono(9f), color = Blake.pp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().height(16.dp)) {
                        eligible.forEachIndexed { i, w ->
                            Box(Modifier.weight((weightOf(w) / totalW).toFloat()).fillMaxHeight()
                                .padding(end = if (i == eligible.lastIndex) 0.dp else 1.dp)
                                .background(participationColor(i)))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val top = eligible.first()
                    Text("Largest slice ${"%.0f".format(weightOf(top) / totalW * 100)}% · each eligible miner shares the block reward in proportion to its contribution.",
                        style = Blake.mono(8f), color = Blake.faint)
                }
            }

            // PARTICIPANTS — connected miners only (disconnected hidden). Collapsible.
            if (online.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Column(Modifier.fillMaxWidth().blakeCard()) {
                    Row(Modifier.fillMaxWidth().clickableNoRipple { showParticipants = !showParticipants },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("PARTICIPANTS", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("${online.size}", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.pp)
                        Spacer(Modifier.weight(1f))
                        Text(if (showParticipants) "▲" else "▼", style = Blake.mono(9f), color = Blake.ppDim)
                    }
                    if (showParticipants) {
                        Spacer(Modifier.height(10.dp))
                        online.forEach { w ->
                            val elig = eligibleOf(w) && (w.hashrateThs ?: 0.0) > 0
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(5.dp).background(if (elig) Blake.ok else Blake.faint, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(w.name ?: "anon", style = Blake.mono(10f), color = Blake.fg, maxLines = 1)
                                Spacer(Modifier.weight(1f))
                                Text(hr(w.hashrateThs), style = Blake.mono(10f), color = Blake.ppDim)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text("ELIGIBILITY", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                Spacer(Modifier.height(10.dp))
                rule("min loyalty", "${pool?.minDays ?: 7} days")
                rule("min power", powerStr(pool?.minPower))
                Spacer(Modifier.height(6.dp))
                Text("Below the floor you still mine but don't share the reward split.",
                    style = Blake.mono(8f), color = Blake.faint)
            }

            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().blakeCard()) {
                Text("CONNECT", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
                Spacer(Modifier.height(10.dp))
                Text("pool.pyblock.xyz:5574", style = Blake.mono(13f), color = Blake.pp)
                Text("user = your BLAKE2b address · pass = x", style = Blake.mono(9f), color = Blake.faint)
            }
            Spacer(Modifier.height(24.dp))
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
