package com.astrolexis.pyblock

import com.astrolexis.pyblock.data.crypto.Nip44
import com.astrolexis.pyblock.data.nostr.Nostr
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The chat's cryptographic gates, runnable off-device. The same checks run inside `check()` at
 * app start, so a regression here would be a crash on launch; here it is a failed build instead.
 */
class ChatCryptoTest {

    /** Sign, verify, tamper, verify again. Proves the BIP-340 argument order is right. */
    @Test fun eventVerificationRoundTripsAndRejectsTampering() {
        val sk = ByteArray(32).also { SecureRandom().nextBytes(it) }
        assertTrue(Nostr.selfTestVerify(sk))
    }

    /** ChaCha20 vs RFC 8439 and the conversation key vs the NIP-44 spec vector. */
    @Test fun nip44MatchesTheSpecVectors() = assertTrue(Nip44.selfTest())

    /** Canonical padding: the exact padded length, and zero padding bytes. */
    @Test fun nip44UnpadIsCanonical() {
        val good = Nip44.pad("hi".toByteArray())
        assertNotNull(Nip44.unpad(good))
        assertNull("extra trailing byte must be refused", Nip44.unpad(good + byteArrayOf(0)))
        val dirty = good.copyOf().also { it[it.size - 1] = 1 }
        assertNull("non-zero padding must be refused", Nip44.unpad(dirty))
        assertFalse(Nip44.unpad(good)?.isEmpty() ?: true)
    }
}
