package com.astrolexis.pyblock.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astrolexis.pyblock.data.model.BlockOddsResponse
import com.astrolexis.pyblock.data.model.BlocksResponse
import com.astrolexis.pyblock.data.model.CarouselStats
import com.astrolexis.pyblock.data.model.ChirpStats
import com.astrolexis.pyblock.data.model.DatumStats
import com.astrolexis.pyblock.data.model.DifficultyAdjustment
import com.astrolexis.pyblock.data.model.HashratePoint
import com.astrolexis.pyblock.data.model.HistoryRange
import com.astrolexis.pyblock.data.model.LottoStats
import com.astrolexis.pyblock.data.model.SV2Stats
import com.astrolexis.pyblock.data.net.ApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsUiState(
    val loading: Boolean = true,
    val lotto: LottoStats? = null,
    val datum: DatumStats? = null,
    val sv2: SV2Stats? = null,
    val chirp: ChirpStats? = null,
    val carousel: CarouselStats? = null,
    val odds: BlockOddsResponse? = null,
    val diff: DifficultyAdjustment? = null,
    val blocks: BlocksResponse? = null,
    val error: String? = null,
    val chartPool: ChartPool = ChartPool.LOTTO,
    val chartRange: HistoryRange = HistoryRange.D1,
    val chartPoints: List<HashratePoint> = emptyList(),
    val chartLoading: Boolean = false,
)

class StatsViewModel : ViewModel() {
    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init { refresh() }

    /** [showSpinner] true for initial load / pull-to-refresh (drives the
     *  pull indicator); false for silent background auto-refresh. */
    fun refresh(showSpinner: Boolean = true) {
        // Don't clear error at the start (avoids a banner flicker); the final
        // outcome sets it based on this refresh's reachability.
        _state.update { it.copy(loading = if (showSpinner) true else it.loading) }
        viewModelScope.launch {
            coroutineScope {
                val l = async { runCatching { ApiClient.api.lottoStats() }.getOrNull() }
                val d = async { runCatching { ApiClient.api.datumStats() }.getOrNull() }
                val s = async { runCatching { ApiClient.api.sv2Stats() }.getOrNull() }
                val c = async { runCatching { ApiClient.api.chirpStats() }.getOrNull() }
                val ca = async { runCatching { ApiClient.api.carouselStats() }.getOrNull() }
                val o = async { runCatching { ApiClient.api.blockOdds() }.getOrNull() }
                val b = async { runCatching { ApiClient.api.recentBlocks() }.getOrNull() }
                val df = async { runCatching { ApiClient.mempool.difficulty() }.getOrNull() }

                val lottoR = l.await(); val datumR = d.await(); val sv2R = s.await()
                val chirpR = c.await(); val carouselR = ca.await(); val oddsR = o.await()
                val blocksR = b.await()
                // NEXT DIFF ADJ is self-hosted (block_odds.diffadj); mempool
                // fallback only if absent.
                val diffR = oddsR?.diffAdj ?: df.await()

                _state.update { st ->
                    // PRESERVE LAST-GOOD: only overwrite a field when its fetch
                    // succeeded, so a failed/partial refresh keeps last-known values.
                    // No-fake-data: a well-formed 200 that omits telemetry decodes to
                    // an all-zeros object (kotlinx defaults). Treat an all-zeros pool
                    // as "no data" (keep last-good, else "—") instead of fabricating
                    // "0 GH/s / 0 workers".
                    val newLotto = (lottoR?.takeIf { it.hashrate1m > 0.0 || it.workers > 0 }) ?: st.lotto
                    val newDatum = (datumR?.takeIf { it.hashrate1m > 0.0 || it.workers > 0 }) ?: st.datum
                    val newSv2 = (sv2R?.takeIf { it.hashrate > 0.0 || it.workers > 0 }) ?: st.sv2
                    val newCarousel = (carouselR?.takeIf { it.hashrateTh > 0.0 || it.miners > 0 }) ?: st.carousel
                    val newChirp = (chirpR?.takeIf { it.hashrate > 0.0 || it.workers > 0 }) ?: st.chirp
                    val newOdds = oddsR ?: st.odds
                    val newBlocks = blocksR ?: st.blocks
                    // Only show the offline banner when there's genuinely nothing
                    // to display. Preserved last-good data stays silent.
                    val hasData = newLotto != null || newDatum != null || newSv2 != null ||
                        newChirp != null || newCarousel != null || newOdds != null || newBlocks != null
                    st.copy(
                        loading = false,
                        lotto = newLotto, datum = newDatum, sv2 = newSv2,
                        chirp = newChirp, carousel = newCarousel,
                        odds = newOdds, blocks = newBlocks,
                        diff = diffR ?: st.diff,
                        error = if (hasData) null else "could not reach pyblock.xyz",
                    )
                }
            }
            loadChart()
        }
    }

    fun setChartPool(pool: ChartPool) {
        if (pool == _state.value.chartPool) return
        _state.update { it.copy(chartPool = pool) }
        loadChart()
    }

    fun setChartRange(range: HistoryRange) {
        if (range == _state.value.chartRange) return
        _state.update { it.copy(chartRange = range) }
        loadChart()
    }

    private fun loadChart() {
        val pool = _state.value.chartPool
        val range = _state.value.chartRange
        _state.update { it.copy(chartLoading = true) }
        viewModelScope.launch {
            val result = runCatching { ChartsRepo.history(pool, range) }
            _state.update {
                // Ignore if the selection changed while fetching.
                if (it.chartPool != pool || it.chartRange != range) it
                else result.fold(
                    // Preserve the last-good chart on a transient failure instead
                    // of blanking it to "no data".
                    onSuccess = { pts -> it.copy(chartPoints = pts, chartLoading = false) },
                    onFailure = { _ -> it.copy(chartLoading = false) },
                )
            }
        }
    }
}
