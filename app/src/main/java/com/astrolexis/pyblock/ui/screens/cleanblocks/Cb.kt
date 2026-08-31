package com.astrolexis.pyblock.ui.screens.cleanblocks

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.astrolexis.pyblock.ui.theme.PyType
import java.util.Locale
import kotlin.math.roundToInt

/** Exact palette from cleanblocks.php CSS :root, kept local so the screen
 *  matches the web regardless of the app theme. */
object Cb {
    val bg = Color(0xFF080C08)
    val surface = Color(0xFF0E130E)
    val bg2 = Color(0xFF0B120B)
    val text = Color(0xFFDCE7DC)
    val muted = Color(0xFF829A82)
    val faint = Color(0xFF586858)
    val green = Color(0xFF2FD968)
    val greenHi = Color(0xFF5FE08E)
    val greenLo = Color(0xFF0E5230)
    val accent = Color(0xFF00FF41)
    val red = Color(0xFFFF5C6A)
    val redHi = Color(0xFFFF8A94)
    val redLo = Color(0xFF5A1F27)
    val amber = Color(0xFFE0B035)
    val line = Color(0xFF78C878).copy(alpha = 0.14f)
    val line2 = Color(0xFF78C878).copy(alpha = 0.30f)
    val pillInk = Color(0xFF1A1200)
    val headWhite = Color(0xFFEBFFEF)

    fun mono(size: Float): TextStyle = PyType.mono(size)
}

/** Formatting helpers mirroring the JS in cleanblocks.php. */
object CbFmt {
    fun n(v: Int): String = String.format(Locale.US, "%,d", v)

    fun btc(sats: Int): String {
        val b = sats / 1e8
        val dp = if (sats >= 10_000_000) 3 else if (sats >= 100_000) 4 else 5
        return String.format(Locale.US, "%.${dp}f", b)
    }

    fun mb(vb: Int): String =
        if (vb >= 1_000_000) String.format(Locale.US, "%.2f MB", vb / 1e6)
        else "${(vb / 1e3).roundToInt()} KB"

    fun ago(ts: Int): String {
        val s = maxOf(0, (System.currentTimeMillis() / 1000).toInt() - ts)
        if (s < 90) return "${s}s ago"
        val m = s / 60
        if (m < 90) return "${m}m ago"
        val h = m / 60
        if (h < 48) return "${h}h ago"
        return "${h / 24}d ago"
    }
}
