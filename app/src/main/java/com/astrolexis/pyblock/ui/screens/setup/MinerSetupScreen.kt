package com.astrolexis.pyblock.ui.screens.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.model.Stratum
import com.astrolexis.pyblock.ui.components.QrCode
import com.astrolexis.pyblock.ui.components.RetroTextField
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.Starfield
import com.astrolexis.pyblock.ui.theme.moduleFrame

enum class Firmware(val label: String, val supportsSV2: Boolean) {
    BITAXE("Bitaxe / AxeOS", true),
    NERDAXE("NerdAxe", true),
    ANTMINER("Antminer (stock)", false),
    WHATSMINER("Whatsminer", false),
}

@Composable
fun MinerSetupScreen() {
    var firmware by remember { mutableStateOf(Firmware.BITAXE) }
    var pool by remember { mutableStateOf(Stratum.Pool.LOTTO) }
    var address by remember { mutableStateOf("") }
    var worker by remember { mutableStateOf("") }

    val sv2Mismatch = pool.needsSV2 && !firmware.supportsSV2
    val addressPlaceholder = stringResource(R.string.setup_username_address_placeholder)
    val username = run {
        val base = address.trim().ifEmpty { addressPlaceholder }
        val w = worker.trim()
        if (w.isEmpty()) base else "$base.$w"
    }

    Box(Modifier.fillMaxSize().background(PyTheme.bg)) {
        Starfield()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
        MarqueeTitle(text = stringResource(R.string.setup_title))
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.setup_subtitle),
            style = PyType.mono(13f), color = PyTheme.cyan,
        )
        Spacer(Modifier.height(16.dp))

        PickerRow(stringResource(R.string.setup_firmware_label), Firmware.entries.map { it.label }, firmware.ordinal) {
            firmware = Firmware.entries[it]
        }
        Spacer(Modifier.height(12.dp))
        PickerRow(stringResource(R.string.setup_pool_label), Stratum.Pool.entries.map { "${it.displayName} · ${it.feeDisplay}" }, pool.ordinal) {
            pool = Stratum.Pool.entries[it]
        }

        if (sv2Mismatch) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.setup_sv2_mismatch, firmware.label, pool.displayName),
                style = PyType.mono(12f), color = PyTheme.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .moduleFrame(PyTheme.danger)
                    .padding(10.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.setup_payout_address_label), style = PyType.mono(11f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        RetroTextField(address, { address = it }, "bc1q...")
        Spacer(Modifier.height(8.dp))
        RetroTextField(worker, { worker = it }, stringResource(R.string.setup_worker_placeholder), color = PyTheme.cyan)

        Spacer(Modifier.height(16.dp))
        ConfigCard(pool, username, firmware, sv2Mismatch)

        if (!sv2Mismatch) {
            Spacer(Modifier.height(16.dp))
            QrCard(pool.url)
        }
        Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PickerRow(label: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Text(label, style = PyType.mono(11f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { i, opt ->
            val active = i == selectedIndex
            Text(
                opt,
                style = PyType.mono(12f),
                color = if (active) PyTheme.yellow else PyTheme.cyan,
                modifier = Modifier
                    .background(if (active) PyTheme.yellow.copy(alpha = 0.1f) else Color.Transparent)
                    .border(1.dp, if (active) PyTheme.yellow else PyTheme.primary.copy(alpha = 0.4f), RectangleShape)
                    .clickableNoRipple { Haptics.select(); onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ConfigCard(pool: Stratum.Pool, username: String, firmware: Firmware, dimmed: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .moduleFrame(PyTheme.primary)
            .padding(12.dp),
    ) {
        Text(stringResource(R.string.setup_paste_into, firmware.label.uppercase()), style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        ConfigRow(stringResource(R.string.setup_config_stratum_url), pool.hostPortURL)
        pool.authorityPubkey?.let {
            ConfigRow(stringResource(R.string.setup_config_authority_pubkey), it)
        }
        ConfigRow(stringResource(R.string.setup_config_user), username)
        ConfigRow(stringResource(R.string.setup_config_password), "x")
        if (pool.authorityPubkey != null) {
            HorizontalDivider(color = PyTheme.primary.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
            ConfigRow(stringResource(R.string.setup_config_combined_string), pool.url)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.setup_workers_appear_note),
            style = PyType.mono(10f), color = PyTheme.primaryDim,
        )
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = PyType.mono(10f), color = PyTheme.cyan.copy(alpha = 0.6f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = PyType.mono(11f),
                color = PyTheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "⧉",
                style = PyType.mono(14f),
                color = PyTheme.magenta,
                modifier = Modifier
                    .clickableNoRipple { Haptics.tap(); clipboard.setText(AnnotatedString(value)) }
                    .padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun QrCard(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .moduleFrame(PyTheme.primary)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.setup_qr_title), style = PyType.mono(11f), color = PyTheme.cyan, letterSpacing = 2.sp)
        Spacer(Modifier.height(10.dp))
        QrCode(text, size = 200.dp)
    }
}
