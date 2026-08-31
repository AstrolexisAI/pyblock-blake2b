package com.astrolexis.pyblock.ui.screens.workers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.store.ChainStore
import com.astrolexis.pyblock.data.store.RigWatchStore
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.model.AddressWorkers
import com.astrolexis.pyblock.data.model.WorkerInfo
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.ui.components.RetroTextField
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import com.astrolexis.pyblock.ui.theme.neonText
import kotlinx.coroutines.launch

@Composable
fun WorkerSearchScreen() {
    var address by remember { mutableStateOf("") }
    var workers by remember { mutableStateOf<AddressWorkers?>(null) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { RigWatchStore.refresh() }   // load flag + armed targets

    fun search() {
        val a = address.trim()
        if (a.isEmpty()) return
        loading = true; searched = true; workers = null
        scope.launch {
            workers = runCatching { ApiClient.api.allWorkers()[a] }.getOrNull()
            loading = false
        }
    }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        MarqueeTitle(text = stringResource(R.string.wsearch_header))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.wsearch_subtitle), style = PyType.mono(12f), color = PyTheme.cyan.copy(alpha = 0.8f))
        Spacer(Modifier.height(12.dp))
        RetroTextField(address, { address = it }, "bc1q...")
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.wsearch_search_button), style = PyType.mono(14f), color = PyTheme.magenta, letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.magenta, RectangleShape)
                .clickableNoRipple { Haptics.thock(); search() }.padding(vertical = 10.dp),
        )
        Spacer(Modifier.height(16.dp))

        when {
            loading -> Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                CircularProgressIndicator(color = PyTheme.primary)
            }
            searched -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.wsearch_workers_label), style = PyType.mono(14f), color = PyTheme.yellow, letterSpacing = 3.sp)
                    Spacer(Modifier.weight(1f))
                    workers?.let { Text(stringResource(R.string.wsearch_workers_total, it.workers), style = PyType.mono(12f), color = PyTheme.cyan) }
                }
                Spacer(Modifier.height(8.dp))
                val list = workers?.worker.orEmpty()
                if (list.isEmpty()) {
                    Text(stringResource(R.string.wsearch_no_workers), style = PyType.mono(13f), color = PyTheme.primaryDim,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), textAlign = TextAlign.Center)
                } else {
                    list.forEach { w ->
                        WorkerRow(w)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun WorkerRow(w: WorkerInfo) {
    val suffix = w.workername.substringAfter('.', "").ifEmpty { stringResource(R.string.wsearch_default_worker) }
    val stale = (System.currentTimeMillis() / 1000 - w.lastshare) > 600
    val accent = if (stale) PyTheme.danger else PyTheme.primary
    val rw by RigWatchStore.state.collectAsState()
    val graceMin by RigWatchStore.graceDefault.collectAsState()
    val address = w.workername.substringBefore('.')
    val apiWorker = w.workername.substringAfter('.', "").ifEmpty { null }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().moduleFrame(accent).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (stale) "✗ " else "◆ ", style = PyType.mono(13f), color = accent)
            Text(suffix, style = PyType.mono(14f), color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(w.hashrate1m, style = PyType.mono(15f).neonText(PyTheme.yellow), color = PyTheme.yellow)
        }
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.wsearch_hashrate_1h_1d, w.hashrate1hr, w.hashrate1d), style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f))
        // Rig Watch — arm an offline alert for this worker. Hidden until the
        // server flag is on (dry-run). Tier enforced server-side.
        if (rw.enabled) {
            Spacer(Modifier.height(8.dp))
            val armed = rw.targets.firstOrNull { it.btcAddress == address && it.workerName == apiWorker && it.kind == "offline" }
            Text(
                if (busy) "…" else if (armed != null) stringResource(R.string.wsearch_alert_armed) else stringResource(R.string.wsearch_arm_alert),
                style = PyType.mono(12f), textAlign = TextAlign.Center,
                color = if (armed != null) PyTheme.bg else PyTheme.primary,
                modifier = Modifier.fillMaxWidth()
                    .background(if (armed != null) PyTheme.primary else Color.Transparent)
                    .border(1.dp, PyTheme.primary, RectangleShape)
                    .clickableNoRipple {
                        Haptics.select()
                        if (busy) return@clickableNoRipple
                        busy = true
                        scope.launch {
                            if (armed != null) { RigWatchStore.disarm(armed); warning = null }
                            else warning = RigWatchStore.armOffline(address, apiWorker, ChainStore.wire)
                            busy = false
                        }
                    }.padding(vertical = 8.dp),
            )
            warning?.let { Text(stringResource(R.string.wsearch_warning, it), style = PyType.mono(9f), color = PyTheme.yellow, modifier = Modifier.padding(top = 3.dp)) }
            Text(
                if (rw.isWhale) stringResource(R.string.wsearch_push_whale, graceMin / 60)
                else stringResource(R.string.wsearch_push_lotto, graceMin / 60),
                style = PyType.mono(9f), color = PyTheme.primaryDim, modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
