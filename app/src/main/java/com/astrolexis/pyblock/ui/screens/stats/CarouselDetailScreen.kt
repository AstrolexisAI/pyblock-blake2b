package com.astrolexis.pyblock.ui.screens.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.astrolexis.pyblock.data.model.CarouselRing
import com.astrolexis.pyblock.data.model.CarouselStats
import com.astrolexis.pyblock.data.model.Stratum
import com.astrolexis.pyblock.data.model.SuppliersSnapshot
import com.astrolexis.pyblock.data.model.TemplateSupplier
import com.astrolexis.pyblock.data.net.ApiClient
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class CarouselDetailUiState(
    val loading: Boolean = true,
    val stats: CarouselStats? = null,
    val ring: CarouselRing? = null,
    val suppliers: SuppliersSnapshot? = null,
) {
    /** The supplier whose template is being mined right now. */
    val currentSupplier: String? get() = ring?.rotations?.firstOrNull()?.supplier
}

class CarouselDetailViewModel : ViewModel() {
    private val _state = MutableStateFlow(CarouselDetailUiState())
    val state: StateFlow<CarouselDetailUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            coroutineScope {
                val s = async { runCatching { ApiClient.api.carouselStats() }.getOrNull() }
                val r = async { runCatching { ApiClient.api.carouselRing() }.getOrNull() }
                val p = async { runCatching { ApiClient.api.carouselSuppliers() }.getOrNull() }
                val stats = s.await(); val ring = r.await(); val raw = p.await()
                _state.update {
                    it.copy(
                        loading = false,
                        stats = stats ?: it.stats,
                        ring = ring ?: it.ring,
                        suppliers = raw?.toSnapshot() ?: it.suppliers,
                    )
                }
            }
        }
    }

    /** One ring poll tick — the whole point of the carousel is watching the
     *  template spin, so the screen calls this every 6s while open. */
    suspend fun pollRing() {
        runCatching { ApiClient.api.carouselRing() }.getOrNull()?.let { r ->
            _state.update { it.copy(ring = r) }
        }
    }
}

/** Equal-slice donut of the carousel's active templates. The slice for the
 *  supplier being mined RIGHT NOW is solid white (carousel color) with a
 *  glow; the rest are dim outlines. Center shows the current supplier. */
@Composable
private fun SupplierDonut(suppliers: List<String>, active: String?, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val outerR = min(cx, cy) - 6.dp.toPx()
            val lineW = 24.dp.toPx()
            val midR = outerR - lineW / 2f
            val arcTopLeft = Offset(cx - midR, cy - midR)
            val arcSize = Size(midR * 2f, midR * 2f)
            val n = max(1, suppliers.size)
            val gapDeg = if (n > 1) 3f else 0f
            val sliceDeg = 360f / n

            suppliers.forEachIndexed { i, name ->
                val start = -90f + i * sliceDeg + gapDeg / 2f
                val sweep = sliceDeg - gapDeg
                if (name == active) {
                    // Glow pass under the solid stroke.
                    drawArc(
                        color = Color.White.copy(alpha = 0.35f),
                        startAngle = start, sweepAngle = sweep, useCenter = false,
                        topLeft = arcTopLeft, size = arcSize,
                        style = Stroke(width = lineW + 10.dp.toPx(), cap = StrokeCap.Butt),
                    )
                    drawArc(
                        color = Color.White,
                        startAngle = start, sweepAngle = sweep, useCenter = false,
                        topLeft = arcTopLeft, size = arcSize,
                        style = Stroke(width = lineW, cap = StrokeCap.Butt),
                    )
                } else {
                    drawArc(
                        color = Color.White.copy(alpha = 0.13f),
                        startAngle = start, sweepAngle = sweep, useCenter = false,
                        topLeft = arcTopLeft, size = arcSize,
                        style = Stroke(width = lineW, cap = StrokeCap.Butt),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.carousel_now_mining), style = PyType.mono(9f), color = PyTheme.cyan.copy(alpha = 0.7f),
                letterSpacing = 2.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                active ?: "—",
                style = PyType.mono(20f).copy(
                    shadow = Shadow(color = Color.White.copy(alpha = 0.6f), blurRadius = 12f),
                ),
                color = Color.White,
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 130.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarouselDetailScreen(onBack: () -> Unit) {
    val vm: CarouselDetailViewModel = viewModel()
    val state by vm.state.collectAsState()

    // Poll the rotation feed every 6s while this screen is open — cancelled
    // automatically when the overlay closes and the effect leaves composition.
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(6_000)
            vm.pollRing()
        }
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
                MarqueeTitle(text = stringResource(R.string.carousel_title))
                Spacer(Modifier.weight(1f))
                Text("✕", style = PyType.mono(22f), color = Color.Transparent)   // balance the row
            }
            Spacer(Modifier.height(16.dp))

            CarouselIntro()
            state.currentSupplier?.let { current ->
                Spacer(Modifier.height(18.dp))
                NowMining(state, current)
            }
            state.stats?.let { s ->
                Spacer(Modifier.height(18.dp))
                CarouselStatsCard(s)
            }
            state.ring?.let { r ->
                Spacer(Modifier.height(18.dp))
                RotationFeed(r)
            }
            state.suppliers?.let { snap ->
                Spacer(Modifier.height(18.dp))
                SuppliersSection(snap)
            }
            Spacer(Modifier.height(18.dp))
            CarouselConnectCard()
            Spacer(Modifier.height(8.dp))
        }
    }
    }
}

// ---- Sections ----

@Composable
private fun CarouselIntro() {
    Column {
        Text(stringResource(R.string.carousel_intro_tagline),
            style = PyType.mono(14f), color = PyTheme.cyan)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.carousel_intro_body),
            style = PyType.mono(12f), color = PyTheme.cyan.copy(alpha = 0.7f),
        )
    }
}

/** Donut of active templates — equal slices, the one being mined right now
 *  highlighted in white (the carousel color), supplier name in the middle.
 *  The 6s ring poll moves the highlight. */
@Composable
private fun NowMining(state: CarouselDetailUiState, current: String) {
    val liveNames = state.suppliers?.suppliers?.filter { it.live }?.map { it.name }.orEmpty()
    SupplierDonut(
        suppliers = liveNames.ifEmpty { listOf(current) },
        active = current,
        modifier = Modifier
            .fillMaxWidth()
            .moduleFrame(Color.White)
            .padding(12.dp)
            .height(230.dp),
    )
}

@Composable
private fun CarouselStatsCard(s: CarouselStats) {
    Row(
        Modifier
            .fillMaxWidth()
            .moduleFrame(PyTheme.primary)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Stat(stringResource(R.string.carousel_stat_hash), stringResource(R.string.carousel_stat_hash_value, s.hashrateTh / 1000), Modifier.weight(1f))
        Stat(stringResource(R.string.carousel_stat_miners), "${s.miners}", Modifier.weight(1f))
        Stat(stringResource(R.string.carousel_stat_suppliers), "${s.suppliers}", Modifier.weight(1f))
        Stat(stringResource(R.string.carousel_stat_fee), stringResource(R.string.carousel_stat_fee_value), Modifier.weight(1f))
    }
}

@Composable
private fun Stat(k: String, v: String, modifier: Modifier) {
    Column(modifier) {
        Text(k, style = PyType.mono(9f), color = PyTheme.cyan.copy(alpha = 0.6f), letterSpacing = 1.sp)
        Text(v, style = PyType.mono(15f).neonText(PyTheme.yellow), color = PyTheme.yellow, maxLines = 1)
    }
}

/** Recent rotation history — proof the wheel spins. */
@Composable
private fun RotationFeed(ring: CarouselRing) {
    Column(
        Modifier
            .fillMaxWidth()
            .moduleFrame(PyTheme.primary)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.carousel_rotation_feed), style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (ring.live) PyTheme.primary else PyTheme.danger, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(if (ring.live) stringResource(R.string.carousel_live) else stringResource(R.string.carousel_stale), style = PyType.mono(10f),
                color = if (ring.live) PyTheme.primary else PyTheme.danger)
        }
        Spacer(Modifier.height(6.dp))
        ring.rotations.take(12).forEachIndexed { i, rot ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(rot.supplier, style = PyType.mono(12f),
                    color = if (i == 0) PyTheme.yellow else PyTheme.primary.copy(alpha = 0.8f),
                    maxLines = 1, modifier = Modifier.weight(1f))
                Text(rotationTime(rot.atEpochSeconds), style = PyType.mono(10f), color = PyTheme.primaryDim)
            }
        }
    }
}

/** Supplier table with per-supplier dedicated mining port. */
@Composable
private fun SuppliersSection(snap: SuppliersSnapshot) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.carousel_template_suppliers), style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.carousel_sovereign_nodes, snap.suppliers.size, snap.supplierCount),
                style = PyType.mono(10f), color = PyTheme.primaryDim)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.carousel_suppliers_body),
            style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(8.dp))
        snap.suppliers.forEach { s ->
            SupplierRow(s)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SupplierRow(s: TemplateSupplier) {
    val clip = LocalClipboardManager.current
    // Three states: live (green), paused/hold (yellow), offline (red).
    val dotColor = if (s.live) PyTheme.primary else if (s.paused) PyTheme.yellow else PyTheme.danger.copy(alpha = 0.6f)
    val nameColor = if (s.live) PyTheme.primary else if (s.paused) PyTheme.yellow else PyTheme.primaryDim
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (s.live) PyTheme.primary.copy(alpha = 0.04f)
                else if (s.paused) PyTheme.yellow.copy(alpha = 0.05f)
                else PyTheme.bg,
            )
            .border(1.dp, (if (s.paused) PyTheme.yellow else PyTheme.primary).copy(alpha = 0.25f), RectangleShape)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(s.name, style = PyType.mono(13f), color = nameColor, maxLines = 1)
            if (s.paused) {
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.carousel_paused), style = PyType.mono(8f), color = PyTheme.bg,
                    modifier = Modifier.background(PyTheme.yellow).padding(horizontal = 3.dp))
            } else if (s.fresh) {
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.carousel_fresh), style = PyType.mono(8f), color = PyTheme.bg,
                    modifier = Modifier.background(PyTheme.primary).padding(horizontal = 3.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.carousel_supplier_port, s.port),
                style = PyType.mono(11f), color = PyTheme.magenta,
                modifier = Modifier
                    .border(1.dp, PyTheme.magenta.copy(alpha = 0.6f), RectangleShape)
                    .clickableNoRipple { Haptics.tap(); clip.setText(AnnotatedString(Stratum.hostPortURL(s.port))) }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.carousel_supplier_txs, s.txs), style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f))
            Text(stringResource(R.string.carousel_supplier_jobs, s.jobs), style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f))
            Text(stringResource(R.string.carousel_supplier_miners, s.miners), style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f))
            if (s.feesSats > 0) {
                Text(stringResource(R.string.carousel_supplier_fees, s.feesSats / 100_000_000.0),
                    style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f))
            }
        }
    }
}

// ---- Connect ----

@Composable
private fun CarouselConnectCard() {
    val clip = LocalClipboardManager.current
    val url = Stratum.Pool.CAROUSEL.hostPortURL
    Column(
        Modifier
            .fillMaxWidth()
            .moduleFrame(Color.White)
            .padding(12.dp),
    ) {
        Text(stringResource(R.string.carousel_connect), style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(url, style = PyType.mono(11f), color = PyTheme.primary,
                maxLines = 1, modifier = Modifier.weight(1f))
            Text(
                "⧉", style = PyType.mono(14f), color = PyTheme.magenta,
                modifier = Modifier
                    .clickableNoRipple { Haptics.tap(); clip.setText(AnnotatedString(url)) }
                    .padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.carousel_connect_body),
            style = PyType.mono(10f), color = PyTheme.primaryDim,
        )
    }
}

// ---- Helpers ----

private val rotationFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

private fun rotationTime(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(rotationFormatter)
