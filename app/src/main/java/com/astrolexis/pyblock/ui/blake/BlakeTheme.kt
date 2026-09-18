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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.isSpecified
import androidx.compose.foundation.layout.widthIn
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
    val wave = Color(0xFF35C7E0)    // WAVICLES accent — cyan/water (rune Dagaz)
    val datum = Color(0xFFFF8A3D)   // DATUM · your own gateway — ember orange (rune Ansuz); loud on purpose, like WAVICLES cyan
    val line = Color(0xFFB96BFF).copy(alpha = 0.16f)   // hairline borders
    val line2 = Color(0xFFB96BFF).copy(alpha = 0.08f)  // separators

    // Monospace everywhere; global ~18% readability bump (matches iOS `scale`).
    // Small text (< 12pt: labels, hints, subtitles) gets an extra flat lift so the
    // tiny mono captions stay legible on device — the whole app reads a notch larger.
    private const val SCALE = 1.18f
    /** Width fit, set from the root: the iPhone is ~400pt wide and Android phones ~360dp, so the
     *  same point sizes wrapped and clipped there. Below 400dp the whole type scale shrinks with
     *  the screen (never below 0.85), so the layouts fit as they do on iOS. */
    @Volatile var fit: Float = 1f
    fun mono(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle {
        val eff = if (size < 12f) size + 1.6f else size
        return TextStyle(fontFamily = FontFamily.Monospace, fontSize = (eff * SCALE * fit).roundToInt().sp, fontWeight = weight)
    }

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

/** A KPI: big mono number + small UPPERCASE label below (iOS `BlakeStat`).
 *  `alignEnd` right-aligns the value + label (for the right column of a two-up KPI row,
 *  so the digits line up under the label edge and nothing runs off the card). */
@Composable
fun BlakeStat(value: String, label: String, accent: Color = Blake.pp, alignEnd: Boolean = false) {
    // Two stats share a card row; each keeps to its half of the screen so the labels shrink to
    // fit instead of running into each other (the callers place them with a weighted spacer).
    val half = ((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 72) / 2).dp
    Column(Modifier.widthIn(max = half), horizontalAlignment = if (alignEnd) androidx.compose.ui.Alignment.End else androidx.compose.ui.Alignment.Start) {
        FitText(value, style = Blake.mono(24f, FontWeight.ExtraBold), color = accent, minScale = 0.6f)
        FitText(label.uppercase(), style = Blake.mono(9f).copy(letterSpacing = 2.sp), color = Blake.ppDim, minScale = 0.65f)
    }
}

/** One line that shrinks to fit its width instead of wrapping or clipping — the iOS
 *  `minimumScaleFactor`. Labels, tab names and card titles use it; body text does not. */
@Composable
fun FitText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, minScale: Float = 0.6f,
            textAlign: androidx.compose.ui.text.style.TextAlign? = null) {
    var scale by androidx.compose.runtime.remember(text, style) { androidx.compose.runtime.mutableStateOf(1f) }
    var settled by androidx.compose.runtime.remember(text, style) { androidx.compose.runtime.mutableStateOf(false) }
    val fitted = style.copy(
        fontSize = style.fontSize * scale,
        letterSpacing = if (style.letterSpacing.isSpecified) style.letterSpacing * scale else style.letterSpacing,
    )
    Text(text, modifier = modifier.drawWithContent { if (settled) drawContent() },
        style = fitted, color = color, maxLines = 1, softWrap = false, textAlign = textAlign,
        overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
        onTextLayout = { r ->
            if (r.hasVisualOverflow && scale > minScale) scale = (scale - 0.06f).coerceAtLeast(minScale) else settled = true
        })
}
