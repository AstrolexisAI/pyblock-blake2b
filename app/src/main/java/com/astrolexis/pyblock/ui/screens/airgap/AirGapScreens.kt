package com.astrolexis.pyblock.ui.screens.airgap

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.wallet.AirGapSigner
import com.astrolexis.pyblock.data.wallet.AirGapUr
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.QrCode
import com.astrolexis.pyblock.ui.components.QrScanner
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import com.sparrowwallet.hummingbird.ResultType
import com.sparrowwallet.hummingbird.URDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Air-gap hub — the wallet entry point for offline signing. Build an unsigned payment in
 * SEND → "Export unsigned", sign it here (your key never goes online), then broadcast the
 * signed PSBT. Android mirror of iOS AirGapHubView / AirGapSignView / AirGapBroadcastView.
 */
@Composable
fun AirGapHubScreen(onBack: () -> Unit) {
    var showSign by remember { mutableStateOf(false) }
    var showBroadcast by remember { mutableStateOf(false) }

    if (showSign) { BackHandler { showSign = false }; AirGapSignScreen(onBack = { showSign = false }); return }
    if (showBroadcast) { BackHandler { showBroadcast = false }; AirGapBroadcastScreen(onBack = { showBroadcast = false }); return }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            airGapHeader("AIR-GAP", "offline signing", onBack)
            Spacer(Modifier.height(14.dp))
            Text("Sign and broadcast with an offline device. Build an unsigned payment in Send → Export, sign it here (your key never goes online), then broadcast the signed PSBT.",
                style = PyType.mono(11f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(16.dp))
            hubRow("SIGN A PSBT", "review + sign offline") { Haptics.tap(); showSign = true }
            Spacer(Modifier.height(10.dp))
            hubRow("BROADCAST A SIGNED PSBT", "relay a signed tx to the network") { Haptics.tap(); showBroadcast = true }
            Spacer(Modifier.height(12.dp))
            Text("Tip: create the unsigned PSBT in SEND → “Export unsigned”.",
                style = PyType.mono(9f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Air-gap SIGN — scan/paste unsigned PSBT, review outputs, sign, show the signed UR QR. */
@Composable
fun AirGapSignScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var psbtB64 by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<AirGapSigner.Summary?>(null) }
    var signedB64 by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }

    fun reset() { psbtB64 = null; summary = null; signedB64 = null; error = null }
    fun load(raw: String) {
        val t = raw.trim(); if (t.isEmpty()) return
        signedB64 = null
        scope.launch {
            try { summary = AirGapSigner.summarize(ctx, t); psbtB64 = t; error = null }
            catch (e: AirGapSigner.AirGapException) { summary = null; psbtB64 = null; error = e.userMessage }
            catch (e: Exception) { summary = null; psbtB64 = null; error = "Couldn't read that PSBT." }
        }
    }

    if (showScanner) {
        BackHandler { showScanner = false }
        UrScannerScreen(onComplete = { b64 -> load(b64); showScanner = false }, onCancel = { showScanner = false })
        return
    }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            airGapHeader("SIGN PSBT", "air-gap · offline signer", onBack)
            Spacer(Modifier.height(16.dp))
            val signed = signedB64
            val s = summary
            when {
                signed != null -> Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(14.dp)) {
                    Text("SIGNED ✓", style = PyType.mono(14f), color = PyTheme.primary, letterSpacing = 2.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Scan this on your online device to broadcast. Nothing was sent from here.",
                        style = PyType.mono(10f), color = PyTheme.primaryDim)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AnimatedUrQr(signed, 240.dp) }
                    Spacer(Modifier.height(12.dp))
                    airGapButton("COPY SIGNED PSBT", filled = false) { Haptics.tap(); clip.setText(AnnotatedString(signed)) }
                    Spacer(Modifier.height(8.dp))
                    airGapButton("SIGN ANOTHER", filled = false) { reset(); Haptics.tap() }
                }
                s != null -> Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(14.dp)) {
                    Text("REVIEW — WHAT YOU'RE SIGNING", style = PyType.mono(10f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
                    Spacer(Modifier.height(8.dp))
                    s.outputs.forEach { o -> outputRow(o) }
                    Spacer(Modifier.height(8.dp))
                    kvRow("LEAVING YOUR WALLET", "${"%,d".format(s.toOthersSats)} sat", PyTheme.yellow)
                    kvRow("NETWORK FEE", "${"%,d".format(s.feeSats)} sat", PyTheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("⚠ Only sign if these outputs are what you intend.", style = PyType.mono(9f), color = PyTheme.yellow)
                    Spacer(Modifier.height(12.dp))
                    airGapButton("SIGN", filled = true) {
                        val b64 = psbtB64 ?: return@airGapButton
                        Haptics.thock()
                        scope.launch {
                            try { signedB64 = AirGapSigner.sign(ctx, b64); error = null; Haptics.sent() }
                            catch (e: AirGapSigner.AirGapException) { Haptics.error(); error = e.userMessage }
                            catch (e: Exception) { Haptics.error(); error = "Couldn't sign." }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    airGapButton("SCAN A DIFFERENT PSBT", filled = false) { reset(); Haptics.tap(); showScanner = true }
                }
                else -> Column(Modifier.fillMaxWidth()) {
                    Text("Scan or paste an unsigned PSBT. You'll see exactly what it pays before signing. This device never goes online — the signed PSBT comes back as a QR to broadcast elsewhere.",
                        style = PyType.mono(11f), color = PyTheme.primaryDim)
                    Spacer(Modifier.height(14.dp))
                    airGapButton("SCAN PSBT", filled = true) { Haptics.tap(); showScanner = true }
                    Spacer(Modifier.height(8.dp))
                    airGapButton("PASTE PSBT", filled = false) { Haptics.tap(); clip.getText()?.text?.let { load(it) } }
                }
            }
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, style = PyType.mono(11f), color = PyTheme.danger) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Air-gap BROADCAST — scan/paste a SIGNED PSBT, review it, relay through a PyBLØCK node. */
@Composable
fun AirGapBroadcastScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var psbtB64 by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<AirGapSigner.Summary?>(null) }
    var txid by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var broadcasting by remember { mutableStateOf(false) }

    fun load(raw: String) {
        val t = raw.trim(); if (t.isEmpty()) return
        txid = null
        scope.launch {
            try { summary = AirGapSigner.summarize(ctx, t); psbtB64 = t; error = null }
            catch (e: AirGapSigner.AirGapException) { summary = null; psbtB64 = null; error = e.userMessage }
            catch (e: Exception) { summary = null; psbtB64 = null; error = "Couldn't read that PSBT." }
        }
    }

    if (showScanner) {
        BackHandler { showScanner = false }
        UrScannerScreen(onComplete = { b64 -> load(b64); showScanner = false }, onCancel = { showScanner = false })
        return
    }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            airGapHeader("BROADCAST PSBT", "air-gap · relay signed tx", onBack)
            Spacer(Modifier.height(16.dp))
            val tx = txid
            val s = summary
            when {
                tx != null -> Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(14.dp)) {
                    Text("BROADCAST ✓", style = PyType.mono(14f), color = PyTheme.primary, letterSpacing = 2.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Sent to the network.", style = PyType.mono(11f), color = PyTheme.primaryDim)
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer { Text(tx, style = PyType.mono(10f), color = PyTheme.cyan) }
                    Spacer(Modifier.height(10.dp))
                    airGapButton("COPY TXID", filled = false) { Haptics.tap(); clip.setText(AnnotatedString(tx)) }
                }
                s != null -> Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(14.dp)) {
                    Text("REVIEW — WHAT YOU'RE BROADCASTING", style = PyType.mono(10f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
                    Spacer(Modifier.height(8.dp))
                    s.outputs.forEach { o -> outputRow(o) }
                    Spacer(Modifier.height(8.dp))
                    kvRow("FEE", "${"%,d".format(s.feeSats)} sat", PyTheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("⚠ This relays a real transaction to the network.", style = PyType.mono(9f), color = PyTheme.yellow)
                    Spacer(Modifier.height(12.dp))
                    airGapButton(if (broadcasting) "BROADCASTING…" else "BROADCAST", filled = true) {
                        val b64 = psbtB64 ?: return@airGapButton
                        if (broadcasting) return@airGapButton
                        broadcasting = true; error = null; Haptics.thock()
                        scope.launch {
                            try { txid = AirGapSigner.broadcast(ctx, b64); Haptics.sent() }
                            catch (e: AirGapSigner.AirGapException) { Haptics.error(); error = e.userMessage }
                            catch (e: Exception) { Haptics.error(); error = "Couldn't broadcast (is your node reachable?)." }
                            finally { broadcasting = false }
                        }
                    }
                }
                else -> Column(Modifier.fillMaxWidth()) {
                    Text("Scan or paste a SIGNED PSBT. You'll review it, then it's relayed through your PyBLØCK node.",
                        style = PyType.mono(11f), color = PyTheme.primaryDim)
                    Spacer(Modifier.height(14.dp))
                    airGapButton("SCAN SIGNED PSBT", filled = true) { Haptics.tap(); showScanner = true }
                    Spacer(Modifier.height(8.dp))
                    airGapButton("PASTE SIGNED PSBT", filled = false) { Haptics.tap(); clip.getText()?.text?.let { load(it) } }
                }
            }
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, style = PyType.mono(11f), color = PyTheme.danger) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Shows an UNSIGNED PSBT (built watch-only in Send) as an animated UR QR to hand to an
 *  offline signer. No key involved — transport out only. Mirrors iOS AirGapExportSheet. */
@Composable
fun AirGapExportScreen(psbtB64: String, onBack: () -> Unit) {
    val clip = LocalClipboardManager.current
    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            airGapHeader("UNSIGNED PSBT", "air-gap · sign elsewhere", onBack)
            Spacer(Modifier.height(14.dp))
            Text("Scan this on your offline signer (PyBLØCK ‘Sign PSBT’ or any wallet). Large PSBTs animate — keep it in view. Nothing has been signed or sent.",
                style = PyType.mono(11f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AnimatedUrQr(psbtB64, 240.dp) }
            Spacer(Modifier.height(16.dp))
            airGapButton("COPY PSBT", filled = false) { Haptics.tap(); clip.setText(AnnotatedString(psbtB64)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Renders a PSBT as an animated `ur:crypto-psbt` QR — a static QR when it fits one part,
 *  otherwise cycling through fountain parts a few frames per second. */
@Composable
fun AnimatedUrQr(psbtBase64: String, size: Dp = 240.dp) {
    val encoder = remember(psbtBase64) { AirGapUr.encoder(psbtBase64) }
    var frame by remember(psbtBase64) { mutableStateOf(psbtBase64) }
    var label by remember(psbtBase64) { mutableStateOf("") }
    LaunchedEffect(psbtBase64) {
        val enc = encoder ?: return@LaunchedEffect
        if (enc.isSinglePart) {
            frame = enc.nextPart(); label = ""
        } else {
            while (true) {
                frame = enc.nextPart()
                label = "part ${enc.seqNum} · ${enc.seqLen} parts — keep it in view"
                delay(200)
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        QrCode(frame, size)
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(label, style = PyType.mono(9f), color = PyTheme.primaryDim)
        }
    }
}

/** Continuous scanner that reassembles a BC-UR animated PSBT (or accepts a single
 *  base64/UR QR), then hands back the PSBT as base64. Shows live progress. */
@Composable
private fun UrScannerScreen(onComplete: (String) -> Unit, onCancel: () -> Unit) {
    val decoder = remember { URDecoder() }
    var progress by remember { mutableStateOf(0.0) }
    var done by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        QrScanner(title = "SCAN PSBT", continuous = true, onResult = { code ->
            if (done) return@QrScanner
            val s = code.trim()
            if (s.lowercase().startsWith("ur:")) {
                runCatching { decoder.receivePart(s) }
                progress = runCatching { decoder.estimatedPercentComplete }.getOrDefault(0.0)
                val res = runCatching { decoder.result }.getOrNull()
                if (res != null && res.type == ResultType.SUCCESS && res.ur != null) {
                    AirGapUr.psbtBase64(res.ur)?.let { done = true; onComplete(it) }
                }
            } else {
                // Single-QR base64 PSBT (small) — take it directly.
                done = true; onComplete(s)
            }
        }, onClose = onCancel)
        Text(
            if (progress > 0) "${(progress * 100).toInt()}% — keep the animated QR in view" else "Point at the PSBT QR",
            style = PyType.mono(12f), color = Color.White, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 44.dp),
        )
    }
}

// ── shared bits ──────────────────────────────────────────────────────────────

@Composable
private fun airGapHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PyType.mono(18f), color = PyTheme.yellow, letterSpacing = 2.sp)
            Text(subtitle, style = PyType.mono(9f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
        }
        Text("✕", style = PyType.mono(22f), color = PyTheme.primaryDim,
            modifier = Modifier.clickableNoRipple { Haptics.tap(); onBack() }.padding(8.dp))
    }
}

@Composable
private fun hubRow(title: String, hint: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, PyTheme.cyan.copy(alpha = 0.5f), RectangleShape)
            .clickableNoRipple(onClick).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = PyType.mono(14f), color = PyTheme.cyan, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text("›", style = PyType.mono(16f), color = PyTheme.primaryDim)
        }
        Text(hint, style = PyType.mono(9f), color = PyTheme.primaryDim)
    }
}

@Composable
private fun outputRow(o: AirGapSigner.Out) {
    val c = if (o.isSelf) PyTheme.primaryDim else PyTheme.cyan
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).border(1.dp, PyTheme.primary.copy(alpha = 0.3f), RectangleShape).padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (o.isSelf) "↩ change (you)" else "→ recipient", style = PyType.mono(10f), color = c)
            Spacer(Modifier.weight(1f))
            Text("${"%,d".format(o.sats)} sat", style = PyType.mono(13f), color = c)
        }
        Text(o.address, style = PyType.mono(9f), color = PyTheme.primaryDim, maxLines = 2)
    }
}

@Composable
private fun kvRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PyType.mono(10f), color = PyTheme.primaryDim)
        Spacer(Modifier.weight(1f))
        Text(value, style = PyType.mono(13f), color = color)
    }
}

@Composable
private fun airGapButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        label, style = PyType.mono(14f), letterSpacing = 1.sp,
        color = if (filled) PyTheme.bg else PyTheme.cyan, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
            .background(if (filled) PyTheme.yellow else Color.Transparent)
            .border(1.dp, if (filled) PyTheme.yellow else PyTheme.cyan.copy(alpha = 0.6f), RectangleShape)
            .clickableNoRipple(onClick).padding(vertical = 12.dp),
    )
}
