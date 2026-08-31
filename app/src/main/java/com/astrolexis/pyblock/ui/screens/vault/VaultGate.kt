package com.astrolexis.pyblock.ui.screens.vault

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.wallet.WalletVault
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame
import kotlinx.coroutines.launch

/** Gates the Wallet tab behind the spending password: forced setup when none is
 *  set, an unlock screen when locked, and the content once unlocked. Mirrors iOS VaultGate. */
/** Opt-in on Android: the wallet works normally until the user turns the password
 *  on. Once enabled, this gate shows the unlock screen when locked. */
@Composable
fun VaultGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        WalletVault.init(ctx)                      // retry if Keystore wasn't ready at launch
        com.astrolexis.pyblock.data.wallet.WalletStore.ensureLoaded(ctx)
    }
    val enabled by WalletVault.enabled.collectAsState()
    val unlocked by WalletVault.unlocked.collectAsState()
    val hasWallets by com.astrolexis.pyblock.data.wallet.WalletStore.wallets.collectAsState()
    when {
        // A spending password is REQUIRED once you hold self-custody keys — set it
        // now (this encrypts the existing keys). Matches iOS. Only forced when there
        // are wallets to protect; a track-only user isn't nagged.
        !enabled && hasWallets.isNotEmpty() -> SetPasswordScreen(onClose = {}, mandatory = true)
        enabled && !unlocked -> UnlockScreen()
        else -> content()
    }
}

/** Opt-in setup, opened from the wallet screen. Calls [onClose] when the password
 *  is enabled (or the user backs out). */
@Composable
fun SetPasswordScreen(onClose: () -> Unit, mandatory: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val canEnable = pw.length >= 8 && pw == confirm

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
    Starfield()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MarqueeTitle(text = if (mandatory) stringResource(R.string.vault_protect_title) else stringResource(R.string.vault_set_title))
            Spacer(Modifier.weight(1f))
            // Mandatory setup can't be dismissed — you hold self-custody keys.
            if (!mandatory) {
                Text("✕", style = PyType.mono(20f), color = PyTheme.primaryDim,
                    modifier = Modifier.clickableNoRipple { Haptics.tap(); onClose() }.padding(4.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (mandatory)
                stringResource(R.string.vault_desc_mandatory)
             else
                stringResource(R.string.vault_desc_optional),
            style = PyType.mono(11f), color = PyTheme.primaryDim)
        Spacer(Modifier.height(14.dp))
        WarningBox()
        Spacer(Modifier.height(14.dp))
        PwField(pw, stringResource(R.string.vault_pw_placeholder_new)) { pw = it; error = null }
        Spacer(Modifier.height(10.dp))
        PwField(confirm, stringResource(R.string.vault_pw_placeholder_confirm)) { confirm = it; error = null }
        error?.let { Spacer(Modifier.height(8.dp)); Text(it, style = PyType.mono(10f), color = PyTheme.magenta) }
        Spacer(Modifier.height(14.dp))
        GateButton(if (busy) stringResource(R.string.vault_btn_encrypting) else stringResource(R.string.vault_btn_enable), enabled = canEnable && !busy, fill = PyTheme.magenta) {
            Haptics.thock()
            scope.launch {
                if (pw.length < 8) { error = ctx.getString(R.string.vault_err_min_length); return@launch }
                if (pw != confirm) { error = ctx.getString(R.string.vault_err_mismatch); return@launch }
                error = null; busy = true
                val ok = WalletVault.enable(ctx, pw)
                busy = false
                if (!ok) error = ctx.getString(R.string.vault_err_enable_failed) else onClose()
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.vault_backup_reminder),
            style = PyType.mono(9f), color = PyTheme.primaryDim)
    }
    }
}

@Composable
private fun UnlockScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
    Starfield()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        com.astrolexis.pyblock.ui.components.LockGlyph(PyTheme.primary, 46.dp)
        Spacer(Modifier.height(14.dp))
        MarqueeTitle(text = stringResource(R.string.vault_locked_title))
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.vault_locked_subtitle), style = PyType.mono(11f), color = PyTheme.primaryDim,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        PwField(pw, stringResource(R.string.vault_pw_placeholder), border = if (wrong) PyTheme.magenta else PyTheme.primary) { pw = it; wrong = false }
        if (wrong) { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.vault_err_wrong_password), style = PyType.mono(10f), color = PyTheme.magenta) }
        Spacer(Modifier.height(16.dp))
        GateButton(if (busy) stringResource(R.string.vault_btn_unlocking) else stringResource(R.string.vault_btn_unlock), enabled = pw.isNotEmpty() && !busy, fill = PyTheme.primary) {
            Haptics.thock()
            scope.launch {
                busy = true
                val ok = WalletVault.unlock(ctx, pw)
                busy = false
                wrong = !ok
                if (!ok) pw = ""
            }
        }
    }
    }
}

// MARK: bits

@Composable
private fun WarningBox() {
    Column(Modifier.fillMaxWidth().moduleFrame(PyTheme.magenta).padding(12.dp)) {
        Text(stringResource(R.string.vault_warning_title), style = PyType.mono(12f), color = PyTheme.magenta, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.vault_warning_body),
            style = PyType.mono(10f), color = PyTheme.primary)
    }
}

@Composable
private fun PwField(value: String, placeholder: String, border: Color = PyTheme.primary, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().border(1.dp, border.copy(alpha = 0.6f), RectangleShape).padding(12.dp)) {
        if (value.isEmpty()) Text(placeholder, style = PyType.mono(15f), color = PyTheme.primaryDim)
        BasicTextField(value, onChange, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            textStyle = PyType.mono(15f).copy(color = PyTheme.cyan),
            cursorBrush = SolidColor(PyTheme.cyan),
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GateButton(label: String, enabled: Boolean, fill: Color, onClick: () -> Unit) {
    val fg = if (enabled) PyTheme.bg else PyTheme.primaryDim
    Text(label, style = PyType.mono(14f), color = fg, textAlign = TextAlign.Center, letterSpacing = 1.sp,
        modifier = Modifier.fillMaxWidth()
            .background(if (enabled) fill else Color.Transparent)
            .border(1.dp, if (enabled) fill else PyTheme.primaryDim, RectangleShape)
            .clickableNoRipple { if (enabled) onClick() }.padding(vertical = 12.dp))
}
