package com.astrolexis.pyblock.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the native DM push link after a reboot. Starting a
 *  `remoteMessaging` FGS from BOOT_COMPLETED is allowed on 14+. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching { DmPushService.start(context.applicationContext) }
        }
    }
}
