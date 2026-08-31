package com.astrolexis.pyblock.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/** Retro theme accessor mirroring iOS `PyTheme`. `primary`/`primarySoft`/
 *  `primaryDim` read the active palette from [LocalPalette]. */
object PyTheme {
    val bg = PyBg
    val cyan = PyCyan
    val yellow = PyYellow
    val magenta = PyMagenta
    val danger = PyDanger

    val primary: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.primary
    val primarySoft: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.primary.copy(alpha = 0.06f)
    val primaryDim: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.primary.copy(alpha = 0.55f)
}

@Composable
fun PyBlockTheme(paletteId: String = "matrix", content: @Composable () -> Unit) {
    val palette = PyPalettes.byId(paletteId)
    val colors = darkColorScheme(
        primary = palette.primary,
        background = PyBg,
        surface = PyBg,
        onPrimary = PyBg,
        onBackground = palette.primary,
        onSurface = palette.primary,
    )
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
