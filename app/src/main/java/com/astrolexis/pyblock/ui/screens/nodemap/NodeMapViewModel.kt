package com.astrolexis.pyblock.ui.screens.nodemap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astrolexis.pyblock.data.model.LandRing
import com.astrolexis.pyblock.data.model.NodeMapData
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.data.net.LandRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NodeMapUiState(
    val map: NodeMapData? = null,
    val land: List<LandRing> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class NodeMapViewModel : ViewModel() {
    private val _state = MutableStateFlow(NodeMapUiState())
    val state: StateFlow<NodeMapUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val land = runCatching { LandRepo.land() }.getOrDefault(emptyList())
            _state.update { it.copy(land = land) }
            val map = runCatching { ApiClient.api.nodeMap() }.getOrNull()
            _state.update { it.copy(map = map, loading = false, error = if (map == null) "no data" else null) }
        }
    }
}
