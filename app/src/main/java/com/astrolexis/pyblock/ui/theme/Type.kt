package com.astrolexis.pyblock.ui.theme

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R

/** VT323 pixel font + the pyMono scale (1.4×, matching iOS `pyScale`).
 *  `includeFontPadding = false` + centered line-height trim make VT323 sit tight
 *  to its box the way SwiftUI `Text` does, so baselines/vertical-centering match iOS. */
object PyType {
    val family = FontFamily(Font(R.font.vt323_regular))
    const val scale = 1.4f

    fun mono(size: Float): TextStyle =
        TextStyle(
            fontFamily = family,
            fontSize = (size * scale).sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
        )

    val header: TextStyle = mono(24f)
    val title: TextStyle = mono(64f)
}
