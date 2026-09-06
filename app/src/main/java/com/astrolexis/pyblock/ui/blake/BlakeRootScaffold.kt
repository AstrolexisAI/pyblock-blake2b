package com.astrolexis.pyblock.ui.blake

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.astrolexis.pyblock.data.blake.BlakeBalanceStore
import com.astrolexis.pyblock.data.nostr.NostrClient
import com.astrolexis.pyblock.data.wallet.PendingPayment
import com.astrolexis.pyblock.data.wallet.WalletVault
import com.astrolexis.pyblock.ui.components.clickableNoRipple

/** Four-tab shell for the dedicated BLAKE2b app — POOL · WALLET · CHAT · CHIRP.
 *  Mirrors iOS RootView: flat black, floating capsule tab bar, app-wide "RECEIVED" banner. */
private data class BTab(val route: String, val label: String, val icon: ImageVector)
private val BTABS = listOf(
    BTab("pool", "POOL", Icons.Filled.ShowChart),
    BTab("wallet", "WALLET", Icons.Filled.AccountBalanceWallet),
    BTab("chat", "CHAT", Icons.Filled.Forum),
    BTab("chirp", "CHIRP", Icons.Filled.Groups),
    BTab("wavicles", "WAVICLES", Icons.Filled.Waves),
)

@Composable
fun BlakeRootScaffold() {
    val ctx = LocalContext.current
    val nav = rememberNavController()
    val current = nav.currentBackStackEntryAsState().value?.destination?.route

    val chat: NostrClient = viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> chat.connect()
                Lifecycle.Event.ON_STOP -> { chat.disconnect(); WalletVault.lock() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Seed + start the live push stream app-wide (mirrors iOS RootView.task) so the
    // "received" banner fires on ANY tab, not just when WALLET is open.
    LaunchedEffect(Unit) {
        com.astrolexis.pyblock.data.blake.BlakeSentStore.init(ctx)
        BlakeBalanceStore.refresh(ctx)
        BlakeBalanceStore.startLive(ctx)
    }

    // App-wide "received" banner — fires on any tab when the balance rises.
    val receiveEvent by BlakeBalanceStore.receiveEvent.collectAsState()
    LaunchedEffect(receiveEvent) {
        if (receiveEvent != null) {
            com.astrolexis.pyblock.ui.Haptics.tap()
            kotlinx.coroutines.delay(3_600)
            BlakeBalanceStore.clearReceiveEvent()
        }
    }

    Scaffold(
        containerColor = Blake.bg,
        bottomBar = { if (current != "vanity") BlakeTabBar(nav, current) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(Blake.bg)) {
            NavHost(navController = nav, startDestination = "pool") {
                composable("pool") { BlakePoolScreen() }
                composable("wallet") { BlakeWalletScreen(onLaunchVanity = { nav.navigate("vanity") }) }
                composable("chat") {
                    BlakeChatScreen(
                        client = chat,
                        onPay = { addr, amt, peer -> PendingPayment.set(addr, amt, peer); nav.navigate("wallet") },
                    )
                }
                composable("chirp") { BlakeChirpScreen() }
                composable("wavicles") { BlakeWaviclesScreen() }
                composable("vanity") { BlakeVanityScreen(onClose = { nav.popBackStack() }) }
            }

            // Received banner overlay (top).
            AnimatedVisibility(
                visible = receiveEvent != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                val ev = receiveEvent
                Row(
                    Modifier.statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth()
                        .background(Blake.ink, Blake.shape).border(1.dp, Blake.ok.copy(alpha = 0.7f), Blake.shape).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⬇", style = Blake.mono(18f, FontWeight.ExtraBold), color = Blake.ok)
                    Spacer(Modifier.size(10.dp))
                    Column {
                        Text("RECEIVED", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.ok, letterSpacing = 2.sp)
                        Text("+${Blake.btc(ev?.deltaSats ?: 0)} ${Blake.RUNE}", style = Blake.mono(13f, FontWeight.ExtraBold), color = Blake.hero)
                    }
                }
            }
        }
    }
}

@Composable
private fun BlakeTabBar(nav: NavHostController, current: String?) {
    Box(Modifier.fillMaxWidth().background(Blake.bg).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().background(Blake.ink, RoundedCornerShape(26.dp))
                .border(1.dp, Blake.line, RoundedCornerShape(26.dp)).padding(6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BTABS.forEach { tab ->
                val selected = current == tab.route
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickableNoRipple {
                            if (current != tab.route) nav.navigate(tab.route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        }
                        .background(if (selected) Blake.pp.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(tab.icon, contentDescription = tab.label,
                        tint = if (selected) Blake.pp else Blake.ppDim, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(3.dp))
                    Text(tab.label, style = Blake.mono(8f, FontWeight.ExtraBold),
                        color = if (selected) Blake.pp else Blake.ppDim, letterSpacing = 1.sp)
                }
            }
        }
    }
}
