package com.astrolexis.pyblock.ui.screens.cleanblocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astrolexis.pyblock.data.model.CBBlock
import com.astrolexis.pyblock.data.model.CBDetail
import com.astrolexis.pyblock.data.model.CBStats
import com.astrolexis.pyblock.data.net.ApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

data class CbUiState(
    val hero: CBDetail? = null,
    val blocks: List<CBBlock> = emptyList(),
    val stats: CBStats? = null,
    val tip: Int = 0,
    val minedFlash: Int = 0,
    val justMined: Int? = null,
    val loadingOlder: Boolean = false,
    val doneOlder: Boolean = false,
) {
    val shownClean get() = blocks.count { it.clean }
    val shownTotal get() = blocks.size
}

class CleanBlocksViewModel : ViewModel() {
    private val _state = MutableStateFlow(CbUiState())
    val state: StateFlow<CbUiState> = _state.asStateFlow()

    private var heroHeight = 0
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = viewModelScope.launch {
            var i = 0
            while (isActive) {
                refreshHero()
                refreshChain()
                if (i % 6 == 0) loadStats()
                i++
                delay(5000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** The candidate advances the instant a block is mined — our mine signal. */
    private suspend fun refreshHero() {
        val p = runCatching { ApiClient.api.cbPending() }.getOrNull() ?: return
        val prev = heroHeight
        heroHeight = p.height
        _state.update { it.copy(hero = p) }
        if (prev == 0 || p.height <= prev) return
        for (h in prev until p.height) {
            if (_state.value.blocks.any { it.height == h }) continue
            val d = runCatching { ApiClient.api.cbDetail(h) }.getOrNull() ?: continue
            chainIn(d.asMinedBlock())
        }
    }

    private fun chainIn(b: CBBlock) {
        _state.update { s ->
            s.copy(
                tip = max(s.tip, b.height),
                minedFlash = s.minedFlash + 1,
                justMined = b.height,
                blocks = listOf(b) + s.blocks.filter { it.height != b.height },
            )
        }
        viewModelScope.launch {
            delay(1800)
            _state.update { if (it.justMined == b.height) it.copy(justMined = null) else it }
        }
    }

    private suspend fun refreshChain() {
        val r = runCatching { ApiClient.api.cbRecent() }.getOrNull() ?: return
        val fresh = (r.blocks ?: emptyList()).sortedByDescending { it.height }
        if (fresh.isEmpty()) return
        _state.update { s ->
            val newTop = fresh.first().height
            val minNew = fresh.last().height
            val ahead = s.blocks.filter { it.height > newTop }
            val tail = s.blocks.filter { it.height < minNew }
            val seen = HashSet<Int>()
            val merged = ArrayList<CBBlock>()
            for (b in ahead + fresh + tail) if (seen.add(b.height)) merged.add(b)
            s.copy(tip = max(max(s.tip, r.tip ?: 0), newTop), blocks = merged)
        }
    }

    private suspend fun loadStats() {
        val st = runCatching { ApiClient.api.cbStats() }.getOrNull() ?: return
        _state.update { it.copy(stats = st) }
    }

    fun loadOlder() {
        val s = _state.value
        if (s.loadingOlder || s.doneOlder || s.blocks.size >= 60) return
        val minH = s.blocks.lastOrNull()?.height ?: return
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            val older = runCatching { ApiClient.api.cbOlder(minH).blocks }
                .getOrNull()?.sortedByDescending { it.height }
            _state.update { st ->
                if (older.isNullOrEmpty()) {
                    st.copy(loadingOlder = false, doneOlder = true)
                } else {
                    val known = st.blocks.map { it.height }.toHashSet()
                    st.copy(loadingOlder = false, blocks = st.blocks + older.filter { it.height !in known })
                }
            }
        }
    }
}
