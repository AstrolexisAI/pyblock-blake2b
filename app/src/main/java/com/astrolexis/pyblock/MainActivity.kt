package com.astrolexis.pyblock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.astrolexis.pyblock.data.store.EntitlementsStore
import com.astrolexis.pyblock.data.store.ThemeStore
import com.astrolexis.pyblock.push.ChatLaunch
import com.astrolexis.pyblock.push.DmPushService
import com.astrolexis.pyblock.push.PushManager
import com.astrolexis.pyblock.ui.blake.BlakeRootScaffold
import com.astrolexis.pyblock.ui.theme.PyBlockTheme

class MainActivity : ComponentActivity() {
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startPushTransports() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        com.astrolexis.pyblock.data.store.LocaleStore.init(this)
        com.astrolexis.pyblock.data.store.PayJoinFeature.init(this)   // Collaborative Send gate (OFF by default)
        com.astrolexis.pyblock.ui.Haptics.init(this)
        com.astrolexis.pyblock.ui.Sfx.init(this)
        setupPush()
        if (intent?.getBooleanExtra(ChatLaunch.EXTRA_OPEN_CHAT, false) == true) ChatLaunch.request()
        setContent {
            LaunchedEffect(Unit) {
                EntitlementsStore.refresh()   // device account (or linked wallet)
                ThemeStore.enforce(EntitlementsStore.isWhale)
            }
            // The whole UI is set in fixed mono sizes, like a terminal. A system font scale of
            // 1.3+ (large text on Samsung) wrapped tab labels, headers and KPI rows into each other.
            // Text still grows a little with the setting, but no further than the layouts allow.
            val d = androidx.compose.ui.platform.LocalDensity.current
            // iOS sets these sizes in points and ignores Dynamic Type; same here, so both apps
            // read the same. Narrow screens scale the whole type down instead (Blake.fit).
            com.astrolexis.pyblock.ui.blake.Blake.fit = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp / 400f).coerceIn(0.85f, 1f)
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 1f)
            ) {
            com.astrolexis.pyblock.ui.theme.LocalizedApp {
                PyBlockTheme(paletteId = ThemeStore.effectivePaletteId) {
                    // Sober BLAKE2b look — no arcade CRT boot/scanlines.
                    BlakeRootScaffold()
                }
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(ChatLaunch.EXTRA_OPEN_CHAT, false) == true) ChatLaunch.request()
    }

    override fun onResume() { super.onResume(); DmPushService.appVisible = true }
    override fun onPause() { super.onPause(); DmPushService.appVisible = false }

    /** Register push transports (asks notification permission on 13+ first). */
    private fun setupPush() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startPushTransports()
        }
    }

    private fun startPushTransports() {
        // Server push (blocks/payouts) via UnifiedPush when a distributor exists.
        PushManager.register(this)
        // DMs are handled natively — our own relay link, no third parties.
        DmPushService.start(this)
        requestBatteryExemptionOnce()
    }

    /** One-time system prompt so the relay link survives Doze/vendor freezers.
     *  Sideload distribution — no store policy constraints on this intent. */
    private fun requestBatteryExemptionOnce() {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("pyblock_dm_push", MODE_PRIVATE)
        if (prefs.getBoolean("asked_battery", false)) return
        prefs.edit().putBoolean("asked_battery", true).apply()
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName")),
            )
        }
    }
}
