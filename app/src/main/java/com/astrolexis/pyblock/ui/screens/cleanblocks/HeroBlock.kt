package com.astrolexis.pyblock.ui.screens.cleanblocks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.model.CBDetail
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun HeroBlock(hero: CBDetail, flash: Int, onTap: () -> Unit) {
    val cleanPct = run {
        val tot = hero.cleanN + hero.dirtyN
        if (tot > 0) (100.0 * hero.cleanN / tot).roundToInt() else 100
    }
    val rate = if (hero.vsize > 0) max(1, (hero.feesSats.toDouble() / hero.vsize).roundToInt()) else 0

    val inf = rememberInfiniteTransition(label = "hero")
    val pulse by inf.animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse",
    )

    val seal = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(flash) {
        if (flash == 0) return@LaunchedEffect
        seal.snapTo(0.9f)
        launch {
            pop.animateTo(1.05f, tween(180))
            pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
        }
        seal.animateTo(0f, tween(500))
    }

    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .clip(RoundedCornerShape(16.dp))
            .background(Cb.surface)
            .border(1.5.dp, Cb.amber.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .clickableNoRipple(onTap),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.heroblock_next), style = Cb.mono(12f), color = Cb.pillInk,
                    modifier = Modifier
                        .background(Cb.amber, RoundedCornerShape(50))
                        .padding(horizontal = 9.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("#${CbFmt.n(hero.height)}", style = Cb.mono(24f), color = Cb.headWhite, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Canvas(Modifier.size(7.dp)) { drawCircle(Cb.amber.copy(alpha = pulse)) }
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.heroblock_building), style = Cb.mono(12f), color = Cb.amber)
            }
            Spacer(Modifier.height(10.dp))
            TreemapView(hero.txlist ?: emptyList(), stableKey = hero.height, aspect = 16f / 10f, cornerRadius = 12.dp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Stat("${CbFmt.n(hero.txs)}", stringResource(R.string.heroblock_stat_txs), Cb.text)
                Spacer(Modifier.width(14.dp))
                Stat(CbFmt.btc(hero.feesSats), stringResource(R.string.heroblock_stat_btc_fees), Cb.greenHi)
                Spacer(Modifier.width(14.dp))
                Stat("$rate", "sat/vB", Cb.text)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.heroblock_monetary), style = Cb.mono(11f), color = Cb.green)
            }
            Spacer(Modifier.height(8.dp))
            SplitBar(cleanPct)
        }
        Box(Modifier.matchParentSize().background(Color.White.copy(alpha = seal.value * 0.85f)))
    }
}

@Composable
private fun Stat(value: String, key: String, color: Color) {
    Column {
        Text(value, style = Cb.mono(17f), color = color, maxLines = 1)
        Text(key, style = Cb.mono(9f), color = Cb.muted)
    }
}

@Composable
private fun SplitBar(cleanPct: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.Black.copy(alpha = 0.4f)),
    ) {
        val clean = cleanPct.coerceIn(0, 100)
        if (clean > 0) Box(Modifier.weight(clean.toFloat()).fillMaxHeight().background(Cb.green))
        if (clean < 100) Box(Modifier.weight((100 - clean).toFloat()).fillMaxHeight().background(Cb.red))
    }
}
