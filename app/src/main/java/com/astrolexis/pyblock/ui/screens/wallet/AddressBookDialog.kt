package com.astrolexis.pyblock.ui.screens.wallet

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.crypto.AddressBook
import com.astrolexis.pyblock.data.crypto.AddressEntry
import com.astrolexis.pyblock.data.util.PaymentUri
import com.astrolexis.pyblock.ui.components.CopyGlyph
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType

/** Saved Bitcoin addresses. When [onPick] is set it acts as a picker (used from SEND so you
 *  pay a saved name, not by re-pasting). Add via type/paste; rename or delete in manage mode.
 *  Mirrors iOS AddressBookView. Public data only — never touches fund movement. */
@Composable
fun AddressBookDialog(onPick: ((AddressEntry) -> Unit)? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var entries by remember { mutableStateOf(AddressBook.all(ctx)) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AddressEntry?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(PyTheme.bg).border(1.dp, PyTheme.primary, RectangleShape).padding(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (onPick == null) R.string.abook_title_book else R.string.abook_title_pick),
                    style = PyType.mono(15f), color = PyTheme.primary, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f))
                Text("✕", style = PyType.mono(20f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { onDismiss() })
            }
            Spacer(Modifier.height(12.dp))

            // Add row: name (optional) + address + paste.
            bookField(stringResource(R.string.abook_name_placeholder), name, KeyboardType.Text) { name = it; error = false }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { bookField(stringResource(R.string.abook_address_placeholder), address, KeyboardType.Ascii) { address = it; error = false } }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape)
                    .clickableNoRipple { clipboard.getText()?.text?.let { val p = it.trim(); address = PaymentUri.parse(p)?.address ?: p; error = false } }.padding(10.dp)) {
                    CopyGlyph(PyTheme.cyan, 13.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            val canAdd = address.trim().isNotEmpty()
            Text(stringResource(R.string.abook_add), style = PyType.mono(12f), textAlign = TextAlign.Center,
                color = if (canAdd) PyTheme.bg else PyTheme.primaryDim,
                modifier = Modifier.fillMaxWidth().background(if (canAdd) PyTheme.primary else Color.Transparent)
                    .border(1.dp, if (canAdd) PyTheme.primary else PyTheme.primaryDim, RectangleShape)
                    .clickableNoRipple {
                        if (canAdd) {
                            if (AddressBook.upsert(ctx, name, address) == null) error = true
                            else { name = ""; address = ""; entries = AddressBook.all(ctx) }
                        }
                    }.padding(vertical = 8.dp))
            if (error) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.abook_invalid), style = PyType.mono(9f), color = PyTheme.danger)
            }

            Spacer(Modifier.height(12.dp))
            if (entries.isEmpty()) {
                Text(stringResource(if (onPick == null) R.string.abook_empty_book else R.string.abook_empty_pick),
                    style = PyType.mono(11f), color = PyTheme.primaryDim, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    entries.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .border(1.dp, PyTheme.primary.copy(alpha = 0.25f), RectangleShape)
                                .clickableNoRipple { if (onPick != null) { onPick(e); onDismiss() } }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(e.displayName, style = PyType.mono(13f).copy(fontWeight = FontWeight.Bold),
                                    color = if (e.name.isNotBlank()) PyTheme.cyan else PyTheme.primaryDim)
                                Text(e.short, style = PyType.mono(9f), color = PyTheme.primaryDim)
                            }
                            if (onPick == null) {
                                Text("✎", style = PyType.mono(13f), color = PyTheme.primaryDim,
                                    modifier = Modifier.clickableNoRipple { renaming = e })
                                Spacer(Modifier.width(12.dp))
                                Text("🗑", style = PyType.mono(13f), modifier = Modifier.clickableNoRipple {
                                    AddressBook.remove(ctx, e.id); entries = AddressBook.all(ctx)
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

    renaming?.let { e ->
        SaveToBookDialog(address = e.address, initialName = e.name, titleRes = R.string.abook_rename_title,
            onSave = { nm -> AddressBook.setName(ctx, e.id, nm); entries = AddressBook.all(ctx); renaming = null },
            onDismiss = { renaming = null })
    }
}

/** A one-field dialog to name an address (save-to-book shortcut + rename). */
@Composable
fun SaveToBookDialog(address: String, initialName: String = "", titleRes: Int = R.string.abook_save_title,
                     onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initialName) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(PyTheme.bg).border(1.dp, PyTheme.primary, RectangleShape).padding(18.dp)) {
            Text(stringResource(titleRes), style = PyType.mono(14f), color = PyTheme.primary, letterSpacing = 2.sp)
            Spacer(Modifier.height(4.dp))
            Text(if (address.length <= 22) address else address.take(14) + "…" + address.takeLast(6),
                style = PyType.mono(9f), color = PyTheme.primaryDim)
            Spacer(Modifier.height(10.dp))
            bookField(stringResource(R.string.abook_name_placeholder), name, KeyboardType.Text) { name = it }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.wallet_cancel), style = PyType.mono(13f), color = PyTheme.primary, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).border(1.dp, PyTheme.primary, RectangleShape)
                        .clickableNoRipple { onDismiss() }.padding(vertical = 10.dp))
                Text(stringResource(R.string.abook_add), style = PyType.mono(13f), color = PyTheme.bg, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).background(PyTheme.primary)
                        .clickableNoRipple { onSave(name); onDismiss() }.padding(vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun bookField(placeholder: String, value: String, kb: KeyboardType, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = 0.4f), RectangleShape).padding(10.dp)) {
        if (value.isEmpty()) Text(placeholder, style = PyType.mono(11f), color = PyTheme.primaryDim)
        BasicTextField(value, onChange, textStyle = PyType.mono(11f).copy(color = PyTheme.cyan),
            cursorBrush = SolidColor(PyTheme.cyan), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = kb), modifier = Modifier.fillMaxWidth())
    }
}
