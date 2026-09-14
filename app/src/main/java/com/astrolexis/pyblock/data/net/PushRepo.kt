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
        BlakeApi.registerPush(endpoint, addrs)
    }

    /** Fire-and-forget, for callers with no scope of their own (WalletStore.add/remove). */
    fun syncAddressesAsync(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { syncAddresses(app) } }
    }
}
