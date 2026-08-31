package com.astrolexis.pyblock.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.astrolexis.pyblock.MainActivity
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.nostr.Nostr
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/** PyBLØCK-native DM push — no Google, no third-party distributor.
 *
 *  Keeps ONE lightweight WebSocket to our own relay subscribed to kind-4
 *  events addressed to this device's chat identity, and raises the local
 *  notification itself. Nothing leaves the device: the server never learns
 *  when (or whether) we got a DM, and decryption happens locally.
 *
 *  Runs as a `remoteMessaging` foreground service (the type messaging apps
 *  use) with a minimized persistent notification, reconnect backoff and a
 *  75s protocol ping. Battery cost is one idle TLS socket — same class of
 *  footprint as any sovereign messenger. */
class DmPushService : Service() {

    companion object {
        private const val FG_ID = 0x50595053   // "PYPS"
        private const val DM_NOTIF_ID = 0x504d
        private const val LINK_CHANNEL = "pyblock.link"
        private const val CHAT_CHANNEL = "pyblock.chat"
        private const val PREFS = "pyblock_dm_push"
        private const val KEY_SINCE = "since"

        /** App-visibility flag — MainActivity flips it so we don't notify
         *  while the user is looking at the app. */
        @Volatile var appVisible = false

        fun start(ctx: Context) {
            val i = Intent(ctx, DmPushService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(75, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var ws: WebSocket? = null
    private var retries = 0
    private var destroyed = false
    private lateinit var myPubkey: String

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        myPubkey = runCatching { Nostr.pubkeyHex(this) }.getOrDefault("")
        ensureChannels()
        startForeground(FG_ID, linkNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ws == null) connect()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        ws?.cancel()
        super.onDestroy()
    }

    // MARK: relay link

    private fun connect() {
        if (destroyed || myPubkey.isEmpty()) return
        val req = Request.Builder().url("wss://nostr.pyblock.xyz:8443").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retries = 0
                // Only DMs for me, only from now on (persisted watermark, so a
                // reconnect never re-notifies old messages).
                val filter = JSONObject()
                    .put("kinds", JSONArray().put(4))
                    .put("#p", JSONArray().put(myPubkey))
                    .put("since", since())
                webSocket.send(JSONArray().put("REQ").put("dm-push").put(filter).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scheduleReconnect()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = scheduleReconnect()
        })
    }

    private fun scheduleReconnect() {
        if (destroyed) return
        ws = null
        retries += 1
        // 5s → 10 → 20 → … → 5 min, with jitter so clients don't sync up.
        val delayMs = min(5_000L shl min(retries - 1, 6), 300_000L) + Random.nextLong(0, 3_000)
        main.postDelayed({ if (!destroyed && ws == null) connect() }, delayMs)
    }

    private fun handle(text: String) {
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return
        if (arr.length() < 3 || arr.optString(0) != "EVENT") return
        val ev = arr.optJSONObject(2) ?: return
        if (ev.optInt("kind") != 4) return
        val author = ev.optString("pubkey")
        val createdAt = ev.optLong("created_at")
        if (author == myPubkey) { advance(createdAt); return }   // my own echo
        if (createdAt <= since() - 1) return                     // stale replay

        // Decrypt locally to silence protocol markers (read receipts) and to
        // tell a payment receipt apart from a regular message. The plaintext
        // never leaves this function.
        val decoded = runCatching {
            Nostr.decryptDM(this, decodeEvent(ev) ?: return, myPubkey)
        }.getOrNull()
        advance(createdAt)
        val plain = decoded?.second ?: ""
        if (plain.startsWith("pyblock:read?")) return
        if (appVisible) return   // the open app renders it live
        val body = if (plain.startsWith("pyblock:paid?"))
            "⚡ Sats incoming — a payment receipt just arrived"
        else "✉ New encrypted DM"
        notifyDm(body)
    }

    private fun decodeEvent(o: JSONObject): com.astrolexis.pyblock.data.nostr.NostrEvent? {
        return runCatching {
            val tags = mutableListOf<List<String>>()
            val t = o.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until t.length()) {
                val row = t.optJSONArray(i) ?: continue
                tags.add((0 until row.length()).map { row.optString(it) })
            }
            com.astrolexis.pyblock.data.nostr.NostrEvent(
                o.getString("id"), o.getString("pubkey"), o.getLong("created_at"),
                o.getInt("kind"), tags, o.getString("content"), o.getString("sig"))
        }.getOrNull()
    }

    // MARK: watermark

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)
    private fun since(): Long {
        val s = prefs().getLong(KEY_SINCE, 0L)
        if (s > 0) return s
        val now = System.currentTimeMillis() / 1000
        prefs().edit().putLong(KEY_SINCE, now).apply()
        return now
    }

    private fun advance(ts: Long) {
        if (ts > since()) prefs().edit().putLong(KEY_SINCE, ts).apply()
    }

    // MARK: notifications

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(LINK_CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(LINK_CHANNEL, "Relay link", NotificationManager.IMPORTANCE_MIN))
        }
        if (mgr.getNotificationChannel(CHAT_CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHAT_CHANNEL, "Encrypted DMs", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    /** The (minimized, silent) FGS notification Android requires. */
    private fun linkNotification(): Notification =
        NotificationCompat.Builder(this, LINK_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PyBLØCK · sovereign push")
            .setContentText("Connected to your pool's relay")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun notifyDm(body: String) {
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ChatLaunch.EXTRA_OPEN_CHAT, true)
        }
        val pi = PendingIntent.getActivity(this, 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHAT_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PyBLØCK")
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // Fixed id: a burst of DMs collapses into one refreshed notification.
        runCatching { NotificationManagerCompat.from(this).notify(DM_NOTIF_ID, n) }
    }
}
