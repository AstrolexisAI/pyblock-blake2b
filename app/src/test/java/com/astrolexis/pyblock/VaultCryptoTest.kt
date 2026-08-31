package com.astrolexis.pyblock

import com.astrolexis.pyblock.data.crypto.VaultCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-JVM validation of the spending-vault crypto (scrypt + AES-GCM), so the
 *  fund-critical KDF is verified without a device. Vectors: RFC 7914 + hashlib.scrypt. */
class VaultCryptoTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun rfc7914_n16_r1() {
        assertEquals(
            "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
            hex(VaultCrypto.scrypt(ByteArray(0), ByteArray(0), 16, 1, 1, 64)))
    }

    @Test fun rfc7914_password_nacl() {
        assertEquals(
            "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b3731622eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            hex(VaultCrypto.scrypt("password".toByteArray(), "NaCl".toByteArray(), 1024, 8, 16, 64)))
    }

    @Test fun r2_permutation() {
        assertEquals(
            "0227f451f171a74a52117e195e21e0c30e5775a43082a5896a6edb7c501bb7380d6855a5d8ac0e04fc90bdf0902aae2f6b3746964ad1a48a98322ead81227533",
            hex(VaultCrypto.scrypt("pass".toByteArray(), "salt".toByteArray(), 16, 2, 1, 64)))
    }

    @Test fun prodParams() {
        assertEquals(
            "e8757f1018e4a6f30f75d38972fb2162e87035278caec1a9bb4f840087a4af93",
            hex(VaultCrypto.scrypt("correct horse battery staple".toByteArray(), "pyblock-vault-salt".toByteArray(), 16384, 8, 1, 32)))
    }

    @Test fun selfTestPasses() { assertTrue(VaultCrypto.selfTest()) }

    @Test fun vmkWrapRoundTrip() {
        val vmk = VaultCrypto.randomBytes(32)
        val w = VaultCrypto.wrapVMK(vmk, "correct-horse-8")!!
        assertTrue(VaultCrypto.unwrapVMK(w, "correct-horse-8")!!.contentEquals(vmk))
        assertNull(VaultCrypto.unwrapVMK(w, "wrong-password"))
        // serialize round-trip
        val w2 = VaultCrypto.WrappedKey.parse(w.serialize())!!
        assertTrue(VaultCrypto.unwrapVMK(w2, "correct-horse-8")!!.contentEquals(vmk))
    }
}
