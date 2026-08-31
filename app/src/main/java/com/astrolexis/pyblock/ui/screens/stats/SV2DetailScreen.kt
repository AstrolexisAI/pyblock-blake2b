package com.astrolexis.pyblock.ui.screens.stats

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.model.SV2Channel
import com.astrolexis.pyblock.data.model.SV2Stats
import com.astrolexis.pyblock.data.model.SV2Worker
import com.astrolexis.pyblock.data.model.Stratum
import com.astrolexis.pyblock.data.net.ApiClient
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

data class SV2DetailUiState(
    val loading: Boolean = true,
    val stats: SV2Stats? = null,
    val workers: List<SV2Worker> = emptyList(),
)

class SV2DetailViewModel : ViewModel() {
    private val _state = MutableStateFlow(SV2DetailUiState())
    val state: StateFlow<SV2DetailUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            coroutineScope {
                val s = async { runCatching { ApiClient.api.sv2Stats() }.getOrNull() }
                val w = async { runCatching { ApiClient.api.sv2Workers() }.getOrNull() }
                val stats = s.await(); val workers = w.await()
                // Preserve last-good on a partial/failed refresh — don't blank the sheet.
                _state.update {
                    it.copy(loading = false, stats = stats ?: it.stats, workers = workers?.miners ?: it.workers)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SV2DetailScreen(onBack: () -> Unit) {
    val vm: SV2DetailViewModel = viewModel()
    val state by vm.state.collectAsState()

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim,
                        modifier = Modifier.clickableNoRipple { Haptics.tap(); onBack() })
                    Spacer(Modifier.weight(1f))
                    MarqueeTitle(text = "SV2")
                    Spacer(Modifier.weight(1f))
                    Text("✕", style = PyType.mono(22f), color = Color.Transparent)
                }
                Spacer(Modifier.height(16.dp))

                Text(stringResource(R.string.sv2_intro_title), style = PyType.mono(14f), color = PyTheme.cyan)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.sv2_intro_body), style = PyType.mono(12f), color = PyTheme.cyan.copy(alpha = 0.7f))
                Spacer(Modifier.height(18.dp))

                state.stats?.let { StatsCard(it); Spacer(Modifier.height(18.dp)) }
                WorkerList(state)
                Spacer(Modifier.height(18.dp))
                StratumCard()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun StatsCard(s: SV2Stats) {
    Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.cyan).padding(12.dp)) {
        StatRow(stringResource(R.string.sv2_stat_hashrate), String.format(Locale.US, "%.1f TH/s", s.hashrate))
        StatRow(stringResource(R.string.sv2_stat_workers), "${s.workers}")
        StatRow(stringResource(R.string.sv2_stat_blocks), "${s.blocks}")
        s.bestdiff?.let { StatRow(stringResource(R.string.sv2_stat_best_diff), String.format(Locale.US, "%.2e", it)) }
        StatRow(stringResource(R.string.sv2_stat_fee), "0.9%")
    }
}

@Composable
private fun StatRow(k: String, v: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        Text(v, style = PyType.mono(14f).neonText(PyTheme.yellow), color = PyTheme.yellow)
    }
}

@Composable
private fun WorkerList(state: SV2DetailUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sv2_top_workers), style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 3.sp)
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.sv2_workers_total, state.workers.size), style = PyType.mono(10f), color = PyTheme.primaryDim)
    }
    Spacer(Modifier.height(8.dp))
    if (state.workers.isEmpty() && !state.loading) {
        Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.sv2_no_data), style = PyType.mono(13f), color = PyTheme.primaryDim)
        }
    }
    state.workers.forEach { w -> WorkerRow(w); Spacer(Modifier.height(8.dp)) }
}

@Composable
private fun WorkerRow(w: SV2Worker) {
    Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.cyan).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(midTrunc(w.address), style = PyType.mono(11f), color = PyTheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(String.format(Locale.US, "%.2f TH/s", w.hashrate),
                style = PyType.mono(13f).neonText(PyTheme.yellow), color = PyTheme.yellow)
        }
        w.channels?.forEach { ch -> ChannelRow(ch) }
        Row(Modifier.padding(top = 2.dp)) {
            w.bestDiff?.let {
                Text(stringResource(R.string.sv2_worker_best, String.format(Locale.US, "%.2e", it)),
                    style = PyType.mono(9f), color = PyTheme.primaryDim)
                Spacer(Modifier.width(12.dp))
            }
            w.sharePct?.let {
                Text(stringResource(R.string.sv2_share_pct, String.format(Locale.US, "%.1f%%", it)),
                    style = PyType.mono(9f), color = PyTheme.primaryDim)
            }
        }
    }
}

@Composable
private fun ChannelRow(ch: SV2Channel) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("  ↳ ${midTrunc(ch.worker)}", style = PyType.mono(10f), color = PyTheme.cyan,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        when {
            ch.hashrate != null -> Text(String.format(Locale.US, "%.2f TH/s", ch.hashrate),
                style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.8f))
            ch.shares != null -> Text(stringResource(R.string.sv2_shares, ch.shares),
                style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun StratumCard() {
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.cyan).padding(12.dp)) {
        Text(stringResource(R.string.sv2_connect), style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        StratumField(stringResource(R.string.sv2_field_stratum_url), Stratum.Pool.SV2.hostPortURL, clip)
        StratumField(stringResource(R.string.sv2_field_authority_pubkey), Stratum.SV2_AUTHORITY_PUBKEY, clip)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.sv2_connect_help), style = PyType.mono(10f), color = PyTheme.primaryDim)
    }
}

@Composable
private fun StratumField(label: String, value: String, clip: androidx.compose.ui.platform.ClipboardManager) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.6f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(midTrunc(value, head = 22, tail = 12), style = PyType.mono(10f), color = PyTheme.primary,
                maxLines = 1, modifier = Modifier.weight(1f))
            Text("⧉", style = PyType.mono(14f), color = PyTheme.magenta,
                modifier = Modifier.clickableNoRipple { Haptics.tap(); clip.setText(AnnotatedString(value)) }.padding(start = 8.dp))
        }
    }
}

private fun midTrunc(s: String, head: Int = 14, tail: Int = 10): String =
    if (s.length <= head + tail + 1) s else s.take(head) + "…" + s.takeLast(tail)
