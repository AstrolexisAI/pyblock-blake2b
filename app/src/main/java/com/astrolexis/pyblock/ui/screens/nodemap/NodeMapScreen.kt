package com.astrolexis.pyblock.ui.screens.nodemap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.astrolexis.pyblock.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.astrolexis.pyblock.data.model.LandRing
import com.astrolexis.pyblock.data.model.NodeMapData
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val GREEN = Color(0xFF00FF41)
private val LAND_STROKE = GREEN.copy(alpha = 0.30f)
private val GRAT = GREEN.copy(alpha = 0.06f)
private val LIMB = GREEN.copy(alpha = 0.25f)
private val HOME_GOLD = Color(0xFFE6BD4A)
private val SPHERE0 = Color(0xFF0D1610)
private val SPHERE1 = Color(0xFF070D09)

@Composable
fun NodeMapScreen(onLaunchGame: () -> Unit = {}) {
    val vm: NodeMapViewModel = viewModel()
    val state by vm.state.collectAsState()

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MarqueeTitle(text = stringResource(R.string.nodemap_title))
            Spacer(Modifier.weight(1f))
            state.map?.let {
                Text(stringResource(R.string.nodemap_peers_count, it.points.size), style = PyType.mono(12f), color = PyTheme.cyan)
            }
        }
        Text(stringResource(R.string.nodemap_drag_hint), style = PyType.mono(10f), color = PyTheme.primaryDim)
        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when {
                state.loading -> Text(stringResource(R.string.nodemap_loading), style = PyType.mono(12f), color = PyTheme.primaryDim)
                else -> Globe(state.map, state.land, Modifier.fillMaxSize())
            }
            // Hidden arcade entry — Node Defender (matches iOS ⛶ corner button).
            Text(
                "⛶", style = PyType.mono(20f), color = PyTheme.primary,
                modifier = Modifier.align(Alignment.TopEnd)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickableNoRipple { Haptics.powerUp(); onLaunchGame() }
                    .padding(8.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            LegendDot(HOME_GOLD, stringResource(R.string.nodemap_legend_home))
            LegendDot(GREEN, stringResource(R.string.nodemap_legend_peers))
        }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Spacer(Modifier.size(6.dp))
        Text(label, style = PyType.mono(11f), color = PyTheme.cyan.copy(alpha = 0.8f))
    }
}

@Composable
private fun Globe(map: NodeMapData?, land: List<LandRing>, modifier: Modifier) {
    var dragYaw by remember { mutableDoubleStateOf(0.0) }
    var dragPitch by remember { mutableDoubleStateOf(0.0) }
    var frameNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { withFrameNanos { frameNs = it } } }

    val tSec = frameNs / 1_000_000_000.0
    val camYaw = dragYaw + tSec * 0.066
    val camPitch = dragPitch.coerceIn(-1.35, 1.35)

    Canvas(
        modifier.pointerInput(Unit) {
            detectDragGestures { change, drag ->
                dragYaw -= drag.x * 0.008
                dragPitch = (dragPitch + drag.y * 0.008).coerceIn(-1.35, 1.35)
                change.consume()
            }
        },
    ) {
        drawGlobe(map, land, camYaw, camPitch)
    }
}

private fun DrawScope.drawGlobe(map: NodeMapData?, land: List<LandRing>, camYaw: Double, camPitch: Double) {
    val w = size.width
    val h = size.height
    val cx = w / 2.0
    val cy = h / 2.0
    val r = min(w * 0.5, h * 0.52) * 0.94
    val deg = PI / 180.0
    val cP = cos(camPitch)
    val sP = sin(camPitch)

    // project → [x, y, z]; z>0 is front-facing
    fun proj(lon: Double, lat: Double): DoubleArray {
        val la = lat * deg
        val lo = lon * deg - camYaw
        val cla = cos(la)
        val x = cla * sin(lo)
        val y = sin(la)
        val z = cla * cos(lo)
        return doubleArrayOf(cx + r * x, cy - r * (y * cP - z * sP), y * sP + z * cP)
    }

    // 1. Sphere
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(SPHERE0, SPHERE1),
            center = Offset((cx - r * 0.35).toFloat(), (cy - r * 0.4).toFloat()),
            radius = (r * 1.6).toFloat(),
        ),
        radius = r.toFloat(),
        center = Offset(cx.toFloat(), cy.toFloat()),
    )

    // 2. Graticule (batched)
    val grat = Path()
    var lat = -60.0
    while (lat <= 60.0) {
        var started = false
        var lon = -180.0
        while (lon <= 180.0) {
            val p = proj(lon, lat)
            if (p[2] > 0) {
                if (started) grat.lineTo(p[0].toFloat(), p[1].toFloat())
                else { grat.moveTo(p[0].toFloat(), p[1].toFloat()); started = true }
            } else started = false
            lon += 6.0
        }
        lat += 30.0
    }
    var lon = -180.0
    while (lon < 180.0) {
        var started = false
        var la = -90.0
        while (la <= 90.0) {
            val p = proj(lon, la)
            if (p[2] > 0) {
                if (started) grat.lineTo(p[0].toFloat(), p[1].toFloat())
                else { grat.moveTo(p[0].toFloat(), p[1].toFloat()); started = true }
            } else started = false
            la += 6.0
        }
        lon += 30.0
    }
    drawPath(grat, color = GRAT, style = Stroke(width = 1f))

    // 3. Land coastlines (batched, decimated)
    val landPath = Path()
    for (ring in land) {
        val n = ring.lon.size
        val st = maxOf(1, n / 60)
        var started = false
        var i = 0
        while (i < n) {
            val p = proj(ring.lon[i].toDouble(), ring.lat[i].toDouble())
            if (p[2] > 0) {
                if (started) landPath.lineTo(p[0].toFloat(), p[1].toFloat())
                else { landPath.moveTo(p[0].toFloat(), p[1].toFloat()); started = true }
            } else started = false
            i += st
        }
    }
    drawPath(landPath, color = LAND_STROKE, style = Stroke(width = 1.2f))

    // 4. Limb
    drawCircle(color = LIMB, radius = r.toFloat(), center = Offset(cx.toFloat(), cy.toFloat()), style = Stroke(width = 1.5f))

    // 5. Peer points
    if (map == null) return
    val pts = map.points
    val step = if (pts.size > 2500) 2 else 1
    var idx = 0
    while (idx < pts.size) {
        val pt = pts[idx]
        val p = proj(pt.geo.lon, pt.geo.lat)
        if (p[2] > 0) {
            val fade = min(1.0, p[2] * 1.4 + 0.15).toFloat()
            if (pt.node == "home") {
                drawCircle(HOME_GOLD.copy(alpha = fade), radius = 2.4f * density, center = Offset(p[0].toFloat(), p[1].toFloat()))
            } else {
                drawCircle(GREEN.copy(alpha = 0.24f * fade), radius = 1.6f * density, center = Offset(p[0].toFloat(), p[1].toFloat()))
            }
        }
        idx += step
    }
}
