package com.astrolexis.pyblock.ui.screens.chat

import androidx.compose.ui.graphics.Color

/** CHAT FLAIR name-color palette. Keys travel in kind-0 profiles ("color"),
 *  colors render locally — mirrors iOS `ChatFlair.palette`. */
object FlairPalette {
    val entries: List<Pair<String, Color>> = listOf(
        "magenta" to Color(0xFFFF40A6),
        "cyan" to Color(0xFF33E6F2),
        "yellow" to Color(0xFFFFD933),
        "green" to Color(0xFF4DF273),
        "orange" to Color(0xFFFF8C26),
        "red" to Color(0xFFFF4D40),
        "violet" to Color(0xFFB373FF),
        "white" to Color(0xFFF2F2F2),
    )

    fun color(key: String?): Color? = key?.let { k -> entries.firstOrNull { it.first == k }?.second }
}
