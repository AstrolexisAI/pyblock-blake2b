package com.astrolexis.pyblock.ui.blake

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The BLAKE2b visual identity — ported 1:1 from iOS `Blake` (b.pyblock.xyz blake.css).
 * Minimal, flat, dark, purple, mono. Sober > flashy. Pure-black bg, near-black violet
 * cards with a 1px purple hairline, lavender text, clean monospace (NOT the arcade
 * pixel font). No starfield, no gradients, no heavy shadows.
 */
object Blake {
    val bg = Color(0xFF000000)      // pure black
    val ink = Color(0xFF0B0610)     // card surface (near-black, violet tint)
    val fg = Color(0xFFDCD0EC)      // primary text (light lavender)
    val pp = Color(0xFFB96BFF)      // brand PURPLE (accents, values, links)
    val ppDim = Color(0xFF8F6FD0)   // dim purple (labels, subtitles)
    val faint = Color(0xFF5C4A6A)   // tertiary / hints
    val hero = Color(0xFFEFE6FB)    // hero title (white-lavender)
    val ok = Color(0xFF2FD968)      // live/eligible
    val warn = Color(0xFFE0B035)    // paused/RC/warning
    val danger = Color(0xFFE0556A)  // dropped/error
    val line = Color(0xFFB96BFF).copy(alpha = 0.16f)   // hairline borders
    val line2 = Color(0xFFB96BFF).copy(alpha = 0.08f)  // separators

    // Monospace everywhere; global ~18% readability bump (matches iOS `scale`).
    private const val SCALE = 1.18f
    fun mono(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = (size * SCALE).roundToInt().sp, fontWeight = weight)

    /** BTC from sats, trailing zeros trimmed (1.50000000 → "1.5", 0 → "0"). */
    fun btc(sats: Long): String {
        if (sats == 0L) return "0"
        var s = "%.8f".format(sats / 100_000_000.0)
        while (s.endsWith("0")) s = s.dropLast(1)
        if (s.endsWith(".")) s = s.dropLast(1)
        return s
    }

    const val RUNE = "ᛒ"

    /** Card corner radius — subtly rounded, matching b.pyblock.xyz. */
    val shape = RoundedCornerShape(10.dp)
}

/** Flat card: `ink` surface + 1px hairline border, subtly rounded. No gradients/shadows. */
fun Modifier.blakeCard(padding: Dp = 16.dp): Modifier =
    this.background(Blake.ink, Blake.shape).border(1.dp, Blake.line, Blake.shape).padding(padding)

/** A KPI: big mono number + small UPPERCASE label below (iOS `BlakeStat`). */
@Composable
fun BlakeStat(value: String, label: String, accent: Color = Blake.pp) {
    Column {
        Text(value, style = Blake.mono(24f, FontWeight.ExtraBold), color = accent, maxLines = 1)
        Text(label.uppercase(), style = Blake.mono(9f), color = Blake.ppDim, letterSpacing = 2.sp)
    }
}
