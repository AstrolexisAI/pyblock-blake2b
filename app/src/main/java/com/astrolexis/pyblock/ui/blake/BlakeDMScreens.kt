package com.astrolexis.pyblock.ui.blake

import com.astrolexis.pyblock.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.height
import com.astrolexis.pyblock.data.nostr.ChatMedia
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.nostr.NostrClient
import com.astrolexis.pyblock.ui.components.clickableNoRipple

/** ✉ DMS inbox — encrypted (NIP-44) private threads. Blake port of iOS DMInboxView. */
@Composable
fun BlakeDMInbox(client: NostrClient, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val state by client.state.collectAsState()
    val peers = client.dmPeers()
    Column(Modifier.fillMaxSize().background(Blake.bg)) {
        Row(Modifier.fillMaxWidth().background(Blake.ink).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("✕", style = Blake.mono(20f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple(onClose))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.blk_private), style = Blake.mono(16f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 2.sp)
                Text(stringResource(R.string.blk_end_to_end_encrypted_nip_44), style = Blake.mono(8f), color = Blake.faint)
            }
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(Blake.line))
        if (peers.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.size(40.dp))
                Text(stringResource(R.string.blk_no_messages_yet), style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.ppDim)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.blk_long_press_a_message_in_community_messag), style = Blake.mono(9f), color = Blake.faint, textAlign = TextAlign.Center)
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(peers, key = { it }) { peer ->
                val last = state.conversations[peer]?.lastOrNull()
                val lastIn = state.conversations[peer]?.lastOrNull { !it.mine }
                val unread = lastIn != null && (state.myReadUpTo[peer] ?: 0L) < lastIn.createdAt
                Row(Modifier.fillMaxWidth().border(1.dp, Blake.line, Blake.shape).padding(12.dp).clickableNoRipple { onOpen(peer) },
                    verticalAlignment = Alignment.CenterVertically) {
                    BlakeIdenticon(seed = peer, dimen = 30.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(client.name(peer), style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.fg, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(6.dp))
                            last?.let { Text(ChatMedia.clock(it.createdAt), style = Blake.mono(7f), color = Blake.faint) }
                        }
                        // An image or a payment is not its raw marker text.
                        val preview = last?.let { m ->
                            val body = when {
                                m.text.startsWith("pyblock:img?") -> "image"
                                m.text.startsWith("pyblock:paid?") || m.text.startsWith("bitcoin:") -> "payment"
                                else -> m.text
                            }
                            (if (m.mine) "you: " else "") + body
                        } ?: ""
                        Text(preview, style = Blake.mono(9f), color = if (unread) Blake.fg else Blake.faint, maxLines = 1)
                    }
                    if (unread) Box(Modifier.size(8.dp).background(Blake.pp, androidx.compose.foundation.shape.CircleShape))
                    else Text("›", style = Blake.mono(16f), color = Blake.ppDim)
                }
            }
        }
    }
}

/** ✉ DMS thread — one encrypted conversation. Blake port of iOS DMThreadView. */
@Composable
fun BlakeDMThread(client: NostrClient, peer: String, onClose: () -> Unit) {
    val state by client.state.collectAsState()
    val thread = state.conversations[peer] ?: emptyList()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Same treatment as the room: land without animation, then animate only for what arrives
    // afterwards. Animating on every size change ran the scroll once per replayed message when a
    // thread loaded.
    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(peer) { client.markRead(peer) }
    LaunchedEffect(thread.lastOrNull()?.id) {
        if (thread.isEmpty()) return@LaunchedEffect
        if (primed) listState.animateScrollToItem(thread.size - 1)
        else { listState.scrollToItem(thread.size - 1); primed = true }
        client.markRead(peer)
    }

    Column(Modifier.fillMaxSize().background(Blake.bg)) {
        Row(Modifier.fillMaxWidth().background(Blake.ink).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("✕", style = Blake.mono(20f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple(onClose))
            Spacer(Modifier.width(12.dp))
            BlakeIdenticon(seed = peer, dimen = 26.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(client.name(peer), style = Blake.mono(14f, FontWeight.ExtraBold), color = Blake.hero, maxLines = 1)
                Text(stringResource(R.string.blk_end_to_end_encrypted), style = Blake.mono(7f), color = Blake.faint)
            }
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(Blake.line))

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(thread, key = { it.id }) { m ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                    val img = ChatMedia.imageUrl(m.text)
                    val foreign = ChatMedia.foreignImageUrl(m.text)
                    when {
                        foreign != null -> Column(Modifier.background(Blake.ink, Blake.shape).border(1.dp, Blake.warn.copy(alpha = 0.5f), Blake.shape).padding(10.dp)) {
                            Text(stringResource(R.string.blk_image_from_another_site), style = Blake.mono(9f), color = Blake.warn)
                            Text(foreign, style = Blake.mono(7f), color = Blake.faint, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        img != null -> coil.compose.AsyncImage(model = img, contentDescription = null,
                            modifier = Modifier.width(220.dp).height(165.dp).border(1.dp, Blake.line, Blake.shape))
                        else -> androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(m.text, style = Blake.mono(12f), color = Blake.fg,
                                modifier = Modifier.background(if (m.mine) Blake.pp.copy(alpha = 0.14f) else Blake.ink, Blake.shape).border(1.dp, Blake.line, Blake.shape).padding(10.dp))
                        }
                    }
                    Text(ChatMedia.clock(m.createdAt), style = Blake.mono(7f), color = Blake.faint, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        Box(Modifier.fillMaxWidth().size(1.dp).background(Blake.line))
        Row(Modifier.fillMaxWidth().background(Blake.ink).navigationBarsPadding().imePadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value = draft, onValueChange = { draft = it },
                textStyle = Blake.mono(12f).copy(color = Blake.fg), cursorBrush = SolidColor(Blake.pp),
                modifier = Modifier.weight(1f).border(1.dp, Blake.line, Blake.shape).padding(10.dp),
                decorationBox = { inner -> if (draft.isEmpty()) Text(stringResource(R.string.blk_encrypted_message), style = Blake.mono(12f), color = Blake.faint); inner() })
            Spacer(Modifier.width(8.dp))
            val canSend = draft.isNotBlank()
            Text(stringResource(R.string.blk_send), style = Blake.mono(11f, FontWeight.ExtraBold), color = if (canSend) Blake.bg else Blake.faint,
                modifier = Modifier.then(if (canSend) Modifier.background(Blake.pp, Blake.shape) else Modifier.border(1.dp, Blake.line, Blake.shape))
                    .padding(horizontal = 14.dp, vertical = 11.dp)
                    .clickableNoRipple { if (canSend) { client.sendDM(peer, draft.trim()); draft = ""; com.astrolexis.pyblock.ui.Haptics.tap() } })
        }
    }
}
