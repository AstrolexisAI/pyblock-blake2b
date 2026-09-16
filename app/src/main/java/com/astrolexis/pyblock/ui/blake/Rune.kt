package com.astrolexis.pyblock.ui.blake

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The 24 runes of the Elder Futhark, as strokes.
 *
 * Not text. iOS ships a runic font and most Android phones do not, so a rune sent as a character
 * renders on one platform and as a hollow box on the other. Each rune is a list of straight
 * segments on a 100 × 100 grid, drawn on a Canvas, and the same table lives in the iOS app — so a
 * rune next to a name looks identical everywhere. The brand mark ᛒ is one of them.
 */
enum class Rune(val glyph: String, val segments: List<FloatArray>) {
    FEHU("ᚠ", s(50,10,50,90, 50,30,76,14, 50,50,76,34)),
    URUZ("ᚢ", s(30,10,30,90, 30,10,70,36, 70,36,70,90)),
    THURISAZ("ᚦ", s(40,10,40,90, 40,30,70,50, 70,50,40,70)),
    ANSUZ("ᚨ", s(45,10,45,90, 45,14,76,34, 45,36,76,56)),
    RAIDHO("ᚱ", s(35,10,35,90, 35,10,66,30, 66,30,35,50, 35,50,70,90)),
    KENAZ("ᚲ", s(34,50,70,14, 34,50,70,86)),
    GEBO("ᚷ", s(26,16,74,84, 74,16,26,84)),
    WUNJO("ᚹ", s(35,10,35,90, 35,10,66,30, 66,30,35,50)),
    HAGALAZ("ᚺ", s(30,10,30,90, 70,10,70,90, 30,36,70,64)),
    NAUTHIZ("ᚾ", s(50,10,50,90, 30,40,70,60)),
    ISA("ᛁ", s(50,10,50,90)),
    JERA("ᛃ", s(35,22,58,42, 58,42,35,62, 65,38,42,58, 42,58,65,78)),
    EIHWAZ("ᛇ", s(50,10,50,90, 50,10,34,24, 50,90,66,76)),
    PERTHRO("ᛈ", s(35,10,35,90, 35,10,60,26, 60,26,45,50, 45,50,60,74, 60,74,35,90)),
    ALGIZ("ᛉ", s(50,36,50,90, 50,36,30,12, 50,36,70,12)),
    SOWILO("ᛊ", s(66,10,36,40, 36,40,66,60, 66,60,36,90)),
    TIWAZ("ᛏ", s(50,10,50,90, 50,10,30,28, 50,10,70,28)),
    BERKANAN("ᛒ", s(35,10,35,90, 35,10,60,28, 60,28,35,48, 35,48,60,68, 60,68,35,90)),
    EHWAZ("ᛖ", s(30,10,30,90, 70,10,70,90, 30,10,50,36, 50,36,70,10)),
    MANNAZ("ᛗ", s(30,10,30,90, 70,10,70,90, 30,10,70,50, 70,10,30,50)),
    LAGUZ("ᛚ", s(40,10,40,90, 40,10,66,36)),
    INGWAZ("ᛜ", s(50,26,70,50, 70,50,50,74, 50,74,30,50, 30,50,50,26)),
    DAGAZ("ᛞ", s(26,14,26,86, 74,14,74,86, 26,14,74,86, 74,14,26,86)),
    OTHALA("ᛟ", s(50,12,70,36, 70,36,50,60, 50,60,30,36, 30,36,50,12, 43,52,24,88, 57,52,76,88));

    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        /** Reverse lookup, for a glyph that arrived over the wire. */
        fun fromGlyph(g: String): Rune? = entries.firstOrNull { it.glyph == g }
        /** The six a message can be answered with. Runes, not emoji: the room is ours. */
        val reactions = listOf(TIWAZ, WUNJO, KENAZ, GEBO, FEHU, ALGIZ)
        /** The ink an earned mark is drawn in: the two product runes keep their product's colour
         *  (Dagaz → WAVICLES, Ansuz → DATUM) so the mark says where it was won. */
        fun markInk(r: Rune): androidx.compose.ui.graphics.Color = when (r) { DAGAZ -> Blake.wave; ANSUZ -> Blake.datum; else -> Blake.pp }
    }
}

/** Segments packed four ints at a time. */
private fun s(vararg v: Int): List<FloatArray> =
    v.toList().chunked(4).map { floatArrayOf(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) }

/** How a rune is drawn — what gets sold. ENGRAVED thin, CAST heavy, TEMPERED heavy with a light core. */
enum class RuneForge { ENGRAVED, CAST, TEMPERED }

/** One or more runes drawn as strokes. Several at once make a bindrune. */
@Composable
fun RuneGlyph(runes: List<Rune>, forge: RuneForge = RuneForge.CAST, ink: Color = Blake.pp, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 100f
        val w = (if (forge == RuneForge.ENGRAVED) 5f else if (forge == RuneForge.CAST) 12f else 11f) * k
        val cap = if (forge == RuneForge.ENGRAVED) StrokeCap.Square else StrokeCap.Round
        for (r in runes) for (seg in r.segments)
            drawLine(ink, Offset(seg[0] * k, seg[1] * k), Offset(seg[2] * k, seg[3] * k), strokeWidth = w, cap = cap)
        if (forge == RuneForge.TEMPERED)
            for (r in runes) for (seg in r.segments)
                drawLine(Blake.hero.copy(alpha = 0.55f), Offset(seg[0] * k, seg[1] * k), Offset(seg[2] * k, seg[3] * k), strokeWidth = 3.5f * k, cap = StrokeCap.Round)
    }
}

@Composable
fun RuneGlyph(rune: Rune, forge: RuneForge = RuneForge.CAST, ink: Color = Blake.pp, size: Dp = 16.dp, modifier: Modifier = Modifier) =
    RuneGlyph(listOf(rune), forge, ink, size, modifier)
