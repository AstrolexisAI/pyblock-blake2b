package com.astrolexis.pyblock.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/// Keeps a chat LazyColumn anchored to the newest message.
///
/// While the reader is at the bottom, any drift — a new message, a payment
/// card changing height, the keyboard resizing the viewport — snaps the list
/// back so the last message stays focused. A drag up releases the pin (no
/// hijacking while reading history) and NewMessagesPill offers the way back.
@Composable
fun rememberPinnedChat(itemCount: Int, onActivity: (() -> Unit)? = null): PinnedChat {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val pin = remember { PinnedChat(listState, scope) }

    // New items: follow if pinned, otherwise count them for the pill.
    LaunchedEffect(itemCount) {
        val delta = itemCount - pin.prevCount
        pin.prevCount = itemCount
        if (pin.pinned) pin.jump(animate = true) else if (delta > 0) pin.unseen += delta
        onActivity?.invoke()
    }

    // Enforce the anchor while idle; let user gestures decide the pin.
    LaunchedEffect(Unit) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val atBottom = last == null || (last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 24)
            Triple(atBottom, listState.isScrollInProgress, info.totalItemsCount)
        }.collect { (atBottom, scrolling, _) ->
            when {
                scrolling && !pin.autoScrolling -> {
                    pin.pinned = atBottom
                    if (atBottom) pin.unseen = 0
                }
                !scrolling && pin.pinned && !atBottom -> pin.jump(animate = false)
                !scrolling && !pin.pinned && atBottom -> { pin.pinned = true; pin.unseen = 0 }
            }
        }
    }
    return pin
}

class PinnedChat internal constructor(
    val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    internal var prevCount = 0
    internal var autoScrolling = false
    var pinned by mutableStateOf(true); internal set
    var unseen by mutableIntStateOf(0); internal set

    fun jump(animate: Boolean) {
        pinned = true
        unseen = 0
        scope.launch {
            val idx = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            autoScrolling = true
            try {
                if (animate) listState.animateScrollToItem(idx) else listState.scrollToItem(idx)
            } finally {
                autoScrolling = false
            }
        }
    }
}

/// "↓ N" pill shown while the reader is up in history and new messages land.
@Composable
fun BoxScope.NewMessagesPill(pin: PinnedChat) {
    if (!pin.pinned && pin.unseen > 0) {
        Text(
            "↓ ${pin.unseen}",
            style = PyType.mono(11f),
            color = PyTheme.bg,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 10.dp)
                .background(PyTheme.primary, RoundedCornerShape(999.dp))
                .clickableNoRipple { pin.jump(animate = true) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
