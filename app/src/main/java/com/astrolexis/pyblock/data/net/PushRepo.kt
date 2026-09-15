package com.astrolexis.pyblock.data.net

import android.content.Context
import com.astrolexis.pyblock.data.blake.BlakeApi
import com.astrolexis.pyblock.data.model.DeviceRegPush
import com.astrolexis.pyblock.data.nostr.Nostr
import com.astrolexis.pyblock.data.store.DeviceStore
import com.astrolexis.pyblock.data.wallet.WalletStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Registers the UnifiedPush delivery endpoint with the server, HMAC-signed by
 *  the existing anonymous device so the endpoint attaches to *that* device
 *  (same account as entitlements/orders). Server POSTs pushes to the endpoint. */
object PushRepo {
    private const val URL = "https://pyblock.xyz:8443/api/devices.php"
    private const val PATH = "/api/devices.php"
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val mediaType = "application/json".toMediaType()
    private const val PREFS = "pyblock_push"
    private const val KEY_ENDPOINT = "endpoint"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun registerEndpoint(ctx: Context, endpoint: String): Boolean =
        withContext(Dispatchers.IO) {
            if (endpoint.isBlank()) return@withContext false
            val creds = DeviceStore.credentials() ?: return@withContext false
            val npub = runCatching { Nostr.pubkeyHex(ctx) }.getOrDefault("")
            val bytes = json.encodeToString(DeviceRegPush(endpoint = endpoint, nostrPubkey = npub,
                marks = Nostr.showMarks(ctx))).toByteArray()
            val s = HmacSigner.sign("POST", PATH, bytes, creds.second)
            val req = Request.Builder()
                .url(URL)
                .post(bytes.toRequestBody(mediaType))
                .addHeader("Content-Type", "application/json")
                .addHeader("X-PyBLOCK-Device-Id", creds.first.toString())
                .addHeader("X-PyBLOCK-Timestamp", s.ts)
                .addHeader("X-PyBLOCK-Nonce", s.nonce)
                .addHeader("X-PyBLOCK-Signature", s.sig)
                .build()
            runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
        }

    // ---- Which addresses the server should watch ----
    //
    // Registering the delivery endpoint only tells the server WHERE to push. It still has to be
    // told WHAT to watch, and nothing in this app ever told it: BlakeApi.registerPush existed and
    // was never called, so no payment to a BLAKE2b address has ever produced a push here. iOS has
    // done this since the wallet shipped (PushManager.syncAddresses, on every wallet change), which
    // is why a payment lands on one platform with a notification and on the other in silence.

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Keep the delivery endpoint so the address list can be re-sent without waiting for the
     *  distributor to hand it to us again. */
    fun rememberEndpoint(ctx: Context, endpoint: String) {
        if (endpoint.isNotBlank()) prefs(ctx).edit().putString(KEY_ENDPOINT, endpoint).apply()
    }

    /** (Re)register the current wallet addresses. Call whenever the wallet set changes and on
     *  foreground; the server replaces the list, so re-sending it is how a removed address stops
     *  being watched. */
    suspend fun syncAddresses(ctx: Context) {
        val endpoint = prefs(ctx).getString(KEY_ENDPOINT, null)?.takeIf { it.isNotBlank() } ?: return
        WalletStore.ensureLoaded(ctx)
        val addrs = WalletStore.wallets.value.map { it.address }.filter { it.isNotBlank() }.distinct()
        if (addrs.isEmpty()) return
        registerPushSigned(ctx, endpoint, addrs)
    }

    /**
     * The wallet addresses that belong to this device, for the server to tie mining to the chat
     * key. Replaces the list; ten at most. The runes beside a name are computed from these, and the
     * server found that almost no device had ever sent any — the push registration above carries
     * addresses too, but into a different table.
     */
    suspend fun syncDeviceAddresses(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val creds = DeviceStore.credentials() ?: return@withContext false
        WalletStore.ensureLoaded(ctx)
        val addrs = WalletStore.wallets.value.map { it.address }.filter { it.isNotBlank() }.distinct().take(10)
        if (addrs.isEmpty()) return@withContext false
        val bytes = org.json.JSONObject().put("addresses", org.json.JSONArray(addrs)).toString().toByteArray()
        val path = "/api/app/addresses.php"
        val s = HmacSigner.sign("POST", path, bytes, creds.second)
        val req = Request.Builder()
            .url("https://pyblock.xyz:8443$path")
            .post(bytes.toRequestBody(mediaType))
            .addHeader("Content-Type", "application/json")
            .addHeader("X-PyBLOCK-Device-Id", creds.first.toString())
            .addHeader("X-PyBLOCK-Timestamp", s.ts)
            .addHeader("X-PyBLOCK-Nonce", s.nonce)
            .addHeader("X-PyBLOCK-Signature", s.sig)
            .build()
        runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /**
     * The push registration, signed by the device. It was born unsigned, with no reference to the
     * device account — and the tier lives in the account. Without the signature the server cannot
     * prove the person asking for a rig alert is PRO, so it sends nothing; its reply says
     * `device_linked:false` when that happens. Same body BlakeApi.registerPush sends.
     */
    private suspend fun registerPushSigned(ctx: Context, endpoint: String, addrs: List<String>): Boolean = withContext(Dispatchers.IO) {
        val creds = DeviceStore.credentials() ?: return@withContext BlakeApi.registerPush(endpoint, addrs, rigQuiet = Nostr.rigAlerts(ctx)).let { true }
        val body = org.json.JSONObject()
            .put("endpoint", endpoint).put("addresses", org.json.JSONArray(addrs))
            .put("platform", "android").put("push_provider", "unifiedpush")
            .put("bundle", "com.astrolexis.pyblockblake2b")
            .put("preferences", org.json.JSONObject().put("rig_quiet", Nostr.rigAlerts(ctx)))
            .toString().toByteArray()
        val path = "/api/app/push/register.php"
        val s = HmacSigner.sign("POST", path, body, creds.second)
        val req = Request.Builder()
            .url("https://pyblock.xyz:8443$path?chain=blake2b")
            .post(body.toRequestBody(mediaType))
            .addHeader("Content-Type", "application/json")
            .addHeader("X-PyBLOCK-Device-Id", creds.first.toString())
            .addHeader("X-PyBLOCK-Timestamp", s.ts)
            .addHeader("X-PyBLOCK-Nonce", s.nonce)
            .addHeader("X-PyBLOCK-Signature", s.sig)
            .build()
        runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** Fire-and-forget, for callers with no scope of their own (WalletStore.add/remove). */
    fun syncAddressesAsync(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { syncAddresses(app) }; runCatching { syncDeviceAddresses(app) } }
    }
}
