package com.astrolexis.pyblock.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.model.ChirpMiner
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import com.astrolexis.pyblock.ui.theme.neonText
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.pow

/** "Don't trust — verify." Educational replay of the CHIRP weighted lottery
 *  (Efraimidis–Spirakis) over the live miner registry with a user-supplied seed
 *  (any block hash). Mirrors iOS VerifyDrawView:
 *    u_i   = U(seed, addr_i) ∈ (0,1)  — first 8 bytes of SHA256(seed‖addr)
 *    key_i = u_i^(1/w_i)
 *    winners = top-N by key. */
@Composable
fun VerifyDrawScreen(miners: List<ChirpMiner>, onBack: () -> Unit) {
    var seed by remember { mutableStateOf("") }
    val draw = remember(seed, miners) { computeDraw(seed.trim(), miners) }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim,
                    modifier = Modifier.clickableNoRipple { Haptics.tap(); onBack() })
                Spacer(Modifier.weight(1f))
                MarqueeTitle(text = stringResource(R.string.verifydraw_title))
                Spacer(Modifier.weight(1f))
                Text("✕", style = PyType.mono(22f), color = Color.Transparent)
            }
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.verifydraw_intro), style = PyType.mono(12f), color = PyTheme.cyan)
            Spacer(Modifier.height(16.dp))

            // Formula card
            Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(12.dp)) {
                Text(stringResource(R.string.verifydraw_formula_u), style = PyType.mono(14f), color = PyTheme.primary)
                Text(stringResource(R.string.verifydraw_formula_key), style = PyType.mono(14f), color = PyTheme.primary)
                Text(stringResource(R.string.verifydraw_formula_winners), style = PyType.mono(14f), color = PyTheme.primary)
            }
            Spacer(Modifier.height(16.dp))

            // Seed input
            Text(stringResource(R.string.verifydraw_seed_label), style = PyType.mono(11f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(10.dp)) {
                if (seed.isEmpty()) Text("000000000000000000...", style = PyType.mono(12f), color = PyTheme.primaryDim)
                BasicTextField(
                    value = seed, onValueChange = { seed = it },
                    textStyle = PyType.mono(12f).copy(color = PyTheme.primary),
                    cursorBrush = SolidColor(PyTheme.cyan), singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))

            if (draw.isNotEmpty()) {
                Text(stringResource(R.string.verifydraw_result_header, draw.size),
                    style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 3.sp)
                Spacer(Modifier.height(8.dp))
                draw.forEachIndexed { i, e ->
                    val top = i == 0
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .background(if (top) PyTheme.yellow.copy(alpha = 0.06f) else PyTheme.bg)
                            .border(1.dp, if (top) PyTheme.yellow.copy(alpha = 0.5f) else PyTheme.primary.copy(alpha = 0.2f), RectangleShape)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("#${i + 1}", style = PyType.mono(13f), color = if (top) PyTheme.yellow else PyTheme.primaryDim,
                            modifier = Modifier.width(36.dp))
                        Text(midTrunc(e.address), style = PyType.mono(11f), color = PyTheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(String.format(Locale.US, "key %.4f", e.key),
                            style = PyType.mono(11f).neonText(if (top) PyTheme.yellow else PyTheme.cyan),
                            color = if (top) PyTheme.yellow else PyTheme.cyan)
                    }
                }
            } else if (seed.isNotBlank()) {
                Text(stringResource(R.string.verifydraw_empty), style = PyType.mono(12f), color = PyTheme.primaryDim)
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.verifydraw_footnote), style = PyType.mono(10f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(16.dp))
        }
    }
}

private data class DrawEntry(val address: String, val weight: Double, val u: Double, val key: Double)

private fun computeDraw(seed: String, miners: List<ChirpMiner>): List<DrawEntry> {
    if (seed.isEmpty()) return emptyList()
    return miners.filter { it.eligible }.map { m ->
        val u = uniform(seed, m.address)
        val key = if (m.weight > 0) u.pow(1.0 / m.weight) else 0.0
        DrawEntry(m.address, m.weight, u, key)
    }.sortedByDescending { it.key }
}

/** Deterministic uniform in (0,1) from the first 8 bytes of SHA256(seed‖address). */
private fun uniform(seed: String, address: String): Double {
    val digest = MessageDigest.getInstance("SHA-256").digest((seed + address).toByteArray(Charsets.UTF_8))
    var v = 0UL
    for (i in 0 until 8) v = (v shl 8) or digest[i].toULong().and(0xFFuL)
    return maxOf(v.toDouble() / ULong.MAX_VALUE.toDouble(), Double.MIN_VALUE)
}

private fun midTrunc(s: String, head: Int = 14, tail: Int = 10): String =
    if (s.length <= head + tail + 1) s else s.take(head) + "…" + s.takeLast(tail)
