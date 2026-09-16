package com.astrolexis.pyblock.ui.blake

import com.astrolexis.pyblock.R
import androidx.compose.ui.res.stringResource
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

    sheetBox(stringResource(R.string.blk_ricochets), Blake.pp, onClose) {
        val mine = records.filter { it.network == "mainnet" }
        if (mine.isEmpty()) Text(stringResource(R.string.blk_no_ricochets_yet), style = Blake.mono(10f), color = Blake.faint)
        else mine.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, Blake.line, RectangleShape).padding(12.dp)
                .clickableNoRipple { detail = r }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.blk_hops_txs, r.hops, r.txids.size), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.fg)
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
    sheetBox(stringResource(R.string.blk_chain), Blake.pp, onClose) {
        Text(stringResource(R.string.blk_transactions), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        r.txids.forEachIndexed { i, t ->
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).border(1.dp, Blake.line, RectangleShape).padding(10.dp)
                .clickableNoRipple { onCopy(t) }) {
                Text(if (i == 0) "source" else if (i == r.txids.size - 1) stringResource(R.string.blk_recipient) else "hop $i", style = Blake.mono(8f), color = Blake.faint)
                Text(mid(t, 12, 10), style = Blake.mono(10f), color = Blake.pp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.blk_hop_addresses_provable), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.warn, letterSpacing = 2.sp)
        Text(stringResource(R.string.blk_the_last_hop_is_the_address_a_recipient_),
            style = Blake.mono(8f), color = Blake.faint)
        Spacer(Modifier.height(8.dp))
        r.hopAddresses.forEachIndexed { i, a ->
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).border(1.dp, Blake.line, RectangleShape).padding(10.dp)) {
                Text(if (i == r.hopAddresses.size - 1) stringResource(R.string.blk_sender_last_hop) else "hop $i",
                    style = Blake.mono(8f), color = if (i == r.hopAddresses.size - 1) Blake.warn else Blake.faint)
                Text(mid(a, 12, 8), style = Blake.mono(10f), color = Blake.fg, modifier = Modifier.clickableNoRipple { onCopy(a) })
                if (revealed && i < r.hopWifs.size)
                    Text(r.hopWifs[i], style = Blake.mono(9f), color = Blake.warn, modifier = Modifier.clickableNoRipple { onCopy(r.hopWifs[i]) })
            }
        }
        Spacer(Modifier.height(10.dp))
        sheetBtn(if (revealed) stringResource(R.string.blk_hide_keys) else stringResource(R.string.blk_reveal_keys), Blake.warn) { revealed = !revealed }
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
        com.astrolexis.pyblock.ui.components.QrScanner(title = stringResource(R.string.blk_scan_a_pynym1_identity_key),
            onResult = { code -> restoreInput = code.trim(); restoring = true; pending = null; restoreErr = null; scanningKey = false },
            onClose = { scanningKey = false })
        return
    }

    sheetBox(stringResource(R.string.blk_paynym), Blake.pp, onClose) {
        // My code
        Text(stringResource(R.string.blk_my_paynym), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
        Spacer(Modifier.height(10.dp))
        if (myCode.isNotEmpty()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.background(Blake.hero).padding(10.dp)) { QrCode(text = myCode, size = 180.dp) }
            }
            Spacer(Modifier.height(10.dp))
            Text(myCode, style = Blake.mono(9f), color = Blake.pp, modifier = Modifier.fillMaxWidth().clickableNoRipple { onCopy(myCode) })
            Spacer(Modifier.height(8.dp))
            sheetBtn(stringResource(R.string.blk_copy_code), Blake.pp, filled = true) { onCopy(myCode) }
        } else {
            // Empty means the identity store could not be read. Say so loudly: a blank card invites
            // the user to assume a glitch, when the risk is receiving to addresses they can't derive.
            Text(stringResource(R.string.blk_paynym_unavailable), style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.danger, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.blk_your_paynym_identity_could_not_be_read_f),
                style = Blake.mono(9f), color = Blake.warn)
        }
        Spacer(Modifier.height(6.dp))
        if (myCode.isNotEmpty()) Text(stringResource(R.string.blk_share_once_anyone_can_pay_you_repeatedly), style = Blake.mono(8f), color = Blake.faint)

        // IDENTITY BACKUP — the PayNym has no seed phrase, so these 64 bytes are the only way back.
        // Reveal is opt-in (as sensitive as a WIF) and restore is a two-step confirm that shows the
        // resulting payment code before anything is written.
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.blk_identity_backup), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.blk_your_paynym_has_no_seed_phrase_this_key_),
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
                    com.astrolexis.pyblock.ui.components.copySensitiveToClipboard(ctx, idk, com.astrolexis.pyblock.data.store.AppStrings.get(R.string.blk_paynym_identity_key))
                })
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.blk_anyone_holding_this_key_can_derive_every),
                style = Blake.mono(8f), color = Blake.warn)
            Spacer(Modifier.height(8.dp))
            sheetBtn(stringResource(R.string.blk_hide_key), Blake.ppDim) { revealed = false }
        } else if (idk != null) {
            sheetBtn(stringResource(R.string.blk_reveal_identity_key), Blake.warn) { revealed = true }
        } else {
            Text(stringResource(R.string.blk_the_identity_key_can_t_be_read_right_now),
                style = Blake.mono(9f), color = Blake.danger)
        }
        Spacer(Modifier.height(10.dp))
        if (restoreDone) {
            Text(stringResource(R.string.blk_paynym_restored_open_each_contact_and_ta),
                style = Blake.mono(9f), color = Blake.ok)
            Spacer(Modifier.height(8.dp))
        }
        if (!restoring) {
            sheetBtn(stringResource(R.string.blk_restore_from_backup), Blake.ppDim) {
                restoring = true; restoreErr = null; restoreDone = false; pending = null
            }
        } else {
            sheetField(restoreInput, stringResource(R.string.blk_pynym1_identity_key), KeyboardType.Text) {
                restoreInput = it; pending = null; restoreErr = null
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.blk_scan), style = Blake.mono(10f), color = Blake.pp,
                    modifier = Modifier.clickableNoRipple { scanningKey = true })
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.blk_paste), style = Blake.mono(10f), color = Blake.pp,
                    modifier = Modifier.clickableNoRipple { restoreInput = paste(); pending = null; restoreErr = null })
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.blk_cancel), style = Blake.mono(10f), color = Blake.ppDim,
                    modifier = Modifier.clickableNoRipple { restoring = false; restoreInput = ""; pending = null; restoreErr = null })
            }
            Spacer(Modifier.height(8.dp))
            val pend = pending
            if (pend == null) {
                sheetBtn(stringResource(R.string.blk_check_key), Blake.pp) {
                    val id = PaymentCode.parseIdentityKey(restoreInput)
                    if (id == null) restoreErr = ctx.getString(R.string.blk_not_a_valid_identity_key_pynym1_check_fo)
                    else { pending = id; restoreErr = null }
                }
            } else {
                Text(if (idk == null) stringResource(R.string.blk_this_will_write_a_paynym_identity_to_thi)
                     else stringResource(R.string.blk_this_replaces_the_paynym_on_this_device_),
                    style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.danger)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.blk_restores_to, PaymentCode.encode(pend)), style = Blake.mono(8f), color = Blake.pp)
                Spacer(Modifier.height(8.dp))
                sheetBtn(stringResource(R.string.blk_confirm_restore), Blake.danger) {
                    if (PaymentCode.importIdentity(ctx, pend)) {
                        myCode = PaymentCode.myCode(ctx)
                        identityKey = runCatching { PaymentCode.myIdentityKey(ctx) }.getOrNull()
                        pending = null; restoring = false; restoreInput = ""; revealed = false; restoreDone = true
                    } else restoreErr = ctx.getString(R.string.blk_could_not_write_to_secure_storage_nothin)
                }
            }
            restoreErr?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Blake.mono(9f), color = Blake.danger) }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.blk_add_contact_2), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            Text(if (adding) "✕" else stringResource(R.string.blk_code), style = Blake.mono(10f), color = Blake.pp, modifier = Modifier.clickableNoRipple { adding = !adding; msg = null })
        }
        if (adding) {
            Spacer(Modifier.height(8.dp))
            sheetField(newCode, stringResource(R.string.blk_their_pm8t_payment_code), KeyboardType.Text) { newCode = it }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.blk_paste), style = Blake.mono(10f), color = Blake.pp, modifier = Modifier.clickableNoRipple { newCode = paste() })
            Spacer(Modifier.height(6.dp))
            sheetField(newLabel, stringResource(R.string.blk_label_optional), KeyboardType.Text) { newLabel = it }
            Spacer(Modifier.height(8.dp))
            sheetBtn(stringResource(R.string.blk_add), Blake.ok) {
                val c = PaynymBook.upsert(ctx, newLabel.ifBlank { "contact" }, newCode.trim())
                if (c != null) { contacts = PaynymBook.all(ctx); newCode = ""; newLabel = ""; adding = false; msg = null }
                else msg = ctx.getString(R.string.blk_not_a_valid_paynym_pm8t_code)
            }
            msg?.let { Text(it, style = Blake.mono(9f), color = Blake.danger) }
        }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.blk_people), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 3.sp)
        Text(stringResource(R.string.blk_everyone_you_can_pay_and_who_can_pay_you),
            style = Blake.mono(8f), color = Blake.faint)
        Spacer(Modifier.height(8.dp))
        if (contacts.isEmpty()) Text(stringResource(R.string.blk_nobody_yet_message_someone_in_community_), style = Blake.mono(9f), color = Blake.faint)
        else contacts.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, Blake.line, RectangleShape).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BlakeIdenticon(seed = c.code, dimen = 28.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.displayName, style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.fg)
                    Text(mid(c.code), style = Blake.mono(8f), color = Blake.faint)
                }
                Text(stringResource(R.string.blk_check), style = Blake.mono(9f), color = Blake.pp, modifier = Modifier.clickableNoRipple {
                    // An explicit check sweeps both derivation schemes from index 0.
                    scope.launch { PaynymNotifications.scan(ctx, full = true); msg = com.astrolexis.pyblock.data.store.AppStrings.get(R.string.blk_checked, c.displayName) }
                })
            }
        }
        msg?.takeIf { !adding }?.let { Spacer(Modifier.height(8.dp)); Text(it, style = Blake.mono(9f), color = Blake.ok) }
    }
}
