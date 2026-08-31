package com.astrolexis.pyblock.ui.screens.game

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.model.NodePoint
import com.astrolexis.pyblock.data.net.DefenderRepo
import com.astrolexis.pyblock.data.store.DefenderStore
import com.astrolexis.pyblock.data.store.LivesStore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.RetroTextField
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.screens.game.DefenderSprite.drawSprite
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.neonText
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun NodeDefenderScreen() {
    val game = remember { DefenderGame() }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    var frame by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<GameOverlay>(GameOverlay.None) }

    LaunchedEffect(Unit) {
        runCatching { com.astrolexis.pyblock.data.net.LandRepo.land() }.getOrNull()?.let { game.land = it }
    }
    LaunchedEffect(Unit) {
        runCatching { com.astrolexis.pyblock.data.net.ApiClient.api.nodeMap().points }
            .getOrNull()?.let { game.points = it }
    }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val t = now / 1e9f
                val dt = if (last == 0L) 1f / 60 else min(0.05f, (now - last) / 1e9f)
                last = now
                if (game.w > 0) game.update(t, dt)
                frame++
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { if (!game.seeded) game.seed(it.width.toFloat(), it.height.toFloat(), density) }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            e.changes.firstOrNull()?.let { game.shipX = it.position.x.coerceIn(20f, game.w - 20f) }
                        }
                    }
                },
        ) {
            frame // subscribe to redraw
            game.draw(this, measurer)
        }

        // HUD
        if (game.started && !game.gameOver) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.game_hud_score, game.score), style = PyType.mono(16f), color = PyTheme.yellow)
                    Text(stringResource(R.string.game_hud_wave, game.wave, game.difficulty.label), style = PyType.mono(13f), color = PyTheme.cyan)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("₿".repeat(LivesStore.lives.coerceIn(0, 9)) + if (LivesStore.lives > 9) "+" else "",
                        style = PyType.mono(16f), color = Color(0xFFF7931A))
                    Spacer(Modifier.height(10.dp))
                    BoostButton(game)
                }
            }
        }

        when {
            !game.started -> StartOverlay(game, onShop = { overlay = GameOverlay.Shop }, onBoard = { overlay = GameOverlay.Board })
            game.gameOver -> GameOverOverlay(game, onShop = { overlay = GameOverlay.Shop }, onBoard = { overlay = GameOverlay.Board })
        }

        when (overlay) {
            GameOverlay.Shop -> DefenderShop(onClose = { overlay = GameOverlay.None })
            GameOverlay.Board -> DefenderLeaderboard(startDiff = game.difficulty.label, onClose = { overlay = GameOverlay.None })
            GameOverlay.None -> {}
        }
    }
}

private enum class GameOverlay { None, Shop, Board }

// ---------------- Boost button (ring meter) ----------------

@Composable
private fun BoostButton(game: DefenderGame) {
    val ready = game.boostReady
    val active = game.boosting
    val charge = game.chargeUI
    Box(
        Modifier.size(64.dp).clickableNoRipple { if (ready && !active) { Haptics.powerUp(); game.activateBoost() } },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 5.dp.toPx()
            drawCircle(PyTheme.cyan.copy(alpha = 0.2f), style = Stroke(sw), radius = size.minDimension / 2 - sw)
            if (charge > 0f) {
                drawArc(
                    color = if (active) PyTheme.yellow else PyTheme.cyan,
                    startAngle = -90f, sweepAngle = 360f * charge, useCenter = false,
                    style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    topLeft = Offset(sw, sw), size = Size(size.width - 2 * sw, size.height - 2 * sw),
                )
            }
        }
        Text(if (active) "⚡" else stringResource(R.string.game_boost),
            style = PyType.mono(if (active) 22f else 11f),
            color = if (ready || active) PyTheme.yellow else PyTheme.cyan.copy(alpha = 0.5f))
    }
}

// ---------------- Game logic ----------------

class DefenderGame {
    enum class Difficulty(val label: String, val speed: Float, val spawn: Float, val fire: Float, val score: Int) {
        EASY("EASY", 0.9f, 1.0f, 0.0015f, 1),
        NORMAL("NORMAL", 1.15f, 1.3f, 0.0035f, 2),
        HARD("HARD", 1.5f, 1.7f, 0.0070f, 3),
    }

    class Enemy(var x: Float, var y: Float, val vy: Float, var hp: Int, val boss: Boolean, val swayPh: Float, val swayAmp: Float)
    class Shot(var x: Float, var y: Float)
    class Expl(var x: Float, var y: Float, var t: Float, val boss: Boolean)
    class Star(var x: Float, var y: Float, val v: Float, val s: Float)
    class HarvestPeer(val lon: Double, val lat: Double, val ping: Double, val cc: String, var granted: Boolean = false)

    var difficulty = Difficulty.NORMAL
    var started by mutableStateOf(false)
    var gameOver by mutableStateOf(false)
    var score by mutableIntStateOf(0)
    var wave by mutableIntStateOf(1)

    // Boost — observable surface for the ring button.
    var chargeUI by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set
    var boostReady by mutableStateOf(false)
        private set
    var boosting by mutableStateOf(false)
        private set

    var shipX = 0f
    var w = 0f; var h = 0f
    var u = 3f          // px-per-point scale (iOS draws in points; Android in px)
    var seeded = false
    var frameT = 0f
    var land: List<com.astrolexis.pyblock.data.model.LandRing> = emptyList()
    var points: List<NodePoint> = emptyList()

    private val enemies = ArrayList<Enemy>()
    private val shots = ArrayList<Shot>()
    private val enemyShots = ArrayList<Shot>()
    private val expl = ArrayList<Expl>()
    private val stars = ArrayList<Star>()
    private val harvestPeers = ArrayList<HarvestPeer>()

    private var waveEnemiesLeft = 0
    private var spawnAt = 0f
    private var lastShot = 0f
    private var invulnUntil = 0f
    private var shake = 0f
    private var harvestUntil = 0f
    private var boostCharge = 0f
    private var boostUntil = 0f

    private val globeTopY get() = h - 0.3375f * w
    private val shipY get() = globeTopY - 46f * u

    fun seed(width: Float, height: Float, density: Float) {
        w = width; h = height; shipX = width / 2; u = density
        stars.clear()
        val count = (w * h / 2600f).toInt().coerceIn(40, 260)
        repeat(count) { stars.add(Star(Random.nextFloat() * w, Random.nextFloat() * h, (0.6f + Random.nextFloat() * 2.4f) * u, if (Random.nextFloat() < 0.15f) 2f else 1f)) }
        seeded = true
    }

    fun start(diff: Difficulty) {
        difficulty = diff; score = 0; wave = 1
        boostCharge = 0f; syncChargeUI()
        gameOver = false; started = true
        continueRun()
    }

    /** Continue keeping score + wave + boost charge (you paid for the run). */
    fun continueRun() {
        enemies.clear(); shots.clear(); enemyShots.clear(); expl.clear()
        harvestPeers.clear(); harvestUntil = 0f; boostUntil = 0f; boosting = false
        gameOver = false
        shipX = w / 2; shake = 0f; invulnUntil = 0f
        startWave()
    }

    private fun startWave() {
        waveEnemiesLeft = 5 + wave * 2 + if (difficulty == Difficulty.HARD) 3 else 0
        spawnAt = 0f
    }

    private fun spawn(t: Float) {
        val boss = Random.nextFloat() < min(0.45f, 0.12f + wave * 0.035f)
        enemies.add(Enemy(
            x = 40f * u + Random.nextFloat() * (w - 80f * u), y = -30f * u,
            vy = (0.55f + wave * 0.09f) * (if (boss) 0.8f else 1f) * difficulty.speed * u,
            hp = if (boss) 2 else 1, boss = boss,
            swayPh = Random.nextFloat() * 6.28f, swayAmp = (18f + Random.nextFloat() * 28f) * u))
        waveEnemiesLeft -= 1
        spawnAt = t + max(0.28f, (1.1f - wave * 0.06f) / difficulty.spawn)
    }

    fun activateBoost() {
        if (boostCharge < 1f) return
        boostCharge = 0f
        boostUntil = frameT + 6f
        syncChargeUI()
        boosting = true
    }

    private fun syncChargeUI() {
        val q = (boostCharge * 20).toInt() / 20f
        if (q != chargeUI) chargeUI = q
        if ((boostCharge >= 1f) != boostReady) boostReady = boostCharge >= 1f
    }

    private fun harvestEnergy(ping: Double): Float = min(0.35, 55.0 / max(25.0, ping)).toFloat()

    private fun beginHarvest(t: Float) {
        val withPing = points.filter { it.ping != null }
        if (withPing.isEmpty()) { wave += 1; startWave(); return }
        harvestPeers.clear()
        repeat(4) {
            val p = withPing[Random.nextInt(withPing.size)]
            harvestPeers.add(HarvestPeer(p.geo.lon, p.geo.lat, p.ping ?: 200.0, p.geo.cc ?: "??"))
        }
        harvestUntil = t + 2.4f
    }

    fun update(t: Float, dt: Float) {
        frameT = t
        if (!started || gameOver) return
        val step = dt * 60
        if (shake > 0) shake -= 0.04f * step
        for (s in stars) { s.y += s.v * step; if (s.y > h) { s.y = 0f; s.x = Random.nextFloat() * w } }

        // Wave flow: spawn → clear → harvest → next.
        val harvesting = t < harvestUntil
        if (harvesting) {
            val progress = 1f - max(0f, (harvestUntil - t) / 2.4f)
            if (progress > 0.85f) for (hp in harvestPeers) if (!hp.granted) {
                hp.granted = true
                boostCharge = min(1f, boostCharge + harvestEnergy(hp.ping))
                syncChargeUI()
            }
        } else if (waveEnemiesLeft > 0 && t > spawnAt) {
            spawn(t)
        } else if (waveEnemiesLeft == 0 && enemies.isEmpty() && harvestPeers.isEmpty()) {
            beginHarvest(t)
        } else if (waveEnemiesLeft == 0 && enemies.isEmpty() && harvestPeers.isNotEmpty()) {
            harvestPeers.clear(); wave += 1; startWave()
        }

        // Auto-fire — boost = triple spread.
        val boostingNow = t < boostUntil
        if (boosting != boostingNow) boosting = boostingNow
        val cadence = if (boostingNow) 0.13f else 0.24f
        if (t - lastShot > cadence) {
            if (boostingNow) {
                shots.add(Shot(shipX - 12 * u, shipY - 16 * u)); shots.add(Shot(shipX, shipY - 22 * u)); shots.add(Shot(shipX + 12 * u, shipY - 16 * u))
            } else shots.add(Shot(shipX, shipY - 20 * u))
            lastShot = t
        }

        // Enemies descend + sway.
        var reached = 0
        val it = enemies.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.y += e.vy * step
            e.x = (e.x + sin(t * 1.8f + e.swayPh) * 0.014f * e.swayAmp * step).coerceIn(16f * u, w - 16f * u)
            if (e.y >= globeTopY - 6f * u) { expl.add(Expl(e.x, e.y, 0f, e.boss)); reached++; it.remove() }
        }
        if (reached > 0) {
            repeat(reached) { LivesStore.consumeLife() }
            shake = 1f
            if (LivesStore.lives <= 0) gameOver = true
        }

        // Player shots + collisions.
        val sIt = shots.iterator()
        while (sIt.hasNext()) {
            val shot = sIt.next()
            shot.y -= 9f * u * step
            var consumed = false
            for (i in enemies.indices) {
                val e = enemies[i]
                val hitW = (if (e.boss) 19f else 16f) * u
                if (abs(e.x - shot.x) < hitW && abs(e.y - shot.y) < 15f * u) {
                    e.hp -= 1; consumed = true
                    if (e.hp <= 0) {
                        score += (if (e.boss) 300 else 100) * difficulty.score
                        expl.add(Expl(e.x, e.y, 0f, e.boss)); enemies.removeAt(i)
                    }
                    break
                }
            }
            if (consumed || shot.y < -12f) sIt.remove()
        }

        // Enemy fire.
        val fireChance = difficulty.fire * (1 + wave * 0.12f)
        for (e in enemies) if (e.y > 20f && Random.nextFloat() < fireChance * step) enemyShots.add(Shot(e.x, e.y + 14f * u))
        val invulnerable = t < invulnUntil
        val eIt = enemyShots.iterator()
        while (eIt.hasNext()) {
            val shot = eIt.next()
            shot.y += 5.2f * u * step
            if (!invulnerable && abs(shot.x - shipX) < 17f * u && abs(shot.y - shipY) < 17f * u) {
                LivesStore.consumeLife(); shake = 1f; invulnUntil = t + 1.5f
                expl.add(Expl(shipX, shipY, 0f, false))
                if (LivesStore.lives <= 0) gameOver = true
                eIt.remove()
            } else if (shot.y > h + 12f) eIt.remove()
        }

        // Explosions age.
        val xIt = expl.iterator()
        while (xIt.hasNext()) { val e = xIt.next(); e.t += 0.06f * step; if (e.t >= 1f) xIt.remove() }
    }

    // --- projection shared by land / harvest / twinkle ---
    private class Proj(val cx: Float, val cy: Float, val R: Float, t: Float) {
        private val camYaw = t * 0.06
        private val cP = cos(-0.35); private val sP = sin(-0.35)
        private val deg = Math.PI / 180.0
        fun project(lon: Double, lat: Double): DoubleArray {
            val la = lat * deg; val lo = lon * deg - camYaw
            val cla = cos(la)
            val x = cla * sin(lo); val y = sin(la); val z = cla * cos(lo)
            return doubleArrayOf(cx + R * x, cy - R * (y * cP - z * sP), y * sP + z * cP)
        }
    }

    fun draw(scope: DrawScope, measurer: TextMeasurer) = with(scope) {
        if (shake > 0) {
            val dx = (Random.nextFloat() * 12 - 6) * shake
            val dy = (Random.nextFloat() * 12 - 6) * shake
            translate(dx, dy) { drawScene(this, measurer) }
        } else drawScene(this, measurer)
    }

    private fun drawScene(scope: DrawScope, measurer: TextMeasurer) = with(scope) {
        // stars
        for (s in stars) {
            val c = if (s.s == 2f) Color(0xFFE0F0FF) else Color(0xFF96C8B4)
            drawRect(c.copy(alpha = if (s.s == 2f) 0.95f else 0.7f), Offset(s.x, s.y), Size(s.s * u, (s.s + 1) * u))
        }
        // globe
        val cx = w / 2; val R = w * 0.75f; val cy = h + R * 0.55f
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFF0D160F), Color(0xFF030504)), center = Offset(cx, cy - R * 0.6f), radius = R * 1.4f),
            radius = R, center = Offset(cx, cy))
        drawCircle(Color(0xFF00FF41).copy(alpha = 0.5f), radius = R, center = Offset(cx, cy), style = Stroke(1.5f))
        val proj = Proj(cx, cy, R, frameT)
        if (land.isNotEmpty()) {
            val lp = Path()
            for (ring in land) {
                val n = ring.lon.size
                val st = max(1, n / 60)
                var prev: DoubleArray? = null
                var i = 0
                while (i < n) {
                    val cur = proj.project(ring.lon[i].toDouble(), ring.lat[i].toDouble())
                    if (cur[2] > 0 && prev != null && prev[2] > 0) { lp.moveTo(prev[0].toFloat(), prev[1].toFloat()); lp.lineTo(cur[0].toFloat(), cur[1].toFloat()) }
                    prev = cur; i += st
                }
            }
            drawPath(lp, Color(0xFF00FF41).copy(alpha = 0.45f), style = Stroke(0.8f))
        }
        // twinkling peers on the arc
        if (points.isNotEmpty()) {
            var i = 0
            while (i < points.size) {
                val p = points[i]
                val pr = proj.project(p.geo.lon, p.geo.lat)
                if (pr[2] > 0) {
                    val col = if (p.node == "home") Color(0xFFE6BD4A) else Color(0xFF2FD968)
                    drawCircle(col.copy(alpha = 0.7f), radius = 1.5f * u, center = Offset(pr[0].toFloat(), pr[1].toFloat()))
                }
                i += 40
            }
        }

        if (!started || gameOver) return@with

        val t = frameT
        val harvesting = t < harvestUntil
        if (harvesting) drawHarvest(this, measurer, proj, t)

        // enemy bolts
        for (s in enemyShots) drawRect(Color(0xFFFF6B6B), Offset(s.x - 1.5f * u, s.y - 7f * u), Size(3f * u, 9f * u))
        // player shots
        for (s in shots) drawRect(Color(0xFFFFFF00), Offset(s.x - 1.2f * u, s.y - 9f * u), Size(2.4f * u, 11f * u))
        // enemies (bank sprites)
        for (e in enemies) {
            val base = if (e.boss) Color(0xFFFF5CC8) else Color(0xFFFF6B6B)
            val hi = if (e.boss) Color.White else Color(0xFFFFE0E0)
            drawSprite(DefenderSprite.BANK, e.x, e.y, (if (e.boss) 3.8f else 3.2f) * u, base, hi)
        }
        // explosions
        for (e in expl) {
            val rr = e.t * 26f * u
            val col = (if (e.boss) Color(0xFFFF5CC8) else Color(0xFFFF6B6B)).copy(alpha = 1 - e.t)
            val p = Path()
            for (a in 0 until 8) {
                val an = a / 8f * 6.2832f
                p.moveTo(e.x + cos(an) * rr * 0.4f, e.y + sin(an) * rr * 0.4f)
                p.lineTo(e.x + cos(an) * rr, e.y + sin(an) * rr)
            }
            drawPath(p, col, style = Stroke(2f))
        }
        // ship (blinks while invulnerable)
        val invuln = t < invulnUntil
        if (!invuln || (t * 8).toInt() % 2 == 0) drawShip(this, measurer)
    }

    private fun drawHarvest(scope: DrawScope, measurer: TextMeasurer, proj: Proj, t: Float) = with(scope) {
        val progress = 1f - max(0f, (harvestUntil - t) / 2.4f)
        val cx = w / 2; val R = w * 0.75f; val cy = h + R * 0.55f
        for (i in harvestPeers.indices) {
            val hp = harvestPeers[i]
            val pr = proj.project(hp.lon, hp.lat)
            val nodeX = if (pr[2] > 0) pr[0].toFloat() else cx
            val nodeY = if (pr[2] > 0) pr[1].toFloat() else (cy - R).toFloat()
            val bx = nodeX + (shipX - nodeX) * progress
            val by = nodeY + (shipY - nodeY) * progress
            drawLine(
                brush = Brush.linearGradient(listOf(Color(0xFF00FF41).copy(alpha = 0.05f), Color(0xFF00FF41).copy(alpha = 0.8f)), start = Offset(nodeX, nodeY), end = Offset(bx, by)),
                start = Offset(nodeX, nodeY), end = Offset(bx, by), strokeWidth = 2f * u)
            drawCircle(Color(0xFF78FFAA), radius = 2.5f * u, center = Offset(bx, by))
            val ringR = (4f + 5f * abs(sin(t * 4 + i))) * u
            drawCircle(Color(0xFF00FF41).copy(alpha = 0.7f), radius = ringR, center = Offset(nodeX, nodeY), style = Stroke(1.5f))
            val lbl = measurer.measure("${hp.ping.toInt()}ms · ${hp.cc}", canvasStyle(11f, PyTheme.cyan))
            drawText(lbl, topLeft = Offset(nodeX - lbl.size.width / 2, nodeY - 26f * u))
        }
        val banner = measurer.measure("◈ HARVESTING NODE DATA", canvasStyle(13f, Color(0xFF7DFFB0)))
        drawText(banner, topLeft = Offset(cx - banner.size.width / 2, shipY - 78f * u))
    }

    private fun drawShip(scope: DrawScope, measurer: TextMeasurer) = with(scope) {
        val skin = LivesStore.selectedSkin
        val s = 16f * u
        val pts = listOf(0f to -s * 1.02f, s * 0.92f to -s * 0.45f, s * 0.92f to s * 0.45f, 0f to s * 1.12f, -s * 0.92f to s * 0.45f, -s * 0.92f to -s * 0.45f)
        val hex = Path()
        pts.forEachIndexed { i, p -> val x = p.first + shipX; val y = p.second + shipY; if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y) }
        hex.close()
        drawPath(hex, skin.fill.copy(alpha = 0.35f))
        drawPath(hex, skin.fill)
        drawPath(hex, skin.stroke, style = Stroke(2f * u))
        val tl = measurer.measure("₿", canvasStyle(22f, Color.White, bold = true))
        drawText(tl, topLeft = Offset(shipX - tl.size.width / 2, shipY - tl.size.height / 2))
    }
}

private fun canvasStyle(sizeSp: Float, color: Color, bold: Boolean = false) =
    TextStyle(color = color, fontSize = sizeSp.sp, fontFamily = FontFamily.Monospace, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

// ---------------- Overlays ----------------

@Composable
private fun StartOverlay(game: DefenderGame, onShop: () -> Unit, onBoard: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MarqueeTitle(text = stringResource(R.string.game_title), accent = PyTheme.primary)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.game_intro),
            style = PyType.mono(13f), color = PyTheme.cyan, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.game_lives), style = PyType.mono(12f), color = PyTheme.primaryDim)
            Text("₿".repeat(min(9, max(0, LivesStore.lives))), style = PyType.mono(16f), color = Color(0xFFF7931A))
            if (LivesStore.lives == 0) Text(stringResource(R.string.game_lives_refill_dash), style = PyType.mono(11f), color = PyTheme.danger)
        }
        Spacer(Modifier.height(18.dp))
        DefenderGame.Difficulty.entries.forEach { d ->
            val c = diffColor(d)
            val enabled = LivesStore.lives > 0
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.width(250.dp).padding(vertical = 5.dp)
                    .border(2.dp, if (enabled) c else c.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                    .clickableNoRipple { if (enabled) { Haptics.thock(); game.start(d) } }.padding(horizontal = 18.dp, vertical = 11.dp),
            ) {
                Text(stringResource(R.string.game_difficulty_row, d.label), style = PyType.mono(17f), color = if (enabled) c else c.copy(alpha = 0.35f), letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.game_score_multiplier, d.score), style = PyType.mono(12f), color = (if (enabled) c else c.copy(alpha = 0.35f)).copy(alpha = 0.7f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(R.string.game_shop), style = PyType.mono(13f), color = PyTheme.yellow, letterSpacing = 1.sp, modifier = Modifier.clickableNoRipple { Haptics.tap(); onShop() })
            Text(stringResource(R.string.game_leaderboard), style = PyType.mono(13f), color = PyTheme.cyan, letterSpacing = 1.sp, modifier = Modifier.clickableNoRipple { Haptics.tap(); onBoard() })
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.game_hint), style = PyType.mono(10f), color = PyTheme.primaryDim)
    }
}

@Composable
private fun GameOverOverlay(game: DefenderGame, onShop: () -> Unit, onBoard: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(DefenderStore.playerName) }
    var rank by remember { mutableStateOf<Int?>(null) }
    var submitted by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.game_over_title), style = PyType.mono(20f), color = PyTheme.danger, letterSpacing = 2.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.game_hud_score, game.score), style = PyType.mono(32f).neonText(PyTheme.yellow), color = PyTheme.yellow)
        val wavesSurvived = game.wave - 1
        val waveWord = if (wavesSurvived == 1) stringResource(R.string.game_wave_singular) else stringResource(R.string.game_wave_plural)
        Text(stringResource(R.string.game_over_survived, wavesSurvived, waveWord), style = PyType.mono(13f), color = PyTheme.cyan)
        Spacer(Modifier.height(16.dp))

        // Score submit
        if (submitted) {
            rank?.let { Text(stringResource(R.string.game_global_rank, it, game.difficulty.label), style = PyType.mono(14f), color = PyTheme.magenta, letterSpacing = 1.sp) }
        } else {
            Box(Modifier.width(180.dp)) { RetroTextField(value = name, onValueChange = { name = it }, placeholder = stringResource(R.string.game_handle_placeholder)) }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.game_submit_score), style = PyType.mono(13f), color = PyTheme.yellow, textAlign = TextAlign.Center,
                modifier = Modifier.border(1.dp, PyTheme.yellow, RoundedCornerShape(4.dp)).clickableNoRipple {
                    val n = name.trim(); if (n.isNotEmpty()) {
                        Haptics.thock()
                        submitted = true
                        scope.launch { rank = DefenderRepo.submit(n, game.score, game.wave, game.difficulty.label) }
                    }
                }.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Spacer(Modifier.height(16.dp))

        if (LivesStore.lives > 0) {
            OverlayButton(stringResource(R.string.game_continue, LivesStore.lives), PyTheme.primary) { Haptics.thock(); game.continueRun() }
        } else {
            Text(stringResource(R.string.game_out_of_lives), style = PyType.mono(14f), color = PyTheme.danger, letterSpacing = 3.sp)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.game_lives_refill), style = PyType.mono(11f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(8.dp))
            OverlayButton(stringResource(R.string.game_get_lives), PyTheme.yellow) { Haptics.coin(); onShop() }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Text(stringResource(R.string.game_change_difficulty), style = PyType.mono(13f), color = PyTheme.cyan, modifier = Modifier.clickableNoRipple { Haptics.tap(); game.started = false })
            Text(stringResource(R.string.game_leaderboard_lower), style = PyType.mono(13f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { Haptics.tap(); onBoard() })
        }
    }
}

@Composable
private fun OverlayButton(text: String, color: Color, onClick: () -> Unit) {
    Text(text, style = PyType.mono(16f), color = color, textAlign = TextAlign.Center, letterSpacing = 1.sp,
        modifier = Modifier.width(250.dp).border(2.dp, color, RoundedCornerShape(4.dp)).clickableNoRipple(onClick).padding(vertical = 12.dp))
}

@Composable
private fun diffColor(d: DefenderGame.Difficulty): Color = when (d) {
    DefenderGame.Difficulty.EASY -> PyTheme.primary
    DefenderGame.Difficulty.NORMAL -> PyTheme.yellow
    DefenderGame.Difficulty.HARD -> PyTheme.danger
}
