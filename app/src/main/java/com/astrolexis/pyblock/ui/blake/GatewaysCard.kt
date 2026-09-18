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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.astrolexis.pyblock.ui.components.clickableNoRipple
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
fun GatewaysCard(rows: List<GatewayRow>, accent: Color = Blake.datum, registered: Int? = null, primeHashrateGhs: Double? = null, startCollapsed: Boolean = false) {
    // Start folded on a product with a dozen gateways: the list made the tab a long scroll before
    // anything else. The header keeps the count and the hashrate, so folded still says what matters.
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(!startCollapsed) }
    Column(Modifier.fillMaxWidth().blakeCard()) {
        Row(Modifier.fillMaxWidth().clickableNoRipple { com.astrolexis.pyblock.ui.Haptics.tap(); expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            RuneGlyph(Rune.EHWAZ, ink = Blake.datum, size = 13.dp); Spacer(Modifier.width(6.dp))
            // The title takes what the count, the registered note and the hashrate leave, and
            // shrinks to fit that; a weighted spacer next to it used to take half the row.
            FitText(stringResource(R.string.blk_gateways_connected), style = Blake.mono(11f, FontWeight.ExtraBold).copy(letterSpacing = 3.sp), color = Blake.ppDim,
                modifier = Modifier.weight(1f), minScale = 0.55f)
            Spacer(Modifier.width(6.dp)); Text("${rows.size}", style = Blake.mono(11f, FontWeight.ExtraBold), color = accent)
            Spacer(Modifier.width(10.dp))
            Text(if (expanded) "▲" else "▼", style = Blake.mono(9f), color = Blake.ppDim)
        }
        // Registered count and the gateways' hashrate on their own line: on a 360dp screen they
        // left the title no room in the header row.
        val meta = buildList {
            registered?.takeIf { it > 0 }?.let { add(stringResource(R.string.blk_registered, it.toString())) }
            primeHashrateGhs?.takeIf { it > 0 }?.let { add(BlakeRentals.th(it / 1000)) }
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth().padding(start = 19.dp)) {
                Text(meta[0], style = Blake.mono(7f), color = Blake.faint, maxLines = 1)
                if (meta.size > 1) { Text("  ·  ", style = Blake.mono(7f), color = Blake.faint); Text(meta[1], style = Blake.mono(8f, FontWeight.ExtraBold), color = Blake.datum, maxLines = 1) }
            }
        }
        if (!expanded) return@Column
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) {
            Text(stringResource(R.string.blk_no_gateways_connected_yet_run_your_own_n), style = Blake.mono(8f), color = Blake.faint)
        } else {
            rows.sortedByDescending { it.accepted ?: 0 }.forEach { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(Modifier.size(5.dp).background(if (g.live) Blake.ok else Blake.faint, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    // A row without the rune keeps its width, so the names line up down the list.
                    if (g.datum) RuneGlyph(Rune.EHWAZ, ink = Blake.datum, size = 12.dp) else Spacer(Modifier.width(12.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.name ?: g.identity?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.blk_unnamed_gateway), style = Blake.mono(10f, FontWeight.ExtraBold), color = if (g.datum) Blake.datum else Blake.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            g.generation?.takeIf { it.isNotEmpty() }?.let { Spacer(Modifier.width(6.dp)); Text(it.uppercase(), style = Blake.mono(6f, FontWeight.ExtraBold), color = Blake.faint, letterSpacing = 1.sp) }
                        }
                        // One line, trimmed at the end — three separate texts wrapped into two
                        // lines on a 360dp screen.
                        val detail = buildList {
                            if (g.name != null && g.identity != null) add(g.identity)
                            g.accepted?.let { add(stringResource(R.string.blk_accepted_2, it.toString())) }
                            g.connectedS?.let { add(stringResource(R.string.blk_up, gwDur(it))) }
                        }.joinToString("  ")
                        Text(detail, style = Blake.mono(7f), color = Blake.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(g.lastShareS?.let { agoText(it) } ?: "—", style = Blake.mono(8f), color = if (g.live) Blake.ok else Blake.faint, maxLines = 1, softWrap = false)
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

/** "12s ago" only while it is seconds; a gateway last seen two days ago used to read "141963s ago". */
@Composable
fun agoText(s: Int): String = when {
    s < 60 -> stringResource(R.string.blk_s_ago, s.toString())
    s < 3600 -> stringResource(R.string.blk_m_ago, (s / 60).toString())
    s < 86400 -> stringResource(R.string.blk_h_ago, (s / 3600).toString())
    else -> stringResource(R.string.blk_d_ago, (s / 86400).toString())
}
