package com.astrolexis.pyblock.ui.screens.vanity

import android.app.Activity
import android.content.Context
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.crypto.EntropyPool
import com.astrolexis.pyblock.data.crypto.VanityCrypto
import com.astrolexis.pyblock.data.crypto.VanityGenerator
import com.astrolexis.pyblock.data.wallet.VanityWallet
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import java.security.SecureRandom

private enum class Phase { AIRGAP, ENTROPY_INTRO, INPUT, ENTROPY_MOTION, ENTROPY_TOUCH, GENERATING, RESULT, DESTROYED, BREACHED }

/** Scan floor for freshly-created wallets (offline, so the tip is unknown). */
private const val WALLET_BIRTHDAY_FLOOR = 955000

/** Offline vanity-address generator + hardened, educational entropy wizard
 *  (parity with iOS). Keys are full-CSPRNG draws mixed with user entropy
 *  (motion + touch + jitter). No seeds. Shown once, then wiped. */
@Composable
fun VanityAddressScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val gen = remember { VanityGenerator() }
    val entropy = remember { EntropyPool(ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager) }
    val online = rememberOnline()
    var phase by remember { mutableStateOf(Phase.AIRGAP) }
    var cryptoOk by remember { mutableStateOf(true) }
    var pattern by remember { mutableStateOf("") }
    var compressed by remember { mutableStateOf(false) }   // default "5" (uncompressed)

    LaunchedEffect(Unit) { cryptoOk = runCatching { VanityCrypto.selfTest() }.getOrDefault(false) }
    // Block screenshots/recording while a plaintext key can be on screen. Use the
    // shared ref-counted flag so overlapping secure surfaces (e.g. navigating from
    // the wallet key screen) can't clear it out from under this one.
    com.astrolexis.pyblock.ui.components.SecureFlag()
    DisposableEffect(Unit) {
        onDispose { gen.reset(); entropy.stopMotion() }
    }
    LaunchedEffect(gen.match) { if (gen.match != null) phase = Phase.RESULT }
    // Continuous air-gap: the offline gate at the start isn't enough — if the
    // network returns during the ceremony, fail-closed (abort grind, wipe the
    // in-progress key + gathered entropy). RESULT keeps the found key but warns.
    LaunchedEffect(online.value, phase) {
        val sensitive = phase == Phase.ENTROPY_INTRO || phase == Phase.INPUT ||
            phase == Phase.ENTROPY_MOTION || phase == Phase.ENTROPY_TOUCH || phase == Phase.GENERATING
        if (online.value && sensitive) {
            gen.reset(); entropy.stopMotion(); entropy.reset(); pattern = ""; phase = Phase.BREACHED
        }
    }

    Box(Modifier.fillMaxSize()) {
        Starfield()
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim,
                    modifier = Modifier.clickableNoRipple { Haptics.tap(); gen.reset(); onClose() }.padding(10.dp))
                Spacer(Modifier.weight(1f))
                MarqueeTitle(text = stringResource(R.string.vanity_title))
                Spacer(Modifier.weight(1f))
                Text(if (online.value) stringResource(R.string.vanity_status_online) else stringResource(R.string.vanity_status_offline), style = PyType.mono(11f),
                    color = if (online.value) PyTheme.danger else PyTheme.primary, modifier = Modifier.padding(end = 12.dp))
            }
            if (!cryptoOk) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.vanity_crypto_selftest_failed), style = PyType.mono(14f),
                        color = PyTheme.danger, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
                }
            } else when (phase) {
                Phase.AIRGAP -> AirgapStep(online.value) { phase = Phase.ENTROPY_INTRO }
                Phase.ENTROPY_INTRO -> EntropyIntroStep { phase = Phase.INPUT }
                Phase.INPUT -> InputStep(pattern, compressed,
                    onPattern = { pattern = it; entropy.feedPattern(it) },
                    onFormat = { compressed = it },
                    onNext = { phase = Phase.ENTROPY_MOTION })
                Phase.ENTROPY_MOTION -> MotionStep(entropy) { phase = Phase.ENTROPY_TOUCH }
                Phase.ENTROPY_TOUCH -> TouchStep(entropy) {
                    phase = Phase.GENERATING; gen.start(pattern, compressed, entropy.pool())
                }
                Phase.GENERATING -> GeneratingStep(gen) { gen.stop(); phase = Phase.INPUT }
                Phase.RESULT -> ResultStep(gen, online.value,
                    onSave = {
                        val m = gen.match
                        if (m != null) WalletStore.add(ctx,
                            VanityWallet(java.util.UUID.randomUUID().toString(), "", m.address, compressed, WALLET_BIRTHDAY_FLOOR),
                            m.wif)
                        else false
                    },
                    onWipe = { gen.reset(); phase = Phase.DESTROYED })
                Phase.DESTROYED -> DestroyedStep { onClose() }
                Phase.BREACHED -> BreachedStep(online.value) { phase = Phase.AIRGAP }
            }
        }
    }
}

// ---- 1 · Air-gap ----

@Composable
private fun AirgapStep(online: Boolean, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🛡", style = PyType.mono(50f))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.vanity_airgap_title), style = PyType.header, color = PyTheme.primary, letterSpacing = 2.sp)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.vanity_airgap_body),
            style = PyType.mono(14f), color = PyTheme.cyan, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.border(1.dp, PyTheme.yellow.copy(alpha = 0.4f), RectangleShape).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.vanity_airgap_swipe1), style = PyType.mono(13f), color = PyTheme.yellow)
            Text(stringResource(R.string.vanity_airgap_swipe2), style = PyType.mono(13f), color = PyTheme.yellow)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.border(1.dp, (if (online) PyTheme.danger else PyTheme.primary).copy(alpha = 0.6f), RectangleShape).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Box(Modifier.width(10.dp).height(10.dp).background(if (online) PyTheme.danger else PyTheme.primary))
            Spacer(Modifier.width(8.dp))
            Text(if (online) stringResource(R.string.vanity_still_online) else stringResource(R.string.vanity_offline_safe), style = PyType.mono(12f), color = if (online) PyTheme.danger else PyTheme.primary)
        }
        Spacer(Modifier.height(24.dp))
        Text(if (online) stringResource(R.string.vanity_waiting_offline) else stringResource(R.string.vanity_continue), style = PyType.mono(17f), letterSpacing = 2.sp, textAlign = TextAlign.Center,
            color = if (online) PyTheme.primaryDim else PyTheme.bg,
            modifier = Modifier.fillMaxWidth()
                .background(if (online) Color.Transparent else PyTheme.primary)
                .border(2.dp, if (online) PyTheme.primaryDim else PyTheme.primary, RectangleShape)
                .clickableNoRipple { if (!online) { Haptics.thock(); onContinue() } }.padding(vertical = 14.dp))
    }
}

// ---- 2 · Entropy intro (educational) ----

@Composable
private fun EntropyIntroStep(onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🎲", style = PyType.mono(50f))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.vanity_entropy_title), style = PyType.header, color = PyTheme.primary, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.vanity_entropy_body1),
            style = PyType.mono(14f), color = PyTheme.cyan, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.vanity_entropy_body2),
            style = PyType.mono(13f), color = PyTheme.primaryDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(14.dp)) {
            Text(stringResource(R.string.vanity_entropy_steps_header), style = PyType.mono(12f), color = PyTheme.yellow)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.vanity_entropy_step1), style = PyType.mono(13f), color = PyTheme.primary)
            Text(stringResource(R.string.vanity_entropy_step2), style = PyType.mono(13f), color = PyTheme.primary)
            Text(stringResource(R.string.vanity_entropy_step3), style = PyType.mono(13f), color = PyTheme.primary)
        }
        Spacer(Modifier.height(20.dp))
        WideButton(stringResource(R.string.vanity_start), PyTheme.primary, onContinue)
    }
}

// ---- 3 · Pattern + key format ----

@Composable
private fun InputStep(pattern: String, compressed: Boolean, onPattern: (String) -> Unit, onFormat: (Boolean) -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.vanity_input_title), style = PyType.mono(15f), color = PyTheme.cyan, letterSpacing = 2.sp)
        Spacer(Modifier.height(10.dp))
        Row {
            Text("1", style = PyType.mono(24f), color = PyTheme.primaryDim)
            Text(pattern, style = PyType.mono(24f), color = PyTheme.yellow)
            Text(if (pattern.isEmpty()) stringResource(R.string.vanity_input_placeholder) else "…", style = PyType.mono(24f), color = PyTheme.primaryDim.copy(alpha = 0.5f))
        }
        Spacer(Modifier.height(6.dp))
        DifficultyBadge(pattern.length)
        Spacer(Modifier.height(10.dp))
        FormatToggle(compressed, onFormat)
        Spacer(Modifier.height(10.dp))
        QwertyKeyboard(
            onKey = { if (pattern.length < 6) onPattern(pattern + it) },
            onDelete = { if (pattern.isNotEmpty()) onPattern(pattern.dropLast(1)) })
        Spacer(Modifier.weight(1f))
        val ready = pattern.isNotEmpty() && VanityCrypto.isValidPattern(pattern)
        Text(stringResource(R.string.vanity_next_add_entropy), style = PyType.mono(17f), letterSpacing = 2.sp, textAlign = TextAlign.Center,
            color = if (ready) PyTheme.bg else PyTheme.primaryDim,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                .background(if (ready) PyTheme.magenta else Color.Transparent)
                .border(2.dp, if (ready) PyTheme.magenta else PyTheme.primaryDim, RectangleShape)
                .clickableNoRipple { if (ready) { Haptics.thock(); onNext() } }.padding(vertical = 12.dp))
    }
}

@Composable
private fun FormatToggle(compressed: Boolean, onFormat: (Boolean) -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormatChip(stringResource(R.string.vanity_format_uncompressed), !compressed) { onFormat(false) }
        FormatChip(stringResource(R.string.vanity_format_compressed), compressed) { onFormat(true) }
    }
}

@Composable
private fun FormatChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, style = PyType.mono(12f), color = if (selected) PyTheme.bg else PyTheme.primaryDim, textAlign = TextAlign.Center,
        modifier = Modifier.background(if (selected) PyTheme.primary else Color.Transparent)
            .border(1.dp, if (selected) PyTheme.primary else PyTheme.primaryDim, RectangleShape)
            .clickableNoRipple { Haptics.select(); onClick() }.padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun DifficultyBadge(n: Int) {
    val (label, color) = when (n) {
        0 -> stringResource(R.string.vanity_diff_none) to PyTheme.primaryDim
        1, 2 -> stringResource(R.string.vanity_diff_instant) to PyTheme.primary
        3 -> stringResource(R.string.vanity_diff_seconds) to PyTheme.primary
        4 -> stringResource(R.string.vanity_diff_minutes) to PyTheme.yellow
        5 -> stringResource(R.string.vanity_diff_hours) to PyTheme.yellow
        else -> stringResource(R.string.vanity_diff_days) to PyTheme.danger
    }
    Text(label, style = PyType.mono(12f), color = color, textAlign = TextAlign.Center)
}

// ---- QWERTY keyboard (invalid Base58 keys disabled) ----

@Composable
private fun QwertyKeyboard(onKey: (Char) -> Unit, onDelete: () -> Unit) {
    var shift by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 8.dp)) {
        KeyRow("1234567890", false, onKey)
        KeyRow("qwertyuiop", shift, onKey)
        KeyRow("asdfghjkl", shift, onKey)
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SpecialCap("⇧", PyTheme.yellow, Modifier.weight(1.4f)) { shift = !shift }
            for (c in "zxcvbnm") KeyCap(if (shift) c.uppercaseChar() else c, Modifier.weight(1f), onKey)
            SpecialCap("⌫", PyTheme.danger, Modifier.weight(1.4f), onDelete)
        }
    }
}

@Composable
private fun KeyRow(chars: String, shift: Boolean, onKey: (Char) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (c in chars) KeyCap(if (shift) c.uppercaseChar() else c, Modifier.weight(1f), onKey)
    }
}

@Composable
private fun KeyCap(c: Char, modifier: Modifier, onKey: (Char) -> Unit) {
    val valid = VanityCrypto.isValidPattern(c.toString())
    Text(c.toString(), style = PyType.mono(18f), textAlign = TextAlign.Center,
        color = if (valid) PyTheme.primary else PyTheme.primaryDim.copy(alpha = 0.3f),
        modifier = modifier.height(38.dp)
            .border(1.dp, (if (valid) PyTheme.primary else PyTheme.primaryDim).copy(alpha = 0.3f), RectangleShape)
            .then(if (valid) Modifier.clickableNoRipple { onKey(c) } else Modifier)
            .padding(top = 7.dp))
}

@Composable
private fun SpecialCap(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Text(label, style = PyType.mono(16f), color = color, textAlign = TextAlign.Center,
        modifier = modifier.height(38.dp).border(1.dp, color.copy(alpha = 0.5f), RectangleShape)
            .clickableNoRipple(onClick).padding(top = 8.dp))
}

// ---- 4 · Motion entropy ----

@Composable
private fun MotionStep(entropy: EntropyPool, onNext: () -> Unit) {
    DisposableEffect(Unit) { entropy.startMotion(); onDispose { entropy.stopMotion() } }
    val p = entropy.motionProgress
    val ready = entropy.motionReady
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (ready) "📳" else "📱", style = PyType.mono(50f))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.vanity_motion_title), style = PyType.header, color = PyTheme.primary, letterSpacing = 2.sp)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.vanity_motion_body),
            style = PyType.mono(13f), color = PyTheme.cyan, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        ProgressBar(p)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.vanity_percent, (p * 100).toInt()), style = PyType.mono(13f), color = if (ready) PyTheme.primary else PyTheme.primaryDim)
        Spacer(Modifier.height(24.dp))
        WideButton(if (ready) stringResource(R.string.vanity_next_scribble) else stringResource(R.string.vanity_keep_shaking), if (ready) PyTheme.magenta else PyTheme.primaryDim) { if (ready) onNext() }
    }
}

// ---- 5 · Touch entropy ----

@Composable
private fun TouchStep(entropy: EntropyPool, onGenerate: () -> Unit) {
    val points = remember { mutableStateOf(listOf<Offset>()) }
    val p = entropy.touchProgress
    val ready = entropy.touchReady
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.vanity_touch_title), style = PyType.header, color = PyTheme.primary, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.vanity_touch_body),
            style = PyType.mono(12f), color = PyTheme.cyan, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(240.dp).border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val pos = change.position
                    entropy.feedTouch(pos.x, pos.y)
                    points.value = (points.value + pos).takeLast(400)
                }
            }) {
            Canvas(Modifier.fillMaxSize()) {
                val pts = points.value
                for (i in 1 until pts.size) drawLine(Color(0xFF00FF00), pts[i - 1], pts[i], strokeWidth = 3f)
            }
            if (points.value.isEmpty())
                Text(stringResource(R.string.vanity_draw_here), style = PyType.mono(13f), color = PyTheme.primaryDim, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(12.dp))
        ProgressBar(p)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.vanity_percent, (p * 100).toInt()), style = PyType.mono(13f), color = if (ready) PyTheme.primary else PyTheme.primaryDim)
        Spacer(Modifier.weight(1f))
        WideButton(if (ready) stringResource(R.string.vanity_generate) else stringResource(R.string.vanity_keep_scribbling), if (ready) PyTheme.magenta else PyTheme.primaryDim) { if (ready) onGenerate() }
    }
}

// ---- 6 · Generating (live entropy stream) ----

@Composable
private fun GeneratingStep(gen: VanityGenerator, onCancel: () -> Unit) {
    var stream by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val rng = SecureRandom()
        while (true) {
            val b = ByteArray(16).also { rng.nextBytes(it) }
            stream = b.joinToString("") { "%02x".format(it) }
            kotlinx.coroutines.delay(90)
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.vanity_grinding), style = PyType.header, color = PyTheme.primary, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        CircularProgressIndicator(color = PyTheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stream, style = PyType.mono(11f), color = PyTheme.primary.copy(alpha = 0.6f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.vanity_keys_tried, "%,d".format(gen.attempts)), style = PyType.mono(13f), color = PyTheme.cyan)
        Text(if (gen.rate > 0) stringResource(R.string.vanity_keys_per_sec, "%,d".format(gen.rate.toInt())) else stringResource(R.string.vanity_warming_up), style = PyType.mono(12f), color = PyTheme.primaryDim)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.vanity_generating_note),
            style = PyType.mono(10f), color = PyTheme.primaryDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.vanity_cancel), style = PyType.mono(15f), color = PyTheme.danger, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.danger, RectangleShape).clickableNoRipple { Haptics.warning(); onCancel() }.padding(vertical = 13.dp))
    }
}

// ---- 7 · Result ----

@Composable
private fun ResultStep(gen: VanityGenerator, online: Boolean, onSave: () -> Boolean, onWipe: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val m = gen.match ?: return
    var saved by remember { mutableStateOf(false) }
    val fmt = if (m.wif.startsWith("5")) stringResource(R.string.vanity_fmt_uncompressed) else stringResource(R.string.vanity_fmt_compressed)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.vanity_found_it), style = PyType.header, color = PyTheme.primary, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        if (online) {
            Text(stringResource(R.string.vanity_result_online_warn),
                style = PyType.mono(11f), color = PyTheme.danger, textAlign = TextAlign.Center,
                modifier = Modifier.border(1.dp, PyTheme.danger.copy(alpha = 0.6f), RectangleShape).padding(10.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(stringResource(R.string.vanity_save_both), style = PyType.mono(12f), color = PyTheme.yellow, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        KeyBlock(stringResource(R.string.vanity_public_address), m.address, PyTheme.primary) { clipboard.setText(AnnotatedString(m.address)) }
        Spacer(Modifier.height(12.dp))
        KeyBlock(stringResource(R.string.vanity_private_key_title, fmt), m.wif, PyTheme.danger) {
            com.astrolexis.pyblock.ui.components.copySensitiveToClipboard(ctx, m.wif, "private key")
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.vanity_private_warn), style = PyType.mono(10f), color = PyTheme.primaryDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        // Option A — hot: keep it in the app as a spendable wallet.
        Text(if (saved) stringResource(R.string.vanity_saved_wallet) else stringResource(R.string.vanity_save_as_wallet), style = PyType.mono(14f),
            color = if (saved) PyTheme.primary else PyTheme.bg, textAlign = TextAlign.Center, letterSpacing = 1.sp,
            modifier = Modifier.fillMaxWidth()
                .background(if (saved) Color.Transparent else PyTheme.magenta)
                .border(2.dp, if (saved) PyTheme.primary else PyTheme.magenta, RectangleShape)
                .clickableNoRipple { if (!saved) { saved = onSave(); if (saved) Haptics.success() } }.padding(vertical = 13.dp))
        Spacer(Modifier.height(10.dp))
        // Option B — cold: wipe from memory, keep only the paper backup.
        Text(if (saved) stringResource(R.string.vanity_done_wipe) else stringResource(R.string.vanity_saved_wipe), style = PyType.mono(14f), color = PyTheme.bg, textAlign = TextAlign.Center, letterSpacing = 1.sp,
            modifier = Modifier.fillMaxWidth().background(PyTheme.primary).clickableNoRipple { Haptics.warning(); onWipe() }.padding(vertical = 14.dp))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun KeyBlock(title: String, value: String, color: Color, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().moduleFrame(color).padding(14.dp)) {
        Text(title, style = PyType.mono(11f), color = color, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Text(value, style = PyType.mono(15f), color = PyTheme.cyan)
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.Row(
            Modifier.border(1.dp, color, RectangleShape).clickableNoRipple { Haptics.tap(); onCopy() }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            com.astrolexis.pyblock.ui.components.CopyGlyph(color, 12.dp)
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.vanity_copy), style = PyType.mono(12f), color = color)
        }
    }
}

// ---- 8 · Destroyed ----

@Composable
private fun DestroyedStep(onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🔥", style = PyType.mono(48f))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.vanity_wiped_title), style = PyType.header, color = PyTheme.primary, letterSpacing = 3.sp)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.vanity_wiped_body),
            style = PyType.mono(14f), color = PyTheme.cyan, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        WideButton(stringResource(R.string.vanity_done), PyTheme.primary, onDone)
    }
}

// ---- Air-gap breach (network returned mid-ceremony) ----

@Composable
private fun BreachedStep(online: Boolean, onRestart: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("⚠", style = PyType.mono(54f), color = PyTheme.danger)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.vanity_breach_title), style = PyType.header, color = PyTheme.danger, letterSpacing = 2.sp)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.vanity_breach_body),
            style = PyType.mono(14f), color = PyTheme.cyan, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.border(1.dp, (if (online) PyTheme.danger else PyTheme.primary).copy(alpha = 0.6f), RectangleShape).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Box(Modifier.width(10.dp).height(10.dp).background(if (online) PyTheme.danger else PyTheme.primary))
            Spacer(Modifier.width(8.dp))
            Text(if (online) stringResource(R.string.vanity_still_online) else stringResource(R.string.vanity_offline_safe),
                style = PyType.mono(12f), color = if (online) PyTheme.danger else PyTheme.primary)
        }
        Spacer(Modifier.height(24.dp))
        WideButton(if (online) stringResource(R.string.vanity_waiting_offline) else stringResource(R.string.vanity_start_over),
            if (online) PyTheme.primaryDim else PyTheme.primary) { if (!online) onRestart() }
    }
}

// ---- shared ----

@Composable
private fun WideButton(label: String, color: Color, onClick: () -> Unit) {
    val filled = color != PyTheme.primaryDim
    Text(label, style = PyType.mono(17f), color = if (filled) PyTheme.bg else PyTheme.primaryDim,
        letterSpacing = 2.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
            .background(if (filled) color else Color.Transparent)
            .border(2.dp, color, RectangleShape).clickableNoRipple { Haptics.thock(); onClick() }.padding(vertical = 15.dp))
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(12.dp).border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape)) {
        Box(Modifier.fillMaxWidth(fraction).height(12.dp).background(PyTheme.primary))
    }
}

// ---- offline detector ----

@Composable
private fun rememberOnline(): androidx.compose.runtime.State<Boolean> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        // Requires ACCESS_NETWORK_STATE. Fully defensive: any ConnectivityManager
        // failure (missing permission, OEM quirk) must NEVER crash the generator —
        // fall back to "offline" so the air-gap step stays usable.
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        fun current(): Boolean = runCatching {
            val n = cm?.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(n) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { state.value = current() }
            override fun onLost(network: Network) { state.value = current() }
            override fun onUnavailable() { state.value = false }
        }
        state.value = current()
        val registered = runCatching { cm?.registerDefaultNetworkCallback(cb) }.isSuccess
        onDispose { if (registered) runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    return state
}
