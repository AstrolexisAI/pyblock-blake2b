package com.astrolexis.pyblock.ui.screens.stats

import androidx.compose.ui.graphics.Color
import com.astrolexis.pyblock.data.model.HashratePoint
import com.astrolexis.pyblock.data.model.HistoryRange
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.ui.theme.PyCyan
import com.astrolexis.pyblock.ui.theme.PyMagenta
import com.astrolexis.pyblock.ui.theme.PyYellow

/** Selectable series for the hashrate-history chart. LOTTO's colour is
 *  overridden to the themeable primary at draw time. */
enum class ChartPool(val display: String, val color: Color) {
    LOTTO("LOTTO", Color(0xFF00FF41)),
    DATUM("DATUM", PyMagenta),
    SV2("SV2", PyCyan),
    CHIRP("CHIRP", PyYellow),
    CAROUSEL("🎠", Color.White),
}

object ChartsRepo {
    suspend fun history(pool: ChartPool, range: HistoryRange): List<HashratePoint> {
        val raw = when (pool) {
            ChartPool.LOTTO -> ApiClient.api.lottoHistory(range = range.serverRange)
            ChartPool.DATUM -> ApiClient.api.datumHistory(range = range.serverRange)
            ChartPool.CAROUSEL -> ApiClient.api.carouselHistory(range = range.serverRange)
            ChartPool.SV2 -> ApiClient.api.sv2History(hours = range.hours)
                .map { HashratePoint(it.ts, it.hashrateTh) }
            ChartPool.CHIRP -> ApiClient.api.chirpHistory(hours = range.hours)
                .map { HashratePoint(it.ts, it.hashrateTh) }
        }
        return windowed(raw, range)
    }

    private fun windowed(raw: List<HashratePoint>, range: HistoryRange): List<HashratePoint> {
        val cutoff = System.currentTimeMillis() / 1000 - range.windowSeconds
        return raw.filter { it.timestamp >= cutoff }.sortedBy { it.timestamp }
    }
}
