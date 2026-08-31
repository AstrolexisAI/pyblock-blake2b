package com.astrolexis.pyblock.ui.screens.chat

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.crypto.PaynymBook
import com.astrolexis.pyblock.data.crypto.PaynymName
import com.astrolexis.pyblock.ui.components.CopyGlyph
import com.astrolexis.pyblock.ui.components.Identicon
import com.astrolexis.pyblock.ui.components.QrCode
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType

/** The PayNym of the peer you're chatting with — their cosmic name, code + QR, with copy and
 *  add-to-contacts. Opened from the DM header. Mirrors the iOS PeerPaynymSheet. */
@Composable
fun PeerPaynymDialog(peerName: String, code: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var saved by remember { mutableStateOf(PaynymBook.contactForCode(ctx, code) != null) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(PyTheme.bg).border(1.dp, PyTheme.primary, RectangleShape).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.peerpnym_header, peerName.uppercase()), style = PyType.mono(13f), color = PyTheme.primary, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text("✕", style = PyType.mono(20f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { onDismiss() })
            }
            Spacer(Modifier.height(12.dp))
            Identicon(code, 52)
            Spacer(Modifier.height(8.dp))
            Text(PaynymName.cosmic(code), style = PyType.mono(15f), color = PyTheme.yellow, letterSpacing = 1.sp)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.background(Color.White).padding(10.dp)) { QrCode(code, 190.dp) }
            Spacer(Modifier.height(10.dp))
            Text(code, style = PyType.mono(9f), color = PyTheme.cyan, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().border(1.dp, PyTheme.primary, RectangleShape)
                    .clickableNoRipple { clipboard.setText(AnnotatedString(code)) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                CopyGlyph(PyTheme.primary, 12.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.peerpnym_copy_code), style = PyType.mono(12f), color = PyTheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (saved) stringResource(R.string.peerpnym_in_contacts) else stringResource(R.string.peerpnym_add_to_contacts), style = PyType.mono(12f),
                color = if (saved) PyTheme.primaryDim else PyTheme.cyan, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, if (saved) PyTheme.primaryDim else PyTheme.cyan, RectangleShape)
                    .clickableNoRipple { if (!saved) { PaynymBook.upsert(ctx, peerName, code); saved = true } }
                    .padding(vertical = 10.dp),
            )
        }
    }
}
