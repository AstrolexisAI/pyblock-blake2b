package com.astrolexis.pyblock.data.net

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Builds the canonical signing string + HMAC-SHA256 the PyBLØCK server
 *  expects on authenticated endpoints. Includes a per-request nonce for
 *  replay protection (server dedupes device_id+ts+nonce):
 *    canonical = METHOD\nPATH\nTIMESTAMP\nNONCE\nSHA256(body-hex)
 *    key = the secret's UTF-8 bytes (not hex-decoded).
 *  The `X-PyBLOCK-Nonce` header carries [Signed.nonce]. */
object HmacSigner {
    private val rng = SecureRandom()

    data class Signed(val ts: String, val nonce: String, val sig: String)

    fun sign(method: String, path: String, body: ByteArray, secret: String): Signed {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = ByteArray(16).also { rng.nextBytes(it) }.toHex()
        val hashHex = MessageDigest.getInstance("SHA-256").digest(body).toHex()
        val canonical = "$method\n$path\n$ts\n$nonce\n$hashHex"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sig = mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).toHex()
        return Signed(ts, nonce, sig)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
