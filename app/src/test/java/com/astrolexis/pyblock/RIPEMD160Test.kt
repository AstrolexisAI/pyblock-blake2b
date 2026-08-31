package com.astrolexis.pyblock

import com.astrolexis.pyblock.data.crypto.RIPEMD160
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/** JVM verification of the hand-written RIPEMD160 + Base58Check port (no device
 *  needed). secp256k1 is the vetted native lib; verified on iOS end-to-end. */
class RIPEMD160Test {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // Bitcoin Base58 (duplicated here to test without loading the secp256k1 class).
    private val b58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray()
    private fun base58(bytes: ByteArray): String {
        val digits = ArrayList<Int>()
        for (b in bytes) {
            var carry = b.toInt() and 0xff
            for (i in digits.indices) { carry += digits[i] shl 8; digits[i] = carry % 58; carry /= 58 }
            while (carry > 0) { digits.add(carry % 58); carry /= 58 }
        }
        val sb = StringBuilder()
        for (b in bytes) { if (b.toInt() == 0) sb.append('1') else break }
        for (i in digits.indices.reversed()) sb.append(b58[digits[i]])
        return sb.toString()
    }
    private fun sha256(x: ByteArray) = MessageDigest.getInstance("SHA-256").digest(x)

    @Test fun ripemd160_vectors() {
        assertEquals("9c1185a5c5e9fc54612808977ee8f548b2258d31", hex(RIPEMD160.hash(ByteArray(0))))
        assertEquals("8eb208f7e05d987a9b044a8e98c6b087f15a0bfc", hex(RIPEMD160.hash("abc".toByteArray())))
        // longer than one block
        assertEquals("52783243c1697bdbe16d37f97f68f08325dc1528",
            hex(RIPEMD160.hash("a".repeat(1000000).toByteArray())))
    }

    @Test fun base58check_address_vector() {
        // Bitcoin wiki: hash160 010966776006953D5567439E5E39F86A0D273BEE → 16UwLL9Risc3QfPqBUvKofHmBQ7wMtjvM
        val h160 = byteArrayOf(
            0x01, 0x09, 0x66, 0x77, 0x60, 0x06, 0x95.toByte(), 0x3D, 0x55, 0x67,
            0x43, 0x9E.toByte(), 0x5E, 0x39, 0xF8.toByte(), 0x6A, 0x0D, 0x27, 0x3B, 0xEE.toByte())
        val payload = byteArrayOf(0x00) + h160
        val checksum = sha256(sha256(payload)).copyOfRange(0, 4)
        assertEquals("16UwLL9Risc3QfPqBUvKofHmBQ7wMtjvM", base58(payload + checksum))
    }
}
