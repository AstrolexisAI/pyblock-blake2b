package com.astrolexis.pyblock.ui.blake

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.wallet.BlakeContact
import com.astrolexis.pyblock.data.wallet.BlakeContactsStore
import com.astrolexis.pyblock.ui.components.clickableNoRipple

/**
 * Address book. Two modes:
 *  - picker ([onPick] set): tap a contact to fill the send destination, then close.
 *  - manager ([onPick] null): just add / delete saved destinations.
 * Mirrors iOS `ContactsView`.
 */
@Composable
fun ContactsSheet(onPick: ((String) -> Unit)?, onClose: () -> Unit) {
    val contacts by BlakeContactsStore.contacts.collectAsState()
    val clip = LocalClipboardManager.current
    var adding by remember { mutableStateOf(false) }
    var newLabel by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    val canSave = BlakeContact.isValidDestination(newValue)

    sheetBox("CONTACTS", Blake.pp, onClose) {
        if (onPick != null) {
            Text("Tap a contact to use it as the recipient.", style = Blake.mono(9f), color = Blake.faint)
            Spacer(Modifier.height(12.dp))
        }

        if (adding) {
            Column(Modifier.fillMaxWidth().border(1.dp, Blake.line, RectangleShape).padding(14.dp)) {
                Text("NEW CONTACT", style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.ppDim, letterSpacing = 2.sp)
                Spacer(Modifier.height(10.dp))
                labeledField("Name (e.g. Stefa)", newLabel, KeyboardType.Text) { newLabel = it }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ADDRESS / PAYNYM", style = Blake.mono(9f), color = Blake.faint, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    Text("PASTE", style = Blake.mono(10f), color = Blake.pp,
                        modifier = Modifier.clickableNoRipple { newValue = sanitizeContact(clip.getText()?.text ?: "") })
                }
                Spacer(Modifier.height(4.dp))
                BasicTextField(newValue, { newValue = it }, textStyle = Blake.mono(12f).copy(color = Blake.fg),
                    cursorBrush = SolidColor(Blake.pp),
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, if (canSave || newValue.isEmpty()) Blake.line else Blake.danger, RectangleShape).padding(10.dp))
                if (newValue.isNotEmpty() && !canSave) {
                    Spacer(Modifier.height(4.dp))
                    Text("That isn't a valid address or PayNym code.", style = Blake.mono(8f), color = Blake.danger)
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Column(Modifier.weight(1f)) {
                        sheetBtn("SAVE", Blake.pp, filled = canSave) {
                            if (canSave) { BlakeContactsStore.add(newLabel, newValue); adding = false; newLabel = ""; newValue = "" }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        sheetBtn("CANCEL", Blake.ppDim) { adding = false; newLabel = ""; newValue = "" }
                    }
                }
            }
        } else {
            sheetBtn("+ ADD CONTACT", Blake.pp) { adding = true }
        }

        Spacer(Modifier.height(12.dp))
        if (contacts.isEmpty() && !adding) {
            Text("No contacts yet. Save an address or a PayNym (PM…) to reuse it.",
                style = Blake.mono(9f), color = Blake.faint)
        }
        contacts.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)
                .clickableNoRipple { if (onPick != null) { onPick(c.value); onClose() } },
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (c.isPaymentCode) "᛭" else "◈", style = Blake.mono(16f, FontWeight.ExtraBold), color = Blake.pp,
                    modifier = Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.label, style = Blake.mono(12f, FontWeight.ExtraBold), color = Blake.fg, maxLines = 1)
                    Text(shortValue(c.value), style = Blake.mono(9f), color = Blake.faint, maxLines = 1)
                }
                if (c.isPaymentCode) {
                    Text("PAYNYM", style = Blake.mono(7f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp)
                    Spacer(Modifier.width(10.dp))
                }
                Text("✕", style = Blake.mono(12f), color = Blake.ppDim,
                    modifier = Modifier.clickableNoRipple { BlakeContactsStore.remove(c) })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Blake.line))
        }
    }
}

@Composable
private fun labeledField(hint: String, value: String, keyboard: KeyboardType, onChange: (String) -> Unit) {
    BasicTextField(value, onChange, singleLine = true, textStyle = Blake.mono(13f).copy(color = Blake.fg),
        cursorBrush = SolidColor(Blake.pp), keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().border(1.dp, Blake.line, RectangleShape).padding(10.dp)) {
                if (value.isEmpty()) Text(hint, style = Blake.mono(13f), color = Blake.faint)
                inner()
            }
        })
}

private fun shortValue(v: String): String =
    if (v.length <= 20) v else v.take(10) + "…" + v.takeLast(6)

private fun sanitizeContact(raw: String): String {
    var s = raw.trim()
    for (p in listOf("bitcoin:", "BITCOIN:")) if (s.startsWith(p)) s = s.removePrefix(p)
    s.indexOf('?').let { if (it >= 0) s = s.substring(0, it) }
    return s.trim()
}
