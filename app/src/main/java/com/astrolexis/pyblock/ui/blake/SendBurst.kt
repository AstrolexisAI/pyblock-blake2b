package com.astrolexis.pyblock.ui.blake

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Coinbase maturity as a bar, not a sentence: how far a mined coin is through its 100 blocks.
 *  Hairline track, amber fill, only while immature. Mirrors iOS MaturityBar. */
@Composable
fun MaturityBar(u: com.astrolexis.pyblock.data.blake.BlakeApi.Utxo, tip: Int, height: androidx.compose.ui.unit.Dp = 2.dp) {
    if (!u.coinbase || tip <= 0 || com.astrolexis.pyblock.data.blake.BlakeFork.isSpendable(u, tip)) return
    val frac = (com.astrolexis.pyblock.data.blake.BlakeFork.confirmations(u, tip).toFloat() /
        com.astrolexis.pyblock.data.blake.BlakeFork.COINBASE_MATURITY).coerceIn(0f, 1f)
    val shown by androidx.compose.animation.core.animateFloatAsState(frac, androidx.compose.animation.core.tween(600), label = "maturity")
    Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(height).background(Blake.line2)) {
        Box(Modifier.fillMaxWidth(shown).height(height).background(Blake.warn))
    }
}
