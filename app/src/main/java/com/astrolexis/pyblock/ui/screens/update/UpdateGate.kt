package com.astrolexis.pyblock.ui.screens.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.astrolexis.pyblock.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.BuildConfig
import com.astrolexis.pyblock.data.model.AndroidVersion
import com.astrolexis.pyblock.data.net.ApiClient
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import com.astrolexis.pyblock.ui.theme.MarqueeTitle
import com.astrolexis.pyblock.ui.theme.PyTheme
import com.astrolexis.pyblock.ui.theme.PyType
import com.astrolexis.pyblock.ui.theme.moduleFrame

/** Sideloaded APKs don't auto-update — poll android_version.php and prompt to
 *  download a newer APK. Forces the update when below min_supported. */
@Composable
fun UpdateGate() {
    var ver by remember { mutableStateOf<AndroidVersion?>(null) }
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        val r = runCatching { ApiClient.api.androidVersion() }.getOrNull() ?: return@LaunchedEffect
        if (r.ok && r.available && r.latestVersionCode > BuildConfig.VERSION_CODE) ver = r
    }

    val v = ver ?: return
    val forced = BuildConfig.VERSION_CODE < v.minSupportedVersionCode

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp)
                .moduleFrame(PyTheme.primary)
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MarqueeTitle(text = stringResource(R.string.update_title))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.update_version, v.latestVersionName), style = PyType.mono(15f), color = PyTheme.primary)
            if (v.changelog.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    Text(v.changelog, style = PyType.mono(12f), color = PyTheme.cyan.copy(alpha = 0.85f))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.update_download), style = PyType.mono(16f), color = PyTheme.primary, textAlign = TextAlign.Center, letterSpacing = 2.sp,
                modifier = Modifier.fillMaxWidth().border(2.dp, PyTheme.primary, RoundedCornerShape(4.dp))
                    .clickableNoRipple { Haptics.thock(); runCatching {
                        val i = Intent(Intent.ACTION_VIEW, Uri.parse(v.apkUrl))
                        val act = com.astrolexis.pyblock.util.findActivity(ctx)
                        if (act != null) act.startActivity(i) else ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } }
                    .padding(vertical = 12.dp),
            )
            if (forced) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.update_unsupported), style = PyType.mono(10f), color = PyTheme.danger, textAlign = TextAlign.Center)
            } else {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.update_later), style = PyType.mono(12f), color = PyTheme.primaryDim, modifier = Modifier.clickableNoRipple { Haptics.tap(); ver = null })
            }
            if (v.sha256.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.update_verify_sha256), style = PyType.mono(9f), color = PyTheme.primaryDim)
                Text(v.sha256, style = PyType.mono(9f), color = PyTheme.primaryDim, textAlign = TextAlign.Center)
            }
        }
    }
}
