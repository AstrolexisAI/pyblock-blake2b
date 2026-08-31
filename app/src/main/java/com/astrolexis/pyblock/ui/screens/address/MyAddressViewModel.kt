package com.astrolexis.pyblock.ui.screens.address

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astrolexis.pyblock.data.model.AddressOrder
import com.astrolexis.pyblock.data.model.AddressWorkers
import com.astrolexis.pyblock.data.model.HashratePoint
import com.astrolexis.pyblock.data.model.OceanData
import com.astrolexis.pyblock.data.model.TrackedAddress
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.data.store.AddressStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddressUiState(
    val addresses: List<TrackedAddress> = emptyList(),
    val selected: String? = null,
    val loading: Boolean = false,
    val workers: AddressWorkers? = null,
    val ocean: OceanData? = null,
    val orders: List<AddressOrder> = emptyList(),
    val chart: List<HashratePoint> = emptyList(),
)

class MyAddressViewModel : ViewModel() {
    private val _state = MutableStateFlow(AddressUiState())
    val state: StateFlow<AddressUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            AddressStore.addresses.collect { list ->
                val prev = _state.value.selected
                val sel = prev?.takeIf { s -> list.any { it.address == s } } ?: list.firstOrNull()?.address
                _state.update { it.copy(addresses = list, selected = sel) }
                when {
                    sel == null -> _state.update {
                        it.copy(workers = null, ocean = null, orders = emptyList(), chart = emptyList())
                    }
                    sel != prev -> load(sel)
                }
            }
        }
    }

    fun select(addr: String) {
        if (addr == _state.value.selected) return
        _state.update { it.copy(selected = addr, workers = null, ocean = null, orders = emptyList(), chart = emptyList()) }
        load(addr)
    }

    fun add(address: String, label: String?) = AddressStore.add(address, label)
    fun remove(address: String) = AddressStore.remove(address)
    fun refresh() = _state.value.selected?.let { load(it) } ?: Unit

    private fun load(addr: String) {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            coroutineScope {
                // Capture Result (not getOrNull) so a network failure is
                // distinguished from a genuine "no data", and each field
                // PRESERVES its last-good value on a failed refresh instead of
                // blanking. (select() already clears when switching address.)
                val w = async { runCatching { ApiClient.api.allWorkers() } }
                val o = async { runCatching { ApiClient.api.ocean(addr) } }
                val ord = async { runCatching { ApiClient.api.ordersByAddress(addr).orders } }
                val ch = async { runCatching { ApiClient.api.minerHistory(addr) } }
                val wR = w.await(); val oR = o.await(); val ordR = ord.await(); val chR = ch.await()
                _state.update {
                    if (it.selected != addr) it
                    else it.copy(
                        loading = false,
                        workers = wR.map { m -> m[addr] }.getOrDefault(it.workers),
                        ocean = oR.map { d -> d?.takeIf { x -> x.hasData } }.getOrDefault(it.ocean),
                        orders = ordR.getOrDefault(it.orders),
                        chart = chR.getOrDefault(it.chart),
                    )
                }
            }
        }
    }
}
