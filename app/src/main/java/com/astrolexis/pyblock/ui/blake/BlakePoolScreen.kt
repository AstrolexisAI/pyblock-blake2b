package com.astrolexis.pyblock.ui.blake

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.blake.BlakeApi
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import kotlinx.coroutines.delay

/** POOL — read-only BLAKE2b pool telemetry + recent blocks. Mirrors iOS PoolView. */
@Composable
fun BlakePoolScreen() {
    var stats by remember { mutableStateOf<BlakeApi.PoolStats?>(null) }
    var status by remember { mutableStateOf<BlakeApi.Status?>(null) }
    var blocks by remember { mutableStateOf<List<BlakeApi.Block>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            BlakeApi.poolStats()?.let { stats = it }
            BlakeApi.status()?.let { status = it }
            BlakeApi.blocks().takeIf { it.isNotEmpty() }?.let { blocks = it }
            delay(20_000)
        }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            MarqueeTitle(text = "PyBLØCK ᛒ", accent = PyTheme.primary)
            Spacer(Modifier.height(4.dp))
            Text("BLAKE2b POOL", style = PyType.mono(11f), color = PyTheme.primaryDim, letterSpacing = 3.sp)

            // Not-yet-operational banner (auto-clears when the server says so).
            if (status?.operational == false) {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.yellow).padding(10.dp)) {
                    Text("⚠ BLAKE2b ${status?.rc ?: "RC"} · TESTING ON MAINNET", style = PyType.mono(11f), color = PyTheme.yellow, letterSpacing = 1.sp)
                    Text("The timechain is still being tested.", style = PyType.mono(9f), color = PyTheme.yellow.copy(alpha = 0.85f))
                }
            }

            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(14.dp)) {
                statRow("NETWORK HASHRATE", stats?.networkHashrateThs?.let { hashrateLabel(it) } ?: "—")
                statRow("BLOCK HEIGHT", (status?.blockHeight ?: stats?.blockHeight)?.let { "#$it" } ?: "—")
                statRow("MINERS", stats?.miners?.toString() ?: "—")
                statRow("CONNECTIONS", stats?.connections?.toString() ?: "—")
                statRow("SHARES ✓", stats?.sharesAccepted?.toString() ?: "—")
                statRow("SHARES ✗", stats?.sharesRejected?.toString() ?: "—")
            }

            Spacer(Modifier.height(18.dp))
            Text("BLOCKS FOUND", style = PyType.mono(13f), color = PyTheme.yellow, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            if (blocks.isEmpty()) {
                Text("No blocks yet.", style = PyType.mono(11f), color = PyTheme.primaryDim)
            } else {
                blocks.take(8).forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("#${b.height}", style = PyType.mono(15f), color = PyTheme.primary)
                        Spacer(Modifier.height(0.dp))
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        Text(b.finderMasked ?: (b.stratum ?: ""), style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.7f))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun statRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PyType.mono(11f), color = PyTheme.cyan.copy(alpha = 0.7f), letterSpacing = 1.sp)
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Text(value, style = PyType.mono(16f), color = PyTheme.yellow)
    }
}

private fun hashrateLabel(th: Double): String = when {
    th >= 1_000_000 -> "%.2f EH/s".format(th / 1_000_000)
    th >= 1_000 -> "%.1f PH/s".format(th / 1_000)
    th >= 1 -> "%.0f TH/s".format(th)
    else -> "%.0f GH/s".format(th * 1_000)
}
