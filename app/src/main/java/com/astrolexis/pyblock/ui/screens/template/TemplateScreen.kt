package com.astrolexis.pyblock.ui.screens.template

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.astrolexis.pyblock.data.model.TemplateAggregates
import com.astrolexis.pyblock.data.model.TemplateTx
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import com.astrolexis.pyblock.ui.theme.neonText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val SILVER = Color(0xFFCDD6D0)
private val TMPL_ORANGE = Color(0xFFFF9E3D)

data class TemplateUiState(
    val agg: TemplateAggregates? = null,
    val txs: List<TemplateTx> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class TemplateViewModel : ViewModel() {
    private val _state = MutableStateFlow(TemplateUiState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            coroutineScope {
                val a = async { runCatching { ApiClient.api.templateData() }.getOrNull() }
                val t = async { runCatching { ApiClient.api.templateTxs() }.getOrNull() }
                val agg = a.await()
                val txs = t.await()?.sortedByDescending { it.vbytes }?.take(2000) ?: emptyList()
                _state.update {
                    it.copy(loading = false, agg = agg, txs = txs, error = if (agg == null && txs.isEmpty()) "could not load template" else null)
                }
            }
        }
    }
}

@Composable
fun TemplateScreen() {
    val vm: TemplateViewModel = viewModel()
    val state by vm.state.collectAsState()
    var selected by remember { mutableStateOf<TemplateTx?>(null) }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        MarqueeTitle(text = stringResource(R.string.templates_block_template), accent = SILVER)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.templates_mempool_snapshot),
            style = PyType.mono(12f), color = SILVER.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(14.dp))

        when {
            state.loading && state.agg == null -> Row(
                Modifier.fillMaxWidth().padding(30.dp), horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator(color = PyTheme.primary) }
            state.agg != null -> {
                StatsCard(state.agg!!)
                Spacer(Modifier.height(14.dp))
                FeerateLegend()
                Spacer(Modifier.height(14.dp))
                TxGrid(state.txs, selected) { selected = it }
            }
        }
        selected?.let {
            Spacer(Modifier.height(14.dp))
            TxDetail(it)
        }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = PyType.mono(11f), color = PyTheme.danger)
        }
        Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StatsCard(a: TemplateAggregates) {
    Column(
        Modifier.fillMaxWidth().moduleFrame(SILVER).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.templates_height), style = PyType.mono(11f), color = SILVER.copy(alpha = 0.7f), letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text("${a.height}", style = PyType.mono(22f).neonText(SILVER), color = SILVER)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox(Modifier.weight(1f), stringResource(R.string.templates_total_weight), num(a.totalWeight), SILVER)
            StatBox(Modifier.weight(1f), stringResource(R.string.templates_total_vbytes), num(a.totalVBytes), PyTheme.cyan)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox(Modifier.weight(1f), stringResource(R.string.templates_min_fee_rate), String.format(Locale.US, "%.0f sat/vB", a.minFeeRate), SILVER)
            StatBox(Modifier.weight(1f), stringResource(R.string.templates_median_fee_rate), String.format(Locale.US, "%.1f sat/vB", a.medianFeeRate), PyTheme.magenta)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox(Modifier.weight(1f), stringResource(R.string.templates_max_fee_rate), String.format(Locale.US, "%.1f sat/vB", a.maxFeeRate), TMPL_ORANGE)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatBox(modifier: Modifier, label: String, value: String, color: Color) {
    Column(
        modifier.background(Color.Black.copy(alpha = 0.6f)).border(1.dp, color.copy(alpha = 0.6f), RectangleShape).padding(10.dp),
    ) {
        Text(label, style = PyType.mono(9f), color = PyTheme.primaryDim, letterSpacing = 1.sp)
        Text(value, style = PyType.mono(16f), color = color, maxLines = 1)
    }
}

@Composable
private fun FeerateLegend() {
    Column {
        Text(stringResource(R.string.templates_feerate_legend), style = PyType.mono(11f), color = SILVER, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.5 to "<1", 2.0 to "1+", 7.0 to "5+", 15.0 to "10+", 30.0 to "20+", 75.0 to "50+", 150.0 to "100+").forEach { (f, l) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(10.dp).background(feerateColor(f)))
                    Text(l, style = PyType.mono(10f), color = PyTheme.cyan)
                }
            }
        }
    }
}

@Composable
private fun TxGrid(txs: List<TemplateTx>, selected: TemplateTx?, onSelect: (TemplateTx) -> Unit) {
    val cells = remember(txs) { squarifyTemplate(txs) }
    Column(
        Modifier.fillMaxWidth().moduleFrame(SILVER).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.templates_transactions), style = PyType.mono(11f), color = SILVER, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.templates_count_shown, txs.size), style = PyType.mono(11f), color = PyTheme.primaryDim)
        }
        Text(stringResource(R.string.templates_grid_hint), style = PyType.mono(10f), color = PyTheme.primaryDim)
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color(0xFF02060A))
                .border(1.dp, SILVER.copy(alpha = 0.18f), RectangleShape)
                .pointerInput(cells) {
                    detectTapGestures { off ->
                        val nx = off.x / size.width
                        val ny = off.y / size.height
                        cells.lastOrNull { nx >= it.x && nx <= it.x + it.w && ny >= it.y && ny <= it.y + it.h }
                            ?.let { Haptics.select(); onSelect(it.tx) }
                    }
                },
        ) {
            val s = size.minDimension
            for (c in cells) {
                drawRect(feerateColor(c.tx.feerate), topLeft = Offset(c.x * s, c.y * s), size = Size(c.w * s, c.h * s))
                val sel = selected?.txid == c.tx.txid
                if (c.w * s > 3.5f && c.h * s > 3.5f) {
                    drawRect(
                        if (sel) Color.White else Color.Black.copy(alpha = 0.4f),
                        topLeft = Offset(c.x * s + 0.3f, c.y * s + 0.3f),
                        size = Size(max(0f, c.w * s - 0.6f), max(0f, c.h * s - 0.6f)),
                        style = Stroke(width = if (sel) 1.5f else 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TxDetail(tx: TemplateTx) {
    Column(
        Modifier.fillMaxWidth().moduleFrame(TMPL_ORANGE).padding(12.dp),
    ) {
        Text(stringResource(R.string.templates_transaction), style = PyType.mono(11f), color = TMPL_ORANGE, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        DetailRow(stringResource(R.string.templates_detail_txid), tx.txid.take(20) + "…")
        DetailRow(stringResource(R.string.templates_detail_fee_rate), String.format(Locale.US, "%.2f sat/vB", tx.feerate))
        DetailRow(stringResource(R.string.templates_detail_size), "${tx.vbytes.toInt()} vB")
        DetailRow(stringResource(R.string.templates_detail_fee), "${num(tx.fee)} sats")
    }
}

@Composable
private fun DetailRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, style = PyType.mono(12f), color = PyTheme.cyan)
        Spacer(Modifier.weight(1f))
        Text(v, style = PyType.mono(12f), color = PyTheme.yellow)
    }
}

// --- helpers ---

private fun num(v: Int): String = String.format(Locale.US, "%,d", v)

fun feerateColor(f: Double): Color = when {
    f >= 100 -> Color(0xFFFF0000)
    f >= 50 -> Color(0xFFFF8A00)
    f >= 20 -> Color(0xFFFFDE00)
    f >= 10 -> Color(0xFF00FF41)
    f >= 5 -> Color(0xFF00FFFF)
    f >= 1 -> Color(0xFF0087FF)
    else -> Color(0xFF8700FF)
}

data class TplCell(val tx: TemplateTx, val x: Float, val y: Float, val w: Float, val h: Float)

/** Squarified treemap over a 1×1 box, area ∝ vbytes. */
fun squarifyTemplate(txs: List<TemplateTx>): List<TplCell> {
    if (txs.isEmpty()) return emptyList()
    val nodes = txs.sortedByDescending { it.vbytes }
    val areas = nodes.map { max(1.0, it.vbytes) }
    val total = areas.sum()
    if (total <= 0) return emptyList()
    val scale = 1.0 / total
    val a = DoubleArray(nodes.size) { areas[it] * scale }

    fun worst(rowIdx: List<Int>, short: Float): Double {
        var s = 0.0; var mx = 0.0; var mn = 1e18
        for (i in rowIdx) { s += a[i]; mx = max(mx, a[i]); mn = min(mn, a[i]) }
        if (s <= 0) return Double.MAX_VALUE
        val sd = short.toDouble()
        return max((sd * sd * mx) / (s * s), (s * s) / (sd * sd * mn))
    }

    val out = ArrayList<TplCell>(nodes.size)
    var x = 0f; var y = 0f; var w = 1f; var h = 1f; var i = 0
    while (i < nodes.size) {
        val short = min(w, h)
        val row = arrayListOf(i); var i2 = i + 1
        while (i2 < nodes.size) {
            val cur = worst(row, short)
            row.add(i2)
            if (worst(row, short) > cur) { row.removeAt(row.size - 1); break }
            i2++
        }
        val s = row.sumOf { a[it] }
        val thick = (s / short).toFloat()
        var off = 0f
        for (idx in row) {
            val len = (a[idx] / thick).toFloat()
            out.add(if (w >= h) TplCell(nodes[idx], x, y + off, thick, len) else TplCell(nodes[idx], x + off, y, len, thick))
            off += len
        }
        if (w >= h) { x += thick; w -= thick } else { y += thick; h -= thick }
        i = i2
    }
    return out
}
