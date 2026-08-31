package com.astrolexis.pyblock.ui.screens.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.wallet.UtxoInfo
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType

/** SEND coin picker — choose exactly which UTXOs fund the payment. [coins] must already be
 *  frozen-filtered by the caller (a frozen coin is NEVER shown or selectable). Mirrors iOS
 *  CoinSelectSheet. */
@Composable
fun CoinSelectDialog(
    coins: List<UtxoInfo>,
    selectedKeys: List<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sorted = coins.sortedByDescending { it.valueSats }
    val selected = sorted.filter { it.key in selectedKeys }
    val total = selected.sumOf { it.valueSats }
    val addrCount = selected.map { it.address }.filter { it.isNotEmpty() }.distinct().size

    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(PyTheme.bg).border(1.dp, PyTheme.cyan, RectangleShape).padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.coins_select_title), style = PyType.mono(15f), color = PyTheme.cyan, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f))
                if (selectedKeys.isNotEmpty()) Text(stringResource(R.string.coins_clear), style = PyType.mono(11f), color = PyTheme.primaryDim,
                    modifier = Modifier.clickableNoRipple { Haptics.tap(); onClear() })
                Spacer(Modifier.width(14.dp))
                Text("✕", style = PyType.mono(20f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { onDismiss() })
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.coins_selected_summary, selectedKeys.size, "%,d".format(total)),
                style = PyType.mono(11f), color = if (selectedKeys.isEmpty()) PyTheme.primaryDim else PyTheme.cyan)
            if (addrCount > 1) {
                Spacer(Modifier.height(4.dp))
                Text("⚠ " + stringResource(R.string.coins_link_warning, addrCount), style = PyType.mono(9f), color = PyTheme.yellow)
            }
            Spacer(Modifier.height(10.dp))
            if (sorted.isEmpty()) {
                Text(stringResource(R.string.coins_empty), style = PyType.mono(11f), color = PyTheme.primaryDim,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    sorted.forEach { c ->
                        val on = c.key in selectedKeys
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .border(1.dp, (if (on) PyTheme.cyan else PyTheme.primary).copy(alpha = 0.3f), RectangleShape)
                                .clickableNoRipple { Haptics.select(); onToggle(c.key) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(16.dp).border(1.dp, if (on) PyTheme.cyan else PyTheme.primaryDim, RectangleShape)
                                .background(if (on) PyTheme.cyan else androidx.compose.ui.graphics.Color.Transparent)) {
                                if (on) Text("✓", style = PyType.mono(11f), color = PyTheme.bg, modifier = Modifier.align(Alignment.Center))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.coins_value_sats, "%,d".format(c.valueSats)), style = PyType.mono(13f), color = PyTheme.yellow)
                                Text(if (c.label.isNotBlank()) c.label else shortAddr(c.address),
                                    style = PyType.mono(9f), color = PyTheme.primaryDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun shortAddr(a: String): String = if (a.length <= 22) a else a.take(10) + "…" + a.takeLast(6)
