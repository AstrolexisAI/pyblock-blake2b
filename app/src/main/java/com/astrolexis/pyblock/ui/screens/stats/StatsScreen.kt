package com.astrolexis.pyblock.ui.screens.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape as GfxRectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.astrolexis.pyblock.data.model.BlockOddsResponse
import com.astrolexis.pyblock.data.model.BlocksResponse
import com.astrolexis.pyblock.data.model.CarouselStats
import com.astrolexis.pyblock.data.model.DifficultyAdjustment
import com.astrolexis.pyblock.data.model.FoundBlock
import com.astrolexis.pyblock.data.model.HistoryRange
import com.astrolexis.pyblock.data.model.PoolOdds
import com.astrolexis.pyblock.ui.components.HashrateChart
import com.astrolexis.pyblock.ui.components.RangePicker
import com.astrolexis.pyblock.data.model.Stratum
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.SegmentedBar
import com.astrolexis.pyblock.ui.theme.moduleFrame
import com.astrolexis.pyblock.ui.theme.neonText
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.util.expectedTime
import com.astrolexis.pyblock.util.formatOdds
import com.astrolexis.pyblock.util.hashrateLabel
import com.astrolexis.pyblock.util.shortDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt
import com.astrolexis.pyblock.R

private const val NEXT_HALVING_HEIGHT = 1_050_000

@Composable
fun StatsScreen() {
    val vm: StatsViewModel = viewModel()
    val state by vm.state.collectAsState()
    var chirpOpen by remember { mutableStateOf(false) }
    var carouselOpen by remember { mutableStateOf(false) }
    var sv2Open by remember { mutableStateOf(false) }

    // Silent auto-refresh every 30s — only while the app is resumed AND no detail
    // subview is open, so it doesn't keep firing the 8-call fan-out off-screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Refresh immediately on each resume/tab-return (fresh data, not up to
            // 30 s stale), then keep the silent cadence.
            while (true) {
                if (!chirpOpen && !carouselOpen && !sv2Open) vm.refresh(showSpinner = false)
                delay(30_000)
            }
        }
    }

    when {
        chirpOpen -> {
            BackHandler { chirpOpen = false }
            ChirpDetailScreen(onBack = { chirpOpen = false })
        }
        carouselOpen -> {
            BackHandler { carouselOpen = false }
            CarouselDetailScreen(onBack = { carouselOpen = false })
        }
        sv2Open -> {
            BackHandler { sv2Open = false }
            SV2DetailScreen(onBack = { sv2Open = false })
        }
        else -> StatsHome(vm, state,
            onOpenChirp = { chirpOpen = true },
            onOpenCarousel = { carouselOpen = true },
            onOpenSv2 = { sv2Open = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsHome(
    vm: StatsViewModel,
    state: StatsUiState,
    onOpenChirp: () -> Unit,
    onOpenCarousel: () -> Unit,
    onOpenSv2: () -> Unit,
) {
    // HASHRATE WARP + POOL PULSE: derive from live per-pool hashrate (all TH/s).
    val poolHr = listOf(state.lotto?.hashrate1m, state.datum?.hashrate1m, state.sv2?.hashrate, state.chirp?.hashrate)
    val totalTh = poolHr.filterNotNull().sum()
    var warpBase by remember { mutableStateOf(0.0) }
    LaunchedEffect(totalTh) { if (warpBase == 0.0 && totalTh > 0) warpBase = totalTh }
    val warp = if (warpBase > 0 && totalTh > 0) sqrt(totalTh / warpBase).toFloat().coerceIn(0.7f, 1.6f) else 1f
    fun share(hr: Double?): Float = if (totalTh > 0 && hr != null) (hr / totalTh).toFloat() else 0f
    val totalWorkers = listOf(state.lotto?.workers, state.datum?.workers, state.sv2?.workers, state.chirp?.workers).filterNotNull().sum()

    // BLOCK-FOUND FANFARE: fire the celebration when a higher pool block appears
    // (never on first load). Tapping a block row replays its fanfare.
    var fanfareBlock by remember { mutableStateOf<FoundBlock?>(null) }
    var lastSeenHeight by remember { mutableStateOf(0) }
    val topBlock = state.blocks?.blocks?.maxByOrNull { it.height }
    LaunchedEffect(topBlock?.height) {
        val h = topBlock?.height ?: 0
        if (h > 0) {
            if (lastSeenHeight in 1 until h) fanfareBlock = topBlock
            lastSeenHeight = h
        }
    }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
    Starfield(speedMultiplier = warp)
    com.astrolexis.pyblock.ui.components.MinerSwarm(workers = totalWorkers)
    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = { vm.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MarqueeTitle(text = stringResource(R.string.stats_pool_stats))
            Spacer(Modifier.weight(1f))
            AntiSpamBadge()
        }
        Spacer(Modifier.height(12.dp))

        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniCard(Modifier.weight(1f).fillMaxHeight(), "LOTTO", Stratum.Pool.LOTTO.activePort, PyTheme.primary,
                state.lotto?.workers, state.lotto?.hashrate1m, share = share(state.lotto?.hashrate1m))
            MiniCard(Modifier.weight(1f).fillMaxHeight(), "DATUM", Stratum.Pool.DATUM.activePort, PyTheme.magenta,
                state.datum?.workers, state.datum?.hashrate1m, share = share(state.datum?.hashrate1m))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniCard(Modifier.weight(1f).fillMaxHeight(), "SV2 ▸", Stratum.Pool.SV2.activePort, PyTheme.cyan,
                state.sv2?.workers, state.sv2?.hashrate, share = share(state.sv2?.hashrate),
                onClick = onOpenSv2)
            MiniCard(Modifier.weight(1f).fillMaxHeight(), "CHIRP", Stratum.Pool.CHIRP.activePort, PyTheme.yellow,
                state.chirp?.workers, state.chirp?.hashrate, share = share(state.chirp?.hashrate),
                extra = state.chirp?.let { stringResource(R.string.stats_chirp_eligible, it.candidates) },
                onClick = onOpenChirp)
        }
        Spacer(Modifier.height(10.dp))
        CarouselTile(state.carousel, onClick = onOpenCarousel)

        state.odds?.let {
            Spacer(Modifier.height(16.dp))
            OddsSection(it)
        }
        state.diff?.let {
            Spacer(Modifier.height(12.dp))
            NetworkSection(it, state.odds?.height)
        }

        Spacer(Modifier.height(16.dp))
        ChartSection(state, vm)

        Spacer(Modifier.height(16.dp))
        BlocksSection(state.blocks, onReplay = { fanfareBlock = it })

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(state.error!!, style = PyType.mono(12f), color = PyTheme.danger)
        }
        Spacer(Modifier.height(8.dp))
        }
    }
    com.astrolexis.pyblock.ui.components.BlockFanfare(fanfareBlock) { fanfareBlock = null }
    }
}

// ---- Mini pool cards ----

@Composable
private fun MiniCard(
    modifier: Modifier,
    name: String,
    port: Int,
    color: Color,
    workers: Int?,
    hashrate: Double?,
    share: Float = 0f,
    extra: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .moduleFrame(color)
            .then(if (onClick != null) Modifier.clickableNoRipple { Haptics.tap(); onClick() } else Modifier)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = PyType.mono(15f), color = color, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(":$port", style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.6f))
        }
        com.astrolexis.pyblock.ui.components.OdometerText(hashrateLabel(hashrate), style = PyType.mono(20f).neonText(PyTheme.yellow), color = PyTheme.yellow)
        // POOL PULSE: this pool's share of total network hashrate (unlit gauge when 0/arcade-off).
        Spacer(Modifier.height(5.dp))
        SegmentedBar(progress = share, modifier = Modifier.fillMaxWidth(), segments = 12, accent = color)
        Spacer(Modifier.height(5.dp))
        Text(
            extra ?: stringResource(R.string.stats_workers, workers?.toString() ?: "—"),
            style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f), maxLines = 1,
        )
    }
}

@Composable
private fun CarouselTile(c: CarouselStats?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .moduleFrame(Color.White.copy(alpha = 0.8f))
            .clickableNoRipple { Haptics.tap(); onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("🎠 CAROUSEL", style = PyType.mono(15f), color = Color.White, letterSpacing = 1.sp)
            Text(
                c?.let { stringResource(R.string.stats_carousel_miners_suppliers, it.miners, it.suppliers) } ?: stringResource(R.string.stats_carousel_no_miners),
                style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f), maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(hashrateLabel(c?.hashrateTh), style = PyType.mono(18f).neonText(PyTheme.yellow), color = PyTheme.yellow)
            Text(":30000", style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun AntiSpamBadge() {
    Text(
        stringResource(R.string.stats_anti_spam),
        style = PyType.mono(9f),
        color = PyTheme.bg,
        letterSpacing = 2.sp,
        modifier = Modifier.background(PyTheme.primary).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

// ---- Block odds ----

@Composable
private fun OddsSection(odds: BlockOddsResponse) {
    SectionBox {
        Row {
            Text(stringResource(R.string.stats_block_odds), style = PyType.mono(14f), color = PyTheme.yellow, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.stats_odds_subtitle), style = PyType.mono(10f), color = PyTheme.primaryDim)
        }
        Spacer(Modifier.height(6.dp))
        OddsRow("LOTTO", odds.pools["lotto"], PyTheme.primary)
        OddsRow("DATUM", odds.pools["datum"], PyTheme.magenta)
        OddsRow("SV2", odds.pools["sv2"], PyTheme.cyan)
        OddsRow("CHIRP", odds.pools["chirp"], PyTheme.yellow)
        OddsRow("CAROUSEL", odds.pools["tmpl"], Color.White)
    }
}

@Composable
private fun OddsRow(name: String, p: PoolOdds?, color: Color) {
    if (p == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 3.dp),
    ) {
        Text(name, style = PyType.mono(13f), color = color, letterSpacing = 1.sp,
            maxLines = 1, modifier = Modifier.width(84.dp))
        Text(formatOdds(p.odds.y1), style = PyType.mono(13f), color = PyTheme.cyan)
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.stats_odds_one_in, expectedTime(p.secondsPerBlock)), style = PyType.mono(12f), color = PyTheme.yellow)
    }
}

// ---- Network (difficulty + halving) ----

@Composable
private fun NetworkSection(diff: DifficultyAdjustment, height: Int?) {
    SectionBox {
        Row {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.stats_next_diff_adj), style = PyType.mono(9f), color = PyTheme.cyan.copy(alpha = 0.6f), letterSpacing = 1.sp)
                val up = diff.difficultyChange >= 0
                Text(
                    String.format(Locale.US, "%s%.2f%%", if (up) "+" else "", diff.difficultyChange),
                    style = PyType.mono(17f), color = if (up) PyTheme.danger else PyTheme.primary,
                )
                val days = diff.remainingTime / 86_400_000.0
                Text(stringResource(R.string.stats_diff_in_days, days), style = PyType.mono(10f), color = PyTheme.primaryDim)
            }
            if (height != null) {
                val remaining = max(0, NEXT_HALVING_HEIGHT - height)
                // Use each chain's real pace (avg block time from the diff-adj
                // estimate) so the fork's slow blocks push the halving out
                // consistently with NEXT DIFF ADJ; falls back to 10 min/block.
                val avgBlockMs = if (diff.remainingBlocks > 0) diff.remainingTime / diff.remainingBlocks else 600_000L
                val etaMillis = System.currentTimeMillis() + remaining.toLong() * avgBlockMs
                val label = Instant.ofEpochMilli(etaMillis).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.US))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.stats_next_halving), style = PyType.mono(9f), color = PyTheme.cyan.copy(alpha = 0.6f), letterSpacing = 1.sp)
                    Text(label, style = PyType.mono(17f), color = PyTheme.yellow)
                    Text(stringResource(R.string.stats_blocks_count, remaining), style = PyType.mono(10f), color = PyTheme.primaryDim)
                }
            }
        }
    }
}

// ---- Hashrate history chart ----

@Composable
private fun ChartSection(state: StatsUiState, vm: StatsViewModel) {
    SectionBox {
        Text(stringResource(R.string.stats_hashrate_history), style = PyType.header, color = PyTheme.yellow, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ChartPool.entries.forEach { p ->
                PoolToggleButton(p, state.chartPool == p) { vm.setChartPool(p) }
            }
        }
        Spacer(Modifier.height(8.dp))
        RangePicker(
            range = state.chartRange,
            allowed = listOf(HistoryRange.H1, HistoryRange.D1, HistoryRange.D7),
        ) { vm.setChartRange(it) }
        Spacer(Modifier.height(8.dp))
        HashrateChart(state.chartPoints, chartColor(state.chartPool))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PoolToggleButton(
    pool: ChartPool,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = chartColor(pool)
    Box(
        modifier = Modifier
            .weight(1f)
            .height(36.dp)
            .background(if (selected) c.copy(alpha = 0.12f) else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, c, GfxRectangleShape)
            .clickableNoRipple { Haptics.select(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            pool.display,
            style = PyType.mono(13f),
            color = if (selected) c else c.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            letterSpacing = 1.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun chartColor(pool: ChartPool): Color =
    if (pool == ChartPool.LOTTO) PyTheme.primary else pool.color

// ---- Blocks found ----

@Composable
private fun BlocksSection(blocks: BlocksResponse?, onReplay: (FoundBlock) -> Unit = {}) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.stats_blocks_found), style = PyType.header, color = PyTheme.yellow, letterSpacing = 4.sp)
            Spacer(Modifier.weight(1f))
            if (blocks != null) {
                Text(stringResource(R.string.stats_blocks_lotto_datum, blocks.lotto, blocks.datum),
                    style = PyType.mono(12f), color = PyTheme.cyan)
            }
        }
        Spacer(Modifier.height(8.dp))
        val list = blocks?.blocks.orEmpty()
        if (list.isEmpty()) {
            Text(stringResource(R.string.stats_no_data), style = PyType.mono(12f), color = PyTheme.primaryDim)
        } else {
            list.take(5).forEach { b ->
                BlockRow(b, onClick = { onReplay(b) })
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun BlockRow(b: FoundBlock, onClick: () -> Unit = {}) {
    val color = if (b.protocolName == "DATUM") PyTheme.magenta else PyTheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .moduleFrame(color)
            .clickableNoRipple { Haptics.tap(); onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("#${b.height}", style = PyType.mono(18f), color = color)
            Text(
                b.protocolName + (b.relay?.let { " · $it" } ?: ""),
                style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f), letterSpacing = 1.sp,
            )
        }
        Text(shortDateTime(b.timestamp), style = PyType.mono(11f), color = PyTheme.primaryDim)
    }
}

// ---- Shared ----

@Composable
private fun SectionBox(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .moduleFrame(PyTheme.primary)
            .padding(12.dp),
        content = content,
    )
}

