package com.astrolexis.pyblock.ui.screens.game

import androidx.compose.ui.graphics.Color

/** A Node Defender ship skin — a colour palette for the ₿-shield. Orange
 *  is free; the rest unlock via the skins purchase or a Whale subscription. */
data class GameSkin(val id: String, val name: String, val fill: Color, val stroke: Color, val free: Boolean) {
    companion object {
        val all = listOf(
            GameSkin("orange", "Bitcoin", Color(0xFFF7931A), Color(0xFFFFCF8A), true),
            GameSkin("matrix", "Matrix", Color(0xFF00FF41), Color(0xFFA0FFB4), false),
            GameSkin("cyber", "Cyber", Color(0xFF00E5FF), Color(0xFFB4F5FF), false),
            GameSkin("gold", "Gold", Color(0xFFFFC53D), Color(0xFFFFEBAA), false),
            GameSkin("vapor", "Vapor", Color(0xFFFF00AA), Color(0xFFFFAAE1), false),
            GameSkin("ice", "Ice", Color(0xFF78B4FF), Color(0xFFD2E6FF), false),
            GameSkin("blood", "Blood", Color(0xFFFF3C3C), Color(0xFFFFA0A0), false),
        )
        fun byId(id: String): GameSkin = all.firstOrNull { it.id == id } ?: all[0]
    }
}
