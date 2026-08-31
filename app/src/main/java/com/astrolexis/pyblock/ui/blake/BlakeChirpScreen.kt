package com.astrolexis.pyblock.ui.blake

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

/** CHIRP — read-only BLAKE2b shared-coinbase syndicate stats. Mirrors iOS ChirpView. */
@Composable
fun BlakeChirpScreen() {
    var pool by remember { mutableStateOf<BlakeApi.ChirpPool?>(null) }
    LaunchedEffect(Unit) {
        while (true) { BlakeApi.chirpPool()?.let { pool = it }; delay(20_000) }
    }
    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            MarqueeTitle(text = "CHIRP", accent = PyTheme.primary)
            Spacer(Modifier.height(4.dp))
            Text("Shared coinbase lottery · BLAKE2b", style = PyType.mono(10f), color = PyTheme.primaryDim, letterSpacing = 1.sp)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.yellow).padding(14.dp)) {
                row("HASHRATE", pool?.hashrate?.let { "%.0f TH/s".format(it) } ?: "—")
                row("WORKERS", pool?.workers?.toString() ?: "—")
                row("ELIGIBLE", pool?.candidates?.toString() ?: "—")
                row("BLOCKS", pool?.blocks?.toString() ?: "—")
                pool?.minDays?.let { row("MIN DAYS", it.toString()) }
                pool?.minPower?.let { row("MIN POWER", "%.0f TH/s".format(it)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun row(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PyType.mono(11f), color = PyTheme.cyan.copy(alpha = 0.7f), letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text(value, style = PyType.mono(16f), color = PyTheme.yellow)
    }
}
