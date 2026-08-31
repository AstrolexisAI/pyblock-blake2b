package com.astrolexis.pyblock.ui.blake

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.astrolexis.pyblock.data.nostr.NostrClient
import com.astrolexis.pyblock.data.wallet.PendingPayment
import com.astrolexis.pyblock.data.wallet.WalletVault
import com.astrolexis.pyblock.ui.components.CrtOverlay
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.screens.chat.NostrChatScreen
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType

/** Four-tab shell for the dedicated BLAKE2b app — POOL · WALLET · CHAT · CHIRP.
 *  Mirrors the iOS RootView. Chat is the SAME shared community as the SHA-256 app
 *  (same relay + channel), so posts appear in one place across both apps. */
private data class BTab(val route: String, val label: String)
private val BTABS = listOf(
    BTab("pool", "POOL"),
    BTab("wallet", "WALLET"),
    BTab("chat", "CHAT"),
    BTab("chirp", "CHIRP"),
)

@Composable
fun BlakeRootScaffold() {
    val nav = rememberNavController()
    val current = nav.currentBackStackEntryAsState().value?.destination?.route

    // App-wide chat client (activity-scoped): connect while foregrounded, lock vault on background.
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

    Scaffold(
        containerColor = PyTheme.bg,
        bottomBar = { if (current != "vanity") BlakeBottomBar(nav) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            NavHost(navController = nav, startDestination = "pool") {
                composable("pool") { BlakePoolScreen() }
                composable("wallet") {
                    BlakeWalletScreen(onLaunchVanity = { nav.navigate("vanity") })
                }
                composable("chat") {
                    NostrChatScreen(
                        client = chat,
                        onClose = { nav.popBackStack() },
                        embedded = true,
                        onPay = { addr, amt, peer ->
                            PendingPayment.set(addr, amt, peer)
                            nav.navigate("wallet")
                        },
                        onBuy = { /* no mining-buy in the BLAKE2b app */ },
                    )
                }
                composable("chirp") { BlakeChirpScreen() }
                composable("vanity") {
                    com.astrolexis.pyblock.ui.screens.vanity.VanityAddressScreen(onClose = { nav.popBackStack() })
                }
            }
            CrtOverlay()
        }
    }
}

@Composable
private fun BlakeBottomBar(nav: NavHostController) {
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(PyTheme.primary.copy(alpha = 0.5f)))
        Row(
            Modifier.fillMaxWidth().background(PyTheme.bg).navigationBarsPadding().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BTABS.forEach { tab ->
                val selected = current == tab.route
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickableNoRipple {
                        if (current != tab.route) nav.navigate(tab.route) {
                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                ) {
                    Text(
                        tab.label,
                        style = PyType.mono(12f),
                        color = if (selected) PyTheme.primary else PyTheme.primaryDim,
                        textAlign = TextAlign.Center,
                        letterSpacing = 2.sp,
                    )
                }
            }
        }
    }
}
