package com.astrolexis.pyblock.push

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush

/** Registers UnifiedPush — the app's only push transport (sovereign, no Google
 *  Play Services / FCM). Needs a distributor (ntfy, …) installed; with none,
 *  this is a silent no-op. */
object PushManager {
    fun register(ctx: Context) {
        runCatching {
            val distributors = UnifiedPush.getDistributors(ctx)
            if (distributors.isEmpty()) return  // no distributor installed → no push
            if (UnifiedPush.getAckDistributor(ctx) == null) {
                UnifiedPush.saveDistributor(ctx, distributors.first())
            }
            UnifiedPush.register(ctx)
        }
    }
}
