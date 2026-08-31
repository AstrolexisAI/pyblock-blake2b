package com.astrolexis.pyblock.ui.screens.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.net.ProRepo
import com.astrolexis.pyblock.data.store.EntitlementsStore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.moduleFrame
import com.astrolexis.pyblock.ui.theme.neonText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Cross-device entitlements: one purchase, all your devices.
 *  The device that owns the tier shows a short code; the other claims it. */
@Composable
fun LinkedDevicesSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var remaining by remember { mutableIntStateOf(0) }
    var entering by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf<com.astrolexis.pyblock.data.model.AccountStatus?>(null) }

    suspend fun refreshGroup() { group = ProRepo.accountStatus() }
    LaunchedEffect(Unit) { refreshGroup() }

    // Countdown for the displayed code.
    LaunchedEffect(code) {
        while (code != null && remaining > 0) { delay(1_000); remaining -= 1 }
        if (remaining <= 0) code = null
    }

    Text(stringResource(R.string.linked_devices_header), style = PyType.mono(11f), color = PyTheme.primaryDim, letterSpacing = 2.sp)
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier.fillMaxWidth().moduleFrame(PyTheme.primary).padding(14.dp),
    ) {
        Text(stringResource(R.string.linked_intro),
            style = PyType.mono(11f), color = PyTheme.cyan.copy(alpha = 0.8f))
        Spacer(Modifier.height(12.dp))

        // Devices sharing this account (when the group has >1).
        group?.takeIf { it.linked && it.devices.size > 1 }?.let { g ->
            val anchorId = g.anchorId
            g.devices.forEach { d ->
                val isAnchor = d.id == anchorId
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            buildString {
                                append(d.platform.replaceFirstChar { it.uppercase() })
                                if (d.thisDevice) append(stringResource(R.string.linked_this_device_suffix))
                                if (isAnchor) append(stringResource(R.string.linked_owner_suffix))
                            },
                            style = PyType.mono(12f), color = if (d.thisDevice) PyTheme.cyan else PyTheme.primary,
                        )
                        Text("#${d.id}", style = PyType.mono(9f), color = PyTheme.primaryDim)
                    }
                    // Anchor can't be removed while others depend on it (owner_cannot_leave).
                    if (!isAnchor) {
                        Text(stringResource(R.string.linked_unlink), style = PyType.mono(11f), color = PyTheme.danger,
                            modifier = Modifier.clickableNoRipple {
                                Haptics.warning()
                                scope.launch {
                                    busy = true; status = null
                                    val r = ProRepo.unlinkDevice(if (d.thisDevice) null else d.id)
                                    status = when {
                                        r?.ok == true -> { refreshGroup(); EntitlementsStore.refresh(); ctx.getString(R.string.linked_status_unlinked) }
                                        r != null -> ctx.getString(R.string.linked_status_error, (r.error ?: "failed"))
                                        else -> ctx.getString(R.string.linked_status_offline)
                                    }
                                    busy = false
                                }
                            }.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        code?.let { c ->
            Text(c.chunked(3).joinToString("-"), style = PyType.mono(30f).neonText(PyTheme.yellow), color = PyTheme.yellow,
                letterSpacing = 4.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.linked_code_expires, remaining),
                style = PyType.mono(10f), color = PyTheme.primaryDim,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }

        if (entering) {
            Box(Modifier.fillMaxWidth().border(1.dp, PyTheme.primary.copy(alpha = 0.5f), RectangleShape).padding(10.dp)) {
                if (draft.isEmpty()) Text(stringResource(R.string.linked_code_placeholder), style = PyType.mono(13f), color = PyTheme.primaryDim)
                BasicTextField(draft, { draft = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(6) },
                    textStyle = PyType.mono(18f).copy(color = PyTheme.cyan, letterSpacing = 4.sp),
                    cursorBrush = SolidColor(PyTheme.cyan), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkBtn(if (code == null) stringResource(R.string.linked_btn_show_code) else stringResource(R.string.linked_btn_new_code), enabled = !busy, Modifier.weight(1f).fillMaxHeight()) {
                Haptics.thock()
                scope.launch {
                    busy = true; status = null
                    val r = ProRepo.linkStart()
                    when {
                        r?.ok == true -> { code = r.code; remaining = r.expiresIn }
                        r != null -> status = ctx.getString(R.string.linked_status_error, (r.error ?: "server error"))
                        else -> status = ctx.getString(R.string.linked_status_offline)
                    }
                    busy = false
                }
            }
            if (!entering) {
                LinkBtn(stringResource(R.string.linked_btn_enter_code), enabled = !busy, Modifier.weight(1f).fillMaxHeight()) { Haptics.tap(); entering = true }
            } else {
                LinkBtn(stringResource(R.string.linked_btn_link), enabled = !busy && draft.length == 6, Modifier.weight(1f).fillMaxHeight()) {
                    Haptics.thock()
                    scope.launch {
                        busy = true; status = null
                        val r = ProRepo.linkClaim(draft)
                        when {
                            r?.ok == true -> {
                                status = ctx.getString(R.string.linked_status_linked, r.devices)
                                entering = false; draft = ""
                                EntitlementsStore.refresh()
                                refreshGroup()
                            }
                            r != null -> status = ctx.getString(R.string.linked_status_error, (r.error ?: "rejected"))
                            else -> status = ctx.getString(R.string.linked_status_offline)
                        }
                        busy = false
                    }
                }
            }
        }

        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = PyType.mono(11f),
                color = if (it.startsWith("✓")) PyTheme.primary else PyTheme.danger)
        }
    }
}

@Composable
private fun LinkBtn(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(label, style = PyType.mono(12f), textAlign = TextAlign.Center,
        color = if (enabled) PyTheme.cyan else PyTheme.primaryDim,
        modifier = modifier.border(1.dp, if (enabled) PyTheme.cyan else PyTheme.primaryDim, RectangleShape)
            .clickableNoRipple { if (enabled) onClick() }.padding(vertical = 10.dp))
}
