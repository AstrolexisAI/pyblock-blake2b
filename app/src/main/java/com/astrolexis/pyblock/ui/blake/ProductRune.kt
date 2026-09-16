package com.astrolexis.pyblock.ui.blake

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The rune of each product, exactly as the website draws them (components/dagaz.php, 2026-09-16):
 *  ᛞ Dagaz = WAVICLES · ᚨ Ansuz = CHIRP (the rune of the signal) · ᚱ Raidho = CAROUSEL (the wheel; LOTTO
 *  was its earlier name) · ᛖ Ehwaz = DATUM, your own gateway (the pair of trust: your node ⇄ the pool) ·
 *  ᛜ Ingwaz = Stratum V2. One place, so the two surfaces never disagree again. */
object ProductRune {
    fun runeFor(product: String): Rune? = when (product.lowercase()) {
        "carousel", "lotto", "lotto_asic", "carousel_prime" -> Rune.RAIDHO
        "chirp", "chirp_prime" -> Rune.ANSUZ
        "wavicles" -> Rune.DAGAZ
        "datum" -> Rune.EHWAZ
        "sv2" -> Rune.INGWAZ
        else -> null
    }
}

/** A product's rune drawn in its colour, or nothing for a product without one. */
@Composable
fun ProductRuneGlyph(product: String, ink: Color = Blake.pp, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    ProductRune.runeFor(product)?.let { RuneGlyph(it, ink = ink, size = size, modifier = modifier) }
}
