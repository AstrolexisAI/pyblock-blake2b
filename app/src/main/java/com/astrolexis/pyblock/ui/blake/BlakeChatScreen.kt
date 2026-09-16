package com.astrolexis.pyblock.ui.blake

import com.astrolexis.pyblock.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.unit.IntOffset
import com.astrolexis.pyblock.data.nostr.SendStatus
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
    val prefs = remember { ctx.getSharedPreferences("pyblock_chat_ui", android.content.Context.MODE_PRIVATE) }
    var draft by remember { mutableStateOf(prefs.getString("draft.community", "") ?: "") }
    var showName by remember { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<NostrEvent?>(null) }
    var profile by remember { mutableStateOf<String?>(null) }
    var highlighted by remember { mutableStateOf<String?>(null) }
    var newDividerId by remember { mutableStateOf<String?>(null) }
    val clip = LocalClipboardManager.current
    var showDMs by remember { mutableStateOf(false) }
    var dmPeer by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val myPubkey = remember { runCatching { Nostr.pubkeyHex(ctx) }.getOrDefault("") }
    // Two rooms. The lounge is the WHALE channel; everyone sees the door, WHALE goes in.
    var lounge by remember { mutableStateOf(false) }
    val isWhale = com.astrolexis.pyblock.data.store.EntitlementsStore.isWhale
    val roomMessages = if (lounge) state.whaleMessages else state.messages

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            uploading = true
            val jpeg = ChatImage.downscaleJpeg(ctx, uri)
            val url = if (jpeg != null) ChatMediaRepo.uploadImage(jpeg) else null
            if (url != null) client.post("pyblock:img?url=$url", toWhaleLounge = lounge)
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
    val lastId = roomMessages.lastOrNull()?.id
    // Rows: a run (same author within five minutes, same day) shares one header; day boundaries
    // get a separator; the "new messages" line goes above the first message since the room was
    // last read, when there are enough of them to be worth a line.
    val rows = remember(roomMessages, newDividerId) { buildRows(roomMessages, newDividerId) }
    LaunchedEffect(lastId, lounge) {
        val n = roomMessages.size
        if (n == 0) return@LaunchedEffect
        val lastMine = roomMessages.last().pubkey == myPubkey
        when {
            !primed -> {
                val since = prefs.getLong(if (lounge) "read.lounge" else "read.community", 0L)
                val fresh = if (since > 0) roomMessages.filter { it.created_at > since && it.pubkey != myPubkey } else emptyList()
                newDividerId = if (fresh.size >= 3) fresh.first().id else null
                val target = newDividerId?.let { id -> rows.indexOfFirst { it.m.id == id } }?.takeIf { it >= 0 }
                // Land on the "new messages" line when there is one, else the newest — no
                // animation. Rows measure late (an image resolving its size), so correct once more.
                listState.scrollToItem(target ?: (rows.size - 1))
                delay(150)
                if (target == null) listState.scrollToItem(rows.size - 1)
                primed = true
            }
            atBottom || lastMine -> { listState.animateScrollToItem(rows.size - 1); unread = 0 }
            else -> unread++
        }
    }
    LaunchedEffect(atBottom, lastId) {
        if (atBottom) { unread = 0; roomMessages.lastOrNull()?.let { prefs.edit().putLong(if (lounge) "read.lounge" else "read.community", it.created_at).apply() } }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { prefs.edit().putString(if (lounge) "draft.lounge" else "draft.community", draft).apply() }
    }

    // DM overlays.
    val peer = dmPeer
    if (peer != null) { BlakeDMThread(client, peer, onClose = { dmPeer = null }); return }
    if (showDMs) { BlakeDMInbox(client, onOpen = { dmPeer = it }, onClose = { showDMs = false }); return }

    Column(Modifier.fillMaxSize().background(Blake.bg)) {
        // Two rows. One row could not hold a room name, the badge, DMS and NAME at a readable size.
        Column(Modifier.fillMaxWidth().background(Blake.ink).statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (lounge) stringResource(R.string.blk_lounge) else stringResource(R.string.blk_community), style = Blake.mono(18f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 2.sp, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(7.dp).background(if (state.connected) Blake.ok else Blake.warn, CircleShape))
                Spacer(Modifier.weight(1f))
                BlakeTierBadge()
                Spacer(Modifier.width(8.dp))
                val unreadDMs = state.unreadDmCount()
                if (unreadDMs > 0)
                    Text(stringResource(R.string.blk_dms_2, unreadDMs), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.bg, maxLines = 1, softWrap = false,
                        modifier = Modifier.background(Blake.pp, Blake.shape).padding(horizontal = 6.dp, vertical = 2.dp).clickableNoRipple { showDMs = true })
                else
                    Text(stringResource(R.string.blk_dms), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.pp, maxLines = 1, softWrap = false, modifier = Modifier.clickableNoRipple { showDMs = true })
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.blk_name), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.pp, maxLines = 1, softWrap = false, modifier = Modifier.clickableNoRipple { showName = true })
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to stringResource(R.string.blk_community), true to stringResource(R.string.blk_lounge)).forEach { (l, label) ->
                    val on = lounge == l
                    Text(label, style = Blake.mono(8f, FontWeight.ExtraBold), color = if (on) Blake.bg else Blake.ppDim, letterSpacing = 1.sp, maxLines = 1, softWrap = false,
                        modifier = Modifier.then(if (on) Modifier.background(Blake.pp, Blake.shape) else Modifier.border(1.dp, Blake.line, Blake.shape))
                            .padding(horizontal = 8.dp, vertical = 4.dp).clickableNoRipple { if (lounge != l) {
                            com.astrolexis.pyblock.ui.Haptics.tap()
                            // Each room keeps its own half-written message and its own read mark.
                            prefs.edit().putString(if (lounge) "draft.lounge" else "draft.community", draft)
                                .putLong(if (lounge) "read.lounge" else "read.community", roomMessages.lastOrNull()?.created_at ?: 0L).apply()
                            lounge = l; primed = false; unread = 0; replyTo = null
                            draft = prefs.getString(if (l) "draft.lounge" else "draft.community", "") ?: ""
                            newDividerId = null
                        } })
                }
                if (!lounge && state.whaleMessages.isNotEmpty()) Text(stringResource(R.string.blk_inside, state.whaleMessages.size), style = Blake.mono(8f), color = Blake.faint)
            }
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(Blake.line))

        if (lounge && !isWhale) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                RuneGlyph(Rune.LAGUZ, forge = RuneForge.TEMPERED, ink = Blake.hero, size = 56.dp)
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.blk_the_lounge), style = Blake.mono(14f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 3.sp)
                Text(stringResource(R.string.blk_the_whale_room_messages_inside, state.whaleMessages.size), style = Blake.mono(9f), color = Blake.ppDim)
            }
        } else
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (roomMessages.isEmpty()) {
                    item { Text(if (lounge) stringResource(R.string.blk_nothing_here_yet_whales_say_something) else stringResource(R.string.blk_no_messages_yet_say_hi_to_the_pybl_ck_co), style = Blake.mono(10f), color = Blake.faint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 40.dp)) }
                }
                items(rows, key = { it.m.id }) { row ->
                    val m = row.m
                    Column(Modifier.fillMaxWidth()) {
                        row.day?.let { DaySeparator(it) }
                        if (row.newLine) NewMessagesLine()
                        val quoteId = client.replyParentId(m)
                        val quoted = quoteId?.let { client.message(it) }
                        SwipeToReply(onReply = { replyTo = m; com.astrolexis.pyblock.ui.Haptics.tap() }) {
                            Bubble(m, mine = m.pubkey == myPubkey, name = state.profiles[m.pubkey], header = row.header,
                                marks = state.marks[m.pubkey].orEmpty(),
                                ink = paletteColor(client.colorFor(m.pubkey)) ?: flair(m.pubkey),
                                forge = forgeOf(client.forgeFor(m.pubkey)),
                                sigil = parseSigil(client.sigilFor(m.pubkey)),
                                reactions = state.reactionSummary(m.id, myPubkey),
                                status = if (m.pubkey == myPubkey) state.sendStatus[m.id] else null,
                                quoteId = quoteId, quoted = quoted,
                                quoteName = quoted?.let { state.profiles[it.pubkey] ?: "…${it.pubkey.takeLast(6)}" },
                                quoteInk = quoted?.let { paletteColor(client.colorFor(it.pubkey)) ?: flair(it.pubkey) } ?: Blake.faint,
                                lit = highlighted == m.id,
                                onQuoteTap = { id ->
                                    val idx = rows.indexOfFirst { it.m.id == id }
                                    if (idx >= 0) scope.launch { listState.animateScrollToItem(idx); highlighted = id; delay(1400); if (highlighted == id) highlighted = null }
                                },
                                onReact = { e -> client.react(m.id, m.pubkey, e) },
                                onReply = { replyTo = m },
                                onCopy = { clip.setText(AnnotatedString(m.content)); toast(ctx, ctx.getString(R.string.blk_copied_2)) },
                                onRetry = { client.retry(m.id) },
                                onProfile = { if (m.pubkey == myPubkey) showName = true else profile = m.pubkey },
                                onDm = { if (m.pubkey != myPubkey) dmPeer = m.pubkey },
                                onBlock = { client.blockUser(m.pubkey) },
                                onReport = { client.reportMessage(m.id, m.pubkey) })
                        }
                    }
                }
            }
            // The arrow is there whenever the newest message is off screen; it counts only what
            // arrived meanwhile.
            if (!atBottom && primed) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                        .then(if (unread > 0) Modifier.background(Blake.pp, Blake.shape) else Modifier.background(Blake.ink, Blake.shape))
                        .border(1.dp, Blake.pp, Blake.shape).padding(horizontal = 12.dp, vertical = 7.dp)
                        .clickableNoRipple { scope.launch { listState.animateScrollToItem(rows.size - 1) }; unread = 0; newDividerId = null }) {
                    Text("↓", style = Blake.mono(11f, FontWeight.ExtraBold), color = if (unread > 0) Blake.bg else Blake.pp)
                    if (unread > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(if (unread == 1) stringResource(R.string.blk_1_new_message) else stringResource(R.string.blk_new_messages_n, unread),
                            style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.bg, letterSpacing = 1.sp)
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().size(1.dp).background(Blake.line))
        replyTo?.let { r ->
            val rInk = paletteColor(client.colorFor(r.pubkey)) ?: flair(r.pubkey)
            Row(Modifier.fillMaxWidth().background(Blake.ink).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(2.dp).height(24.dp).background(rInk))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.blk_reply_to, state.profiles[r.pubkey] ?: "…${r.pubkey.takeLast(6)}"), style = Blake.mono(8f, FontWeight.ExtraBold), color = rInk, maxLines = 1)
                    Text(if (ChatMedia.imageUrl(r.content) != null) stringResource(R.string.blk_image) else r.content, style = Blake.mono(9f), color = Blake.ppDim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Text("✕", style = Blake.mono(12f), color = Blake.faint, modifier = Modifier.padding(6.dp).clickableNoRipple { replyTo = null })
            }
        }
        Row(Modifier.fillMaxWidth().background(Blake.ink).navigationBarsPadding().imePadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (uploading) "…" else "＋", style = Blake.mono(18f, FontWeight.ExtraBold), color = Blake.pp,
                modifier = Modifier.border(1.dp, Blake.line, Blake.shape).padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickableNoRipple { if (!uploading) picker.launch("image/*") })
            Spacer(Modifier.width(8.dp))
            BasicTextField(value = draft, onValueChange = { draft = it },
                textStyle = Blake.mono(12f).copy(color = Blake.fg), cursorBrush = SolidColor(Blake.pp),
                modifier = Modifier.weight(1f).border(1.dp, Blake.line, Blake.shape).padding(10.dp),
                decorationBox = { inner -> if (draft.isEmpty()) Text(stringResource(R.string.blk_message), style = Blake.mono(12f), color = Blake.faint); inner() })
            Spacer(Modifier.width(8.dp))
            val canSend = draft.isNotBlank()
            Text(stringResource(R.string.blk_send), style = Blake.mono(11f, FontWeight.ExtraBold), color = if (canSend) Blake.bg else Blake.faint,
                modifier = Modifier.then(if (canSend) Modifier.background(Blake.pp, Blake.shape) else Modifier.border(1.dp, Blake.line, Blake.shape))
                    .padding(horizontal = 14.dp, vertical = 11.dp)
                    .clickableNoRipple { if (canSend) { client.post(draft.trim(), replyTo = replyTo, toWhaleLounge = lounge); draft = ""; replyTo = null; prefs.edit().putString(if (lounge) "draft.lounge" else "draft.community", "").apply(); com.astrolexis.pyblock.ui.Haptics.tap() } })
        }
    }

    if (showName) NameSheet(client) { showName = false }
    profile?.let { pk ->
        ProfileCard(client, pk, onClose = { profile = null },
            onMessage = { profile = null; dmPeer = pk },
            onPay = { code -> profile = null; onPay(code, null, pk) })
    }
}

/** One transcript row: the message plus whether it opens a run, which day it starts, and
 *  whether the "new messages" line goes above it. */
private data class ChatRow(val m: NostrEvent, val header: Boolean, val day: String?, val newLine: Boolean)

private fun buildRows(list: List<NostrEvent>, dividerId: String?): List<ChatRow> {
    val out = ArrayList<ChatRow>(list.size)
    var prev: NostrEvent? = null
    for (m in list) {
        val sameDay = prev != null && ChatMedia.sameDay(prev.created_at, m.created_at)
        val run = prev != null && prev.pubkey == m.pubkey && m.created_at - prev.created_at < 300 && sameDay
        out.add(ChatRow(m, header = !run, day = if (sameDay) null else ChatMedia.dayLabel(m.created_at), newLine = m.id == dividerId))
        prev = m
    }
    return out
}

@Composable
private fun DaySeparator(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Blake.line))
        Text(label, style = Blake.mono(7f, FontWeight.ExtraBold), color = Blake.faint, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Blake.line))
    }
}

@Composable
private fun NewMessagesLine() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Blake.pp.copy(alpha = 0.6f)))
        Text(stringResource(R.string.blk_new_messages), style = Blake.mono(7f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 2.sp, modifier = Modifier.padding(horizontal = 8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Blake.pp.copy(alpha = 0.6f)))
    }
}

/** Drag a message to the right to answer it. Vertical drags stay with the list; only a
 *  horizontal one moves the row. */
@Composable
private fun SwipeToReply(onReply: () -> Unit, content: @Composable () -> Unit) {
    var offset by remember { mutableStateOf(0f) }
    var fired by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(offset, label = "swipe")
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxPx = with(density) { 72.dp.toPx() }; val firePx = with(density) { 48.dp.toPx() }
    Box(Modifier.fillMaxWidth()
        .draggable(orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta ->
                offset = (offset + delta * 0.6f).coerceIn(0f, maxPx)
                if (offset > firePx && !fired) { fired = true; com.astrolexis.pyblock.ui.Haptics.tap() }
            },
            onDragStopped = { if (offset > firePx) onReply(); fired = false; offset = 0f })) {
        Text("↩", style = Blake.mono(14f, FontWeight.ExtraBold), color = Blake.pp,
            modifier = Modifier.align(Alignment.CenterStart).alpha((shown / firePx).coerceIn(0f, 1f)).offset { IntOffset((shown - with(density) { 28.dp.toPx() }).toInt(), 0) })
        Box(Modifier.offset { IntOffset(shown.toInt(), 0) }) { content() }
    }
}

/** Who someone is, and the three things you can do with them — from a tap on their name. */
@Composable
private fun ProfileCard(client: NostrClient, pubkey: String, onClose: () -> Unit, onMessage: () -> Unit, onPay: (String) -> Unit) {
    val state by client.state.collectAsState()
    val name = state.profiles[pubkey] ?: "…${pubkey.takeLast(8)}"
    val ink = paletteColor(client.colorFor(pubkey)) ?: flair(pubkey)
    val forge = forgeOf(client.forgeFor(pubkey))
    val sigil = parseSigil(client.sigilFor(pubkey))
    val code = client.paynymFor(pubkey)
    var confirmBlock by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(Blake.ink, Blake.shape).border(1.dp, Blake.line, Blake.shape).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sigil.isNotEmpty()) RuneGlyph(sigil, forge = forge, ink = ink, size = 44.dp) else BlakeIdenticon(seed = pubkey, dimen = 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = Blake.mono(15f, FontWeight.ExtraBold), color = ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(pubkey.take(12) + "…", style = Blake.mono(8f), color = Blake.faint)
                }
                Text("✕", style = Blake.mono(20f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple(onClose))
            }
            val marks = state.marks[pubkey].orEmpty()
            if (marks.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.blk_runes_earned_mining), style = Blake.mono(8f, FontWeight.ExtraBold), color = Blake.faint, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    marks.take(6).forEach { mk ->
                        Rune.forMark(mk.rune)?.let { r ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                RuneGlyph(r, forge = forge, ink = Rune.markInk(r), size = 22.dp)
                                Text(r.name, style = Blake.mono(6f), color = Blake.faint)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(if (code != null) stringResource(R.string.blk_shares_a_paynym_you_can_pay_them_to_a_fr) else stringResource(R.string.blk_hasn_t_shared_a_paynym_you_can_message_t),
                style = Blake.mono(9f), color = Blake.faint)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardAction(stringResource(R.string.blk_message_2), Blake.pp, filled = true, Modifier.weight(1f), onMessage)
                if (code != null) CardAction(stringResource(R.string.blk_pay), Blake.ok, filled = false, Modifier.weight(1f)) { onPay(code) }
                CardAction(stringResource(R.string.blk_block), Blake.danger, filled = false, Modifier.weight(1f)) { confirmBlock = true }
            }
            if (confirmBlock) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.blk_their_messages_and_reactions_disappear_fr), style = Blake.mono(9f), color = Blake.danger)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardAction(stringResource(R.string.blk_cancel), Blake.ppDim, filled = false, Modifier.weight(1f)) { confirmBlock = false }
                    CardAction(stringResource(R.string.blk_block_user), Blake.danger, filled = true, Modifier.weight(1f)) { client.blockUser(pubkey); onClose() }
                }
            }
        }
    }
}

@Composable
private fun CardAction(title: String, tint: Color, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(title, style = Blake.mono(10f, FontWeight.ExtraBold), color = if (filled) Blake.bg else tint, letterSpacing = 1.sp, textAlign = TextAlign.Center, maxLines = 1,
        modifier = modifier.then(if (filled) Modifier.background(tint, Blake.shape) else Modifier).border(1.dp, tint, Blake.shape)
            .padding(vertical = 11.dp).clickableNoRipple { com.astrolexis.pyblock.ui.Haptics.tap(); onClick() })
}


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: NostrEvent, mine: Boolean, name: String?, header: Boolean = true,
                   marks: List<com.astrolexis.pyblock.data.blake.BlakeApi.Mark> = emptyList(),
                   ink: Color = Blake.pp, forge: RuneForge = RuneForge.CAST, sigil: List<Rune> = emptyList(),
                   reactions: List<Triple<String, Int, Boolean>>,
                   status: SendStatus? = null,
                   quoteId: String? = null, quoted: NostrEvent? = null, quoteName: String? = null, quoteInk: Color = Blake.faint,
                   lit: Boolean = false,
                   onQuoteTap: (String) -> Unit = {},
                   onReact: (String) -> Unit, onReply: () -> Unit = {}, onCopy: () -> Unit = {}, onRetry: () -> Unit = {},
                   onProfile: () -> Unit = {}, onDm: () -> Unit, onBlock: () -> Unit, onReport: () -> Unit) {
    val label = name ?: "…${m.pubkey.takeLast(6)}"
    val imgUrl = ChatMedia.imageUrl(m.content)
    val foreign = ChatMedia.foreignImageUrl(m.content)
    val failed = status == SendStatus.FAILED
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()
            .background(if (lit) Blake.pp.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = { if (failed) onRetry() }, onLongClick = { menu = true })
            .padding(top = if (header) 8.dp else 0.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (header) {
            // The name is the door to the person: tap it for their card (message, pay, block).
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickableNoRipple { com.astrolexis.pyblock.ui.Haptics.tap(); onProfile() }) {
                // The bindrune: a sigil chosen by the person, drawn in their forge and ink. It is the
                // thing PRO and WHALE buy that shows without having mined anything.
                if (sigil.isNotEmpty()) { RuneGlyph(sigil, forge = forge, ink = ink, size = 14.dp); Spacer(Modifier.width(4.dp)) }
                Text(label, style = Blake.mono(9f, FontWeight.ExtraBold), color = ink, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                // Runes earned mining, certified by the server. Not for sale.
                marks.take(4).forEach { mk ->
                    Rune.forMark(mk.rune)?.let { r ->
                        Spacer(Modifier.width(2.dp))
                        RuneGlyph(r, forge = forge, ink = Rune.markInk(r), size = 10.dp)
                    }
                }
            }
            Spacer(Modifier.size(3.dp))
        }
        // Time and delivery state, inside the bubble bottom-right where every chat puts them.
        val stamp: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ChatMedia.timeOnly(m.created_at), style = Blake.mono(7f), color = Blake.faint)
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    when (status) {
                        SendStatus.SENDING -> Text("◌", style = Blake.mono(8f), color = Blake.faint)
                        SendStatus.FAILED -> Text("!", style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.danger)
                        else -> Text("✓", style = Blake.mono(8f), color = Blake.faint)
                    }
                }
            }
        }
        if (foreign != null) {
            // Hosted somewhere we don't load from. Loading it would hand that host the reader's IP
            // address, so it stays a link the reader can choose to open.
            Column(Modifier.background(Blake.ink, Blake.shape).border(1.dp, Blake.warn.copy(alpha = 0.5f), Blake.shape).padding(10.dp)) {
                Text(stringResource(R.string.blk_image_from_another_site), style = Blake.mono(9f), color = Blake.warn)
                Text(foreign, style = Blake.mono(7f), color = Blake.faint, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            stamp()
        } else if (imgUrl != null) {
            // One fixed box, so an image that finishes loading doesn't shove the rest of the
            // conversation down under the reader's thumb.
            AsyncImage(model = imgUrl, contentDescription = null,
                modifier = Modifier.width(220.dp).height(165.dp).border(1.dp, Blake.line, Blake.shape))
            stamp()
        } else {
            Column(Modifier.widthIn(max = 300.dp).background(if (mine) Blake.pp.copy(alpha = 0.14f) else Blake.ink, Blake.shape)
                    .border(1.dp, if (failed) Blake.danger else Blake.line, Blake.shape).padding(10.dp)) {
                // The message being answered, inside the answer. Tapping it goes to the original.
                if (quoteId != null) {
                    Row(Modifier.clickableNoRipple { onQuoteTap(quoteId) }.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(2.dp).height(26.dp).background(quoteInk))
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(quoteName ?: stringResource(R.string.blk_earlier_message), style = Blake.mono(8f, FontWeight.ExtraBold), color = quoteInk, maxLines = 1)
                            val qt = when {
                                quoted == null -> stringResource(R.string.blk_not_loaded)
                                ChatMedia.imageUrl(quoted.content) != null -> stringResource(R.string.blk_image)
                                else -> quoted.content
                            }
                            Text(qt, style = Blake.mono(9f), color = Blake.ppDim, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(m.content, style = Blake.mono(12f), color = Blake.fg)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { stamp() }
            }
        }
        if (failed) Text(stringResource(R.string.blk_not_delivered_tap_to_retry), style = Blake.mono(7f, FontWeight.ExtraBold), color = Blake.danger, modifier = Modifier.padding(top = 2.dp))
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
            Spacer(Modifier.size(14.dp))
            Text(stringResource(R.string.blk_reply), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.pp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickableNoRipple { onReply(); menu = false })
            if (imgUrl == null && foreign == null)
                Text(stringResource(R.string.blk_copy), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.pp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickableNoRipple { onCopy(); menu = false })
            if (failed)
                Text(stringResource(R.string.blk_send_again), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.pp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickableNoRipple { onRetry(); menu = false })
            if (!mine) {
                Text(stringResource(R.string.blk_message_2), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.pp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickableNoRipple { onDm(); menu = false })
                Text(stringResource(R.string.blk_report_message), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.warn, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickableNoRipple { onReport(); menu = false })
                Text(stringResource(R.string.blk_block_user), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.danger, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickableNoRipple { onBlock(); menu = false })
            }
        }
    }
}

@Composable
private fun NameSheet(client: NostrClient, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Nostr.displayName(ctx) ?: "") }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().background(Blake.ink, Blake.shape).border(1.dp, Blake.line, Blake.shape).padding(20.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.blk_your_name), style = Blake.mono(16f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text("✕", style = Blake.mono(18f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple(onClose))
            }
            Spacer(Modifier.size(16.dp))
            BasicTextField(value = name, onValueChange = { name = it }, singleLine = true,
                textStyle = Blake.mono(13f).copy(color = Blake.fg), cursorBrush = SolidColor(Blake.pp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Blake.line, Blake.shape).padding(10.dp),
                decorationBox = { inner -> if (name.isEmpty()) Text(stringResource(R.string.blk_display_name), style = Blake.mono(13f), color = Blake.faint); inner() })
            Spacer(Modifier.size(14.dp))
            val isPro = com.astrolexis.pyblock.data.store.EntitlementsStore.isPro
            val isWhale = com.astrolexis.pyblock.data.store.EntitlementsStore.isWhale
            var color by remember { mutableStateOf(Nostr.flairColor(ctx) ?: "pp") }
            var forge by remember { mutableStateOf(Nostr.forge(ctx)) }
            var picked by remember { mutableStateOf(parseSigil(Nostr.sigil(ctx))) }
            val inkNow = paletteColor(color) ?: Blake.pp
            // Purple is free. The other inks are PRO — the first thing the forge sells.
            Text(stringResource(R.string.blk_ink), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("pp", "hero", "ok", "warn", "danger").forEach { key ->
                    val locked = key != "pp" && !isPro
                    Box(Modifier.size(28.dp).background(paletteColor(key)!!, CircleShape).alpha(if (locked) 0.35f else 1f)
                        .border(if (color == key) 2.dp else 0.dp, Blake.hero, CircleShape)
                        .clickableNoRipple { if (locked) toast(ctx, ctx.getString(R.string.blk_the_other_inks_are_pro)) else { color = key; client.setColor(key) } })
                }
            }
            if (!isPro) Text(stringResource(R.string.blk_purple_is_free_pro_opens_the_other_inks), style = Blake.mono(8f), color = Blake.faint)
            Spacer(Modifier.size(12.dp))
            // The forge: how your runes are drawn on everyone's screen. Tempered is the WHALE stroke.
            Text(stringResource(R.string.blk_forge), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Triple("engraved", stringResource(R.string.blk_engraved), RuneForge.ENGRAVED), Triple("cast", stringResource(R.string.blk_cast), RuneForge.CAST), Triple("tempered", stringResource(R.string.blk_tempered), RuneForge.TEMPERED)).forEach { (key, label, f) ->
                    val locked = key == "tempered" && !isWhale
                    val on = forge == key
                    Column(Modifier.weight(1f).border(if (on) 2.dp else 1.dp, if (on) Blake.pp else Blake.line, Blake.shape).padding(vertical = 6.dp)
                        .clickableNoRipple { if (locked) toast(ctx, ctx.getString(R.string.blk_the_tempered_forge_is_whale)) else { forge = key; client.setForge(key) } },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        RuneGlyph(Rune.TIWAZ, forge = f, ink = inkNow, size = 30.dp, modifier = Modifier.alpha(if (locked) 0.35f else 1f))
                        Text(if (locked) stringResource(R.string.blk_whale) else label, style = Blake.mono(7f, FontWeight.ExtraBold), color = if (locked) Blake.hero else if (on) Blake.pp else Blake.faint, letterSpacing = 1.sp)
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            // The bindrune: composed from the base runes, nothing earned required. PRO three, WHALE four.
            val limit = if (isWhale) 4 else if (isPro) 3 else 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.blk_sigil), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text(if (limit == 0) "PRO" else "${picked.size} / $limit", style = Blake.mono(8f, FontWeight.ExtraBold), color = if (limit == 0) Blake.pp else Blake.faint, letterSpacing = 1.sp)
            }
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RuneGlyph(if (picked.isEmpty()) listOf(Rune.ISA) else picked, forge = forgeOf(forge), ink = if (picked.isEmpty()) Blake.faint else inkNow, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Text(if (picked.isEmpty()) stringResource(R.string.blk_tap_runes_below_to_ligate_them_into_your) else picked.joinToString(" · ") { it.label },
                    style = Blake.mono(8f), color = Blake.faint, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.size(6.dp))
            val choosable = Rune.entries.filter { it !in reservedRunes }
            choosable.chunked(7).forEach { rowRunes ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    rowRunes.forEach { r ->
                        val on = r in picked
                        Box(Modifier.weight(1f).border(if (on) 2.dp else 1.dp, if (on) inkNow else Blake.line, Blake.shape).padding(vertical = 5.dp)
                            .clickableNoRipple {
                                if (limit == 0) { toast(ctx, ctx.getString(R.string.blk_a_bindrune_is_pro_three_runes_whale_four)); return@clickableNoRipple }
                                picked = if (on) picked - r else if (picked.size < limit) picked + r else { com.astrolexis.pyblock.ui.Haptics.error(); picked }
                                client.setSigil(picked.map { it.name.lowercase() })
                            }, contentAlignment = Alignment.Center) {
                            RuneGlyph(r, forge = RuneForge.CAST, ink = if (on) inkNow else Blake.ppDim, size = 22.dp)
                        }
                    }
                    repeat(7 - rowRunes.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (picked.isNotEmpty()) Text(stringResource(R.string.blk_clear_sigil), style = Blake.mono(8f, FontWeight.ExtraBold), color = Blake.faint, letterSpacing = 1.sp,
                modifier = Modifier.clickableNoRipple { picked = emptyList(); client.setSigil(emptyList()) })
            Spacer(Modifier.size(16.dp))
            Text(stringResource(R.string.blk_save), style = Blake.mono(13f, FontWeight.ExtraBold), color = Blake.bg, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Blake.pp, Blake.shape).padding(vertical = 12.dp)
                    .clickableNoRipple { if (name.isNotBlank()) { Nostr.setDisplayName(ctx, name.trim()); client.republishProfile(); com.astrolexis.pyblock.ui.Haptics.tap() }; onClose() })
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.blk_your_nostr_identity_is_device_only_blake), style = Blake.mono(8f), color = Blake.faint)
        }
    }
}

private fun paletteColor(key: String?): Color? = when (key) {
    "pp" -> Blake.pp; "hero" -> Blake.hero; "ok" -> Blake.ok; "warn" -> Blake.warn; "danger" -> Blake.danger; "fg" -> Blake.fg; else -> null
}
private fun forgeOf(key: String?): RuneForge = when (key) { "engraved" -> RuneForge.ENGRAVED; "tempered" -> RuneForge.TEMPERED; else -> RuneForge.CAST }
private val reservedRunes = setOf(Rune.BERKANAN, Rune.DAGAZ, Rune.PERTHRO)
/** A sigil as it arrives: rune names, comma-separated, at most four, none of the reserved. */
private fun parseSigil(raw: String): List<Rune> =
    raw.split(",").mapNotNull { n -> runCatching { Rune.valueOf(n.trim().uppercase()) }.getOrNull() }
        .filter { it !in reservedRunes }.distinct().take(4)

private val flairs = listOf(Blake.pp, Blake.hero, Blake.ok, Blake.warn, Blake.danger, Blake.fg)
private fun flair(pubkey: String): Color = flairs[(pubkey.hashCode() and 0x7fffffff) % flairs.size]



private fun toast(ctx: android.content.Context, msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
