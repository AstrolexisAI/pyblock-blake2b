package com.astrolexis.pyblock.data.net

import android.content.Context
import com.astrolexis.pyblock.data.model.DeviceRegPush
import com.astrolexis.pyblock.data.nostr.Nostr
import com.astrolexis.pyblock.data.store.DeviceStore
import kotlinx.coroutines.Dispatchers
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

    suspend fun registerEndpoint(ctx: Context, endpoint: String): Boolean =
        withContext(Dispatchers.IO) {
            if (endpoint.isBlank()) return@withContext false
            val creds = DeviceStore.credentials() ?: return@withContext false
            val npub = runCatching { Nostr.pubkeyHex(ctx) }.getOrDefault("")
            val bytes = json.encodeToString(DeviceRegPush(endpoint = endpoint, nostrPubkey = npub)).toByteArray()
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
}
