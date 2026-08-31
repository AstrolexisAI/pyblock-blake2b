package com.astrolexis.pyblock.data.store

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Watches the device thermal status so the app can auto-calm its arcade
 *  animations when the phone gets hot (>= SEVERE). ANDed into the arcade gate
 *  (see [com.astrolexis.pyblock.ui.theme.arcadeFx]) so every effect stops until
 *  the device cools, then resumes on its own. A wallet shouldn't cook a phone. */
object ThermalStore {
    var isHot by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return   // thermal API is API 29+
        val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        runCatching {
            isHot = pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            pm.addThermalStatusListener { status -> isHot = status >= PowerManager.THERMAL_STATUS_SEVERE }
        }
    }
}
