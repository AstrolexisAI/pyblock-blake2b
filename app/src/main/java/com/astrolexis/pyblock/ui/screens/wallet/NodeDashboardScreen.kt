package com.astrolexis.pyblock.ui.screens.wallet

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.wallet.VanityWallet
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import com.astrolexis.pyblock.ui.theme.neonText

/** Real status of the on-device PyBLØCK NODE — chain tip, totals, and every
 *  watched wallet's sync state. Mirrors iOS NodeDashboardView. Observes the live
 *  per-wallet CBF nodes (resumeAll keeps them running; no navigation churn). */
@Composable
fun NodeDashboardScreen(vm: WalletViewModel, wallets: List<VanityWallet>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { com.astrolexis.pyblock.data.wallet.WalletSyncManager.resumeAll(ctx) }
    val nodes = wallets.map { vm.node(it) }
    val tip = nodes.maxOfOrNull { it.tipHeight } ?: 0
    val totalBal = nodes.sumOf { it.balanceSats }
    val totalPend = nodes.sumOf { it.pendingSats }
    val syncedCount = nodes.count { it.synced }
    val connected = nodes.any { it.running }
    val allSynced = wallets.isNotEmpty() && syncedCount == wallets.size

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
    Starfield()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { Haptics.tap(); onBack() })
            Spacer(Modifier.weight(1f))
            com.astrolexis.pyblock.ui.components.DnaGlyph(if (allSynced) PyTheme.primary else PyTheme.cyan, 15.dp, animated = !allSynced && connected)
            Spacer(Modifier.width(8.dp))
            Text("PyBLØCK NODE", style = PyType.mono(15f), color = PyTheme.yellow, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text("✕", style = PyType.mono(22f), color = Color.Transparent)   // balance the row
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.nodedash_verify_note),
            style = PyType.mono(12f), color = PyTheme.cyan)
        Spacer(Modifier.height(16.dp))

        // CHAIN TIP card
        Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.nodedash_chain_tip), style = PyType.mono(12f), color = PyTheme.primary, letterSpacing = 3.sp)
            Spacer(Modifier.height(6.dp))
            Text(if (tip > 0) "%,d".format(tip) else "—", style = PyType.mono(30f), color = PyTheme.yellow, maxLines = 1)   // sober vault: no glow
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when {
                    wallets.isEmpty() -> PyTheme.primaryDim
                    allSynced -> PyTheme.primary
                    connected -> PyTheme.yellow
                    else -> PyTheme.danger
                }
                Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                val statusLine = when {
                    wallets.isEmpty() -> stringResource(R.string.nodedash_status_idle)
                    allSynced -> stringResource(R.string.nodedash_status_synced)
                    !connected -> stringResource(R.string.nodedash_status_connecting)
                    else -> stringResource(R.string.nodedash_status_syncing, syncedCount, wallets.size)
                }
                Text(statusLine, style = PyType.mono(11f), color = PyTheme.cyan)
            }
        }
        Spacer(Modifier.height(16.dp))

        // aggregate stats
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(stringResource(R.string.nodedash_stat_wallets), "${wallets.size}", PyTheme.cyan, Modifier.weight(1f))
            Stat(stringResource(R.string.nodedash_stat_balance), "%,d".format(totalBal), PyTheme.primary, Modifier.weight(1f))
            Stat(stringResource(R.string.nodedash_stat_pending), "%,d".format(totalPend), if (totalPend > 0) PyTheme.yellow else PyTheme.primaryDim, Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.nodedash_wallets_watched), style = PyType.mono(12f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        if (wallets.isEmpty()) {
            Text(stringResource(R.string.nodedash_empty),
                style = PyType.mono(12f), color = PyTheme.primaryDim)
        } else {
            wallets.forEachIndexed { i, w ->
                val n = nodes[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)
                    .moduleFrame(PyTheme.primary).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if (n.synced) PyTheme.primary else PyTheme.yellow, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(w.label.ifEmpty { stringResource(R.string.nodedash_wallet_fallback) }.uppercase(), style = PyType.mono(13f), color = PyTheme.cyan, letterSpacing = 1.sp, maxLines = 1)
                        Text(if (n.synced) stringResource(R.string.nodedash_wallet_synced) else stringResource(R.string.nodedash_wallet_syncing, (n.filtersPct * 100).toInt()),
                            style = PyType.mono(10f), color = PyTheme.primaryDim)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(stringResource(R.string.nodedash_wallet_sats, n.balanceSats), style = PyType.mono(12f), color = PyTheme.yellow)
                        if (n.pendingSats > 0)
                            Text(stringResource(R.string.nodedash_wallet_zeroconf, n.pendingSats), style = PyType.mono(10f), color = PyTheme.yellow.copy(alpha = 0.8f))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.moduleFrame(color).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = PyType.mono(9f), color = PyTheme.primaryDim, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, style = PyType.mono(15f), color = color, maxLines = 1)
    }
}
