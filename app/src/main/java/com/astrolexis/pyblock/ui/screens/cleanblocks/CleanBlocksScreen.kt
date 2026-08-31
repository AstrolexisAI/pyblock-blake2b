package com.astrolexis.pyblock.ui.screens.cleanblocks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.res.stringResource
import com.astrolexis.pyblock.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.astrolexis.pyblock.data.model.CBBlock
import com.astrolexis.pyblock.data.model.CBDetail
import com.astrolexis.pyblock.data.model.CBStats
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import com.astrolexis.pyblock.ui.theme.neonText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanBlocksScreen() {
    val vm: CleanBlocksViewModel = viewModel()
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<CBDetail?>(null) }
    var showStats by remember { mutableStateOf(false) }

    // Poll only while resumed AND on screen — was polling every 5 s in the
    // background (coroutine delay keeps running) until navigated away.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            vm.start()
            try { kotlinx.coroutines.awaitCancellation() } finally { vm.stop() }
        }
    }

    fun open(b: CBBlock) {
        scope.launch {
            runCatching { ApiClient.api.cbDetail(b.height) }.getOrNull()?.let { detail = it }
        }
    }

    Box(Modifier.fillMaxSize().background(Cb.bg)) {
        Starfield(accent = Cb.green)
        Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 10.dp),
    ) {
        Header(state.shownClean, state.shownTotal - state.shownClean, state.tip)

        Spacer(Modifier.height(12.dp))
        val hero = state.hero
        if (hero != null) {
            Box(Modifier.padding(horizontal = 14.dp)) {
                HeroBlock(hero, state.minedFlash) { Haptics.tap(); detail = hero }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().height(280.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Cb.green)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.cblocks_connecting), style = Cb.mono(11f), color = Cb.faint)
            }
        }

        Spacer(Modifier.height(16.dp))
        RailSection(
            state.blocks, state.justMined, state.doneOlder, state.loadingOlder,
            onOlder = { Haptics.tap(); vm.loadOlder() }, onOpen = { Haptics.tap(); open(it) },
        )

        state.stats?.let {
            Spacer(Modifier.height(16.dp))
            Stats24Strip(it) { Haptics.tap(); showStats = true }
        }

        Spacer(Modifier.height(16.dp))
        Footer()
        }
    }

    detail?.let { d ->
        ModalBottomSheet(
            onDismissRequest = { detail = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Cb.surface,
        ) { BlockDetailSheet(d) }
    }

    if (showStats) {
        state.stats?.let { s ->
            ModalBottomSheet(
                onDismissRequest = { showStats = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Cb.surface,
            ) { Panel24h(s) }
        }
    }
}

@Composable
private fun Header(clean: Int, parasite: Int, tip: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Cb.green)) { append(stringResource(R.string.cblocks_header_clean, clean)) }
                withStyle(SpanStyle(color = Cb.faint)) { append(" / ") }
                withStyle(SpanStyle(color = Cb.red)) { append(stringResource(R.string.cblocks_header_parasite, parasite)) }
                withStyle(SpanStyle(color = Cb.faint)) { append(stringResource(R.string.cblocks_header_judged)) }
            },
            style = Cb.mono(12f), maxLines = 1, modifier = Modifier.weight(1f),
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Cb.faint)) { append(stringResource(R.string.cblocks_tip)) }
                withStyle(SpanStyle(color = Cb.green)) { append("#${CbFmt.n(tip)}") }
            },
            style = Cb.mono(12f),
        )
    }
}

@Composable
private fun RailSection(
    blocks: List<CBBlock>,
    justMined: Int?,
    doneOlder: Boolean,
    loadingOlder: Boolean,
    onOlder: () -> Unit,
    onOpen: (CBBlock) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(Cb.line2))
            Text(stringResource(R.string.cblocks_mined_timechain), style = Cb.mono(10f), color = Cb.faint)
            Box(Modifier.weight(1f).height(1.dp).background(Cb.line2))
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(blocks, key = { it.height }) { b ->
                RailCard(b, b.height == justMined, onOpen, Modifier.animateItem())
            }
            if (!doneOlder) {
                item {
                    Column(
                        Modifier.width(70.dp).height(160.dp).clickableNoRipple(onOlder),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (loadingOlder) CircularProgressIndicator(color = Cb.faint, modifier = Modifier.size(20.dp))
                        else Text("→", style = Cb.mono(20f), color = Cb.faint)
                        Text(stringResource(R.string.cblocks_older), style = Cb.mono(11f), color = Cb.faint)
                    }
                }
            }
        }
    }
}

@Composable
private fun RailCard(b: CBBlock, justMined: Boolean, onTap: (CBBlock) -> Unit, modifier: Modifier = Modifier) {
    val flash = remember { Animatable(if (justMined) 0.9f else 0f) }
    LaunchedEffect(justMined) {
        if (justMined) { flash.snapTo(0.9f); flash.animateTo(0f, tween(600)) }
    }
    Box(modifier) {
        Block3DCard(b) { onTap(b) }
        if (flash.value > 0f) {
            Box(Modifier.matchParentSize().background(Color.White.copy(alpha = flash.value * 0.6f)))
        }
    }
}

@Composable
private fun Stats24Strip(s: CBStats, onClick: () -> Unit) {
    val spamMb = s.parasiteVb / 1e6
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .moduleFrame(Cb.red)
            .clickableNoRipple(onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🛡", style = Cb.mono(26f))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(CbFmt.n(s.parasiteTx), style = Cb.mono(20f).neonText(Cb.redHi), color = Cb.redHi)
                Text(stringResource(R.string.cblocks_spam_txs), style = Cb.mono(10f), color = Cb.muted)
            }
            Text(
                stringResource(R.string.cblocks_reclaimed, String.format(java.util.Locale.US, "%.0f", spamMb)),
                style = Cb.mono(10f), color = Cb.faint,
            )
        }
    }
}

@Composable
private fun Footer() {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Cb.faint)) { append(stringResource(R.string.cblocks_footer_enforces)) }
            withStyle(SpanStyle(color = Cb.green)) { append(stringResource(R.string.cblocks_footer_antispam)) }
            withStyle(SpanStyle(color = Cb.faint)) {
                append(stringResource(R.string.cblocks_footer_tail))
            }
        },
        style = Cb.mono(10f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Spacer(Modifier.height(16.dp))
}
