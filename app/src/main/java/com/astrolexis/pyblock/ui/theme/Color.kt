package com.astrolexis.pyblock.ui.theme

import androidx.compose.ui.graphics.Color

// Semantic retro palette (ported from iOS PyTheme). `primary` is themeable
// (see Palette.kt / Theme.kt); the semantic accents below are fixed.
val PyBg = Color(0xFF000000)
val PyCyan = Color(0xFF00FFFF)
val PyYellow = Color(0xFFFFFF00)
val PyMagenta = Color(0xFFFF00FF)
val PyDanger = Color(0xFFFF5555)

// Arcade ("NEON GRID") neutral tokens — machined-metal greys. Accent-derived
// tokens (glow/edge) are computed from the passed accent in ArcadeKit.
val PyPanelEdge = Color(0xFF141414)
val PyLabelGrey = Color(0xFF6E6E6E)
