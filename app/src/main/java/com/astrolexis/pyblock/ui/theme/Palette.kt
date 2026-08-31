package com.astrolexis.pyblock.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** A selectable accent palette. Premium palettes (free = false) unlock with
 *  Whale later; for now the picker/gating is not wired (Phase 3). */
data class PyPalette(val id: String, val name: String, val primary: Color, val free: Boolean)

object PyPalettes {
    val all = listOf(
        // BLAKE2b app default: brand purple (mirrors iOS Blake.pp 0xB96BFF).
        PyPalette("matrix", "Blake",   Color(0xFFB96BFF), true),
        PyPalette("orange", "Bitcoin", Color(0xFFF7931A), false),
        PyPalette("cyber",  "Cyber",   Color(0xFF00E5FF), false),
        PyPalette("gold",   "Gold",    Color(0xFFFFC53D), false),
        PyPalette("vapor",  "Vapor",   Color(0xFFFF40C1), false),
        PyPalette("ice",    "Ice",     Color(0xFF78B4FF), false),
        PyPalette("amber",  "Amber",   Color(0xFFE0B035), false),
    )
    fun byId(id: String): PyPalette = all.firstOrNull { it.id == id } ?: all[0]
}

/** Current accent palette, provided by [PyBlockTheme]. */
val LocalPalette = staticCompositionLocalOf { PyPalettes.byId("matrix") }
