package com.astrolexis.pyblock.data.crypto

import android.util.Base64
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest

/**
 * Bitcoin "Signed Message" (legacy Electrum / Bitcoin-Core scheme) for our P2PKH
 * vanity + PayNym addresses. Prove control of an address by signing arbitrary
 * text, and verify a signature someone else produced — parity with Bither /
 * Electrum / Sparrow. Byte-for-byte mirror of the iOS `MessageSign.swift`.
 *
 * Format: `dsha256( varint(len(magic)) ‖ "Bitcoin Signed Message:\n" ‖ varint(len(msg)) ‖ msg )`
 * signed with recoverable ECDSA. Output = base64 of `header(27+recid+4·compressed) ‖ r(32) ‖ s(32)`.
 */
object MessageSign {
    private val secp: Secp256k1 by lazy { Secp256k1.get() }
    private val MAGIC = "Bitcoin Signed Message:\n".toByteArray(Charsets.UTF_8)   // 24 bytes

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun dsha256(b: ByteArray): ByteArray = sha256(sha256(b))

    private fun varint(n: Int): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        n <= 0xffff -> byteArrayOf(0xfd.toByte(), (n and 0xff).toByte(), ((n shr 8) and 0xff).toByte())
        else -> byteArrayOf(0xfe.toByte(), (n and 0xff).toByte(), ((n shr 8) and 0xff).toByte(),
            ((n shr 16) and 0xff).toByte(), ((n shr 24) and 0xff).toByte())
    }

    private fun framedHash(message: String): ByteArray {
        val m = message.toByteArray(Charsets.UTF_8)
        val ser = varint(MAGIC.size) + MAGIC + varint(m.size) + m
        return dsha256(ser)
    }

    /** Sign [message] with a WIF private key → base64 signature (null on bad WIF). */
    fun sign(message: String, wif: String): String? {
        val (priv, compressed) = VanityCrypto.decodeWif(wif) ?: return null
        val hash = framedHash(message)
        val sig = try { secp.sign(hash, priv) } catch (e: Exception) { return null }   // 64-byte compact, low-S
        // ACINQ has no recoverable sign — find the recovery id that reproduces our pubkey.
        // Compare in compressed form so it's agnostic to how recover serializes the key.
        val myPub = try { secp.pubKeyCompress(secp.pubkeyCreate(priv)) } catch (e: Exception) { return null }
        var recid = -1
        for (r in 0..3) {
            val rec = try { secp.pubKeyCompress(secp.ecdsaRecover(sig, hash, r)) } catch (e: Exception) { continue }
            if (rec.contentEquals(myPub)) { recid = r; break }
        }
        if (recid < 0) return null
        val header = (27 + recid + if (compressed) 4 else 0).toByte()
        return Base64.encodeToString(byteArrayOf(header) + sig, Base64.NO_WRAP)
    }

    /** Verify a base64 [signature] claims [address] (P2PKH "1…") signed [message]. */
    fun verify(address: String, message: String, signature: String): Boolean {
        val raw = try { Base64.decode(signature.trim(), Base64.DEFAULT) } catch (e: Exception) { return false }
        if (raw.size != 65) return false
        val header = raw[0].toInt() and 0xff
        if (header < 27 || header > 34) return false          // legacy P2PKH signed-message headers
        val recid = (header - 27) and 3
        val compressed = ((header - 27) and 4) != 0
        val compact = raw.copyOfRange(1, 65)
        val hash = framedHash(message)
        val pub = try { secp.ecdsaRecover(compact, hash, recid) } catch (e: Exception) { return false }
        val pubForAddr = if (compressed) (try { secp.pubKeyCompress(pub) } catch (e: Exception) { return false }) else pub
        return VanityCrypto.p2pkhAddress(pubForAddr) == address
    }

    /**
     * Byte-for-byte interop check against an independent (pure-Python, RFC6979-verified)
     * reference vector — proves our SIGN is externally valid and our VERIFY accepts foreign
     * signatures. Must produce the SAME bytes as the iOS self-test.
     */
    fun selfTest(): Boolean {
        val wif = "KwntMbt59tTsj8xqpqYqRRWufyjGunvhSyeMo3NTYpFYzZbXJ5Hp"   // priv 0x11…11, compressed
        val addr = "1Q1pE5vPGEEMqRcVRMbtBK842Y6Pzo6nK9"
        val msg = "PyBLOCK signed message test"
        val expected = "H9jfYylNACE+6Z1H2u7241rcH0FK/k45voZMBfwW1IfIXtTyqDNdrMMpoCzZHo7AOh5HsOnCXRVBxlB+ZdcgX1w="
        if (sign(msg, wif) != expected) return false                       // deterministic (RFC6979) match
        if (!verify(addr, msg, expected)) return false
        if (verify(addr, "$msg!", expected)) return false                  // tamper rejected
        return true
    }
}
