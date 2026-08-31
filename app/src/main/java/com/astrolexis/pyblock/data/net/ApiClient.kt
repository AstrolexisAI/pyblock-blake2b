package com.astrolexis.pyblock.data.net

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Retrofit instances. pyblock.xyz:8443 serves a valid Let's Encrypt cert,
 *  so no pinning/config is needed. */
object ApiClient {
    const val BASE_URL = "https://pyblock.xyz:8443/"
    // Fallback source for NEXT DIFF ADJ when our own node value is unavailable.
    // mempool.space is the reliable canonical instance (mempool.guide has been
    // returning stalled timing data — bogus block-time averages).
    const val MEMPOOL_URL = "https://mempool.space/"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true // POST @Body (e.g. AnonRegReq) must include default fields
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // wallet_utxos.php runs a UTXO-set scan on the node (`scantxoutset`) — up to a
    // few MINUTES uncached — so calls to it go through this long-timeout client.
    // Everything else stays on the snappy 15 s client above.
    private val slowClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private val converter = json.asConverterFactory("application/json".toMediaType())

    val api: PyblockApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(converter)
            .build()
            .create(PyblockApi::class.java)
    }

    /** Same API surface, long-timeout client — use ONLY for walletUtxos(). */
    val apiSlow: PyblockApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(slowClient)
            .addConverterFactory(converter)
            .build()
            .create(PyblockApi::class.java)
    }

    val mempool: MempoolApi by lazy {
        Retrofit.Builder()
            .baseUrl(MEMPOOL_URL)
            .client(client)
            .addConverterFactory(converter)
            .build()
            .create(MempoolApi::class.java)
    }
}
