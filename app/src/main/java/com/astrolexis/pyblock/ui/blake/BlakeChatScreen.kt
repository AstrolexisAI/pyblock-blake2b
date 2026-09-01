package com.astrolexis.pyblock.ui.blake

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.astrolexis.pyblock.data.nostr.Nostr
import com.astrolexis.pyblock.data.nostr.NostrClient
import com.astrolexis.pyblock.data.nostr.NostrEvent
import com.astrolexis.pyblock.ui.components.clickableNoRipple

/** COMMUNITY — the shared PyBLØCK chat (same Nostr channel as the SHA-256 app). Faithful
 *  Blake port of iOS ChatView: header + colored names + bubbles + composer. */
@Composable
fun BlakeChatScreen(client: NostrClient, onPay: (String, Long?, String) -> Unit) {
    val ctx = LocalContext.current
    val state by client.state.collectAsState()
    var draft by remember { mutableStateOf("") }
    var showName by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val myPubkey = remember { runCatching { Nostr.pubkeyHex(ctx) }.getOrDefault("") }

    LaunchedEffect(Unit) { client.connect() }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Column(Modifier.fillMaxSize().background(Blake.bg)) {
        // header
        Row(Modifier.fillMaxWidth().background(Blake.ink).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("COMMUNITY", style = Blake.mono(18f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 2.sp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(7.dp).background(if (state.connected) Blake.ok else Blake.warn, CircleShape))
            Spacer(Modifier.weight(1f))
            Text("⚙ NAME", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.pp,
                modifier = Modifier.clickableNoRipple { showName = true })
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(Blake.line))

        // messages
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.messages.isEmpty()) {
                item {
                    Text("No messages yet. Say hi to the PyBLØCK community.",
                        style = Blake.mono(10f), color = Blake.faint, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp))
                }
            }
            items(state.messages, key = { it.id }) { m -> Bubble(m, mine = m.pubkey == myPubkey, name = state.profiles[m.pubkey]) }
        }

        // composer
        Box(Modifier.fillMaxWidth().size(1.dp).background(Blake.line))
        Row(Modifier.fillMaxWidth().background(Blake.ink).navigationBarsPadding().imePadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = draft, onValueChange = { draft = it },
                textStyle = Blake.mono(12f).copy(color = Blake.fg), cursorBrush = SolidColor(Blake.pp),
                modifier = Modifier.weight(1f).border(1.dp, Blake.line, Blake.shape).padding(10.dp),
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text("message…", style = Blake.mono(12f), color = Blake.faint)
                    inner()
                },
            )
            Spacer(Modifier.width(8.dp))
            val canSend = draft.isNotBlank()
            Text("SEND", style = Blake.mono(11f, FontWeight.ExtraBold), color = if (canSend) Blake.bg else Blake.faint,
                modifier = Modifier
                    .then(if (canSend) Modifier.background(Blake.pp, Blake.shape) else Modifier.border(1.dp, Blake.line, Blake.shape))
                    .padding(horizontal = 14.dp, vertical = 11.dp)
                    .clickableNoRipple { if (canSend) { client.post(draft.trim()); draft = ""; com.astrolexis.pyblock.ui.Haptics.tap() } })
        }
    }

    if (showName) NameSheet(client) { showName = false }
}

@Composable
private fun Bubble(m: NostrEvent, mine: Boolean, name: String?) {
    val label = name ?: "…${m.pubkey.takeLast(6)}"
    val imgUrl = imageUrl(m.content)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Text(label, style = Blake.mono(9f, FontWeight.ExtraBold), color = flair(m.pubkey))
        Spacer(Modifier.size(3.dp))
        if (imgUrl != null) {
            AsyncImage(model = imgUrl, contentDescription = null,
                modifier = Modifier.width(220.dp).border(1.dp, Blake.line, Blake.shape))
        } else {
            Text(m.content, style = Blake.mono(12f), color = Blake.fg,
                modifier = Modifier
                    .background(if (mine) Blake.pp.copy(alpha = 0.14f) else Blake.ink, Blake.shape)
                    .border(1.dp, Blake.line, Blake.shape).padding(10.dp))
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
            Text("Your Nostr identity is device-only. BLAKE2b and SHA-256 users share this room.",
                style = Blake.mono(8f), color = Blake.faint)
        }
    }
}

// A deterministic flair color per pubkey (matches iOS colored names).
private val flairs = listOf(Blake.pp, Blake.hero, Blake.ok, Blake.warn, Blake.danger, Blake.fg)
private fun flair(pubkey: String): Color = flairs[(pubkey.hashCode() and 0x7fffffff) % flairs.size]

// Parse an image marker (pyblock:img?url=...) → the URL, else null.
private fun imageUrl(content: String): String? {
    val m = Regex("pyblock:img\\?url=(\\S+)").find(content) ?: return null
    return m.groupValues[1]
}
