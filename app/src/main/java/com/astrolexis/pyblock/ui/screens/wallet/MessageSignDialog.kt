package com.astrolexis.pyblock.ui.screens.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.BasicTextField
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.crypto.MessageSign
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType

/** Sign a message with one of your addresses' keys, or verify someone else's
 *  signature. Bitcoin legacy signed-message format — interoperable with
 *  Electrum / Sparrow / Bither. Mirrors the iOS MessageSignView. */
@Composable
fun MessageSignDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val wallets by WalletStore.wallets.collectAsState()

    var signMode by remember { mutableStateOf(true) }
    // Sign
    var signWalletId by remember { mutableStateOf(wallets.firstOrNull()?.id) }
    var pickerOpen by remember { mutableStateOf(false) }
    var signMessage by remember { mutableStateOf("") }
    var signature by remember { mutableStateOf("") }
    // Verify
    var vAddress by remember { mutableStateOf("") }
    var vMessage by remember { mutableStateOf("") }
    var vSignature by remember { mutableStateOf("") }
    var vResult by remember { mutableStateOf<Boolean?>(null) }

    val signWallet = wallets.firstOrNull { it.id == signWalletId } ?: wallets.firstOrNull()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.92f).heightIn(max = 640.dp)
                .background(PyTheme.bg).border(1.dp, PyTheme.primary, RectangleShape).padding(18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MarqueeTitle(text = stringResource(R.string.msgsign_title))
                Spacer(Modifier.weight(1f))
                Text("✕", style = PyType.mono(20f), color = PyTheme.primaryDim,
                    modifier = Modifier.clickableNoRipple { Haptics.tap(); onClose() }.padding(4.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Tab(stringResource(R.string.msgsign_tab_sign), signMode) { Haptics.select(); signMode = true }
                Tab(stringResource(R.string.msgsign_tab_verify), !signMode) { Haptics.select(); signMode = false }
            }
            Spacer(Modifier.height(14.dp))

            if (signMode) {
                Label(stringResource(R.string.msgsign_label_address))
                Column(
                    Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape)
                        .clickableNoRipple { Haptics.select(); pickerOpen = !pickerOpen }.padding(12.dp),
                ) {
                    Text(signWallet?.label ?: stringResource(R.string.msgsign_no_wallets), style = PyType.mono(13f), color = PyTheme.cyan)
                    Text(signWallet?.address ?: "—", style = PyType.mono(10f), color = PyTheme.primaryDim, maxLines = 1)
                }
                if (pickerOpen) {
                    wallets.forEach { w ->
                        Text(stringResource(R.string.msgsign_wallet_item, w.label, w.address.take(12)), style = PyType.mono(11f), color = PyTheme.primary,
                            modifier = Modifier.fillMaxWidth().clickableNoRipple {
                                Haptics.select(); signWalletId = w.id; signature = ""; pickerOpen = false
                            }.padding(vertical = 6.dp, horizontal = 8.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Label(stringResource(R.string.msgsign_label_message))
                MultilineField(signMessage, 88.dp) { signMessage = it; signature = "" }
                Spacer(Modifier.height(14.dp))
                ActionButton(stringResource(R.string.msgsign_btn_sign), enabled = signWallet != null && signMessage.isNotBlank(), fill = PyTheme.magenta) {
                    Haptics.thock()
                    val wif = signWallet?.let { WalletStore.wif(ctx, it.id) }
                    signature = if (wif != null) (MessageSign.sign(signMessage, wif) ?: "") else ""
                }
                if (signature.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Label(stringResource(R.string.msgsign_label_signature))
                    Text(signature, style = PyType.mono(11f), color = PyTheme.cyan,
                        modifier = Modifier.fillMaxWidth().border(1.dp, PyTheme.cyan.copy(alpha = 0.5f), RectangleShape).padding(10.dp))
                    Spacer(Modifier.height(8.dp))
                    ActionButton(stringResource(R.string.msgsign_btn_copy), enabled = true, fill = Color.Transparent, textColor = PyTheme.primary) {
                        Haptics.tap()
                        clip.setText(AnnotatedString(signature))
                    }
                }
            } else {
                Label(stringResource(R.string.msgsign_label_address))
                MultilineField(vAddress, 44.dp, mono = 12f) { vAddress = it; vResult = null }
                Spacer(Modifier.height(12.dp))
                Label(stringResource(R.string.msgsign_label_message))
                MultilineField(vMessage, 72.dp) { vMessage = it; vResult = null }
                Spacer(Modifier.height(12.dp))
                Label(stringResource(R.string.msgsign_label_signature))
                MultilineField(vSignature, 64.dp, mono = 11f) { vSignature = it; vResult = null }
                Spacer(Modifier.height(14.dp))
                ActionButton(stringResource(R.string.msgsign_btn_verify), enabled = vAddress.isNotBlank() && vMessage.isNotEmpty() && vSignature.isNotBlank(), fill = PyTheme.primary) {
                    Haptics.thock()
                    vResult = MessageSign.verify(vAddress.trim(), vMessage, vSignature.trim())
                }
                vResult?.let { r ->
                    Spacer(Modifier.height(12.dp))
                    val c = if (r) PyTheme.primary else PyTheme.magenta
                    Row(Modifier.fillMaxWidth().border(1.dp, c.copy(alpha = 0.6f), RectangleShape).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Text(if (r) "✓" else "✗", style = PyType.mono(20f), color = c)
                        Spacer(Modifier.width(10.dp))
                        Text(if (r) stringResource(R.string.msgsign_result_valid) else stringResource(R.string.msgsign_result_invalid), style = PyType.mono(12f), color = c, letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(label: String, active: Boolean, onClick: () -> Unit) {
    Text(label, style = PyType.mono(13f), color = if (active) PyTheme.bg else PyTheme.primaryDim,
        textAlign = TextAlign.Center, letterSpacing = 2.sp,
        modifier = Modifier.weight(1f).border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape)
            .background(if (active) PyTheme.primary else Color.Transparent)
            .clickableNoRipple(onClick).padding(vertical = 9.dp))
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Label(t: String) {
    Text(t, style = PyType.mono(10f), color = PyTheme.primaryDim, letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun MultilineField(value: String, height: androidx.compose.ui.unit.Dp, mono: Float = 13f, onChange: (String) -> Unit) {
    BasicTextField(value, onChange, textStyle = PyType.mono(mono).copy(color = PyTheme.cyan),
        cursorBrush = SolidColor(PyTheme.cyan),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth().height(height)
            .border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape).padding(10.dp))
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, fill: Color, textColor: Color = PyTheme.bg, onClick: () -> Unit) {
    val fg = if (enabled) (if (fill == Color.Transparent) textColor else PyTheme.bg) else PyTheme.primaryDim
    Text(label, style = PyType.mono(14f), color = fg, textAlign = TextAlign.Center, letterSpacing = 1.sp,
        modifier = Modifier.fillMaxWidth()
            .background(if (enabled && fill != Color.Transparent) fill else Color.Transparent)
            .border(1.dp, if (enabled) (if (fill == Color.Transparent) PyTheme.primary else fill) else PyTheme.primaryDim, RectangleShape)
            .clickableNoRipple { if (enabled) onClick() }.padding(vertical = 12.dp))
}
