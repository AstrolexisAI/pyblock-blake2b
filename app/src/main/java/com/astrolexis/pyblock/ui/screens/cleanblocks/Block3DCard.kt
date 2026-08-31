package com.astrolexis.pyblock.ui.screens.cleanblocks

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.model.CBBlock
import com.astrolexis.pyblock.ui.components.clickableNoRipple

private const val CARD_W = 158f
private const val CARD_H = 150f
private const val CARD_D = 9f

@Composable
fun Block3DCard(b: CBBlock, onTap: () -> Unit) {
    val clean = b.clean
    val edge = if (clean) Cb.green.copy(alpha = 0.85f) else Cb.red.copy(alpha = 0.7f)
    val poolColor = if (b.isPending) Cb.amber else if (clean) Cb.green else Cb.redHi

    Column(Modifier.width(CARD_W.dp).clickableNoRipple(onTap)) {
        Box(Modifier.width((CARD_W + CARD_D).dp).height((CARD_H + CARD_D).dp)) {
            // Extruded top + side faces
            Canvas(Modifier.matchParentSize()) {
                val w = CARD_W.dp.toPx(); val h = CARD_H.dp.toPx(); val d = CARD_D.dp.toPx()
                val top = Path().apply {
                    moveTo(0f, d); lineTo(w, d); lineTo(w + d, 0f); lineTo(d, 0f); close()
                }
                drawPath(
                    top,
                    Brush.verticalGradient(
                        if (clean) listOf(Cb.green.copy(alpha = 0.44f), Cb.green.copy(alpha = 0.17f))
                        else listOf(Color(0xFFBE4250).copy(alpha = 0.36f), Color(0xFF96323C).copy(alpha = 0.2f)),
                    ),
                )
                val side = Path().apply {
                    moveTo(w, d); lineTo(w + d, 0f); lineTo(w + d, h); lineTo(w, h + d); close()
                }
                drawPath(
                    side,
                    Brush.verticalGradient(
                        if (clean) listOf(Color(0xFF072C1A).copy(alpha = 0.5f), Color(0xFF051E12).copy(alpha = 0.72f))
                        else listOf(Color(0xFF301014).copy(alpha = 0.5f), Color(0xFF1E0A0D).copy(alpha = 0.72f)),
                    ),
                )
            }

            // Front face
            val faceGrad = Brush.verticalGradient(
                if (clean) listOf(Cb.green.copy(alpha = 0.17f), Color(0xFF072C1A).copy(alpha = 0.5f))
                else listOf(Color(0xFF96323C).copy(alpha = 0.2f), Color(0xFF301014).copy(alpha = 0.5f)),
            )
            Box(
                Modifier
                    .width(CARD_W.dp)
                    .height(CARD_H.dp)
                    .offset(y = CARD_D.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(faceGrad)
                    .border(1.5.dp, edge, RoundedCornerShape(8.dp)),
            ) {
                CardContent(b, clean)
            }

            if (b.isPending) {
                Pill(stringResource(R.string.block3d_pill_next), Modifier.align(Alignment.TopStart).offset(x = (-2).dp, y = 0.dp))
            } else if (b.isOurs) {
                Pill("PyBLØCK", Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = 0.dp))
            }
        }

        Row(
            Modifier.width(CARD_W.dp).padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (b.isPending) stringResource(R.string.block3d_pyblock_candidate) else (b.pool ?: "—"),
                style = Cb.mono(15f), color = poolColor, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (b.isPending) stringResource(R.string.block3d_building) else CbFmt.ago(b.time), style = Cb.mono(11f), color = Cb.faint)
        }
    }
}

@Composable
private fun CardContent(b: CBBlock, clean: Boolean) {
    Column(Modifier.padding(start = 13.dp, top = 12.dp, end = 13.dp, bottom = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${CbFmt.n(b.height)}", style = Cb.mono(21f), color = Cb.headWhite, maxLines = 1, modifier = Modifier.weight(1f))
            Text(if (clean) "✓" else "☣", style = Cb.mono(14f), color = if (clean) Cb.green else Cb.red)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(CbFmt.btc(b.feesSats), style = Cb.mono(27f), color = if (clean) Cb.greenHi else Cb.amber, maxLines = 1)
            Text(stringResource(R.string.block3d_btc_fees), style = Cb.mono(9f), color = Cb.muted)
        }
        Text(
            "${b.satPerVb} sat/vB · ${CbFmt.n(b.txs)} tx · ${CbFmt.mb(b.vsize)}",
            style = Cb.mono(10f), color = Cb.muted, modifier = Modifier.padding(top = 3.dp),
        )
        Bar(b.cleanPct, Modifier.padding(vertical = 9.dp))
        Text(
            if (b.isPending) stringResource(R.string.block3d_mempool_not_mined) else "✍ ${b.coinbaseSig?.ifEmpty { "—" } ?: "—"}",
            style = Cb.mono(11f),
            color = if (b.isPending) Cb.amber else if (clean) Color(0xFFBFE8CF) else Color(0xFFF2C4C9),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.32f))
                .border(1.dp, if (b.isPending) Cb.amber.copy(alpha = 0.3f) else Cb.line, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Bar(cleanPct: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.Black.copy(alpha = 0.4f)),
    ) {
        val clean = cleanPct.coerceIn(0, 100)
        if (clean > 0) {
            Box(Modifier.weight(clean.toFloat()).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Cb.greenLo, Cb.green))))
        }
        if (clean < 100) {
            Box(Modifier.weight((100 - clean).toFloat()).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Cb.redLo, Cb.red))))
        }
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = Cb.mono(11f), color = Cb.pillInk,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Cb.amber)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}
