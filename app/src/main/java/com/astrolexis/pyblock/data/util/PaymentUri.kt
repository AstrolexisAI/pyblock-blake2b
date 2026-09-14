package com.astrolexis.pyblock.data.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * BIP-21 `bitcoin:` URI — the standard, interoperable way to carry an address +
 * optional amount. Used as the payment-request payload inside encrypted DMs, so
 * a request round-trips as a normal (readable) bitcoin: link.
 */
data class PaymentRequest(val address: String, val amountSats: Long?, val label: String?) {
    fun toUri(): String {
        val sb = StringBuilder("bitcoin:").append(address)
        val params = mutableListOf<String>()
        amountSats?.let { params.add("amount=" + PaymentUri.satsToBtc(it)) }
        label?.takeIf { it.isNotBlank() }?.let { params.add("label=" + java.net.URLEncoder.encode(it, "UTF-8")) }
        if (params.isNotEmpty()) sb.append("?").append(params.joinToString("&"))
        return sb.toString()
    }
}

/** A "payment sent" receipt posted back into the DM after a pay/send. */
data class PaymentReceipt(val amountSats: Long?, val txid: String, val from: String? = null) {
    /** [from] is the payer's own payment code. The receiver needs it to derive the address the coins
     *  went to; it rides inside the encrypted DM, private to the two of them, and makes the receipt
     *  self-sufficient even when the payer never advertises the code in their profile. */
    fun toUri(): String {
        var s = "pyblock:paid?txid=$txid"
        amountSats?.let { s += "&amount=$it" }   // sats (internal marker)
        from?.takeIf { it.startsWith("PM") }?.let { s += "&from=$it" }
        return s
    }
}

object PaymentUri {
    fun isReceipt(text: String): Boolean = text.trim().startsWith("pyblock:paid?")
    fun isReadMarker(text: String): Boolean = text.trim().startsWith("pyblock:read?")
    /** A peer handing over their payment code (`pyblock:hello?code=PM…`). Control, never shown. */
    fun isHello(text: String): Boolean = text.trim().startsWith("pyblock:hello?")
    /** Anything that rides as a DM but is not a message. */
    fun isControl(text: String): Boolean = isReadMarker(text) || isHello(text)
    fun helloCode(text: String): String? {
        if (!isHello(text)) return null
        for (kv in text.trim().removePrefix("pyblock:hello?").split("&")) {
            val i = kv.indexOf('='); if (i < 0) continue
            if (kv.substring(0, i) == "code") {
                val code = kv.substring(i + 1)
                return code.takeIf { it.startsWith("PM") && com.astrolexis.pyblock.data.crypto.PaymentCode.decode(it) != null }
            }
        }
        return null
    }

    /** Parse a `pyblock:paid?...` receipt, or null. */
    fun parseReceipt(text: String): PaymentReceipt? {
        val t = text.trim()
        if (!t.startsWith("pyblock:paid?")) return null
        var txid = ""; var amountSats: Long? = null; var from: String? = null
        for (kv in t.removePrefix("pyblock:paid?").split("&")) {
            val i = kv.indexOf('='); if (i < 0) continue
            when (kv.substring(0, i)) {
                "txid" -> txid = kv.substring(i + 1)
                "amount" -> amountSats = kv.substring(i + 1).toLongOrNull()
                "from" -> from = kv.substring(i + 1).takeIf { it.startsWith("PM") }
            }
        }
        return if (txid.isEmpty()) null else PaymentReceipt(amountSats, txid, from)
    }

    /** The payer's payment code carried in a receipt, if it has one and it decodes. */
    fun receiptSender(text: String): String? =
        parseReceipt(text)?.from?.takeIf { com.astrolexis.pyblock.data.crypto.PaymentCode.decode(it) != null }

    /** The `ts` from a `pyblock:read?ts=<n>` marker, or null. */
    fun readMarkerTs(text: String): Long? =
        Regex("ts=(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()

    /** Parse a `bitcoin:` URI, or null if it isn't one. */
    fun parse(text: String): PaymentRequest? {
        val t = text.trim()
        if (!t.startsWith("bitcoin:", ignoreCase = true)) return null
        val body = t.substring("bitcoin:".length)
        val q = body.indexOf('?')
        val address = (if (q >= 0) body.substring(0, q) else body).trim()
        if (address.isEmpty()) return null
        var amountSats: Long? = null
        var label: String? = null
        if (q >= 0) {
            for (kv in body.substring(q + 1).split("&")) {
                val i = kv.indexOf('='); if (i < 0) continue
                val k = kv.substring(0, i); val v = kv.substring(i + 1)
                when (k.lowercase()) {
                    "amount" -> amountSats = btcToSats(v)
                    "label", "message" -> if (label == null) label = urlDecode(v)
                }
            }
        }
        return PaymentRequest(address, amountSats, label)
    }

    fun isPaymentUri(text: String): Boolean = text.trim().startsWith("bitcoin:", ignoreCase = true)

    fun satsToBtc(sats: Long): String =
        BigDecimal(sats).divide(BigDecimal(100_000_000)).setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString()

    private fun btcToSats(btc: String): Long? = try {
        BigDecimal(btc).multiply(BigDecimal(100_000_000)).setScale(0, RoundingMode.DOWN).toLong()
    } catch (e: Exception) { null }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
    private fun urlDecode(s: String): String = try { java.net.URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
}
