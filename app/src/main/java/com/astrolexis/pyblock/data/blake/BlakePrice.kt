package com.astrolexis.pyblock.data.blake

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fiat conversion for the fork coin. Exchanges list BLAKE2b Bitcoin as XBT (NonKYC's pair is
 * BTCB2_USDT); the coin has its own market, so it is NEVER priced at the BTC rate. The USD quote
 * comes from NonKYC; other currencies are crossed through mempool.space's USD-relative rates.
 * No invented numbers: [fiatLabel] returns null until a real XBT quote is loaded. Mirrors iOS.
 */
object BlakePrice {
    const val SOURCE = "NonKYC"
    const val TICKER = "XBT"

    /** XBT/USD last trade on NonKYC (null until fetched). */
    private val _xbtUsd = MutableStateFlow<Double?>(null)
    val xbtUsd: StateFlow<Double?> = _xbtUsd.asStateFlow()
    /** XBT/BTC last trade — cross-check pair, context only. */
    private val _xbtBtc = MutableStateFlow<Double?>(null)
    val xbtBtc: StateFlow<Double?> = _xbtBtc.asStateFlow()
    /** 24h change in percent from the exchange. */
    private val _change24h = MutableStateFlow<Double?>(null)
    val change24h: StateFlow<Double?> = _change24h.asStateFlow()
    /** BTC-denominated fiat rates (mempool.space), used ONLY as USD→ccy cross rates. */
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

    /** USD pinned first (the native quote), rest alphabetical among those with a cross rate. */
    fun available(): List<String> = listOf("USD") + _rates.value.keys.filter { it != "USD" }.sorted()

    /** XBT price of one coin in the selected currency, or null when unknown. */
    fun rate(): Double? {
        val u = _xbtUsd.value ?: return null
        if (u <= 0) return null
        val ccy = _currency.value
        if (ccy == "USD") return u
        val r = _rates.value[ccy] ?: return null
        val usd = _rates.value["USD"] ?: return null
        if (r <= 0 || usd <= 0) return null
        return u * r / usd
    }

    suspend fun refresh() {
        if (System.currentTimeMillis() - fetchedAtMs < 300_000 && _xbtUsd.value != null) return
        withContext(Dispatchers.IO) {
            coroutineScope {
                val usdPair = async { nonkyc("BTCB2_USDT") }
                val btcPair = async { nonkyc("BTCB2_BTC") }
                val cross = async { mempoolRates() }
                usdPair.await()?.let { (last, change) ->
                    _xbtUsd.value = last; _change24h.value = change; fetchedAtMs = System.currentTimeMillis()
                }
                btcPair.await()?.let { (last, _) -> _xbtBtc.value = last }
                cross.await().let { if (it.isNotEmpty()) _rates.value = it }
                // A failed fetch keeps the prior quote — never a fake.
            }
        }
    }

    /** last_price + change_percent from a NonKYC ticker, or null on any failure. */
    private fun nonkyc(market: String): Pair<Double, Double?>? = try {
        val req = Request.Builder().url("https://api.nonkyc.io/api/v2/ticker/$market").get().build()
        client.newCall(req).execute().use { resp ->
            val obj = JSONObject(resp.body?.string() ?: return null)
            val last = obj.optString("last_price").toDoubleOrNull() ?: return null
            if (last <= 0) return null
            Pair(last, obj.optString("change_percent").toDoubleOrNull())
        }
    } catch (e: Exception) { null }

    private fun mempoolRates(): Map<String, Double> = try {
        val req = Request.Builder().url("https://mempool.space/api/v1/prices").get().build()
        client.newCall(req).execute().use { resp ->
            val obj = JSONObject(resp.body?.string() ?: return emptyMap())
            val r = HashMap<String, Double>()
            for (k in obj.keys()) {
                if (k == "time") continue
                val d = obj.optDouble(k, -1.0)
                if (d > 0) r[k] = d          // drop sentinel/broken rates
            }
            r
        }
    } catch (e: Exception) { emptyMap() }

    private fun grouped(v: Double, decimals: Int): String {
        val sym = DecimalFormatSymbols(Locale.US)
        val pattern = if (decimals == 0) "#,##0" else "#,##0." + "0".repeat(decimals)
        return DecimalFormat(pattern, sym).format(v)
    }

    /** Numeric fiat value for a sat amount, or null if no XBT quote. */
    fun fiat(sats: Long): Double? = rate()?.let { sats / 100_000_000.0 * it }

    /** "≈ $12.34" in the selected currency, or null when no XBT quote is loaded. */
    fun fiatLabel(sats: Long): String? {
        val v = fiat(sats) ?: return null
        return "≈ ${symbol(_currency.value)}${grouped(v, if (v >= 1000) 0 else 2)}"
    }

    /** "1 XBT = $167.83" for the quote line under the balance, or null when unknown. */
    fun quoteLabel(): String? {
        val r = rate() ?: return null
        return "1 $TICKER = ${symbol(_currency.value)}${grouped(r, if (r >= 1000) 0 else 2)}"
    }
}
