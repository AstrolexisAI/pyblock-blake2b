package com.astrolexis.pyblock.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.astrolexis.pyblock.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.astrolexis.pyblock.data.crypto.PaymentCode
import com.astrolexis.pyblock.data.crypto.PaynymName
import com.astrolexis.pyblock.ui.components.Identicon
import com.astrolexis.pyblock.ui.components.QrCode
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType

/** "Your PayNym" — your BIP-47 reusable payment code (QR + copy), anchored to
 *  the chat since PayNym is the chat payment identity. Fresh address per
 *  payment, no OP_RETURN. Created on demand, lives only on this device. */
@Composable
fun YourPaynymDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val code = remember { PaymentCode.myCode(ctx) }
    val cosmicName = remember { PaynymName.cosmic(code) }
    var showContacts by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(PyTheme.bg).border(1.dp, PyTheme.primary, RectangleShape).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pnym_your_paynym), style = PyType.mono(16f), color = PyTheme.primary, letterSpacing = 3.sp)
                Spacer(Modifier.width(1.dp).weight(1f))
                Text(stringResource(R.string.pnym_contacts), style = PyType.mono(11f), color = PyTheme.cyan,
                    modifier = Modifier.clickableNoRipple { showContacts = true })
            }
            Spacer(Modifier.height(16.dp))
            Identicon(code, 52)
            Spacer(Modifier.height(10.dp))
            // Deterministic cosmic name — same on every device for this code, so peers can confirm it.
            Text(cosmicName, style = PyType.mono(15f), color = PyTheme.yellow, letterSpacing = 1.sp)
            Spacer(Modifier.height(14.dp))
            QrCode(code, 220.dp)
            Spacer(Modifier.height(14.dp))
            Text(code, style = PyType.mono(10f), color = PyTheme.cyan, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.pnym_share_hint),
                style = PyType.mono(9f), color = PyTheme.primaryDim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().border(1.dp, PyTheme.primary, RectangleShape)
                    .clickableNoRipple { clipboard.setText(AnnotatedString(code)) }.padding(vertical = 10.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                com.astrolexis.pyblock.ui.components.CopyGlyph(PyTheme.primary, 12.dp)
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pnym_copy_code), style = PyType.mono(12f), color = PyTheme.primary)
            }
        }
    }
    if (showContacts) PaynymContactsDialog(onDismiss = { showContacts = false })
}
