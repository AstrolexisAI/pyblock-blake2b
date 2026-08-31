package com.astrolexis.pyblock.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.astrolexis.pyblock.data.model.ChirpMiner
import com.astrolexis.pyblock.data.model.ChirpStats
import com.astrolexis.pyblock.data.model.Stratum
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.data.store.AddressStore
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import com.astrolexis.pyblock.ui.theme.neonText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class ChirpDetailUiState(
    val loading: Boolean = true,
    val stats: ChirpStats? = null,
    val miners: List<ChirpMiner> = emptyList(),
    val btcUsd: Double? = null,
)

class ChirpDetailViewModel : ViewModel() {
    private val _state = MutableStateFlow(ChirpDetailUiState())
    val state: StateFlow<ChirpDetailUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            coroutineScope {
                val s = async { runCatching { ApiClient.api.chirpStats() }.getOrNull() }
                val m = async { runCatching { ApiClient.api.chirpMiners() }.getOrNull() }
                val p = async { runCatching { ApiClient.mempool.prices() }.getOrNull() }
                val stats = s.await(); val miners = m.await(); val prices = p.await()
                _state.update {
                    it.copy(
                        loading = false,
                        stats = stats ?: it.stats,
                        miners = miners ?: it.miners,
                        btcUsd = prices?.usd?.takeIf { v -> v > 0 } ?: it.btcUsd,
                    )
                }
            }
        }
    }
}

/** Approximate coinbase payout for this miner if a block landed now.
 *  R(1−f)·wᵢ/Σw over currently-eligible miners. Uses the 3.125 BTC
 *  subsidy (fees excluded — conservative). */
private fun blockNowSats(me: ChirpMiner, miners: List<ChirpMiner>): Long? {
    val eligibleWeights = miners.filter { it.eligible }.sumOf { it.weight }
    if (eligibleWeights <= 0) return null
    val net = 312_500_000.0 * (1 - 0.009)
    return (net * me.weight / eligibleWeights).toLong()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChirpDetailScreen(onBack: () -> Unit) {
    val vm: ChirpDetailViewModel = viewModel()
    val state by vm.state.collectAsState()
    val tracked by AddressStore.addresses.collectAsState()
    val myAddress = tracked.firstOrNull()?.address

    // "Verify the draw" — public replay of the Efraimidis–Spirakis lottery. Mirrors iOS.
    var showVerify by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (showVerify) {
        androidx.activity.compose.BackHandler { showVerify = false }
        VerifyDrawScreen(miners = state.miners, onBack = { showVerify = false })
        return
    }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim,
                        modifier = Modifier.clickableNoRipple { Haptics.tap(); onBack() })
                    Spacer(Modifier.weight(1f))
                    MarqueeTitle(text = "CHIRP")
                    Spacer(Modifier.weight(1f))
                    Text("✕", style = PyType.mono(22f), color = Color.Transparent)   // balance the row
                }
                Spacer(Modifier.height(16.dp))

                ChirpIntro()
                Spacer(Modifier.height(18.dp))
                MyStatusCard(state, myAddress)
                state.stats?.let { s ->
                    Spacer(Modifier.height(18.dp))
                    ChirpStatsCard(s)
                    Spacer(Modifier.height(18.dp))
                    EligibilityCard(s)
                }
                Spacer(Modifier.height(18.dp))
                Leaderboard(state)
                Spacer(Modifier.height(18.dp))
                // "Don't trust — verify." Public replay of the weighted draw.
                Text(stringResource(R.string.chirp_verify_the_draw),
                    style = PyType.mono(13f), color = PyTheme.cyan, letterSpacing = 2.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, PyTheme.cyan, RectangleShape)
                        .clickableNoRipple { Haptics.tap(); showVerify = true }
                        .padding(vertical = 11.dp))
                Spacer(Modifier.height(18.dp))
                ChirpConnectCard()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ---- Sections ----

@Composable
private fun ChirpIntro() {
    Column {
        Text(stringResource(R.string.chirp_intro_tagline),
            style = PyType.mono(14f), color = PyTheme.cyan)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.chirp_intro_body),
            style = PyType.mono(12f), color = PyTheme.cyan.copy(alpha = 0.7f),
        )
    }
}

// ---- My status (personalized) ----

@Composable
private fun MyStatusCard(state: ChirpDetailUiState, myAddress: String?) {
    if (myAddress == null) return
    val me = state.miners.firstOrNull { it.address == myAddress }
    if (me != null) {
        Column(
            Modifier
                .fillMaxWidth()
                .moduleFrame(PyTheme.magenta)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chirp_my_status), style = PyType.mono(13f), color = PyTheme.magenta, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (me.eligible) stringResource(R.string.chirp_eligible) else stringResource(R.string.chirp_not_yet),
                    style = PyType.mono(13f),
                    color = if (me.eligible) PyTheme.primary else PyTheme.danger,
                    letterSpacing = 2.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(midTruncate(myAddress), style = PyType.mono(11f), color = PyTheme.primary, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            if (me.eligible) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.chirp_your_slice), style = PyType.mono(12f), color = PyTheme.cyan)
                    Spacer(Modifier.weight(1f))
                    Text(String.format(Locale.US, "%.2f%%", me.weight * 100),
                        style = PyType.mono(22f).neonText(PyTheme.yellow), color = PyTheme.yellow)
                }
                // The dopamine line: concrete sats if the pool found a block
                // this instant. payout_i = R(1-f)·w_i/Σw over eligible weights.
                blockNowSats(me, state.miners)?.let { sats ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.chirp_block_now), style = PyType.mono(12f), color = PyTheme.cyan)
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(String.format(Locale.US, "~%,d sats", sats),
                                style = PyType.mono(18f), color = PyTheme.primary)
                            usdLabel(sats, state.btcUsd)?.let {
                                Text(it, style = PyType.mono(11f), color = PyTheme.primaryDim)
                            }
                        }
                    }
                }
            } else {
                val daysGate = state.stats?.minDays ?: 7.0
                val powerGate = state.stats?.minPower ?: 500_000.0
                val daysLeft = max(0.0, daysGate - me.days)
                if (me.days < daysGate) {
                    Text(
                        stringResource(R.string.chirp_days_gate,
                            ceil(daysLeft), if (daysLeft > 1) "s" else ""),
                        style = PyType.mono(12f), color = PyTheme.yellow,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (me.power < powerGate) {
                    Text(stringResource(R.string.chirp_power_gate),
                        style = PyType.mono(12f), color = PyTheme.cyan)
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                GateProgressBar(stringResource(R.string.chirp_gate_days), me.days, state.stats?.minDays ?: 7.0, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                GateProgressBar(stringResource(R.string.chirp_gate_pow), me.power, state.stats?.minPower ?: 500_000.0, Modifier.weight(1f))
            }
        }
    } else if (state.miners.isNotEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .moduleFrame(PyTheme.magenta)
                .padding(14.dp),
        ) {
            Text(stringResource(R.string.chirp_my_status), style = PyType.mono(13f), color = PyTheme.magenta, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.chirp_no_shares, Stratum.Pool.CHIRP.port),
                style = PyType.mono(12f), color = PyTheme.cyan.copy(alpha = 0.8f),
            )
        }
    }
}

// ---- Stats + gates ----

@Composable
private fun ChirpStatsCard(s: ChirpStats) {
    Column(
        Modifier
            .fillMaxWidth()
            .moduleFrame(PyTheme.yellow)
            .padding(12.dp),
    ) {
        StatRow(stringResource(R.string.chirp_stat_hashrate), String.format(Locale.US, "%.1f TH/s", s.hashrate))
        StatRow(stringResource(R.string.chirp_stat_workers), "${s.workers}")
        StatRow(stringResource(R.string.chirp_stat_blocks), "${s.blocks}")
        StatRow(stringResource(R.string.chirp_stat_candidates), "${s.candidates}")
        s.bestdiff?.let { StatRow(stringResource(R.string.chirp_stat_best_diff), String.format(Locale.US, "%.2e", it)) }
        StatRow(stringResource(R.string.chirp_stat_fee), Stratum.Pool.CHIRP.feeDisplay)
    }
}

@Composable
private fun StatRow(k: String, v: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(k, style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        Text(v, style = PyType.mono(14f), color = PyTheme.yellow)
    }
}

@Composable
private fun EligibilityCard(s: ChirpStats) {
    Column(
        Modifier
            .fillMaxWidth()
            .moduleFrame(PyTheme.primary)
            .padding(12.dp),
    ) {
        Text(stringResource(R.string.chirp_gates_title), style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.chirp_gates_intro),
            style = PyType.mono(11f), color = PyTheme.cyan.copy(alpha = 0.8f))
        Spacer(Modifier.height(8.dp))
        Row {
            GateBox(stringResource(R.string.chirp_min_days), "${s.minDays.toInt()}", Modifier.weight(1f))
            Spacer(Modifier.width(14.dp))
            GateBox(stringResource(R.string.chirp_min_power), powerLabel(s.minPower), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.chirp_gates_fail),
            style = PyType.mono(10f), color = PyTheme.primaryDim)
    }
}

@Composable
private fun GateBox(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .background(PyTheme.bg)
            .border(1.dp, PyTheme.primary.copy(alpha = 0.3f), RectangleShape)
            .padding(8.dp),
    ) {
        Text(label, style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f), letterSpacing = 2.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, style = PyType.mono(18f), color = PyTheme.yellow)
    }
}

// ---- Leaderboard ----

@Composable
private fun Leaderboard(state: ChirpDetailUiState) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.chirp_leaderboard), style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.chirp_miner_count, state.miners.size), style = PyType.mono(10f), color = PyTheme.primaryDim)
        }
        Spacer(Modifier.height(8.dp))
        if (state.miners.isEmpty() && !state.loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.chirp_no_data), style = PyType.mono(13f), color = PyTheme.primaryDim)
            }
        }
        state.miners.forEach { m ->
            MinerRow(m, state.stats)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MinerRow(m: ChirpMiner, stats: ChirpStats?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (m.eligible) PyTheme.yellow.copy(alpha = 0.05f) else PyTheme.bg)
            .border(
                1.dp,
                if (m.eligible) PyTheme.yellow.copy(alpha = 0.4f) else PyTheme.primary.copy(alpha = 0.2f),
                RectangleShape,
            )
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (m.eligible) "✓" else "✗", style = PyType.mono(14f),
                color = if (m.eligible) PyTheme.primary else PyTheme.danger)
            Spacer(Modifier.width(6.dp))
            Text(midTruncate(m.address), style = PyType.mono(11f), color = PyTheme.primary,
                maxLines = 1, modifier = Modifier.weight(1f))
            Text(String.format(Locale.US, "%.1f%%", m.weight * 100),
                style = PyType.mono(13f), color = PyTheme.yellow)
        }
        Spacer(Modifier.height(6.dp))
        // Eligibility progress: two bars showing how close they are to the
        // days / power floors.
        Row {
            GateProgressBar(stringResource(R.string.chirp_gate_days), m.days, stats?.minDays ?: 7.0, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            GateProgressBar(stringResource(R.string.chirp_gate_pow), m.power, stats?.minPower ?: 500_000.0, Modifier.weight(1f))
        }
    }
}

@Composable
private fun GateProgressBar(label: String, value: Double, cap: Double, modifier: Modifier) {
    val pct = if (cap > 0) min(value / cap, 1.0).toFloat() else 0f
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = PyType.mono(9f), color = PyTheme.cyan.copy(alpha = 0.7f), letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(String.format(Locale.US, "%.0f%%", pct * 100), style = PyType.mono(9f),
                color = if (pct >= 1f) PyTheme.primary else PyTheme.primaryDim)
        }
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).background(PyTheme.primarySoft)) {
            if (pct > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(pct)
                        .height(4.dp)
                        .background(if (pct >= 1f) PyTheme.primary else PyTheme.cyan),
                )
            }
        }
    }
}

// ---- Connect ----

@Composable
private fun ChirpConnectCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .moduleFrame(PyTheme.magenta)
            .padding(12.dp),
    ) {
        Text(stringResource(R.string.chirp_connect), style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        StratumField(stringResource(R.string.chirp_stratum_url), Stratum.Pool.CHIRP.hostPortURL)
        StratumField(stringResource(R.string.chirp_authority_pubkey), Stratum.SV2_AUTHORITY_PUBKEY)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.chirp_connect_help),
            style = PyType.mono(10f), color = PyTheme.primaryDim,
        )
    }
}

@Composable
private fun StratumField(label: String, value: String) {
    val clip = LocalClipboardManager.current
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.6f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(midTruncate(value, head = 22, tail = 12), style = PyType.mono(10f),
                color = PyTheme.primary, maxLines = 1, modifier = Modifier.weight(1f))
            Text(
                "⧉", style = PyType.mono(14f), color = PyTheme.magenta,
                modifier = Modifier
                    .clickableNoRipple { Haptics.tap(); clip.setText(AnnotatedString(value)) }
                    .padding(start = 8.dp),
            )
        }
    }
}

// ---- Helpers ----

private fun powerLabel(p: Double): String = when {
    p >= 1e9 -> String.format(Locale.US, "%.1fG", p / 1e9)
    p >= 1e6 -> String.format(Locale.US, "%.1fM", p / 1e6)
    p >= 1e3 -> String.format(Locale.US, "%.0fk", p / 1e3)
    else -> String.format(Locale.US, "%.0f", p)
}

/** "≈ $12.34" for a sat amount, or null when no price is loaded. */
private fun usdLabel(sats: Long, btcUsd: Double?): String? {
    if (btcUsd == null) return null
    val dollars = sats / 100_000_000.0 * btcUsd
    return when {
        dollars >= 100 -> String.format(Locale.US, "≈ $%.0f", dollars)
        dollars >= 1 -> String.format(Locale.US, "≈ $%.2f", dollars)
        else -> String.format(Locale.US, "≈ $%.3f", dollars)
    }
}

/** Middle truncation, mirroring iOS `.truncationMode(.middle)`. */
private fun midTruncate(s: String, head: Int = 14, tail: Int = 10): String =
    if (s.length <= head + tail + 1) s else s.take(head) + "…" + s.takeLast(tail)
