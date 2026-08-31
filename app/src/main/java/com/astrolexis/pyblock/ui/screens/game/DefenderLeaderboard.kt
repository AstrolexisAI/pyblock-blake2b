package com.astrolexis.pyblock.ui.screens.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.model.DefenderScore
import com.astrolexis.pyblock.data.net.DefenderRepo
import com.astrolexis.pyblock.data.store.DefenderStore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.neonText

@Composable
fun DefenderLeaderboard(startDiff: String = "HARD", onClose: () -> Unit) {
    var diff by remember { mutableStateOf(startDiff) }
    var scores by remember { mutableStateOf<List<DefenderScore>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val me = DefenderStore.playerName

    LaunchedEffect(diff) {
        loading = true
        scores = DefenderRepo.board(diff)
        loading = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))) {
        Starfield()
        Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MarqueeTitle(text = stringResource(R.string.leaderboard_title), accent = PyTheme.cyan)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("EASY", "NORMAL", "HARD").forEach { d ->
                    val active = d == diff
                    val c = diffColor(d)
                    Text(d, style = PyType.mono(12f), color = if (active) PyTheme.bg else c, letterSpacing = 1.sp,
                        modifier = Modifier.background(if (active) c else Color.Transparent).border(1.dp, c, RoundedCornerShape(2.dp))
                            .clickableNoRipple { Haptics.select(); diff = d }.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            when {
                loading -> { Spacer(Modifier.height(24.dp)); CircularProgressIndicator(color = PyTheme.primary) }
                scores.isEmpty() -> { Spacer(Modifier.height(24.dp)); Text(stringResource(R.string.leaderboard_empty), style = PyType.mono(12f), color = PyTheme.primaryDim) }
                else -> Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    scores.forEachIndexed { i, s ->
                        val mine = s.name.equals(me, ignoreCase = true) && me.isNotBlank()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .background(if (mine) PyTheme.magenta.copy(alpha = 0.12f) else Color.Transparent)
                                .border(1.dp, if (mine) PyTheme.magenta.copy(alpha = 0.6f) else PyTheme.primary.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text("#${i + 1}", style = PyType.mono(13f), color = rankColor(i), modifier = Modifier.width(44.dp))
                            Text(s.name.ifBlank { stringResource(R.string.leaderboard_anon) }, style = PyType.mono(13f), color = if (mine) PyTheme.magenta else PyTheme.cyan)
                            Spacer(Modifier.weight(1f))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${s.score}", style = PyType.mono(14f).neonText(PyTheme.yellow), color = PyTheme.yellow)
                                Text(stringResource(R.string.leaderboard_wave, s.wave), style = PyType.mono(9f), color = PyTheme.primaryDim)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.leaderboard_back), style = PyType.mono(14f), color = PyTheme.primaryDim, textAlign = TextAlign.Center,
                modifier = Modifier.border(1.dp, PyTheme.primaryDim, RoundedCornerShape(4.dp)).clickableNoRipple { Haptics.tap(); onClose() }.padding(horizontal = 28.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun diffColor(d: String): Color = when (d) {
    "EASY" -> PyTheme.primary; "NORMAL" -> PyTheme.yellow; else -> PyTheme.danger
}

@Composable
private fun rankColor(i: Int): Color = when (i) {
    0 -> PyTheme.yellow; 1 -> PyTheme.cyan; 2 -> Color(0xFFCD7F32); else -> PyTheme.primaryDim
}
