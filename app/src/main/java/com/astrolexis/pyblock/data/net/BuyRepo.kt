package com.astrolexis.pyblock.data.net

import com.astrolexis.pyblock.data.model.NHOrderReq
import com.astrolexis.pyblock.data.model.OrderCreateResponse
import com.astrolexis.pyblock.data.model.OrdersListResponse
import com.astrolexis.pyblock.data.store.DeviceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Package order creation — POST /api/app/hashrate_order.php with HMAC auth
 *  (provider-neutral path). Self-heals on 403 by re-registering once. */
object BuyRepo {
    private const val URL = "https://pyblock.xyz:8443/api/app/hashrate_order.php"
    private const val PATH = "/api/app/hashrate_order.php"
    private const val DEV_URL = "https://pyblock.xyz:8443/api/app/orders_by_device.php"
    private const val DEV_PATH = "/api/app/orders_by_device.php"
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val mediaType = "application/json".toMediaType()

    suspend fun createOrder(
        btcAddress: String,
        port: Int,
        hashratePhs: Double,
        hours: Double,
    ): OrderCreateResponse = withContext(Dispatchers.IO) {
        val bytes = json.encodeToString(NHOrderReq(btcAddress, port, hashratePhs, hours)).toByteArray()

        var creds = DeviceStore.credentials()
            ?: return@withContext OrderCreateResponse(ok = false, errors = listOf("device registration failed"))
        var result = send(bytes, creds)
        if (result == null) {
            creds = DeviceStore.credentials(forceNew = true)
                ?: return@withContext OrderCreateResponse(ok = false, errors = listOf("device registration failed"))
            result = send(bytes, creds)
        }
        result ?: OrderCreateResponse(ok = false, errors = listOf("authentication failed"))
    }

    /** Every order placed by THIS device (HMAC), so a buyer sees their order even
     *  for an address they don't track. Self-heals on 403 once. */
    suspend fun ordersByDevice(): OrdersListResponse = withContext(Dispatchers.IO) {
        var creds = DeviceStore.credentials() ?: return@withContext OrdersListResponse(ok = false)
        var result = sendDeviceOrders(creds)
        if (result == null) {
            creds = DeviceStore.credentials(forceNew = true) ?: return@withContext OrdersListResponse(ok = false)
            result = sendDeviceOrders(creds)
        }
        result ?: OrdersListResponse(ok = false)
    }

    /** null on 403 (auth stale) to trigger retry. */
    private fun sendDeviceOrders(creds: Pair<Int, String>): OrdersListResponse? {
        val s = HmacSigner.sign("GET", DEV_PATH, ByteArray(0), creds.second)
        val req = Request.Builder().url(DEV_URL).get()
            .addHeader("X-PyBLOCK-Device-Id", creds.first.toString())
            .addHeader("X-PyBLOCK-Timestamp", s.ts)
            .addHeader("X-PyBLOCK-Nonce", s.nonce)
            .addHeader("X-PyBLOCK-Signature", s.sig)
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (r.code == 403) return null
                val txt = r.body?.string().orEmpty()
                runCatching { json.decodeFromString<OrdersListResponse>(txt) }.getOrNull()
            }
        } catch (e: java.io.IOException) { OrdersListResponse(ok = false) }
    }

    /** null on 403 (auth stale) to trigger retry. */
    private fun send(bytes: ByteArray, creds: Pair<Int, String>): OrderCreateResponse? {
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
        return try {
            client.newCall(req).execute().use { r ->
                if (r.code == 403) return null
                val txt = r.body?.string().orEmpty()
                runCatching { json.decodeFromString<OrderCreateResponse>(txt) }
                    .getOrElse { OrderCreateResponse(ok = false, errors = listOf("bad server response")) }
            }
        } catch (e: java.io.IOException) {
            OrderCreateResponse(ok = false, errors = listOf("network unavailable"))
        }
    }
}
