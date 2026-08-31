package com.astrolexis.pyblock.ui.screens.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.astrolexis.pyblock.data.model.AddressOrder
import com.astrolexis.pyblock.data.model.TrackedAddress
import com.astrolexis.pyblock.data.model.poolNameForPort
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.data.store.AddressStore
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrdersUiState(
    val orders: List<AddressOrder> = emptyList(),
    val loading: Boolean = true,
    val hasAddresses: Boolean = true,
)

class OrdersViewModel : ViewModel() {
    private val _state = MutableStateFlow(OrdersUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            AddressStore.addresses.collect { load(it) }
        }
    }

    private fun load(addrs: List<TrackedAddress>) {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            // Address-scoped orders (tracked addrs) + THIS DEVICE's own orders — so a
            // buyer sees their order even for an address they don't track.
            val results = coroutineScope {
                addrs.map { a ->
                    async { runCatching { ApiClient.api.ordersByAddress(a.address).orders } }
                }.awaitAll()
            }
            val deviceOrders = runCatching { com.astrolexis.pyblock.data.net.BuyRepo.ordersByDevice().orders }
            val anyOk = deviceOrders.isSuccess || results.any { it.isSuccess }
            val merged = (results.mapNotNull { it.getOrNull() }.flatten() +
                deviceOrders.getOrDefault(emptyList())).distinctBy { it.id }
            // Only replace on a successful fetch; on total failure keep last-good.
            _state.update {
                if (anyOk) it.copy(loading = false, orders = merged, hasAddresses = addrs.isNotEmpty() || merged.isNotEmpty())
                else it.copy(loading = false)
            }
        }
    }
}

@Composable
fun OrdersScreen() {
    val vm: OrdersViewModel = viewModel()
    val state by vm.state.collectAsState()

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        MarqueeTitle(text = stringResource(R.string.orders_title))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.orders_subtitle), style = PyType.mono(12f), color = PyTheme.cyan.copy(alpha = 0.8f))
        Spacer(Modifier.height(14.dp))

        when {
            state.loading -> Row(Modifier.fillMaxWidth().padding(30.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = PyTheme.primary)
            }
            !state.hasAddresses -> com.astrolexis.pyblock.ui.components.PressStart(
                headline = stringResource(R.string.arcade_insert_coin),
                message = stringResource(R.string.orders_empty_no_addresses),
            )
            state.orders.isEmpty() -> com.astrolexis.pyblock.ui.components.PressStart(
                headline = stringResource(R.string.arcade_press_start),
                message = stringResource(R.string.orders_empty_no_orders),
            )
            else -> state.orders.forEach { o ->
                OrderRow(o)
                Spacer(Modifier.height(8.dp))
            }
        }
        }
    }
}

@Composable
private fun OrderRow(o: AddressOrder) {
    val color = orderColor(o.status)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .moduleFrame(color)
            .padding(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.orders_row_pool_id, poolNameForPort(o.port), o.id.take(8)), style = PyType.mono(14f), color = PyTheme.primary)
            Text(stringResource(R.string.orders_row_sats_hashrate, o.totalSats, "%.1f".format(o.hashratePhs)), style = PyType.mono(11f), color = PyTheme.cyan.copy(alpha = 0.7f))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(o.status.uppercase(), style = PyType.mono(12f), color = color)
            // active/submitted = being delivered — a near-empty budget bar early in a
            // multi-hour order is normal, so show a positive state, not a stuck "0%".
            when {
                // Server flags a REAL stall (consumed_sat not growing >20min) via error_msg.
                o.status == "active" && o.errorMsg == "Delivery stalled" ->
                    Text(stringResource(R.string.orders_row_stalled), style = PyType.mono(10f), color = PyTheme.danger)
                o.status == "active" ->
                    Text(stringResource(R.string.orders_row_delivering, "%.1f".format(o.hashratePhs)), style = PyType.mono(10f), color = PyTheme.primary)
                o.status == "submitted" ->
                    Text(stringResource(R.string.orders_row_starting), style = PyType.mono(10f), color = PyTheme.cyan)
                else ->
                    Text(stringResource(R.string.orders_row_pct_consumed, o.pctConsumed), style = PyType.mono(11f), color = PyTheme.yellow)
            }
        }
    }
    if ((o.status == "active" && o.errorMsg != "Delivery stalled") || o.status == "submitted") {
        Text(stringResource(R.string.orders_budget_gradual, "%.0f".format(o.durationH)),
            style = PyType.mono(9f), color = PyTheme.primaryDim, modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 6.dp))
    }
}

private fun orderColor(status: String): Color = when (status) {
    "settled" -> Color(0xFF00FF41)
    "active" -> Color(0xFFFFFF00)
    "submitted", "paid" -> Color(0xFF00FFFF)
    "pending_payment" -> Color(0xFFFF00FF)
    "expired", "failed" -> Color(0xFFFF5555)
    else -> Color(0xFF8C8C8C)
}
