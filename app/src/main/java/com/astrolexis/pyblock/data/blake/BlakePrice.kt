package com.astrolexis.pyblock.data.blake

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fiat conversion. BLAKE2b IS Bitcoin, so coins price at the BTC rate — real multi-currency
 * prices from mempool.space (5-min cache). No invented numbers: [fiatLabel] returns null
 * until a real price is loaded. Mirrors iOS `BlakePrice`.
 */
object BlakePrice {
    private val _rates = MutableStateFlow<Map<String, Double>>(emptyMap())
    val rates: StateFlow<Map<String, Double>> = _rates.asStateFlow()

    private val _currency = MutableStateFlow("USD")
    val currency: StateFlow<String> = _currency.asStateFlow()

    private var fetchedAtMs = 0L
    private var prefs: android.content.SharedPreferences? = null
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    private val symbols = mapOf(
        "USD" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "¥", "CNY" to "¥", "BRL" to "R$",
        "AUD" to "A$", "CAD" to "C$", "CHF" to "CHF ", "MXN" to "$", "SGD" to "S$", "HKD" to "HK$",
        "INR" to "₹", "KRW" to "₩", "RUB" to "₽", "TRY" to "₺", "ZAR" to "R", "SEK" to "kr ",
        "NOK" to "kr ", "DKK" to "kr ", "PLN" to "zł ", "THB" to "฿", "ILS" to "₪", "UYU" to "\$U ",
    )

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("pyblockb.price", Context.MODE_PRIVATE)
        _currency.value = prefs?.getString("ccy", "USD") ?: "USD"
    }

    fun setCurrency(c: String) { _currency.value = c; prefs?.edit()?.putString("ccy", c)?.apply() }

    fun symbol(c: String): String = symbols[c] ?: "$c "

    /** USD pinned first, rest alphabetical. */
    fun available(): List<String> = listOf("USD") + _rates.value.keys.filter { it != "USD" }.sorted()

    suspend fun refresh() {
        if (System.currentTimeMillis() - fetchedAtMs < 300_000 && _rates.value.isNotEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("https://mempool.space/api/v1/prices").get().build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use
                    val obj = JSONObject(body)
                    val r = HashMap<String, Double>()
                    for (k in obj.keys()) {
                        if (k == "time") continue
                        val d = obj.optDouble(k, -1.0)
                        if (d > 0) r[k] = d          // drop sentinel/broken rates
                    }
                    if (r.isNotEmpty()) { _rates.value = r; fetchedAtMs = System.currentTimeMillis() }
                }
            } catch (e: Exception) { /* keep prior; no fake numbers */ }
        }
    }

    /** "≈ $78,401" in the selected currency, or null when no price is loaded. */
    /** Fiat conversion is intentionally DISABLED for the BLAKE2b app: the fork's market value is
     *  uncertain, so a BTC-priced fiat figure would mislead. Returning null hides every fiat
     *  display (all call sites are conditional on this). */
    fun fiatLabel(sats: Long): String? = null
}
