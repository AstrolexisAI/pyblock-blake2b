package com.astrolexis.pyblock.ui.screens.wallet

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import com.astrolexis.pyblock.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.data.wallet.MempoolParse
import com.astrolexis.pyblock.data.wallet.WalletActivityCache
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.CopyGlyph
import com.astrolexis.pyblock.ui.components.WalletArrow
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import androidx.compose.ui.text.style.TextAlign
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class ActRow(
    val id: String, val txid: String, val net: Long, val address: String, val label: String,
    val confirmations: Int?, val timestamp: Long?, val height: Int?,
)

/** ACTIVITY — received (⬇) and sent (⬆) txs across all wallets, from the cache
 *  (primed from persisted SQLite). Mirrors the iOS WalletActivitySection. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WalletActivity(vm: WalletViewModel) {
    val ctx = LocalContext.current
    val wallets by WalletStore.wallets.collectAsState()
    var rows by remember { mutableStateOf<List<ActRow>>(emptyList()) }       // confirmed / node-tracked
    var pending by remember { mutableStateOf<List<ActRow>>(emptyList()) }    // 0-conf pulled live from the mempool

    // Reactive tx-history: re-read the cache the instant any node rewrites it
    // (send / receive / sync bump BdkNode.txCacheVersion) — no waiting for a poll.
    // Also keyed on tip so confirmation counts advance as blocks come in.
    val versionsSum = wallets.sumOf { vm.node(it).txCacheVersion.toLong() }
    val tipSum = wallets.sumOf { vm.node(it).tipHeight.toLong() }
    var primeDone by remember(wallets) { mutableStateOf(false) }
    LaunchedEffect(wallets, versionsSum, tipSum) {
        val prime = !primeDone
        val out = ArrayList<ActRow>()
        for (w in wallets) {
            val node = vm.node(w)
            var txs = WalletActivityCache.cached(ctx, w.id)
            if (txs.isEmpty() && prime) txs = WalletActivityCache.persisted(ctx, w)
            val tip = if (node.tipHeight > 0) node.tipHeight else w.cachedTip
            for (t in txs) if (t.net != 0L) {
                val conf = when {
                    !t.confirmed -> null
                    t.height != null && tip >= t.height -> tip - t.height + 1
                    else -> 0
                }
                out.add(ActRow(t.txid + w.id, t.txid, t.net, w.address, w.label, conf, t.timestamp, t.height))
            }
        }
        rows = out.sortedByDescending { it.timestamp ?: 0L }
        primeDone = true
    }

    // Live 0-conf: pull unconfirmed incoming payments straight from PyBLØCK's own
    // node so a received payment shows the instant the wallet opens — no waiting for
    // the round-robin sync or the first confirmation. Network poll only while the app
    // is resumed (paused in background), at a calm cadence.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(wallets) {
      lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while (true) {
            val pend = ArrayList<ActRow>()
            for (w in wallets) {
                val mtxs = try { ApiClient.api.walletMempool(w.address).txs } catch (e: Exception) { emptyList() }
                for (mtx in mtxs) {
                    val sats = MempoolParse.incomingSats(w.address, mtx.hex)
                    if (sats <= 0) continue
                    pend.add(ActRow(mtx.txid + w.id, mtx.txid, sats, w.address, w.label, 0,
                        if (mtx.seen > 0) mtx.seen else System.currentTimeMillis() / 1000, null))
                }
            }
            pending = pend
            delay(20_000)
        }
      }
    }

    // Newest always on top: unconfirmed rows first — conf NULL is also 0-conf
    // (a just-broadcast send from the cache has no timestamp yet and must never
    // sink to the bottom) — then newest-by-time within each group.
    val merged = remember(rows, pending, wallets) {
        run {
            val known = rows.mapTo(HashSet()) { it.id }
            (rows + pending.filter { it.id !in known }).sortedWith(
                compareByDescending<ActRow> { (it.confirmations ?: 0) == 0 }
                    .thenByDescending { it.timestamp ?: Long.MAX_VALUE }
            )
        }
    }

    var detail by remember { mutableStateOf<ActRow?>(null) }
    var blockDetail by remember { mutableStateOf<com.astrolexis.pyblock.data.model.CBDetail?>(null) }
    var loadingBlock by remember { mutableStateOf(false) }
    var blockMsg by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Text(stringResource(R.string.activity_header), style = PyType.mono(14f), color = PyTheme.yellow, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        if (merged.isEmpty()) {
            Text(stringResource(R.string.activity_empty),
                style = PyType.mono(10f), color = PyTheme.primaryDim)
        } else {
            merged.forEach { row -> ActivityRow(row) { detail = row } }
        }
    }

    detail?.let { r ->
        // "View on explorer" opens PyBLØCK's OWN CleanBlocks explorer (in-app), not a 3rd party.
        // Resolve the block by TXID. Only offered once the tx is confirmed (it's in a block by then).
        val confirmed = (r.confirmations ?: 0) > 0
        val notFoundMsg = stringResource(R.string.wallets_block_not_found)
        val onViewBlock: (() -> Unit)? = if (confirmed) {
            {
                loadingBlock = true
                blockMsg = null
                scope.launch {
                    val d = runCatching { ApiClient.api.cbDetailByTxid(r.txid, r.txid) }.getOrNull()
                    loadingBlock = false
                    if (d != null) { blockDetail = d; detail = null } else { blockMsg = notFoundMsg }
                }
            }
        } else null
        // Resolve strings HERE (inside the app's LocalizedApp) and pass them in — a Dialog is a
        // separate window whose stringResource doesn't follow the in-app language reliably.
        val inc = r.net >= 0
        val labels = TxLabels(
            title = stringResource(if (inc) R.string.activity_detail_received else R.string.activity_detail_sent),
            amount = stringResource(R.string.activity_amount_sats, "${if (inc) "+" else "−"}${"%,d".format(kotlin.math.abs(r.net))}"),
            address = stringResource(R.string.activity_detail_address),
            txid = stringResource(R.string.activity_detail_txid),
            explorer = stringResource(R.string.activity_detail_explorer),
            loading = stringResource(R.string.wallets_loading),
        )
        TxDetailDialog(r, labels, onViewBlock, loadingBlock, blockMsg) { detail = null; blockMsg = null; loadingBlock = false }
    }

    if (blockDetail != null) {
        // Capture the app's correctly-localized Context/Configuration HERE — this parent
        // composes under the root LocalizedApp (its own strings render in the chosen
        // language). A ModalBottomSheet is a separate window whose subcomposition may fall
        // back to the raw device locale, so we re-provide the captured (correct) context
        // inside it. Capturing outside the sheet is what makes this reliable — re-reading
        // LocalContext.current from within the sheet is the ambiguity that left it Spanish.
        val appCtx = LocalContext.current
        val appCfg = androidx.compose.ui.platform.LocalConfiguration.current
        val d = blockDetail!!
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { blockDetail = null },
            containerColor = com.astrolexis.pyblock.ui.screens.cleanblocks.Cb.surface,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalContext provides appCtx,
                androidx.compose.ui.platform.LocalConfiguration provides appCfg,
            ) {
                com.astrolexis.pyblock.ui.screens.cleanblocks.BlockDetailSheet(d)
            }
        }
    }
}

@Composable
private fun ActivityRow(r: ActRow, onClick: () -> Unit) {
    val incoming = r.net >= 0
    val color = if (incoming) PyTheme.primary else PyTheme.magenta
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp)
            .border(1.dp, PyTheme.primary.copy(alpha = 0.3f), RectangleShape)
            .clickableNoRipple { com.astrolexis.pyblock.ui.Haptics.tap(); onClick() }.padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 1.dp)) { WalletArrow(down = incoming, color = color, size = 16.dp, animated = false) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.activity_amount_sats, "${if (incoming) "+" else "−"}${"%,d".format(kotlin.math.abs(r.net))}"),
                style = PyType.mono(14f), color = color)
            Text("${r.label} · ${r.address.take(10)}…${r.address.takeLast(6)}",
                style = PyType.mono(9f), color = PyTheme.primaryDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(confLabel(r.confirmations), style = PyType.mono(10f),
                color = if ((r.confirmations ?: 0) > 0) PyTheme.primary else PyTheme.yellow)
            r.timestamp?.let { Text(ago(it), style = PyType.mono(9f), color = PyTheme.primaryDim) }
        }
    }
}

private data class TxLabels(val title: String, val amount: String, val address: String, val txid: String, val explorer: String, val loading: String)

@Composable
private fun TxDetailDialog(
    r: ActRow,
    labels: TxLabels,
    onViewBlock: (() -> Unit)?,
    loadingBlock: Boolean,
    blockMsg: String?,
    onDismiss: () -> Unit,
) {
    val clip = LocalClipboardManager.current
    val incoming = r.net >= 0
    val color = if (incoming) PyTheme.primary else PyTheme.magenta
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(PyTheme.bg).border(1.dp, color, RectangleShape)
            .heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WalletArrow(down = incoming, color = color, size = 16.dp, animated = false)
                Spacer(Modifier.width(8.dp))
                Text(labels.title, style = PyType.mono(15f), color = color, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text("✕", style = PyType.mono(20f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { onDismiss() })
            }
            Spacer(Modifier.height(12.dp))
            Text(labels.amount, style = PyType.mono(22f), color = color)
            Spacer(Modifier.height(4.dp))
            Text(confLabel(r.confirmations), style = PyType.mono(11f), color = if ((r.confirmations ?: 0) > 0) PyTheme.primary else PyTheme.yellow)
            r.timestamp?.let { Text(ago(it), style = PyType.mono(10f), color = PyTheme.primaryDim) }
            Spacer(Modifier.height(14.dp))
            DetailField(labels.address, r.address) { clip.setText(AnnotatedString(r.address)); Haptics.tap() }
            Spacer(Modifier.height(10.dp))
            DetailField(labels.txid, r.txid) { clip.setText(AnnotatedString(r.txid)); Haptics.tap() }
            if (onViewBlock != null) {
                Spacer(Modifier.height(14.dp))
                Text(if (loadingBlock) labels.loading else labels.explorer,
                    style = PyType.mono(12f), color = PyTheme.cyan, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.cyan, RectangleShape)
                        .clickableNoRipple { if (!loadingBlock) { Haptics.tap(); onViewBlock() } }.padding(vertical = 10.dp))
                if (blockMsg != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(blockMsg, style = PyType.mono(9f), color = PyTheme.yellow, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun DetailField(label: String, value: String, onCopy: () -> Unit) {
    Text(label, style = PyType.mono(9f), color = PyTheme.primaryDim, letterSpacing = 1.sp)
    Spacer(Modifier.height(2.dp))
    Row(Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = 0.4f), RectangleShape)
        .clickableNoRipple(onCopy).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(value, style = PyType.mono(11f), color = PyTheme.cyan, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        CopyGlyph(PyTheme.cyan, 12.dp)
    }
}

private fun confLabel(c: Int?): String = when {
    c == null -> "pending"
    c == 0 -> "0-conf"
    else -> "✓ $c conf"
}

private fun ago(tsSec: Long): String {
    val s = System.currentTimeMillis() / 1000 - tsSec
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}
