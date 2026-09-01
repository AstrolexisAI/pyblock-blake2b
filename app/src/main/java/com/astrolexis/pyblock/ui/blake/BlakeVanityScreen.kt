package com.astrolexis.pyblock.ui.blake

import android.content.Context
import android.hardware.SensorManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.crypto.EntropyPool
import com.astrolexis.pyblock.data.crypto.VanityCrypto
import com.astrolexis.pyblock.data.crypto.VanityGenerator
import com.astrolexis.pyblock.data.crypto.toHex
import com.astrolexis.pyblock.data.blake.BlakeFork
import com.astrolexis.pyblock.data.wallet.VanityWallet
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import java.util.UUID

private enum class VStep { PATTERN, MOTION, TOUCH, GRIND }

/** Vanity address grinder with step-by-step entropy hardening — pattern → SHAKE → DRAW →
 *  grind. Faithful Blake port of iOS VanityView. The gathered pool is mixed on top of the
 *  OS CSPRNG; every candidate is still an independent full-entropy 256-bit key. */
@Composable
fun BlakeVanityScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val entropy = remember { EntropyPool(sm) }
    val gen = remember { VanityGenerator() }

    var step by remember { mutableStateOf(VStep.PATTERN) }
    var pattern by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val valid = pattern.isNotEmpty() && VanityCrypto.isValidPattern(pattern)

    DisposableEffect(Unit) { onDispose { gen.reset(); entropy.stopMotion() } }

    Box(Modifier.fillMaxSize().background(Blake.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("VANITY", style = Blake.mono(20f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f))
                Text("✕", style = Blake.mono(22f), color = Blake.ppDim, modifier = Modifier.clickableNoRipple { gen.stop(); entropy.stopMotion(); onClose() })
            }
            Spacer(Modifier.height(20.dp))

            when (step) {
                VStep.PATTERN -> Column(Modifier.fillMaxWidth().blakeCard()) {
                    stepLabel(1, "PATTERN")
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("1", style = Blake.mono(15f, FontWeight.ExtraBold), color = Blake.faint)
                        Spacer(Modifier.size(6.dp))
                        BasicTextField(value = pattern, onValueChange = { pattern = it }, singleLine = true,
                            textStyle = Blake.mono(15f, FontWeight.ExtraBold).copy(color = Blake.pp), cursorBrush = SolidColor(Blake.pp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, Blake.line, RectangleShape).padding(8.dp),
                            decorationBox = { inner -> if (pattern.isEmpty()) Text("PyB", style = Blake.mono(15f, FontWeight.ExtraBold), color = Blake.faint); inner() })
                    }
                    Spacer(Modifier.height(8.dp))
                    if (pattern.isNotEmpty() && !valid) Text("Only Base58 chars (no 0, O, I, l).", style = Blake.mono(8f), color = Blake.danger)
                    else if (valid) Text("≈ ${estimate(pattern)} to find. Two entropy steps first.", style = Blake.mono(8f), color = Blake.faint)
                    Spacer(Modifier.height(12.dp))
                    vBtn("NEXT — HARDEN ENTROPY", enabled = valid, filled = true) { entropy.feedPattern(pattern); step = VStep.MOTION }
                }

                VStep.MOTION -> Column(Modifier.fillMaxWidth().blakeCard()) {
                    LaunchedEffect(Unit) { entropy.startMotion() }
                    stepLabel(2, "SHAKE THE PHONE")
                    Spacer(Modifier.height(14.dp))
                    Text("📳", style = Blake.mono(36f), color = Blake.pp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text("Give it a real shake — the device's motion feeds fresh entropy into the pool.", style = Blake.mono(9f), color = Blake.faint)
                    Spacer(Modifier.height(10.dp))
                    bar(entropy.motionProgress, entropy.motionReady)
                    Spacer(Modifier.height(6.dp))
                    Text(if (entropy.motionReady) "Enough motion gathered ✓" else "${(entropy.motionProgress * 100).toInt()}%",
                        style = Blake.mono(10f), color = if (entropy.motionReady) Blake.ok else Blake.ppDim)
                    Spacer(Modifier.height(12.dp))
                    vBtn(if (entropy.motionReady) "NEXT — DRAW" else "KEEP SHAKING", enabled = entropy.motionReady, filled = true) { entropy.stopMotion(); step = VStep.TOUCH }
                }

                VStep.TOUCH -> Column(Modifier.fillMaxWidth().blakeCard()) {
                    stepLabel(3, "DRAW A SCRIBBLE")
                    Spacer(Modifier.height(14.dp))
                    Text("Doodle randomly — every point's coordinates + timing harden the key.", style = Blake.mono(9f), color = Blake.faint)
                    Spacer(Modifier.height(10.dp))
                    ScribblePad(Modifier.fillMaxWidth().height(200.dp)) { x, y -> entropy.feedTouch(x, y) }
                    Spacer(Modifier.height(10.dp))
                    bar(entropy.touchProgress, entropy.touchReady)
                    Spacer(Modifier.height(6.dp))
                    Text(if (entropy.touchReady) "Enough scribble gathered ✓" else "${(entropy.touchProgress * 100).toInt()}%",
                        style = Blake.mono(10f), color = if (entropy.touchReady) Blake.ok else Blake.ppDim)
                    Spacer(Modifier.height(12.dp))
                    vBtn(if (entropy.touchReady) "GRIND" else "KEEP DRAWING", enabled = entropy.touchReady, filled = true) {
                        gen.reset(); gen.start(pattern, compressed = true, pool = entropy.pool()); step = VStep.GRIND
                    }
                }

                VStep.GRIND -> Column {
                    Column(Modifier.fillMaxWidth().blakeCard()) {
                        Row(Modifier.fillMaxWidth()) {
                            BlakeStat(gen.attempts.toString(), "attempts", Blake.fg)
                            Spacer(Modifier.weight(1f))
                            BlakeStat("${gen.rate.toInt()}", "keys/sec")
                        }
                        if (gen.running) { Spacer(Modifier.height(8.dp)); Text("⟳ grinding for 1$pattern…", style = Blake.mono(9f), color = Blake.pp) }
                    }
                    val m = gen.match
                    if (m != null) {
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.fillMaxWidth().blakeCard()) {
                            Text("FOUND ✓", style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.ok, letterSpacing = 2.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(m.address, style = Blake.mono(11f), color = Blake.pp)
                            Spacer(Modifier.height(10.dp))
                            if (saved) {
                                Text("Saved to your wallets.", style = Blake.mono(10f), color = Blake.ok)
                                Spacer(Modifier.height(8.dp))
                                vBtn("DONE", enabled = true, filled = true) { onClose() }
                            } else {
                                vBtn("SAVE TO WALLET", enabled = true, filled = true) {
                                    val pub = VanityCrypto.validatedPubkeyHex(m.wif, m.address)
                                    val ok = WalletStore.add(ctx, VanityWallet(UUID.randomUUID().toString(), "1$pattern", m.address,
                                        compressed = true, birthday = BlakeFork.FORK_HEIGHT, pubkeyHex = pub), m.wif)
                                    if (ok) { saved = true; gen.reset(); com.astrolexis.pyblock.ui.Haptics.tap() }
                                    else Toast.makeText(ctx, "Save failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else if (gen.running) {
                        Spacer(Modifier.height(16.dp))
                        vBtn("STOP", enabled = true) { gen.stop(); step = VStep.PATTERN }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun stepLabel(n: Int, title: String) {
    Column {
        Text("STEP $n OF 3", style = Blake.mono(9f), color = Blake.ppDim, letterSpacing = 3.sp)
        Text(title, style = Blake.mono(13f, FontWeight.ExtraBold), color = Blake.hero, letterSpacing = 1.sp)
    }
}

@Composable
private fun bar(p: Float, ready: Boolean) {
    Box(Modifier.fillMaxWidth().height(6.dp).background(Blake.line)) {
        Box(Modifier.fillMaxWidth(p).height(6.dp).background(if (ready) Blake.ok else Blake.pp))
    }
}

@Composable
private fun vBtn(label: String, enabled: Boolean, filled: Boolean = false, onClick: () -> Unit) {
    Text(label, style = Blake.mono(13f, FontWeight.ExtraBold), letterSpacing = 1.sp, textAlign = TextAlign.Center,
        color = if (enabled) (if (filled) Blake.bg else Blake.pp) else Blake.faint,
        modifier = Modifier.fillMaxWidth()
            .then(if (enabled && filled) Modifier.background(Blake.pp) else Modifier.border(1.dp, if (enabled) Blake.pp else Blake.line, RectangleShape))
            .padding(vertical = 12.dp).clickableNoRipple { if (enabled) onClick() })
}

@Composable
private fun ScribblePad(modifier: Modifier, onPoint: (Float, Float) -> Unit) {
    val trail = remember { mutableStateOf(listOf<Offset>()) }
    Box(modifier.background(Blake.pp.copy(alpha = 0.05f)).border(1.dp, Blake.line, RectangleShape)
        .pointerInput(Unit) {
            detectDragGestures { change, _ ->
                onPoint(change.position.x, change.position.y)
                trail.value = (trail.value + change.position).takeLast(80)
            }
        }, contentAlignment = Alignment.Center) {
        if (trail.value.isEmpty()) Text("draw here", style = Blake.mono(11f), color = Blake.ppDim)
        Canvas(Modifier.fillMaxSize()) {
            val pts = trail.value
            for (i in 1 until pts.size) drawLine(Blake.pp.copy(alpha = 0.85f), pts[i - 1], pts[i], strokeWidth = 2f)
        }
    }
}

private fun estimate(pattern: String): String {
    val exp = VanityCrypto.expectedAttempts(pattern.length)
    val secs = exp / 2000
    return when {
        secs < 60 -> "${secs.toInt()}s"
        secs < 3600 -> "${(secs / 60).toInt()}m"
        secs < 86400 -> "%.1fh".format(secs / 3600)
        else -> "%.1f days".format(secs / 86400)
    }
}
