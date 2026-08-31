package com.astrolexis.pyblock.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.net.ChatMediaRepo
import com.astrolexis.pyblock.data.util.ChatImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.astrolexis.pyblock.data.nostr.NostrClient
import com.astrolexis.pyblock.data.nostr.NostrEvent
import com.astrolexis.pyblock.data.nostr.NostrUiState
import com.astrolexis.pyblock.data.nostr.reactionSummary
import com.astrolexis.pyblock.ui.components.Identicon
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import com.astrolexis.pyblock.ui.theme.neonText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NostrChatScreen(
    onClose: () -> Unit,
    embedded: Boolean = false,
    client: NostrClient = viewModel(),
    onPay: (String, Long?, String) -> Unit = { _, _, _ -> },
    onBuy: () -> Unit = {},
) {
    val state by client.state.collectAsState()

    // Store-policy UGC gate: require agreeing to community rules before chatting.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var termsAccepted by remember { mutableStateOf(ChatTerms.accepted(ctx)) }
    if (!termsAccepted) { ChatTermsGate { termsAccepted = true }; return }

    var showInbox by remember { mutableStateOf(false) }
    var dmPeer by remember { mutableStateOf<String?>(null) }
    var profilePeer by remember { mutableStateOf<String?>(null) }

    // A tapped DM push landed us here — open the inbox and clear the signal.
    val dmPushRequested by com.astrolexis.pyblock.push.ChatLaunch.openInbox
    LaunchedEffect(dmPushRequested) {
        if (com.astrolexis.pyblock.push.ChatLaunch.consume()) showInbox = true
    }

    DisposableEffect(Unit) {
        client.connect()   // idempotent; the app-wide lifecycle is owned by RootScaffold
        // A payment just completed in the Wallet screen → post a receipt back to the peer.
        com.astrolexis.pyblock.data.wallet.PendingReceipt.consume()?.let { r ->
            client.sendDM(r.peer, com.astrolexis.pyblock.data.util.PaymentReceipt(r.amountSats, r.txid).toUri())
        }
        onDispose { }   // stay connected across tabs so the badge & receive claims keep running
    }

    when {
        dmPeer != null -> {
            BackHandler { dmPeer = null }
            DMConversation(client, state.conversations[dmPeer] ?: emptyList(),
                peerName = client.name(dmPeer!!), peerKey = dmPeer!!,
                onBack = { dmPeer = null }, onSend = { client.sendDM(dmPeer!!, it) }, onPay = onPay)
        }
        showInbox -> {
            BackHandler { showInbox = false }
            DMInbox(client, state, onBack = { showInbox = false }, onOpen = { showInbox = false; dmPeer = it })
        }
        else -> {
            // As a root tab there's nothing to close — let the nav handle Back.
            if (!embedded) BackHandler { onClose() }
            Community(client, state, onClose, embedded, onInbox = { showInbox = true }, onProfile = { profilePeer = it }, onBuy = onBuy)
        }
    }

    profilePeer?.let { pk ->
        ProfileSheet(client, pk, onDismiss = { profilePeer = null }) { profilePeer = null; dmPeer = pk }
    }
}

// MARK: Community

@Composable
private fun Community(
    client: NostrClient, state: NostrUiState,
    onClose: () -> Unit, embedded: Boolean = false, onInbox: () -> Unit, onProfile: (String) -> Unit,
    onBuy: () -> Unit = {},
) {
    var draft by remember { mutableStateOf("") }
    var showPaynym by remember { mutableStateOf(false) }
    if (showPaynym) YourPaynymDialog { showPaynym = false }
    var lounge by remember { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<NostrEvent?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var zoomUrl by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Pick one image → compress off-thread → HMAC upload → post pyblock:img card.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !uploading) {
            uploading = true
            val reply = replyingTo
            scope.launch {
                val jpeg = withContext(Dispatchers.IO) { ChatImage.downscaleJpeg(ctx, uri) }
                val url = if (jpeg != null) ChatMediaRepo.uploadImage(jpeg) else null
                if (url != null) {
                    client.post("pyblock:img?url=$url", replyTo = reply, toWhaleLounge = lounge)
                    replyingTo = null
                    Haptics.thock()
                } else {
                    client.setRejection(ctx.getString(R.string.chat_image_upload_failed))
                    Haptics.warning()
                }
                uploading = false
            }
        }
    }
    val base = if (lounge) state.whaleMessages else state.messages
    val chatList = if (search.isBlank()) base else base.filter {
        it.content.contains(search, ignoreCase = true) || client.name(it.pubkey).contains(search, ignoreCase = true)
    }
    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
    Starfield()
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!embedded) Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim,
                modifier = Modifier.clickableNoRipple { Haptics.tap(); onClose() }.padding(10.dp))
            Box(Modifier.clickableNoRipple { Haptics.tap(); onInbox() }.padding(6.dp)) {
                Text("✉", style = PyType.mono(20f), color = PyTheme.magenta)
                if (state.conversations.isNotEmpty())
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp)
                        .size(7.dp).background(PyTheme.magenta))
            }
            // Your PayNym — reusable payment code (fresh address per payment).
            Text("▦", style = PyType.mono(20f), color = PyTheme.primary,
                modifier = Modifier.clickableNoRipple { Haptics.tap(); showPaynym = true }.padding(8.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MarqueeTitle(text = stringResource(R.string.chat_header_community))
                Text(if (state.connected) stringResource(R.string.chat_status_connected) else stringResource(R.string.chat_status_connecting),
                    style = PyType.mono(9f), color = PyTheme.primaryDim)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clickableNoRipple { Haptics.tap(); onProfile(client.myPubkey) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Identicon(client.myPubkey, 22)
                Spacer(Modifier.width(6.dp))
                Text(state.profiles[client.myPubkey] ?: stringResource(R.string.chat_set_name), style = PyType.mono(11f), color = PyTheme.magenta)
                Spacer(Modifier.width(4.dp))
                TierBadge(client.tierFor(client.myPubkey))
            }
        }
        // COMMUNITY ⇄ 🐋 LOUNGE. The lounge is whale-only: everyone sees the tab
        // (that's the point), tapping it without Whale opens the paywall.
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChannelPill(stringResource(R.string.chat_channel_community), active = !lounge) { Haptics.select(); lounge = false }
            ChannelPill(stringResource(R.string.chat_channel_lounge), active = lounge) {
                Haptics.select()
                if (com.astrolexis.pyblock.data.store.EntitlementsStore.isWhale) lounge = true
                else com.astrolexis.pyblock.ui.screens.pro.PaywallBus.present(
                    com.astrolexis.pyblock.ui.screens.pro.PaywallContext.whaleLounge)
            }
            Spacer(Modifier.weight(1f))
            Text("⌕", style = PyType.mono(20f), color = if (showSearch) PyTheme.cyan else PyTheme.primaryDim,
                modifier = Modifier.clickableNoRipple { Haptics.tap(); showSearch = !showSearch; if (!showSearch) search = "" }
                    .padding(horizontal = 6.dp, vertical = 4.dp))
        }
        if (showSearch) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 6.dp)
                .border(1.dp, PyTheme.cyan.copy(alpha = 0.4f), RectangleShape).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("⌕", style = PyType.mono(14f), color = PyTheme.cyan)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (search.isEmpty()) Text(stringResource(R.string.chat_search_placeholder), style = PyType.mono(13f), color = PyTheme.primaryDim)
                    BasicTextField(search, { search = it }, textStyle = PyType.mono(13f).copy(color = PyTheme.cyan),
                        cursorBrush = SolidColor(PyTheme.cyan), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (search.isNotEmpty()) Text("✕", style = PyType.mono(12f), color = PyTheme.primaryDim,
                    modifier = Modifier.clickableNoRipple { search = "" })
            }
        }
        state.lastRejection?.let { rejection ->
            LaunchedEffect(rejection) {
                kotlinx.coroutines.delay(5_000)
                client.clearRejection()
            }
            Text(stringResource(R.string.chat_rejection, rejection), style = PyType.mono(10f), color = PyTheme.danger,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
                    .background(PyTheme.danger.copy(alpha = 0.12f)).padding(vertical = 5.dp))
        }
        HDivider()
        // key(lounge): fresh list state + scroll pin per channel.
        androidx.compose.runtime.key(lounge) {
            val pin = rememberPinnedChat(itemCount = chatList.size)
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), state = pin.listState) {
                    if (chatList.isEmpty()) item {
                        Text(if (search.isNotBlank()) stringResource(R.string.chat_search_empty)
                             else if (lounge) stringResource(R.string.chat_empty_lounge) else stringResource(R.string.chat_empty_community),
                            style = PyType.mono(12f), color = PyTheme.primaryDim,
                            modifier = Modifier.fillMaxWidth().padding(top = 40.dp), textAlign = TextAlign.Center)
                    }
                    items(chatList, key = { it.id }) { m -> MessageBubble(client, state, m, onProfile, { replyingTo = it }, onBuy, { zoomUrl = it }) }
                }
                NewMessagesPill(pin)
            }
        }
        replyingTo?.let { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                .border(1.dp, PyTheme.cyan.copy(alpha = 0.4f), RectangleShape).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("↩", style = PyType.mono(12f), color = PyTheme.cyan)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.chat_replying_to, client.name(r.pubkey)), style = PyType.mono(9f), color = PyTheme.cyan)
                Spacer(Modifier.width(6.dp))
                Text(if (ChatShareMsg.parse(r.content) != null) stringResource(R.string.chat_reply_share) else r.content.take(48),
                    style = PyType.mono(9f), color = PyTheme.primaryDim, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text("✕", style = PyType.mono(12f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { replyingTo = null })
            }
        }
        InputBar(draft, if (lounge) stringResource(R.string.chat_input_lounge) else stringResource(R.string.chat_input_community), { draft = it },
            onPickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            uploading = uploading) {
            if (draft.isNotBlank()) { client.post(draft, replyTo = replyingTo, toWhaleLounge = lounge); draft = ""; replyingTo = null }
        }
    }
    // Fullscreen image viewer (tap a chat image). Tap anywhere / ✕ to dismiss.
    zoomUrl?.let { url ->
        Box(Modifier.fillMaxSize().background(Color.Black)
            .clickableNoRipple { zoomUrl = null }) {
            AsyncImage(model = url, contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
            Text("✕", style = PyType.mono(26f), color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).clickableNoRipple { zoomUrl = null }.padding(16.dp))
        }
    }
    }
}

/** Structured share cards (block celebration / hashrate brag) — the CTA
 *  funnels straight into the Buy tab. */
@Composable
private fun ShareCardBody(share: ChatShareMsg, onBuy: () -> Unit, onZoom: (String) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (share) {
            is ChatShareMsg.Image -> {
                AsyncImage(model = share.url, contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.sizeIn(maxWidth = 220.dp, maxHeight = 240.dp)
                        .border(1.dp, PyTheme.primary.copy(alpha = 0.4f), RectangleShape)
                        .clickableNoRipple { Haptics.tap(); onZoom(share.url) })
            }
            is ChatShareMsg.Block -> {
                Text(if (share.lotto) stringResource(R.string.chat_share_lotto_winner) else stringResource(R.string.chat_share_clean_block),
                    style = PyType.mono(11f), color = if (share.lotto) PyTheme.yellow else PyTheme.primary,
                    letterSpacing = 2.sp)
                Text(stringResource(R.string.chat_share_block_height, share.height), style = PyType.mono(18f).neonText(PyTheme.cyan), color = PyTheme.cyan)
                Text(if (share.lotto) stringResource(R.string.chat_share_lotto_reward) else stringResource(R.string.chat_share_money_not_spam),
                    style = PyType.mono(10f), color = PyTheme.primaryDim)
                ShareCta(stringResource(R.string.chat_share_mine_with_us), onBuy)
            }
            is ChatShareMsg.Stack -> {
                Text(stringResource(R.string.chat_share_stacking_hashrate), style = PyType.mono(11f), color = PyTheme.yellow, letterSpacing = 2.sp)
                Text(String.format(Locale.US, "+%.2f PH/s", share.phs), style = PyType.mono(18f).neonText(PyTheme.cyan), color = PyTheme.cyan)
                Text(stringResource(R.string.chat_share_over_lightning), style = PyType.mono(10f), color = PyTheme.primaryDim)
                ShareCta(stringResource(R.string.chat_share_buy_hashrate), onBuy)
            }
        }
    }
}

@Composable
private fun ShareCta(label: String, onClick: () -> Unit) {
    Text(label, style = PyType.mono(11f), color = PyTheme.bg,
        modifier = Modifier.padding(top = 2.dp).background(PyTheme.yellow)
            .clickableNoRipple { Haptics.coin(); onClick() }.padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
private fun ChannelPill(label: String, active: Boolean, onClick: () -> Unit) {
    Text(label, style = PyType.mono(10f), letterSpacing = 2.sp,
        color = if (active) PyTheme.bg else PyTheme.primaryDim,
        modifier = Modifier
            .background(if (active) PyTheme.primary else Color.Transparent)
            .border(1.dp, if (active) PyTheme.primary else PyTheme.primaryDim.copy(alpha = 0.5f), RectangleShape)
            .clickableNoRipple(onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MessageBubble(client: NostrClient, state: NostrUiState, m: NostrEvent,
                          onProfile: (String) -> Unit, onReply: (NostrEvent) -> Unit, onBuy: () -> Unit = {},
                          onZoom: (String) -> Unit = {}) {
    val mine = m.pubkey == client.myPubkey
    val color = FlairPalette.color(client.colorFor(m.pubkey))
        ?: if (mine) PyTheme.magenta else PyTheme.cyan
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top) {
        if (!mine) {
            Box(Modifier.clickableNoRipple { Haptics.tap(); onProfile(m.pubkey) }) { Identicon(m.pubkey, 28) }
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (mine) stringResource(R.string.chat_you) else client.name(m.pubkey), style = PyType.mono(9f), color = color)
                TierBadge(client.tierFor(m.pubkey))
            }
            // Quoted parent for a reply.
            client.replyParentId(m)?.let { pid -> client.message(pid)?.let { parent ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("↳", style = PyType.mono(9f), color = PyTheme.cyan)
                    Spacer(Modifier.width(4.dp))
                    Text(client.name(parent.pubkey) + ": " +
                            (if (ChatShareMsg.parse(parent.content) != null) stringResource(R.string.chat_reply_share) else parent.content.take(40)),
                        style = PyType.mono(9f), color = PyTheme.primaryDim, maxLines = 1)
                }
            } }
            Box {
                // Long-press any message → react / reply (+ report / block for peers).
                var menu by remember { mutableStateOf(false) }
                Box(
                    Modifier.moduleFrame(color)
                        .combinedClickable(enabled = true, onClick = {}, onLongClick = { Haptics.warning(); menu = true })
                        .padding(10.dp),
                ) {
                    val share = ChatShareMsg.parse(m.content)
                    if (share != null) ShareCardBody(share, onBuy, onZoom)
                    else Text(m.content, style = PyType.mono(13f), color = PyTheme.primary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf("🔥", "⚡", "👍", "😂", "🐋").forEach { e ->
                        DropdownMenuItem(text = { Text("$e   " + stringResource(R.string.chat_react)) },
                            onClick = { Haptics.tap(); client.react(m.id, m.pubkey, e); menu = false })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_reply)) },
                        onClick = { Haptics.tap(); onReply(m); menu = false })
                    if (!mine) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_report_message)) },
                            onClick = { Haptics.warning(); client.reportMessage(m.id, m.pubkey); menu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_block_user)) },
                            onClick = { Haptics.warning(); client.blockUser(m.pubkey); menu = false })
                    }
                }
            }
            // Reaction chips (yours highlighted); tap to add.
            val reacts = state.reactionSummary(m.id, client.myPubkey)
            if (reacts.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    reacts.forEach { (emoji, count, isMine) ->
                        Row(Modifier
                            .border(1.dp, (if (isMine) PyTheme.cyan else PyTheme.primaryDim).copy(alpha = 0.5f), RectangleShape)
                            .clickableNoRipple { Haptics.tap(); client.react(m.id, m.pubkey, emoji) }
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(emoji, style = PyType.mono(11f))
                            Spacer(Modifier.width(2.dp))
                            Text("$count", style = PyType.mono(9f), color = if (isMine) PyTheme.cyan else PyTheme.primaryDim)
                        }
                    }
                }
            }
            Text(time(m.created_at), style = PyType.mono(8f), color = PyTheme.primaryDim)
        }
        if (mine) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clickableNoRipple { Haptics.tap(); onProfile(m.pubkey) }) { Identicon(m.pubkey, 28) }
        }
    }
}

// MARK: shared

@Composable
fun HDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(PyTheme.primary.copy(alpha = 0.3f)))
}

@Composable
fun InputBar(value: String, placeholder: String, onChange: (String) -> Unit,
             onPickImage: (() -> Unit)? = null, uploading: Boolean = false, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape).padding(10.dp)) {
            if (value.isEmpty()) Text(placeholder, style = PyType.mono(14f), color = PyTheme.primaryDim)
            BasicTextField(value, onChange, textStyle = PyType.mono(14f).copy(color = PyTheme.cyan),
                cursorBrush = SolidColor(PyTheme.cyan), modifier = Modifier.fillMaxWidth())
        }
        if (onPickImage != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(width = 40.dp, height = 44.dp)
                .border(1.dp, PyTheme.cyan.copy(alpha = 0.5f), RectangleShape)
                .clickableNoRipple { if (!uploading) { Haptics.tap(); onPickImage() } },
                contentAlignment = Alignment.Center) {
                Text(if (uploading) "…" else "＋", style = PyType.mono(20f), color = PyTheme.cyan)
            }
        }
        Spacer(Modifier.width(8.dp))
        val enabled = value.isNotBlank()
        Box(Modifier.size(width = 48.dp, height = 44.dp)
            .background(if (enabled) PyTheme.magenta else Color.Transparent)
            .border(2.dp, if (enabled) PyTheme.magenta else PyTheme.primaryDim, RectangleShape)
            .clickableNoRipple { if (enabled) { Haptics.thock(); onSend() } }, contentAlignment = Alignment.Center) {
            Text("▸", style = PyType.mono(22f), color = if (enabled) PyTheme.bg else PyTheme.primaryDim)
        }
    }
}

fun time(ts: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ts * 1000))

/** Subscription flair next to a name in chat. 🐋 = Whale · PRO pill = Pro.
 *  Advertised via the Nostr kind-0 profile ("tier"); own tier from the verified
 *  entitlement. Drives social proof for the paid tiers. */
@Composable
fun TierBadge(tier: String?, big: Boolean = false) {
    when (tier) {
        "whale" -> Text("🐋", style = PyType.mono(if (big) 18f else 12f))
        "pro" -> Text(
            "PRO", style = PyType.mono(if (big) 10f else 7f), color = PyTheme.bg,
            modifier = Modifier
                .background(PyTheme.yellow, androidx.compose.foundation.shape.RoundedCornerShape(50))
                .padding(horizontal = if (big) 5.dp else 3.dp, vertical = 1.dp),
        )
    }
}
