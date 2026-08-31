package com.astrolexis.pyblock.ui.screens.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.wallet.UtxoInfo
import com.astrolexis.pyblock.data.wallet.UtxoMetaStore
import com.astrolexis.pyblock.data.wallet.VanityWallet
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame

/** COINS hub — every wallet's UTXOs, with freeze (do-not-spend) toggle + label. Mirrors iOS
 *  CoinsView. Freezing is enforced in BdkNode.send (a frozen coin is never spent). */
@Composable
fun CoinsScreen(vm: WalletViewModel, wallets: List<VanityWallet>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var coins by remember { mutableStateOf<List<UtxoInfo>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var labeling by remember { mutableStateOf<UtxoInfo?>(null) }

    LaunchedEffect(wallets, reload) {
        coins = wallets.flatMap { vm.node(it).unspentOutputs() }.sortedByDescending { it.valueSats }
    }

    val spendable = coins.filter { !it.frozen }.sumOf { it.valueSats }
    val frozen = coins.filter { it.frozen }.sumOf { it.valueSats }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", style = PyType.mono(28f), color = PyTheme.primaryDim,
                    modifier = Modifier.clickableNoRipple { Haptics.tap(); onBack() }.padding(horizontal = 10.dp))
                Text(stringResource(R.string.coins_title), style = PyType.mono(16f), color = PyTheme.cyan, letterSpacing = 3.sp)
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.cyan).padding(14.dp)) {
                    Text(stringResource(R.string.coins_spendable_sats, "%,d".format(spendable)), style = PyType.mono(13f), color = PyTheme.yellow)
                    if (frozen > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("❄ " + stringResource(R.string.coins_frozen_sats, "%,d".format(frozen)), style = PyType.mono(12f), color = PyTheme.cyan)
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (coins.isEmpty()) {
                    Text(stringResource(R.string.coins_empty), style = PyType.mono(12f), color = PyTheme.primaryDim,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                } else {
                    coins.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .border(1.dp, (if (c.frozen) PyTheme.cyan else PyTheme.primary).copy(alpha = 0.3f), RectangleShape)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.coins_value_sats, "%,d".format(c.valueSats)),
                                    style = PyType.mono(14f), color = if (c.frozen) PyTheme.primaryDim else PyTheme.yellow,
                                    textDecoration = if (c.frozen) TextDecoration.LineThrough else TextDecoration.None)
                                Text("${c.walletLabel} · ${if (c.label.isNotBlank()) c.label else shortAddr(c.address)}",
                                    style = PyType.mono(9f), color = PyTheme.primaryDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("✎", style = PyType.mono(13f), color = PyTheme.primaryDim,
                                modifier = Modifier.clickableNoRipple { labeling = c })
                            Spacer(Modifier.width(14.dp))
                            Text(if (c.frozen) stringResource(R.string.coins_frozen_badge) else "❄",
                                style = PyType.mono(if (c.frozen) 10f else 15f), color = PyTheme.cyan,
                                modifier = Modifier.clickableNoRipple {
                                    Haptics.select()
                                    UtxoMetaStore.toggleFrozen(ctx, c.key, c.valueSats, c.walletId)
                                    reload++
                                })
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    labeling?.let { c ->
        SaveToBookDialog(address = shortAddr(c.address), initialName = c.label, titleRes = R.string.coins_label_title,
            onSave = { nm -> UtxoMetaStore.setLabel(ctx, c.key, nm, c.walletId); reload++; labeling = null },
            onDismiss = { labeling = null })
    }
}
