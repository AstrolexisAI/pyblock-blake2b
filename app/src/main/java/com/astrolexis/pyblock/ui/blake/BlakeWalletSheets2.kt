package com.astrolexis.pyblock.ui.blake

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.crypto.PaymentCode
import com.astrolexis.pyblock.data.crypto.PaynymBook
import com.astrolexis.pyblock.data.crypto.PaynymNotifications
import com.astrolexis.pyblock.data.wallet.RicochetHistory
import com.astrolexis.pyblock.data.wallet.RicochetRecord
import com.astrolexis.pyblock.ui.components.QrCode
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.launch

/** Past BLAKE2b ricochets, newest first — each opens its chain detail (txids + provable hops). */
@Composable
fun RicochetHistorySheet(onCopy: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val records by RicochetHistory.records.collectAsState()
    var detail by remember { mutableStateOf<RicochetRecord?>(null) }
    val d = detail
    if (d != null) { RicochetChainSheet(d, onCopy) { detail = null }; return }

    sheetBox("RICOCHETS", Blake.pp, onClose) {
        val mine = records.filter { it.network == "mainnet" }
        if (mine.isEmpty()) Text("No ricochets yet.", style = Blake.mono(10f), color = Blake.faint)
        else mine.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, Blake.line, RectangleShape).padding(12.dp)
                .clickableNoRipple { detail = r }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${r.hops} hop${if (r.hops == 1) "" else "s"} · ${r.txids.size} txs", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.fg)
                    Text(mid(r.finalTxid, 10, 8), style = Blake.mono(8f), color = Blake.faint)
                }
                Text(if (r.amountSats > 0) "${Blake.btc(r.amountSats)} ${Blake.RUNE}" else "MAX", style = Blake.mono(11f), color = Blake.pp)
            }
        }
    }
}

@Composable
private fun RicochetChainSheet(r: RicochetRecord, onCopy: (String) -> Unit, onClose: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    sheetBox("CHAIN", Blake.pp, onClose) {
        Text("TRANSACTIONS", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        r.txids.forEachIndexed { i, t ->
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).border(1.dp, Blake.line, RectangleShape).padding(10.dp)
                .clickableNoRipple { onCopy(t) }) {
                Text(if (i == 0) "source" else if (i == r.txids.size - 1) "→ recipient" else "hop $i", style = Blake.mono(8f), color = Blake.faint)
                Text(mid(t, 12, 10), style = Blake.mono(10f), color = Blake.pp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("HOP ADDRESSES (provable)", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.warn, letterSpacing = 2.sp)
        Text("The last hop is the address a recipient/exchange saw as the sender. Keys prove the hops are yours.",
            style = Blake.mono(8f), color = Blake.faint)
        Spacer(Modifier.height(8.dp))
        r.hopAddresses.forEachIndexed { i, a ->
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).border(1.dp, Blake.line, RectangleShape).padding(10.dp)) {
                Text(if (i == r.hopAddresses.size - 1) "sender (last hop)" else "hop $i",
                    style = Blake.mono(8f), color = if (i == r.hopAddresses.size - 1) Blake.warn else Blake.faint)
                Text(mid(a, 12, 8), style = Blake.mono(10f), color = Blake.fg, modifier = Modifier.clickableNoRipple { onCopy(a) })
                if (revealed && i < r.hopWifs.size)
                    Text(r.hopWifs[i], style = Blake.mono(9f), color = Blake.warn, modifier = Modifier.clickableNoRipple { onCopy(r.hopWifs[i]) })
            }
        }
        Spacer(Modifier.height(10.dp))
        sheetBtn(if (revealed) "HIDE KEYS" else "REVEAL KEYS", Blake.warn) { revealed = !revealed }
    }
}

/** PAYNYM — BIP-47 reusable payment code: share my code (QR), add contacts, check for payments. */
@Composable
fun PaynymSheet(onCopy: (String) -> Unit, paste: () -> String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // `myCode`/`identityKey` are state, not constants: a restore replaces the identity in place.
    var myCode by remember { mutableStateOf(PaymentCode.myCode(ctx)) }
    var contacts by remember { mutableStateOf(PaynymBook.all(ctx)) }
    var adding by remember { mutableStateOf(false) }
    var newCode by remember { mutableStateOf("") }
    var newLabel by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    // Identity backup / restore.
    var identityKey by remember { mutableStateOf(runCatching { PaymentCode.myIdentityKey(ctx) }.getOrNull()) }
    var revealed by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var restoreInput by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PaymentCode.Identity?>(null) }
    var restoreErr by remember { mutableStateOf<String?>(null) }
    var restoreDone by remember { mutableStateOf(false) }
    var scanningKey by remember { mutableStateOf(false) }

    if (scanningKey) {
        com.astrolexis.pyblock.ui.components.QrScanner(title = "SCAN A PYNYM1… IDENTITY KEY",
            onResult = { code -> restoreInput = code.trim(); restoring = true; pending = null; restoreErr = null; scanningKey = false },
            onClose = { scanningKey = false })
        return
    }

    sheetBox("PAYNYM", Blake.pp, onClose) {
        // My code
        Text("MY PAYNYM", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
        Spacer(Modifier.height(10.dp))
        if (myCode.isNotEmpty()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.background(Blake.hero).padding(10.dp)) { QrCode(text = myCode, size = 180.dp) }
            }
            Spacer(Modifier.height(10.dp))
            Text(myCode, style = Blake.mono(9f), color = Blake.pp, modifier = Modifier.fillMaxWidth().clickableNoRipple { onCopy(myCode) })
            Spacer(Modifier.height(8.dp))
            sheetBtn("COPY CODE", Blake.pp, filled = true) { onCopy(myCode) }
        } else {
            // Empty means the identity store could not be read. Say so loudly: a blank card invites
            // the user to assume a glitch, when the risk is receiving to addresses they can't derive.
            Text("⚠ PAYNYM UNAVAILABLE", style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.danger, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Text("Your PayNym identity could not be read from secure storage, so your code can't be shown. Do NOT share a new code — unlock the device and reopen the app. If this persists after a restore, your identity may not have transferred; check before receiving PayNym payments.",
                style = Blake.mono(9f), color = Blake.warn)
        }
        Spacer(Modifier.height(6.dp))
        if (myCode.isNotEmpty()) Text("Share once. Anyone can pay you repeatedly to fresh addresses — no reuse.", style = Blake.mono(8f), color = Blake.faint)

        // IDENTITY BACKUP — the PayNym has no seed phrase, so these 64 bytes are the only way back.
        // Reveal is opt-in (as sensitive as a WIF) and restore is a two-step confirm that shows the
        // resulting payment code before anything is written.
        Spacer(Modifier.height(18.dp))
        Text("IDENTITY BACKUP", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
        Spacer(Modifier.height(6.dp))
        Text("Your PayNym has no seed phrase — this key IS your PayNym, and it's printed on the paper backup. Without it, coins paid to your PayNym cannot be recovered on another device.",
            style = Blake.mono(9f), color = Blake.faint)
        Spacer(Modifier.height(10.dp))
        val idk = identityKey
        if (revealed && idk != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.background(Blake.hero).padding(10.dp)) { QrCode(text = idk, size = 150.dp) }
            }
            Spacer(Modifier.height(8.dp))
            Text(idk, style = Blake.mono(9f), color = Blake.danger,
                modifier = Modifier.fillMaxWidth().clickableNoRipple {
                    com.astrolexis.pyblock.ui.components.copySensitiveToClipboard(ctx, idk, "PayNym identity key")
                })
            Spacer(Modifier.height(6.dp))
            Text("⚠ Anyone holding this key can derive every payment ever sent to your PayNym. Never share it and never store it on a connected device.",
                style = Blake.mono(8f), color = Blake.warn)
            Spacer(Modifier.height(8.dp))
            sheetBtn("HIDE KEY", Blake.ppDim) { revealed = false }
        } else if (idk != null) {
            sheetBtn("REVEAL IDENTITY KEY", Blake.warn) { revealed = true }
        } else {
            Text("The identity key can't be read right now. Unlock the device and reopen the app — do NOT restore over it while it can't be read.",
                style = Blake.mono(9f), color = Blake.danger)
        }
        Spacer(Modifier.height(10.dp))
        if (restoreDone) {
            Text("✓ PayNym restored. Open each contact and tap CHECK to re-import what they sent you.",
                style = Blake.mono(9f), color = Blake.ok)
            Spacer(Modifier.height(8.dp))
        }
        if (!restoring) {
            sheetBtn("RESTORE FROM BACKUP", Blake.ppDim) {
                restoring = true; restoreErr = null; restoreDone = false; pending = null
            }
        } else {
            sheetField(restoreInput, "PYNYM1… identity key", KeyboardType.Text) {
                restoreInput = it; pending = null; restoreErr = null
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⛶ SCAN", style = Blake.mono(10f), color = Blake.pp,
                    modifier = Modifier.clickableNoRipple { scanningKey = true })
                Spacer(Modifier.width(14.dp))
                Text("PASTE", style = Blake.mono(10f), color = Blake.pp,
                    modifier = Modifier.clickableNoRipple { restoreInput = paste(); pending = null; restoreErr = null })
                Spacer(Modifier.weight(1f))
                Text("CANCEL", style = Blake.mono(10f), color = Blake.ppDim,
                    modifier = Modifier.clickableNoRipple { restoring = false; restoreInput = ""; pending = null; restoreErr = null })
            }
            Spacer(Modifier.height(8.dp))
            val pend = pending
            if (pend == null) {
                sheetBtn("CHECK KEY", Blake.pp) {
                    val id = PaymentCode.parseIdentityKey(restoreInput)
                    if (id == null) restoreErr = "Not a valid identity key (PYNYM1…). Check for a mistyped character — the checksum rejected it."
                    else { pending = id; restoreErr = null }
                }
            } else {
                Text(if (idk == null) "This will WRITE a PayNym identity to this device."
                     else "This REPLACES the PayNym on this device. The current one is gone unless you have its key.",
                    style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.danger)
                Spacer(Modifier.height(4.dp))
                Text("Restores to: ${PaymentCode.encode(pend)}", style = Blake.mono(8f), color = Blake.pp)
                Spacer(Modifier.height(8.dp))
                sheetBtn("CONFIRM RESTORE", Blake.danger) {
                    if (PaymentCode.importIdentity(ctx, pend)) {
                        myCode = PaymentCode.myCode(ctx)
                        identityKey = runCatching { PaymentCode.myIdentityKey(ctx) }.getOrNull()
                        pending = null; restoring = false; restoreInput = ""; revealed = false; restoreDone = true
                    } else restoreErr = "Could not write to secure storage. Nothing was changed."
                }
            }
            restoreErr?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Blake.mono(9f), color = Blake.danger) }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ADD CONTACT", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            Text(if (adding) "✕" else "+ CODE", style = Blake.mono(10f), color = Blake.pp, modifier = Modifier.clickableNoRipple { adding = !adding; msg = null })
        }
        if (adding) {
            Spacer(Modifier.height(8.dp))
            sheetField(newCode, "their PM8T… payment code", KeyboardType.Text) { newCode = it }
            Spacer(Modifier.height(4.dp))
            Text("PASTE", style = Blake.mono(10f), color = Blake.pp, modifier = Modifier.clickableNoRipple { newCode = paste() })
            Spacer(Modifier.height(6.dp))
            sheetField(newLabel, "label (optional)", KeyboardType.Text) { newLabel = it }
            Spacer(Modifier.height(8.dp))
            sheetBtn("ADD", Blake.ok) {
                val c = PaynymBook.upsert(ctx, newLabel.ifBlank { "contact" }, newCode.trim())
                if (c != null) { contacts = PaynymBook.all(ctx); newCode = ""; newLabel = ""; adding = false; msg = null }
                else msg = "Not a valid PayNym (PM8T…) code."
            }
            msg?.let { Text(it, style = Blake.mono(9f), color = Blake.danger) }
        }

        Spacer(Modifier.height(16.dp))
        Text("CONTACTS", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        if (contacts.isEmpty()) Text("No PayNym contacts yet.", style = Blake.mono(9f), color = Blake.faint)
        else contacts.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, Blake.line, RectangleShape).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BlakeIdenticon(seed = c.code, dimen = 28.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.displayName, style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.fg)
                    Text(mid(c.code), style = Blake.mono(8f), color = Blake.faint)
                }
                Text("CHECK", style = Blake.mono(9f), color = Blake.pp, modifier = Modifier.clickableNoRipple {
                    scope.launch { PaynymNotifications.scan(ctx); msg = "Checked ${c.displayName}." }
                })
            }
        }
        msg?.takeIf { !adding }?.let { Spacer(Modifier.height(8.dp)); Text(it, style = Blake.mono(9f), color = Blake.ok) }
    }
}
