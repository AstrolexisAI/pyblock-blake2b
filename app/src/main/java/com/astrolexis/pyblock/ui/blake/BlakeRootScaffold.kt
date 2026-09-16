package com.astrolexis.pyblock.ui.blake

import com.astrolexis.pyblock.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material.icons.filled.Grain
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
import com.astrolexis.pyblock.data.net.PushRepo
import com.astrolexis.pyblock.data.nostr.NostrClient
import com.astrolexis.pyblock.data.wallet.PendingPayment
import com.astrolexis.pyblock.data.wallet.WalletVault
import com.astrolexis.pyblock.ui.components.clickableNoRipple

/** Four-tab shell for the dedicated BLAKE2b app — POOL · WALLET · CHAT · CHIRP.
 *  Mirrors iOS RootView: flat black, floating capsule tab bar, app-wide "RECEIVED" banner. */
private data class BTab(val route: String, val label: Int, val icon: ImageVector)
private val BTABS = listOf(
    BTab("pool", R.string.blk_pool, Icons.Filled.ShowChart),
    BTab("wallet", R.string.blk_wallet, Icons.Filled.AccountBalanceWallet),
    BTab("chat", R.string.blk_chat, Icons.Filled.Forum),
    BTab("carousel", R.string.blk_carousel, Icons.Filled.Groups),   // icon unused: the product's rune is drawn
    BTab("chirp", R.string.blk_chirp, Icons.Filled.Groups),
    BTab("wavicles", R.string.blk_wavicles, Icons.Filled.Grain),
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
                Lifecycle.Event.ON_START -> { chat.connect(); PushRepo.syncAddressesAsync(ctx) }
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

    // Every event lands on ONE quiet line at the top, on any tab. A receive also flies its
    // squadron in first; everything else just states itself and leaves.
    LaunchedEffect(Unit) { WalletEvents.drain() }
    val receiveEvent by BlakeBalanceStore.receiveEvent.collectAsState()
    LaunchedEffect(receiveEvent) {
        receiveEvent?.let { ev ->
            com.astrolexis.pyblock.ui.Haptics.tap(); com.astrolexis.pyblock.ui.Sfx.received()
            WalletEvents.post(WalletEvents.Kind.Received(ev.deltaSats))
            BlakeBalanceStore.clearReceiveEvent()
        }
    }
    val confirmedEvent by BlakeBalanceStore.confirmedEvent.collectAsState()
    LaunchedEffect(confirmedEvent) {
        confirmedEvent?.let { ev -> com.astrolexis.pyblock.ui.Sfx.blip(); WalletEvents.post(WalletEvents.Kind.Confirmed(ev.deltaSats)); BlakeBalanceStore.clearConfirmedEvent() }
    }
    val maturedEvent by BlakeBalanceStore.maturedEvent.collectAsState()
    LaunchedEffect(maturedEvent) {
        maturedEvent?.let { ev -> com.astrolexis.pyblock.ui.Haptics.tap(); com.astrolexis.pyblock.ui.Sfx.powerUp(); WalletEvents.post(WalletEvents.Kind.Matured(ev.deltaSats)); BlakeBalanceStore.clearMaturedEvent() }
    }
    val formation by WalletEvents.formation.collectAsState()

    Scaffold(
        containerColor = Blake.bg,
        bottomBar = { if (current != "vanity") BlakeTabBar(nav, current) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(Blake.bg)) {
            NavHost(navController = nav, startDestination = "pool") {
                composable("pool") { BlakePoolScreen() }
                composable("wallet") {
                    BlakeWalletScreen(onLaunchVanity = { nav.navigate("vanity") },
                        onPaid = { peer, txid, sats ->
                            val me = runCatching { com.astrolexis.pyblock.data.crypto.PaymentCode.myCode(ctx) }.getOrNull()
                            chat.sendDM(peer, com.astrolexis.pyblock.data.util.PaymentReceipt(sats, txid, me).toUri())
                        })
                }
                composable("chat") {
                    BlakeChatScreen(
                        client = chat,
                        onPay = { addr, amt, peer -> PendingPayment.set(addr, amt, peer); nav.navigate("wallet") },
                    )
                }
                composable("carousel") { BlakeCarouselScreen() }
                composable("chirp") { BlakeChirpScreen() }
                composable("wavicles") { BlakeWaviclesScreen() }
                composable("vanity") { BlakeVanityScreen(onClose = { nav.popBackStack() }) }
            }

            // No banner: events show IN PLACE (wallet status line, pool timechain line). A receive
            // flies its squadron into the balance number first.
            formation?.let { ReceiveFormation(it) }
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
                    // The products use their own runes (drawn), not system icons: Raidho, Ansuz, Dagaz.
                    val ink = if (selected) Blake.pp else Blake.ppDim
                    when (tab.route) {
                        "wavicles" -> DagazRune(20.dp, ink)
                        "carousel" -> RuneGlyph(Rune.RAIDHO, ink = ink, size = 20.dp)
                        "chirp" -> RuneGlyph(Rune.ANSUZ, ink = ink, size = 20.dp)
                        else ->
                        Icon(tab.icon, contentDescription = stringResource(tab.label), tint = ink, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.size(3.dp))
                    Text(stringResource(tab.label), style = Blake.mono(8f, FontWeight.ExtraBold),
                        color = if (selected) Blake.pp else Blake.ppDim, letterSpacing = 1.sp)
                }
            }
        }
    }
}
