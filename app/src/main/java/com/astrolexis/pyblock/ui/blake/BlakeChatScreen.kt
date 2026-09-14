package com.astrolexis.pyblock.ui.blake

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.delay
import androidx.compose.runtime.derivedStateOf
import com.astrolexis.pyblock.data.nostr.ChatMedia
import com.astrolexis.pyblock.data.nostr.unreadDmCount
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.astrolexis.pyblock.data.net.ChatMediaRepo
import com.astrolexis.pyblock.data.nostr.Nostr
import com.astrolexis.pyblock.data.nostr.NostrClient
import com.astrolexis.pyblock.data.nostr.NostrEvent
import com.astrolexis.pyblock.data.nostr.reactionSummary
import com.astrolexis.pyblock.data.util.ChatImage
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.launch

/** COMMUNITY — shared PyBLØCK chat (same Nostr channel as the SHA-256 app). Faithful Blake
 *  port of iOS ChatView: colored names, bubbles, images, reactions, moderation + DMs. */
@Composable
fun BlakeChatScreen(client: NostrClient, onPay: (String, Long?, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by client.state.collectAsState()
    var draft by remember { mutableStateOf("") }
    var showName by remember { mutableStateOf(false) }
    var showDMs by remember { mutableStateOf(false) }
    var dmPeer by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val myPubkey = remember { runCatching { Nostr.pubkeyHex(ctx) }.getOrDefault("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            uploading = true
            val jpeg = ChatImage.downscaleJpeg(ctx, uri)
            val url = if (jpeg != null) ChatMediaRepo.uploadImage(jpeg) else null
            if (url != null) client.post("pyblock:img?url=$url")
            uploading = false
        }
    }

    LaunchedEffect(Unit) { client.connect() }
    // The relay refused a message: give the words back instead of losing them.
    LaunchedEffect(state.rejectedDraft) {
        val text = state.rejectedDraft ?: return@LaunchedEffect
        if (draft.isBlank()) draft = text
        client.consumeRejectedDraft()
    }

    // Whether the newest message is on screen. That decides whether an arriving message pulls the
    // view down or waits behind a button — being yanked to the bottom mid-sentence was the other
    // half of what made this chat unpleasant. Scrolling to the LAST message on every size change
    // was the first half: the id changes on every arrival, so during the backfill the target moved
    // constantly and the list visibly chased it.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    var primed by remember { mutableStateOf(false) }
    var unread by remember { mutableStateOf(0) }
    val lastId = state.messages.lastOrNull()?.id
    LaunchedEffect(lastId) {
        val n = state.messages.size
        if (n == 0) return@LaunchedEffect
        val lastMine = state.messages.last().pubkey == myPubkey
        when {
            !primed -> {
                // Land on the newest, no animation. Rows measure late (an image resolving its
                // size), so correct once more a moment later.
                listState.scrollToItem(n - 1)
                delay(150)
                listState.scrollToItem(state.messages.size - 1)
                primed = true
            }
            atBottom || lastMine -> { listState.animateScrollToItem(n - 1); unread = 0 }
            else -> unread++
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) unread = 0 }

    // DM overlays.
    val peer = dmPeer
    if (peer != null) { BlakeDMThread(client, peer, onClose = { dmPeer = null }); return }
    if (showDMs) { BlakeDMInbox(client, onOpen = { dmPeer = it }, onClose = { showDMs = false }); return }

    Column(Modifier.fillMaxSize().background(Blake.bg)) {
        Row(Modifier.fillMaxWidth().background(Blake.ink).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("COMMUNITY", style = Blake.mono(18f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 2.sp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(7.dp).background(if (state.connected) Blake.ok else Blake.warn, CircleShape))
            Spacer(Modifier.weight(1f))
            val unreadDMs = state.unreadDmCount()
            if (unreadDMs > 0)
                Text("✉ DMS $unreadDMs", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.bg,
                    modifier = Modifier.background(Blake.pp, Blake.shape).padding(horizontal = 6.dp, vertical = 2.dp).clickableNoRipple { showDMs = true })
            else
                Text("✉ DMS", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.pp, modifier = Modifier.clickableNoRipple { showDMs = true })
            Spacer(Modifier.width(12.dp))
            Text("⚙ NAME", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.pp, modifier = Modifier.clickableNoRipple { showName = true })
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(Blake.line))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.messages.isEmpty()) {
                    item { Text("No messages yet. Say hi to the PyBLØCK community.", style = Blake.mono(10f), color = Blake.faint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 40.dp)) }
                }
                items(state.messages, key = { it.id }) { m ->
                    Bubble(m, mine = m.pubkey == myPubkey, name = state.profiles[m.pubkey],
                        marks = state.marks[m.pubkey].orEmpty(),
                        reactions = state.reactionSummary(m.id, myPubkey),
                        onReact = { e -> client.react(m.id, m.pubkey, e) },
                        onDm = { if (m.pubkey != myPubkey) dmPeer = m.pubkey },
                        onBlock = { client.blockUser(m.pubkey) },
                        onReport = { client.reportMessage(m.id, m.pubkey) })
                }
            }
            if (!atBottom && unread > 0) {
                Text(if (unread == 1) "↓ 1 NEW MESSAGE" else "↓ $unread NEW MESSAGES",
                    style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.bg, letterSpacing = 1.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                        .background(Blake.pp, Blake.shape).padding(horizontal = 12.dp, vertical = 7.dp)
                        .clickableNoRipple { scope.launch { listState.animateScrollToItem(state.messages.size - 1) }; unread = 0 })
            }
        }

        Box(Modifier.fillMaxWidth().size(1.dp).background(Blake.line))
        Row(Modifier.fillMaxWidth().background(Blake.ink).navigationBarsPadding().imePadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (uploading) "…" else "＋", style = Blake.mono(18f, FontWeight.ExtraBold), color = Blake.pp,
                modifier = Modifier.border(1.dp, Blake.line, Blake.shape).padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickableNoRipple { if (!uploading) picker.launch("image/*") })
            Spacer(Modifier.width(8.dp))
            BasicTextField(value = draft, onValueChange = { draft = it },
                textStyle = Blake.mono(12f).copy(color = Blake.fg), cursorBrush = SolidColor(Blake.pp),
                modifier = Modifier.weight(1f).border(1.dp, Blake.line, Blake.shape).padding(10.dp),
                decorationBox = { inner -> if (draft.isEmpty()) Text("message…", style = Blake.mono(12f), color = Blake.faint); inner() })
            Spacer(Modifier.width(8.dp))
            val canSend = draft.isNotBlank()
            Text("SEND", style = Blake.mono(11f, FontWeight.ExtraBold), color = if (canSend) Blake.bg else Blake.faint,
                modifier = Modifier.then(if (canSend) Modifier.background(Blake.pp, Blake.shape) else Modifier.border(1.dp, Blake.line, Blake.shape))
                    .padding(horizontal = 14.dp, vertical = 11.dp)
                    .clickableNoRipple { if (canSend) { client.post(draft.trim()); draft = ""; com.astrolexis.pyblock.ui.Haptics.tap() } })
        }
    }

    if (showName) NameSheet(client) { showName = false }
}


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: NostrEvent, mine: Boolean, name: String?, marks: List<com.astrolexis.pyblock.data.blake.BlakeApi.Mark> = emptyList(),
                   reactions: List<Triple<String, Int, Boolean>>,
                   onReact: (String) -> Unit, onDm: () -> Unit, onBlock: () -> Unit, onReport: () -> Unit) {
    val label = name ?: "…${m.pubkey.takeLast(6)}"
    val imgUrl = ChatMedia.imageUrl(m.content)
    val foreign = ChatMedia.foreignImageUrl(m.content)
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menu = true }),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Blake.mono(9f, FontWeight.ExtraBold), color = flair(m.pubkey), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            // Runes earned mining, certified by the server. Not for sale.
            marks.take(4).forEach { mk ->
                runCatching { Rune.valueOf(mk.rune.uppercase()) }.getOrNull()?.let { r ->
                    Spacer(Modifier.width(2.dp))
                    RuneGlyph(r, ink = if (r == Rune.DAGAZ) Color(0xFF35C7E0) else Blake.pp, size = 10.dp)
                }
            }
            Spacer(Modifier.width(6.dp))
            // The time was fetched, stored and sorted on, and never shown to anyone.
            Text(ChatMedia.clock(m.created_at), style = Blake.mono(7f), color = Blake.faint)
        }
        Spacer(Modifier.size(3.dp))
        if (foreign != null) {
            // Hosted somewhere we don't load from. Loading it would hand that host the reader's IP
            // address, so it stays a link the reader can choose to open.
            Column(Modifier.background(Blake.ink, Blake.shape).border(1.dp, Blake.warn.copy(alpha = 0.5f), Blake.shape).padding(10.dp)) {
                Text("image from another site", style = Blake.mono(9f), color = Blake.warn)
                Text(foreign, style = Blake.mono(7f), color = Blake.faint, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        } else if (imgUrl != null) {
            // One fixed box, so an image that finishes loading doesn't shove the rest of the
            // conversation down under the reader's thumb.
            AsyncImage(model = imgUrl, contentDescription = null,
                modifier = Modifier.width(220.dp).height(165.dp).border(1.dp, Blake.line, Blake.shape))
        } else {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(m.content, style = Blake.mono(12f), color = Blake.fg,
                    modifier = Modifier.background(if (mine) Blake.pp.copy(alpha = 0.14f) else Blake.ink, Blake.shape).border(1.dp, Blake.line, Blake.shape).padding(10.dp))
            }
        }
        if (reactions.isNotEmpty()) {
            Spacer(Modifier.size(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                reactions.forEach { (emoji, count, isMine) ->
                    val tint = if (isMine) Blake.pp else Blake.ppDim
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.border(1.dp, Blake.line, Blake.shape).padding(horizontal = 5.dp, vertical = 2.dp).clickableNoRipple { onReact(emoji) }) {
                        // A rune drawn as strokes when we know it; anything else (an emoji from an
                        // older build, or the other app) stays as the text it is.
                        val rune = Rune.fromGlyph(emoji)
                        if (rune != null) RuneGlyph(rune, ink = tint, size = 11.dp) else Text(emoji, style = Blake.mono(9f), color = tint)
                        Spacer(Modifier.width(3.dp))
                        Text("$count", style = Blake.mono(9f), color = tint)
                    }
                }
            }
        }
    }
    if (menu) Dialog(onDismissRequest = { menu = false }) {
        Column(Modifier.fillMaxWidth().background(Blake.ink, Blake.shape).border(1.dp, Blake.line, Blake.shape).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                // Runes, not emoji. The glyph goes over the wire; the chip draws it as strokes.
                Rune.reactions.forEach { r -> RuneGlyph(r, size = 24.dp, modifier = Modifier.clickableNoRipple { onReact(r.glyph); menu = false }) }
            }
            if (!mine) {
                Spacer(Modifier.size(14.dp))
                Text("MESSAGE", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.pp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickableNoRipple { onDm(); menu = false })
                Text("REPORT MESSAGE", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.warn, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickableNoRipple { onReport(); menu = false })
                Text("BLOCK USER", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.danger, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickableNoRipple { onBlock(); menu = false })
            }
        }
    }
}

@Composable
private fun NameSheet(client: NostrClient, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Nostr.displayName(ctx) ?: "") }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(Blake.ink, Blake.shape).border(1.dp, Blake.line, Blake.shape).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("YOUR NAME", style = Blake.mono(16f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text("✕", style = Blake.mono(18f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple(onClose))
            }
            Spacer(Modifier.size(16.dp))
            BasicTextField(value = name, onValueChange = { name = it }, singleLine = true,
                textStyle = Blake.mono(13f).copy(color = Blake.fg), cursorBrush = SolidColor(Blake.pp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Blake.line, Blake.shape).padding(10.dp),
                decorationBox = { inner -> if (name.isEmpty()) Text("display name", style = Blake.mono(13f), color = Blake.faint); inner() })
            Spacer(Modifier.size(16.dp))
            Text("SAVE", style = Blake.mono(13f, FontWeight.ExtraBold), color = Blake.bg, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Blake.pp, Blake.shape).padding(vertical = 12.dp)
                    .clickableNoRipple { if (name.isNotBlank()) { Nostr.setDisplayName(ctx, name.trim()); com.astrolexis.pyblock.ui.Haptics.tap() }; onClose() })
            Spacer(Modifier.size(10.dp))
            Text("Your Nostr identity is device-only. BLAKE2b and SHA-256 users share this room.", style = Blake.mono(8f), color = Blake.faint)
        }
    }
}

private val flairs = listOf(Blake.pp, Blake.hero, Blake.ok, Blake.warn, Blake.danger, Blake.fg)
private fun flair(pubkey: String): Color = flairs[(pubkey.hashCode() and 0x7fffffff) % flairs.size]

