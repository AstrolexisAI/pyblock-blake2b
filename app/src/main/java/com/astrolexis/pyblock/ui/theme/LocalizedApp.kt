package com.astrolexis.pyblock.ui.theme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.astrolexis.pyblock.data.store.LocaleStore

/** Wraps the app in a Context configured to the selected [LocaleStore.lang], so
 *  every `stringResource(...)` reads that language and recomposes live when the
 *  user changes it in-app — no Activity recreation needed. */
@Composable
fun LocalizedApp(content: @Composable () -> Unit) {
    val lang by LocaleStore.lang.collectAsState()
    val base = LocalContext.current
    val localized = remember(lang, base) {
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(LocaleStore.locale(lang))
        // ContextThemeWrapper (NOT createConfigurationContext): keeps the Activity
        // reachable via baseContext, so ctx.startActivity()/findActivity()/FLAG_SECURE
        // still work. createConfigurationContext returns a detached ContextImpl and
        // silently broke share/export (crash) + screenshot protection.
        android.view.ContextThemeWrapper(base, 0).apply { applyOverrideConfiguration(cfg) }
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
    ) { content() }
}
