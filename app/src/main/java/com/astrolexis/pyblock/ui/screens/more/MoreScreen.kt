package com.astrolexis.pyblock.ui.screens.more

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import kotlinx.coroutines.launch

private data class MoreItem(val label: String, val icon: ImageVector, val route: String)

private val ITEMS = listOf(
    MoreItem("Template", Icons.Filled.GridView, "template"),
    MoreItem("Clean Blocks", Icons.Filled.ViewInAr, "cleanblocks"),
    MoreItem("Bitcoin Academy", Icons.Filled.School, "academy"),
    MoreItem("Workers", Icons.Filled.Search, "workers"),
    MoreItem("Address", Icons.Filled.AccountCircle, "address"),
    MoreItem("Miner Setup", Icons.Filled.Build, "setup"),
    MoreItem("Node Map", Icons.Filled.Public, "nodemap"),
    MoreItem("Settings", Icons.Filled.Settings, "settings"),
)

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MarqueeTitle(text = stringResource(R.string.more_title))
            Spacer(Modifier.height(16.dp))
            ITEMS.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .moduleFrame(PyTheme.primary)
                        .clickableNoRipple { Haptics.tap(); onNavigate(item.route) }
                        .padding(14.dp),
                ) {
                    Icon(item.icon, contentDescription = item.label, tint = PyTheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(item.label, style = PyType.mono(14f), color = PyTheme.primary, maxLines = 1)
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(28.dp))
            BlockEgg()
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** Easter egg — tap the voxel block to reveal the last block on the timechain
 *  as giant pixel digits (ports the iOS MoreView egg). */
@Composable
private fun BlockEgg() {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val primary = PyTheme.primary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().clickableNoRipple {
            Haptics.select()
            if (revealed) { revealed = false; return@clickableNoRipple }
            loading = true; failed = false
            scope.launch {
                // template_data.php height = NEXT block being built; -1 = last mined.
                val agg = runCatching { ApiClient.api.templateData() }.getOrNull()
                loading = false
                if (agg != null && agg.height > 0) { height = agg.height - 1; revealed = true }
                else { failed = true; revealed = true }
            }
        },
    ) {
        when {
            revealed && failed -> {
                Text(stringResource(R.string.more_connection_failed), style = PyType.mono(12f), color = PyTheme.danger, letterSpacing = 3.sp)
                Text(stringResource(R.string.more_connection_failed_detail), style = PyType.mono(10f), color = PyTheme.primaryDim)
            }
            revealed -> {
                Text(stringResource(R.string.more_last_block), style = PyType.mono(10f), color = PyTheme.cyan, letterSpacing = 3.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                PixelBlockDigits(height, primary)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.more_tap_to_hide), style = PyType.mono(9f), color = PyTheme.primaryDim)
            }
            else -> {
                VoxelBlock(primary, dim = loading)
                if (loading) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.more_hashing), style = PyType.mono(10f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
                }
            }
        }
    }
}

/** Isometric voxel block — three shaded green faces + glow. */
@Composable
private fun VoxelBlock(primary: Color, dim: Boolean) {
    val pulse by rememberInfiniteTransition(label = "egg").animateFloat(
        initialValue = 0.7f, targetValue = if (dim) 0.4f else 0.7f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    Canvas(Modifier.size(78.dp)) {
        val s = size.minDimension
        val cx = size.width / 2; val cy = size.height / 2
        val w = s * 0.42f; val half = s * 0.5f; val lift = s * 0.22f
        val t = Offset(cx, cy - half)
        val tr = Offset(cx + w, cy - half + lift)
        val br = Offset(cx + w, cy + half - lift)
        val b = Offset(cx, cy + half)
        val bl = Offset(cx - w, cy + half - lift)
        val tl = Offset(cx - w, cy - half + lift)
        val c = Offset(cx, cy - half + 2 * lift)
        fun face(pts: List<Offset>): Path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        val a = if (dim) 0.5f else 1f
        // right (darkest)
        drawPath(face(listOf(c, tr, br, b)), primary.copy(alpha = 0.45f * a))
        drawPath(face(listOf(c, tr, br, b)), Color.White.copy(alpha = 0.25f), style = Stroke(1.5f))
        // left (medium)
        drawPath(face(listOf(tl, c, b, bl)), primary.copy(alpha = 0.7f * a))
        drawPath(face(listOf(tl, c, b, bl)), Color.White.copy(alpha = 0.3f), style = Stroke(1.5f))
        // top (brightest)
        drawPath(face(listOf(t, tr, c, tl)), primary.copy(alpha = pulse * a + (1 - a)))
        drawPath(face(listOf(t, tr, c, tl)), Color.White.copy(alpha = 0.5f), style = Stroke(1.5f))
    }
}

/** Big retro digits with a white step-shadow (VT323 / Tetris feel). */
@Composable
private fun PixelBlockDigits(value: Int, primary: Color) {
    val s = value.toString()
    Box(contentAlignment = Alignment.Center) {
        Text(s, fontSize = 52.sp, color = Color.White.copy(alpha = 0.95f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 4.sp,
            modifier = Modifier.offset(3.dp, 3.dp))
        Text(s, fontSize = 52.sp, color = primary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
    }
}
