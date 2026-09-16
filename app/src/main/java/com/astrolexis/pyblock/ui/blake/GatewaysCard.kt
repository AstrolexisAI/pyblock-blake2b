package com.astrolexis.pyblock.ui.blake

import com.astrolexis.pyblock.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.blake.BlakeApi
import com.astrolexis.pyblock.data.blake.BlakeRentals

/** One connected gateway, whatever product it serves. [datum] = the finder's own node builds the
 *  block (Ehwaz); the house gateway is the other path. */
data class GatewayRow(val id: String, val name: String?, val identity: String?, val generation: String?,
                      val accepted: Int?, val connectedS: Int?, val lastShareS: Int?, val datum: Boolean) {
    val live get() = (lastShareS ?: Int.MAX_VALUE) <= 600
    companion object {
        fun of(r: BlakeApi.PrimeRunner) = GatewayRow((r.gateway ?: "") + (r.identity ?: ""), r.name?.takeIf { it.isNotEmpty() }, r.identity, r.generation, r.accepted, r.connectedS, r.lastShareS, true)
        fun of(c: BlakeApi.WClient) = GatewayRow((c.gateway ?: "") + (c.identity ?: ""), c.name?.takeIf { it.isNotEmpty() }, c.identity,
            c.generation ?: c.userAgent?.substringBefore('/'), c.accepted, c.connectedS, c.lastShareS, c.onDatum)
    }
}

/** The gateways connected to a product right now — names when their runners gave one, the software
 *  generation, shares accepted, uptime, last share. Same card on CAROUSEL, CHIRP and WAVICLES. */
@Composable
fun GatewaysCard(rows: List<GatewayRow>, accent: Color = Blake.datum, registered: Int? = null, primeHashrateGhs: Double? = null) {
    Column(Modifier.fillMaxWidth().blakeCard()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RuneGlyph(Rune.EHWAZ, ink = Blake.datum, size = 13.dp); Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.blk_gateways_connected), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
            Spacer(Modifier.width(6.dp)); Text("${rows.size}", style = Blake.mono(11f, FontWeight.ExtraBold), color = accent)
            Spacer(Modifier.weight(1f))
            registered?.takeIf { it > 0 }?.let { Text(stringResource(R.string.blk_registered, it.toString()), style = Blake.mono(7f), color = Blake.faint); Spacer(Modifier.width(6.dp)) }
            primeHashrateGhs?.takeIf { it > 0 }?.let { Text(BlakeRentals.th(it / 1000), style = Blake.mono(8f, FontWeight.ExtraBold), color = Blake.datum) }
        }
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) {
            Text(stringResource(R.string.blk_no_gateways_connected_yet_run_your_own_n), style = Blake.mono(8f), color = Blake.faint)
        } else {
            rows.sortedByDescending { it.accepted ?: 0 }.forEach { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(Modifier.size(5.dp).background(if (g.live) Blake.ok else Blake.faint, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    if (g.datum) { RuneGlyph(Rune.EHWAZ, ink = Blake.datum, size = 12.dp); Spacer(Modifier.width(6.dp)) }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.name ?: g.identity?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.blk_unnamed_gateway), style = Blake.mono(10f, FontWeight.ExtraBold), color = if (g.datum) Blake.datum else Blake.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            g.generation?.takeIf { it.isNotEmpty() }?.let { Spacer(Modifier.width(6.dp)); Text(it.uppercase(), style = Blake.mono(6f, FontWeight.ExtraBold), color = Blake.faint, letterSpacing = 1.sp) }
                        }
                        Row {
                            if (g.name != null && g.identity != null) { Text(g.identity, style = Blake.mono(7f), color = Blake.faint); Spacer(Modifier.width(6.dp)) }
                            g.accepted?.let { Text(stringResource(R.string.blk_accepted_2, it.toString()), style = Blake.mono(7f), color = Blake.faint); Spacer(Modifier.width(6.dp)) }
                            g.connectedS?.let { Text(stringResource(R.string.blk_up, gwDur(it)), style = Blake.mono(7f), color = Blake.faint) }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(g.lastShareS?.let { stringResource(R.string.blk_s_ago, it.toString()) } ?: "—", style = Blake.mono(8f), color = if (g.live) Blake.ok else Blake.faint)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.blk_its_own_node_builds_the_block_the_other_), style = Blake.mono(7f), color = Blake.faint)
        }
    }
}

/** A found block, with Ehwaz when the finder's own gateway built it. */
@Composable
fun FoundBlockRow(height: Int, who: String, reward: String, whenText: String, datum: Boolean, gatewayName: String?) {
    Row(Modifier.fillMaxWidth().hairline().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("#$height", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.pp); Spacer(Modifier.width(8.dp))
        if (datum) { RuneGlyph(Rune.EHWAZ, ink = Blake.datum, size = 11.dp); Spacer(Modifier.width(6.dp)) }
        Column(Modifier.weight(1f)) {
            Text(who, style = Blake.mono(8f), color = if (datum) Blake.datum else Blake.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            gatewayName?.takeIf { it.isNotEmpty() }?.let { Text(it, style = Blake.mono(7f), color = Blake.faint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(6.dp))
        Text(reward, style = Blake.mono(10f), color = Blake.fg); Spacer(Modifier.width(6.dp))
        Text(whenText, style = Blake.mono(7f), color = Blake.faint)
    }
}

internal fun gwDur(s: Int): String = when { s < 3600 -> "${maxOf(1, s / 60)}m"; s < 86400 -> "${s / 3600}h"; else -> "${s / 86400}d ${(s % 86400) / 3600}h" }
