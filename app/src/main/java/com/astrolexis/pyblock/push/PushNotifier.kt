package com.astrolexis.pyblock.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.astrolexis.pyblock.MainActivity
import com.astrolexis.pyblock.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/** Renders an incoming push (same payload shape as iOS:
 *  `{ "pyblock": { "kind", "height", "hash" } }`) as a system notification.
 *  Shared by both transports (UnifiedPush + FCM). */
object PushNotifier {
    private const val CHANNEL = "pyblock.blocks"
    private const val WALLET_CHANNEL = "pyblock.wallet"
    private const val CHAT_CHANNEL = "pyblock.chat"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A local notification for a wallet payment (received / confirmed). */
    fun notifyWallet(ctx: Context, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr != null && mgr.getNotificationChannel(WALLET_CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(WALLET_CHANNEL, "Payments", NotificationManager.IMPORTANCE_HIGH),
                )
            }
        }
        val n = NotificationCompat.Builder(ctx, WALLET_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(Random.nextInt(), n) }
    }

    fun show(ctx: Context, raw: ByteArray) = show(ctx, raw.toString(Charsets.UTF_8))

    fun show(ctx: Context, payload: String) {
        val (kind, body) = parse(payload)
        val isDM = kind == "nostr_dm"
        ensureChannel(ctx)
        if (isDM) ensureChatChannel(ctx)
        // Tapping a DM push lands the user in the chat inbox; block pushes
        // just open the app.
        val open = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (isDM) putExtra(ChatLaunch.EXTRA_OPEN_CHAT, true)
        }
        val pi = PendingIntent.getActivity(
            ctx, if (isDM) 1 else 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, if (isDM) CHAT_CHANNEL else CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PyBLØCK")
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(if (isDM) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(Random.nextInt(), n) }
    }

    private fun parse(txt: String): Pair<String?, String> {
        val p = runCatching { json.parseToJsonElement(txt).jsonObject["pyblock"]?.jsonObject }.getOrNull()
        val kind = runCatching { p?.get("kind")?.jsonPrimitive?.content }.getOrNull()
        val height = runCatching { p?.get("height")?.jsonPrimitive?.content }.getOrNull()
        val h = height?.let { " #$it" } ?: ""
        val tier = runCatching { p?.get("tier")?.jsonPrimitive?.content }.getOrNull()
        val wn = runCatching { p?.get("worker_name")?.jsonPrimitive?.content }.getOrNull()
        val worker = wn?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
        val body = when (kind) {
            "lotto_block" -> "🎰 LOTTO found a block$h!"
            "bip110_block" -> "🧊 Clean block$h mined — money, not spam"
            "my_address" -> "⛏ Payout to your address$h"
            // The relay only sees ciphertext, so the push stays generic.
            "nostr_dm" -> "✉ New encrypted DM"
            // Time-based LN subscription about to lapse — 1-tap renew in Settings.
            "sub_renew" -> "⚡ Your ${tier?.uppercase() ?: "PyBLØCK"} is expiring soon — renew in one tap"
            // Rig Watch worker-down alerting.
            "worker_offline" -> "⚠ Rig offline$worker — no shares coming in"
            "worker_recovered" -> "✓ Rig back online$worker"
            else -> "New block$h on the timechain"
        }
        return kind to body
    }

    private fun ensureChatChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHAT_CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHAT_CHANNEL, "Encrypted DMs", NotificationManager.IMPORTANCE_HIGH),
                )
            }
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Blocks & payouts", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
        }
    }
}
