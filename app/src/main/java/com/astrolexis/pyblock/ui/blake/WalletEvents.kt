package com.astrolexis.pyblock.ui.blake

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * In-app notifications, the Omarchy way: one quiet line at the top that says what just happened
 * and gets out of the way. Events queue; each shows for a few seconds. Nothing modal, nothing
 * that covers content, nothing that needs dismissing (a tap does). Mirrors iOS WalletEvents.
 */
object WalletEvents {
    sealed class Kind {
        data class Received(val sats: Long) : Kind()
        data class Confirmed(val sats: Long) : Kind()
        data class Matured(val sats: Long) : Kind()
        data class Block(val height: Int, val stratum: String) : Kind()
    }
    data class Event(val id: Long, val kind: Kind) {
        val glyph get() = when (kind) { is Kind.Received -> Blake.RUNE; is Kind.Confirmed -> "✓"; is Kind.Matured -> "◈"; is Kind.Block -> "▣" }
        val text get() = when (val k = kind) {
            is Kind.Received -> "RECEIVED  +${Blake.btc(k.sats)} ${Blake.RUNE}"
            is Kind.Confirmed -> "CONFIRMED  ${Blake.btc(k.sats)} ${Blake.RUNE}"
            is Kind.Matured -> "MATURED  ${Blake.btc(k.sats)} ${Blake.RUNE} now spendable"
            is Kind.Block -> "BLOCK #${k.height}  ${k.stratum.uppercase()}"
        }
        val color: Color get() = when (kind) { is Kind.Received, is Kind.Matured -> Blake.ok; else -> Blake.pp }
    }

    private val _current = MutableStateFlow<Event?>(null)
    val current: StateFlow<Event?> = _current.asStateFlow()
    private val _formation = MutableStateFlow<Event?>(null)
    val formation: StateFlow<Event?> = _formation.asStateFlow()
    private val queue = ArrayDeque<Event>()
    private var seq = 0L
    private var draining = false

    @Synchronized fun post(kind: Kind) {
        val e = Event(++seq, kind)
        queue.addLast(e)
        if (kind is Kind.Received) _formation.value = e
    }
    fun dismiss() { _current.value = null }
    fun formationDone(e: Event) { if (_formation.value?.id == e.id) _formation.value = null }

    /** Drives the queue; called once from the root scaffold. */
    suspend fun drain() {
        while (true) {
            val next = synchronized(this) { queue.removeFirstOrNull() }
            if (next == null) { delay(150); continue }
            _current.value = next
            delay(2_800)
            if (_current.value?.id == next.id) _current.value = null
            delay(300)
        }
    }
}

/** The line itself. Slides in from the right, sits under the status bar, slides out to the left. */
@Composable
fun Ticker(modifier: Modifier = Modifier) {
    val current by WalletEvents.current.collectAsState()
    AnimatedContent(
        targetState = current, label = "ticker", modifier = modifier,
        transitionSpec = { (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut()) },
    ) { e ->
        if (e != null) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Blake.bg, Blake.bg.copy(alpha = 0f))))
                    .statusBarsPadding().padding(horizontal = 20.dp, vertical = 6.dp)
                    .clickableNoRipple { WalletEvents.dismiss() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(e.glyph, style = Blake.mono(11f, FontWeight.ExtraBold), color = e.color)
                Spacer(Modifier.width(8.dp))
                Text(e.text, style = Blake.mono(10f, FontWeight.ExtraBold), color = e.color, letterSpacing = 1.5.sp, maxLines = 1)
            }
        } else Box(Modifier.fillMaxWidth())
    }
}

/**
 * The receive formation: a small squadron of runes enters from the top right in a sine wave, the
 * Galaga way, and folds into the ticker line as the RECEIVED text lands. Canvas, ~1.4 s, never
 * intercepts touches. Mirrors iOS ReceiveFormationView.
 */
@Composable
fun ReceiveFormation(event: WalletEvents.Event, count: Int = 7) {
    var t by remember(event.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(event.id) {
        val start = withFrameNanos { it }
        while (t < 1.5f) { withFrameNanos { now -> t = (now - start) / 1e9f } }
        WalletEvents.formationDone(event)
    }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.MONOSPACE; isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val tx = 26f * density; val ty = 64f * density
        paint.textSize = 14f * density
        drawIntoCanvas { c ->
            for (i in 0 until count) {
                val u = (t - i * 0.09f) / 1.15f
                if (u <= 0f || u >= 1f) continue
                val e = 1f - (1f - u).pow(3)
                val x0 = size.width + 20f * density + i * 12f * density; val y0 = -20f * density
                val x = x0 + (tx - x0) * e
                val y = y0 + (ty - y0) * e + (sin(u * PI * 3 + i * 0.6) * 22 * density * (1 - u)).toFloat()
                val a = if (u < 0.85f) 1f else (1f - u) / 0.15f
                paint.color = android.graphics.Color.argb((a * 255).toInt(), 0x2F, 0xD9, 0x68)
                c.nativeCanvas.drawText(Blake.RUNE, x, y + paint.textSize * 0.35f, paint)
            }
        }
    }
}
