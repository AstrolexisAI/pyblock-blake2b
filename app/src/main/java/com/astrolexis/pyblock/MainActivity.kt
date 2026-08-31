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
import com.astrolexis.pyblock.ui.nav.RootScaffold
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
            com.astrolexis.pyblock.ui.theme.LocalizedApp {
                PyBlockTheme(paletteId = ThemeStore.effectivePaletteId) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        RootScaffold()
                        // CRT power-on over the whole cabinet, once per cold launch.
                        var booted by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                        if (!booted) com.astrolexis.pyblock.ui.components.CrtBootSweep { booted = true }
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
