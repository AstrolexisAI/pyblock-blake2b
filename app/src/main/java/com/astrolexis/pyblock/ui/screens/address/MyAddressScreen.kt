package com.astrolexis.pyblock.ui.screens.address

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.model.AddressOrder
import com.astrolexis.pyblock.data.model.AddressWorkers
import com.astrolexis.pyblock.data.model.OceanData
import com.astrolexis.pyblock.data.model.TrackedAddress
import com.astrolexis.pyblock.data.model.WorkerInfo
import com.astrolexis.pyblock.data.model.poolNameForPort
import com.astrolexis.pyblock.ui.components.HashrateChart
import com.astrolexis.pyblock.ui.components.RetroTextField
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import com.astrolexis.pyblock.ui.theme.neonText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyAddressScreen(onLaunchVanity: () -> Unit = {}) {
    val vm: MyAddressViewModel = viewModel()
    val state by vm.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = { vm.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            Starfield()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarqueeTitle(text = stringResource(R.string.addr_my_address))
                Spacer(Modifier.weight(1f))
                if (state.addresses.isNotEmpty()) {
                    Text(
                        if (showAdd) "✕" else stringResource(R.string.addr_add),
                        style = PyType.mono(13f), color = PyTheme.magenta,
                        modifier = Modifier.clickableNoRipple { Haptics.select(); showAdd = !showAdd },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Text(
                stringResource(R.string.addr_generate_vanity),
                style = PyType.mono(14f), color = PyTheme.magenta, letterSpacing = 1.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, PyTheme.magenta.copy(alpha = 0.6f), androidx.compose.ui.graphics.RectangleShape)
                    .clickableNoRipple { Haptics.thock(); onLaunchVanity() }
                    .padding(vertical = 12.dp),
            )
            Spacer(Modifier.height(12.dp))

            if (state.addresses.size > 1) {
                AddressChips(state.addresses, state.selected) { vm.select(it) }
                Spacer(Modifier.height(12.dp))
            }

            if (showAdd || state.addresses.isEmpty()) {
                AddForm { addr, label ->
                    vm.add(addr, label)
                    showAdd = false
                }
                Spacer(Modifier.height(12.dp))
            }

            val sel = state.selected
            if (sel != null) {
                SelectedHeader(sel) { vm.remove(sel) }
                Spacer(Modifier.height(14.dp))

                Text(stringResource(R.string.addr_hashrate_history), style = PyType.mono(13f), color = PyTheme.yellow, letterSpacing = 3.sp)
                Spacer(Modifier.height(6.dp))
                Panel { HashrateChart(state.chart, PyTheme.primary) }

                Spacer(Modifier.height(14.dp))
                WorkersSection(sel, state.workers)

                state.ocean?.let {
                    Spacer(Modifier.height(14.dp))
                    OceanEarningsCard(it)
                }

                if (state.orders.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    OrdersSection(state.orders)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        }
    }
}

@Composable
private fun AddressChips(addresses: List<TrackedAddress>, selected: String?, onSelect: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        addresses.forEach { a ->
            val active = a.address == selected
            val label = a.label?.uppercase() ?: a.address.takeLast(8)
            Text(
                label,
                style = PyType.mono(12f),
                color = if (active) PyTheme.yellow else PyTheme.cyan,
                modifier = Modifier
                    .background(if (active) PyTheme.yellow.copy(alpha = 0.1f) else Color.Transparent)
                    .border(1.dp, if (active) PyTheme.yellow else PyTheme.primary.copy(alpha = 0.4f), RectangleShape)
                    .clickableNoRipple { Haptics.select(); onSelect(a.address) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun AddForm(onTrack: (String, String?) -> Unit) {
    var address by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .moduleFrame(PyTheme.magenta)
            .padding(12.dp),
    ) {
        RetroTextField(address, { address = it }, "bc1q...")
        Spacer(Modifier.height(8.dp))
        RetroTextField(label, { label = it }, stringResource(R.string.addr_label_optional), color = PyTheme.cyan)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.addr_track),
            style = PyType.mono(14f), color = PyTheme.magenta, letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, PyTheme.magenta, RectangleShape)
                .clickableNoRipple {
                    Haptics.thock()
                    if (address.isNotBlank()) {
                        onTrack(address, label.ifBlank { null })
                        address = ""; label = ""
                    }
                }
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun SelectedHeader(address: String, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .moduleFrame(PyTheme.primary)
            .padding(12.dp),
    ) {
        Text(
            address,
            style = PyType.mono(13f), color = PyTheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.addr_remove), style = PyType.mono(11f), color = PyTheme.danger, letterSpacing = 2.sp,
            modifier = Modifier.clickableNoRipple { Haptics.warning(); onRemove() })
    }
}

@Composable
private fun WorkersSection(address: String, workers: AddressWorkers?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.addr_workers), style = PyType.mono(14f), color = PyTheme.yellow, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            workers?.let { Text(stringResource(R.string.addr_workers_total, it.workers), style = PyType.mono(12f), color = PyTheme.cyan) }
        }
        Spacer(Modifier.height(8.dp))
        val list = workers?.worker.orEmpty()
        if (list.isEmpty()) {
            Text(stringResource(R.string.addr_no_data), style = PyType.mono(13f), color = PyTheme.primaryDim,
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), textAlign = TextAlign.Center)
        } else {
            list.forEach { w ->
                WorkerRow(w)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun WorkerRow(w: WorkerInfo) {
    val suffix = w.workername.substringAfter('.', "").ifEmpty { stringResource(R.string.addr_worker_default) }
    val stale = (System.currentTimeMillis() / 1000 - w.lastshare) > 600
    val accent = if (stale) PyTheme.danger else PyTheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .moduleFrame(accent)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (stale) "✗ " else "◆ ", style = PyType.mono(13f), color = accent)
            Text(suffix, style = PyType.mono(14f), color = accent, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(w.hashrate1m, style = PyType.mono(15f).neonText(PyTheme.yellow), color = PyTheme.yellow)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.addr_worker_rates, w.hashrate1hr, w.hashrate1d),
            style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun OceanEarningsCard(o: OceanData) {
    Column(
        Modifier
            .fillMaxWidth()
            .moduleFrame(PyTheme.primary)
            .padding(14.dp),
    ) {
        Text(stringResource(R.string.addr_earnings_header), style = PyType.mono(13f), color = PyTheme.primary, letterSpacing = 3.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            EarnStat(stringResource(R.string.addr_earn_unpaid), o.unpaid.unpaid)
            EarnStat(stringResource(R.string.addr_earn_lifetime), o.lifetime.lifetime)
            EarnStat(stringResource(R.string.addr_earn_est_day), o.lifetime.estPerDay)
        }
        o.unpaid.blocksFound?.let {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.addr_ocean_blocks, it), style = PyType.mono(10f), color = PyTheme.primaryDim)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.EarnStat(label: String, btc: String?) {
    Column(Modifier.weight(1f)) {
        Text(label, style = PyType.mono(9f), color = PyTheme.cyan.copy(alpha = 0.6f), letterSpacing = 1.sp)
        Text(btc?.let { "$it BTC" } ?: "—", style = PyType.mono(13f), color = PyTheme.yellow, maxLines = 1)
    }
}

@Composable
private fun OrdersSection(orders: List<AddressOrder>) {
    Column {
        Text(stringResource(R.string.addr_orders_title), style = PyType.mono(13f), color = PyTheme.yellow, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        orders.forEach { o ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .moduleFrame(PyTheme.cyan)
                    .padding(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${poolNameForPort(o.port)} #${o.id.take(8)}", style = PyType.mono(13f), color = PyTheme.primary)
                    Text("${o.totalSats} sats · ${"%.1f".format(o.hashratePhs)} PH/s",
                        style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(o.status.uppercase(), style = PyType.mono(11f), color = PyTheme.yellow)
                    Text("${o.pctConsumed}%", style = PyType.mono(10f), color = PyTheme.primaryDim)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .moduleFrame(PyTheme.primary)
            .padding(12.dp),
    ) { content() }
}
