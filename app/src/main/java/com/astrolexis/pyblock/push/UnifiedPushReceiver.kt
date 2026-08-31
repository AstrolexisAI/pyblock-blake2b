package com.astrolexis.pyblock.push

import android.content.Context
import com.astrolexis.pyblock.data.net.PushRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** UnifiedPush delivery receiver (sovereign push, no Google Play Services).
 *  Captures the endpoint (→ server) and renders messages. Fails safe — with no
 *  distributor these callbacks never fire. */
class UnifiedPushReceiver : MessagingReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val app = context.applicationContext
        scope.launch { PushRepo.registerEndpoint(app, endpoint.url) }
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        PushNotifier.show(context, message.content)
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {}

    override fun onUnregistered(context: Context, instance: String) {}
}
