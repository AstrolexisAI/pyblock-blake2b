package com.astrolexis.pyblock.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.astrolexis.pyblock.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import com.astrolexis.pyblock.data.crypto.PaynymBook
import com.astrolexis.pyblock.data.crypto.PaynymContact
import com.astrolexis.pyblock.ui.components.CopyGlyph
import com.astrolexis.pyblock.ui.components.Identicon
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType

/** PayNym address book. When [onPick] is set it acts as a picker (used from SEND so you pay by
 *  cosmic name, not by re-pasting the code). Add via paste/type; each contact shows its
 *  deterministic cosmic name unless you set an alias. */
@Composable
fun PaynymContactsDialog(onPick: ((PaynymContact) -> Unit)? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var contacts by remember { mutableStateOf(PaynymBook.all(ctx)) }
    var alias by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(PyTheme.bg).border(1.dp, PyTheme.primary, RectangleShape).padding(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (onPick == null) stringResource(R.string.pnymc_title_book) else stringResource(R.string.pnymc_title_pay), style = PyType.mono(15f), color = PyTheme.primary, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f))
                Text("✕", style = PyType.mono(20f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { onDismiss() })
            }
            Spacer(Modifier.height(12.dp))

            // Add row
            nymField(stringResource(R.string.pnymc_alias_placeholder), alias, KeyboardType.Text) { alias = it; error = false }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { nymField(stringResource(R.string.pnymc_code_placeholder), code, KeyboardType.Ascii) { code = it; error = false } }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape)
                    .clickableNoRipple { clipboard.getText()?.text?.let { code = it.trim() } }.padding(10.dp)) {
                    CopyGlyph(PyTheme.cyan, 13.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            val canAdd = code.trim().startsWith("PM")
            Text(stringResource(R.string.pnymc_add_contact), style = PyType.mono(12f), textAlign = TextAlign.Center,
                color = if (canAdd) PyTheme.bg else PyTheme.primaryDim,
                modifier = Modifier.fillMaxWidth().background(if (canAdd) PyTheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                    .border(1.dp, if (canAdd) PyTheme.primary else PyTheme.primaryDim, RectangleShape)
                    .clickableNoRipple {
                        if (canAdd) {
                            if (PaynymBook.upsert(ctx, alias, code) == null) { error = true }
                            else { alias = ""; code = ""; contacts = PaynymBook.all(ctx) }
                        }
                    }.padding(vertical = 8.dp))
            if (error) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.pnymc_invalid_code), style = PyType.mono(9f), color = PyTheme.danger)
            }

            Spacer(Modifier.height(12.dp))
            if (contacts.isEmpty()) {
                Text(if (onPick == null) stringResource(R.string.pnymc_empty_book) else stringResource(R.string.pnymc_empty_pick),
                    style = PyType.mono(11f), color = PyTheme.primaryDim, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    contacts.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .border(1.dp, PyTheme.primary.copy(alpha = 0.25f), RectangleShape)
                                .clickableNoRipple { if (onPick != null) { onPick(c); onDismiss() } }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Identicon(c.code, 32)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.displayName, style = PyType.mono(13f).copy(fontWeight = FontWeight.Bold), color = PyTheme.primary)
                                Text(c.code.take(12) + "…" + c.code.takeLast(6), style = PyType.mono(9f), color = PyTheme.primaryDim)
                            }
                            if (onPick == null) {
                                Text("🗑", style = PyType.mono(13f), modifier = Modifier.clickableNoRipple {
                                    PaynymBook.remove(ctx, c.id); contacts = PaynymBook.all(ctx)
                                })
                            } else {
                                com.astrolexis.pyblock.ui.components.LockGlyph(PyTheme.magenta, 12.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun nymField(placeholder: String, value: String, kb: KeyboardType, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = 0.4f), RectangleShape).padding(10.dp)) {
        if (value.isEmpty()) Text(placeholder, style = PyType.mono(11f), color = PyTheme.primaryDim)
        BasicTextField(value, onChange, textStyle = PyType.mono(11f).copy(color = PyTheme.cyan),
            cursorBrush = SolidColor(PyTheme.cyan), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = kb), modifier = Modifier.fillMaxWidth())
    }
}
