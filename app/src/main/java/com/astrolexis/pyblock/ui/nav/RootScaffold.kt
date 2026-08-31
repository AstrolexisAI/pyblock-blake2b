package com.astrolexis.pyblock.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MoreHoriz
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.astrolexis.pyblock.data.nostr.NostrClient
import com.astrolexis.pyblock.data.nostr.unreadDmCount
import com.astrolexis.pyblock.data.wallet.WalletVault
import com.astrolexis.pyblock.ui.components.CrtOverlay
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.screens.vault.VaultGate
import com.astrolexis.pyblock.ui.screens.academy.BitcoinAcademyScreen
import com.astrolexis.pyblock.ui.screens.address.MyAddressScreen
import com.astrolexis.pyblock.ui.screens.buy.BuyScreen
import com.astrolexis.pyblock.ui.screens.workers.WorkerSearchScreen
import com.astrolexis.pyblock.ui.screens.cleanblocks.CleanBlocksScreen
import com.astrolexis.pyblock.ui.screens.chat.NostrChatScreen
import com.astrolexis.pyblock.ui.screens.game.NodeDefenderScreen
import com.astrolexis.pyblock.ui.screens.more.MoreScreen
import com.astrolexis.pyblock.ui.screens.nodemap.NodeMapScreen
import com.astrolexis.pyblock.ui.screens.orders.OrdersScreen
import com.astrolexis.pyblock.ui.screens.settings.SettingsScreen
import com.astrolexis.pyblock.ui.screens.wallet.WalletScreen
import com.astrolexis.pyblock.ui.screens.setup.MinerSetupScreen
import com.astrolexis.pyblock.ui.screens.template.TemplateScreen
import com.astrolexis.pyblock.ui.screens.stats.StatsScreen
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType

private data class Tab(val route: String, val label: String, val icon: ImageVector)

// Six top-level tabs, matching iOS order/labels exactly (STATS·BUY·ORDERS·CHAT·WALLET·MORE).
private val TABS = listOf(
    Tab("stats", "STATS", Icons.Filled.BarChart),
    Tab("buy", "BUY", Icons.Filled.Bolt),
    Tab("orders", "ORDERS", Icons.AutoMirrored.Filled.ListAlt),
    Tab("chat", "CHAT", Icons.Filled.Forum),
    Tab("wallet", "WALLET", Icons.Filled.CurrencyBitcoin),
    Tab("more", "MORE", Icons.Filled.MoreHoriz),
)

/** Routes that keep the WALLET tab highlighted. */
private val WALLET_ROUTES = setOf("wallet")

/** The More menu plus every screen reachable from it. Tapping the More tab
 *  while inside any of these should pop back to the "more" menu, not restore
 *  the deep sub-screen. */
private val MORE_ROUTES = setOf(
    "more", "setup", "cleanblocks", "game", "academy", "workers", "address", "nodemap", "settings", "template",
)

@Composable
fun RootScaffold() {
    val nav = rememberNavController()
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    // App-wide chat client (activity-scoped): stays connected across tabs so the DM-unread
    // badge, receipt-triggered PayNym claims, and look-ahead run everywhere — not just in chat.
    val chat: NostrClient = viewModel()
    val chatState by chat.state.collectAsState()
    val unread = chatState.unreadDmCount()
    // Mirror iOS: connect while the app is foregrounded, disconnect in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> chat.connect()
                Lifecycle.Event.ON_STOP -> { chat.disconnect(); WalletVault.lock() }   // auto-lock the vault
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // A tapped DM push wants the inbox: land on the chat tab; NostrChatScreen
    // consumes the signal to open it.
    val chatRequested by com.astrolexis.pyblock.push.ChatLaunch.openInbox
    LaunchedEffect(chatRequested) {
        if (chatRequested) nav.navigate("chat") { launchSingleTop = true }
    }
    Scaffold(
        containerColor = PyTheme.bg,
        // Full-screen routes (e.g. the air-gapped vanity generator) hide the tab bar.
        bottomBar = { if (current != "vanity") PyBottomBar(nav, unread) },
    ) { padding ->
        // App-wide: a tap on empty space clears field focus, dismissing the keyboard.
        // Taps consumed by children (buttons, list rows, text fields) don't reach here,
        // and drags (scrolling) aren't taps — so nothing else is affected.
        val focusManager = LocalFocusManager.current
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
        ) {
            NavHost(navController = nav, startDestination = "stats") {
                composable("stats") { StatsScreen() }
                composable("buy") {
                    BuyScreen(
                        onPayOnchain = { addr, amt ->
                            com.astrolexis.pyblock.data.wallet.PendingPayment.set(addr, amt?.toLong(), null)
                            nav.navigate("wallet")
                        },
                        onOpenChat = { nav.navigate("chat") })
                }
                composable("orders") { OrdersScreen() }
                composable("template") { TemplateScreen() }
                composable("more") { MoreScreen(onNavigate = { nav.navigate(it) }) }
                // More → sub-screens
                composable("setup") { MinerSetupScreen() }
                composable("cleanblocks") { CleanBlocksScreen() }
                composable("game") { NodeDefenderScreen() }
                composable("academy") { BitcoinAcademyScreen() }
                composable("workers") { WorkerSearchScreen() }
                composable("address") { MyAddressScreen(onLaunchVanity = { nav.navigate("vanity") }) }
                composable("vanity") { com.astrolexis.pyblock.ui.screens.vanity.VanityAddressScreen(onClose = { nav.popBackStack() }) }
                composable("nodemap") { NodeMapScreen(onLaunchGame = { nav.navigate("game") }) }
                composable("settings") { SettingsScreen() }
                composable("chat") {
                    NostrChatScreen(
                        client = chat,     // shared app-wide instance (keeps the badge live)
                        onClose = { nav.popBackStack() },
                        embedded = true,   // root tab — no close button, nav owns Back
                        onPay = { addr, amt, peer ->
                            com.astrolexis.pyblock.data.wallet.PendingPayment.set(addr, amt, peer)
                            nav.navigate("wallet")
                        },
                        onBuy = { nav.navigate("buy") })
                }
                composable("wallet") { VaultGate { WalletScreen(onLaunchVanity = { nav.navigate("vanity") }, onLaunchMining = { nav.navigate("address") }) } }
            }
            CrtOverlay()
            com.astrolexis.pyblock.ui.screens.update.UpdateGate()
            // App-wide Collaborative Send (PayJoin) consent — an inbound request pops the
            // sheet from any tab (mirrors iOS ContentView). Gated OFF until validated.
            if (com.astrolexis.pyblock.data.store.PayJoinFeature.enabled)
                com.astrolexis.pyblock.ui.screens.payjoin.PayJoinConsentHost()
            // App-wide contextual paywall — fired from friction points (locked lesson, limits…).
            val paywallCtx by com.astrolexis.pyblock.ui.screens.pro.PaywallBus.ctx.collectAsState()
            paywallCtx?.let { pc ->
                com.astrolexis.pyblock.ui.screens.pro.ContextualPaywall(pc) {
                    com.astrolexis.pyblock.ui.screens.pro.PaywallBus.dismiss()
                }
            }
        }
    }
}

/** Custom retro tab bar — a plain Row of icon+label columns on pure black with a
 *  1dp primary hairline on top. No Material chrome (no pill, ripple, elevation);
 *  selected vs unselected is expressed only by color (primary vs primaryDim),
 *  mirroring iOS ContentView's hand-rolled bar. */
@Composable
private fun PyBottomBar(nav: NavHostController, unread: Int) {
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Column(
        Modifier
            .fillMaxWidth()
            .background(PyTheme.bg)
            .windowInsetsPadding(WindowInsets.navigationBars)   // black fills to the screen edge (iOS ignoresSafeArea)
            .padding(bottom = 2.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(PyTheme.primary.copy(alpha = 0.25f)))
        Row(Modifier.fillMaxWidth()) {
            TABS.forEach { tab ->
                val selected = when (tab.route) {
                    "more" -> current in MORE_ROUTES
                    "wallet" -> current in WALLET_ROUTES
                    else -> current == tab.route
                }
                val tint = if (selected) PyTheme.primary else PyTheme.primaryDim
                Column(
                    Modifier
                        .weight(1f)
                        .clickableNoRipple {
                            com.astrolexis.pyblock.ui.Haptics.tap()   // arcade cabinet click on tab change
                            com.astrolexis.pyblock.ui.Sfx.select()
                            when {
                                // Deep inside More → pop back to the More menu.
                                tab.route == "more" && current in MORE_ROUTES && current != "more" ->
                                    nav.popBackStack("more", inclusive = false)
                                !selected -> nav.navigate(tab.route) {
                                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box {
                        Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(18.dp))
                        // Unread-DM badge: a 9dp magenta dot with a 1.5dp black ring, no count.
                        if (tab.route == "chat" && unread > 0) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 7.dp, y = (-3).dp)
                                    .size(9.dp)
                                    .background(PyTheme.magenta, CircleShape)
                                    .border(1.5.dp, PyTheme.bg, CircleShape),
                            )
                        }
                    }
                    val labelRes = when (tab.route) {
                        "stats" -> com.astrolexis.pyblock.R.string.nav_stats
                        "buy" -> com.astrolexis.pyblock.R.string.nav_buy
                        "orders" -> com.astrolexis.pyblock.R.string.nav_orders
                        "chat" -> com.astrolexis.pyblock.R.string.nav_chat
                        "wallet" -> com.astrolexis.pyblock.R.string.nav_wallet
                        else -> com.astrolexis.pyblock.R.string.nav_more
                    }
                    Text(androidx.compose.ui.res.stringResource(labelRes), style = PyType.mono(8f), color = tint,
                        letterSpacing = 0.3.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
