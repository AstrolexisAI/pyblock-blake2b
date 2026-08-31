package com.astrolexis.pyblock.ui.screens.cleanblocks

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.moduleFrame
import com.astrolexis.pyblock.ui.theme.neonText
import com.astrolexis.pyblock.data.model.CBBreakdown
import com.astrolexis.pyblock.data.model.CBDetail
import com.astrolexis.pyblock.data.model.CBStats
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun BlockDetailSheet(d: CBDetail) {
    val tot = d.cleanN + d.dirtyN
    val totVb = d.cleanVb + d.dirtyVb
    val dN = if (tot > 0) (100.0 * d.dirtyN / tot).roundToInt() else 0
    val dB = if (totVb > 0) (100.0 * d.dirtyVb / totVb).roundToInt() else 0
    val mult = run {
        val m = if (d.cleanVb > 0) d.cleanFeesSats.toDouble() / d.cleanVb else 0.0
        val p = if (d.dirtyVb > 0) d.dirtyFeesSats.toDouble() / d.dirtyVb else 0.0
        if (p > 0) m / p else 0.0
    }
    val rate = if (totVb > 0) max(1, (d.feesSats.toDouble() / totVb).roundToInt()) else 0

    Column(
        Modifier
            .fillMaxWidth()
            .background(Cb.surface)
            .padding(16.dp),
    ) {
        MarqueeTitle(
            text = if (d.isPending) stringResource(R.string.cbdetail_next_block, CbFmt.n(d.height))
            else if (d.isOurs) stringResource(R.string.cbdetail_block_ours, CbFmt.n(d.height))
            else stringResource(R.string.cbdetail_block, CbFmt.n(d.height)),
            accent = Cb.accent,
        )
        Spacer(Modifier.height(8.dp))
        // subtitle
        val lead = if (d.isPending) stringResource(R.string.cbdetail_lead_mempool)
        else stringResource(R.string.cbdetail_lead_pool, d.pool ?: "—")
        Text(
            stringResource(
                R.string.cbdetail_subtitle,
                lead, CbFmt.n(d.txs), CbFmt.btc(d.feesSats), rate,
            ) + (if (d.isPending) "" else " · ${CbFmt.ago(d.time ?: 0)}"),
            style = Cb.mono(12f), color = if (d.isPending) Cb.amber else Cb.muted,
        )
        Spacer(Modifier.height(12.dp))

        // split bar
        Row(
            Modifier.fillMaxWidth().height(15.dp).clip(RoundedCornerShape(8.dp)),
        ) {
            val cleanW = (100 - dN).coerceIn(0, 100)
            if (cleanW > 0) Box(Modifier.weight(cleanW.toFloat()).fillMaxHeight().background(Cb.green))
            if (cleanW < 100) Box(Modifier.weight((100 - cleanW).toFloat()).fillMaxHeight().background(Cb.red))
        }
        Spacer(Modifier.height(12.dp))

        // counts
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(R.string.cbdetail_clean_count, CbFmt.n(d.cleanN)), style = Cb.mono(13f), color = Cb.green)
            Text(stringResource(R.string.cbdetail_parasite_count, CbFmt.n(d.dirtyN), dN), style = Cb.mono(13f), color = Cb.red)
        }
        Spacer(Modifier.height(10.dp))

        // breakdown
        val b = d.breakdown ?: CBBreakdown()
        Text(
            stringResource(
                R.string.cbdetail_breakdown,
                CbFmt.n(b.inscriptions), CbFmt.n(b.runes),
                CbFmt.n(b.opreturn), CbFmt.n(b.baremultisig), dB,
            ),
            style = Cb.mono(11f), color = Cb.faint,
        )
        Spacer(Modifier.height(10.dp))

        // fees
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Cb.faint)) { append(stringResource(R.string.cbdetail_fees_label)) }
                withStyle(SpanStyle(color = Cb.green)) { append(stringResource(R.string.cbdetail_fees_monetary, CbFmt.btc(d.cleanFeesSats))) }
                withStyle(SpanStyle(color = Cb.faint)) { append(" · ") }
                withStyle(SpanStyle(color = Cb.red)) { append(stringResource(R.string.cbdetail_fees_parasite, CbFmt.btc(d.dirtyFeesSats))) }
                if (mult > 0) withStyle(SpanStyle(color = Cb.faint)) {
                    append(stringResource(R.string.cbdetail_fees_mult, String.format(Locale.US, "%.1f", mult)))
                }
            },
            style = Cb.mono(11f),
        )
        Spacer(Modifier.height(12.dp))

        // coinbase
        if (!d.isPending && !d.coinbaseHex.isNullOrEmpty()) {
            Coinbase(d.coinbaseHex!!)
            Spacer(Modifier.height(12.dp))
        }

        // treemap
        val list = d.txlist
        if (!list.isNullOrEmpty()) {
            TreemapView(list, stableKey = null, aspect = 16f / 9f, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendSquare(Cb.green, stringResource(R.string.cbdetail_legend_clean))
                LegendSquare(Cb.red, stringResource(R.string.cbdetail_legend_parasite))
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.cbdetail_treemap_hint), style = Cb.mono(10f), color = Cb.faint)
            }
        } else {
            Text(stringResource(R.string.cbdetail_loading_txs), style = Cb.mono(11f), color = Cb.faint,
                modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp))
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Coinbase(hex: String) {
    val ascii = asciiFromHex(hex)
    Column(
        Modifier
            .fillMaxWidth()
            .moduleFrame(Cb.green)
            .padding(11.dp),
    ) {
        Text(stringResource(R.string.cbdetail_coinbase_signature), style = Cb.mono(11f), color = Cb.green)
        Spacer(Modifier.height(6.dp))
        Text(ascii.ifEmpty { stringResource(R.string.cbdetail_empty) }, style = Cb.mono(12f), color = Color(0xFFCFE8D9))
    }
}

@Composable
private fun LegendSquare(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, style = Cb.mono(10f), color = Cb.muted)
    }
}

private fun asciiFromHex(hex: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i + 1 < hex.length) {
        val byte = hex.substring(i, i + 2).toIntOrNull(16)
        if (byte != null) sb.append(if (byte in 0x20..0x7e) byte.toChar() else '·')
        i += 2
    }
    return sb.toString()
}

// ---- 24h rollup panel ----

@Composable
fun Panel24h(s: CBStats) {
    val spamMb = s.parasiteVb / 1e6
    val spamPct = if (s.totalVb > 0) (100.0 * s.parasiteVb / s.totalVb).roundToInt() else 0
    val cleanVb = s.totalVb - s.parasiteVb
    val monRate = if (cleanVb > 0) s.cleanFees.toDouble() / cleanVb else 0.0
    val parRate = if (s.parasiteVb > 0) s.parasiteFees.toDouble() / s.parasiteVb else 0.0
    val mult = if (parRate > 0) monRate / parRate else 0.0
    val totFees = s.cleanFees + s.parasiteFees
    val feeParPct = if (totFees > 0) (100.0 * s.parasiteFees / totFees).roundToInt() else 0
    val parPct = if ((s.cleanTx + s.parasiteTx) > 0) (100.0 * s.parasiteTx / (s.cleanTx + s.parasiteTx)).roundToInt() else 0

    Column(
        Modifier.fillMaxWidth().background(Cb.surface).padding(16.dp),
    ) {
        MarqueeTitle(text = stringResource(R.string.cbdetail_panel_header, CbFmt.n(s.blocks)), accent = Cb.green)
        Spacer(Modifier.height(12.dp))
        HeroTile(
            "🛡", CbFmt.n(s.parasiteTx), Cb.redHi,
            stringResource(R.string.cbdetail_hero_parasite_small, String.format(Locale.US, "%.1f", spamMb)), Cb.red.copy(alpha = 0.45f),
            stringResource(R.string.cbdetail_hero_parasite_msg, String.format(Locale.US, "%.1f", spamMb)),
        )
        Spacer(Modifier.height(10.dp))
        HeroTile(
            "💰", String.format(Locale.US, "%.2f", monRate), Cb.green,
            stringResource(R.string.cbdetail_hero_monetary_small), Cb.amber.copy(alpha = 0.45f),
            stringResource(R.string.cbdetail_hero_monetary_msg, String.format(Locale.US, "%.1f", mult), spamPct, feeParPct),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Tile("${CbFmt.n(s.cleanBlocks)}/${CbFmt.n(s.blocks)}", stringResource(R.string.cbdetail_tile_clean_blocks), Cb.green, stringResource(R.string.cbdetail_tile_clean_blocks_sub))
            Tile(CbFmt.n(s.parasiteBlocks), stringResource(R.string.cbdetail_tile_parasite_laden), Cb.red, stringResource(R.string.cbdetail_tile_blocks))
            Tile(String.format(Locale.US, "%.3f BTC", s.cleanFees / 1e8), stringResource(R.string.cbdetail_tile_monetary_fees), Cb.green, stringResource(R.string.cbdetail_tile_pct, 100 - feeParPct))
            Tile(String.format(Locale.US, "%.3f BTC", s.parasiteFees / 1e8), stringResource(R.string.cbdetail_tile_parasite_fees), Cb.red, stringResource(R.string.cbdetail_tile_pct, feeParPct))
            Tile(CbFmt.n(s.cleanTx), stringResource(R.string.cbdetail_tile_monetary_txs), Cb.green, null)
            Tile(CbFmt.n(s.parasiteTx), stringResource(R.string.cbdetail_tile_parasite_txs), Cb.red, stringResource(R.string.cbdetail_tile_pct_of_tx, parPct))
            Tile(String.format(Locale.US, "%.1f MB", spamMb), stringResource(R.string.cbdetail_tile_spam_blockspace), Cb.red, stringResource(R.string.cbdetail_tile_pct_of_block, spamPct))
            Tile(CbFmt.n(s.runes), stringResource(R.string.cbdetail_tile_runes), Cb.red, null)
            Tile(CbFmt.n(s.insc), stringResource(R.string.cbdetail_tile_inscriptions), Cb.red, null)
            Tile(CbFmt.n(s.opret), "OP_RETURN", Cb.amber, stringResource(R.string.cbdetail_tile_multisig_sub, CbFmt.n(s.bare)))
            if (s.oursBlocks > 0) Tile(CbFmt.n(s.oursBlocks), "PyBLØCK ⛏", Cb.green, stringResource(R.string.cbdetail_tile_clean))
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun HeroTile(icon: String, big: String, bigColor: Color, small: String, accent: Color, msg: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .moduleFrame(accent)
            .padding(15.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(icon, style = Cb.mono(30f))
        Column(Modifier.width(120.dp)) {
            Text(big, style = Cb.mono(32f).neonText(bigColor), color = bigColor, maxLines = 1)
            Text(small, style = Cb.mono(10f), color = Cb.muted)
        }
        Text(msg, style = Cb.mono(11f), color = Cb.text)
    }
}

@Composable
private fun Tile(v: String, k: String, c: Color, sub: String?) {
    Column(
        Modifier
            .width(120.dp)
            .moduleFrame(c)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(v, style = Cb.mono(19f), color = c, maxLines = 1)
        Text(k, style = Cb.mono(10f), color = Cb.muted)
        if (sub != null) Text(sub, style = Cb.mono(9f), color = Cb.faint)
    }
}
