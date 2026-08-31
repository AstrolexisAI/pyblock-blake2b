package com.astrolexis.pyblock.data.crypto

import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom

/** Bitcoin key + address primitives for the vanity generator. Key material
 *  comes from the OS CSPRNG (SecureRandom) and secp256k1 (native ACINQ lib) —
 *  never a weak seed. Base58/address/WIF verified against known vectors. */
object VanityCrypto {
    val base58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray()
    private val base58Set = base58.toSet()
    private val rng = SecureRandom()
    private val secp: Secp256k1 by lazy { Secp256k1.get() }

    /** 256-bit key material from the OS CSPRNG (no weak fallback). */
    fun secureRandom32(): ByteArray = ByteArray(32).also { rng.nextBytes(it) }

    private fun sha256(x: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(x)

    /** Compressed pubkey (33 bytes), or null if the key is out of range. */
    fun compressedPubkey(priv: ByteArray): ByteArray? {
        if (!secp.secKeyVerify(priv)) return null
        return try { secp.pubKeyCompress(secp.pubkeyCreate(priv)) } catch (e: Exception) { null }
    }

    /** Uncompressed pubkey (65 bytes, 0x04 prefix), or null if out of range. */
    fun uncompressedPubkey(priv: ByteArray): ByteArray? {
        if (!secp.secKeyVerify(priv)) return null
        return try { secp.pubkeyCreate(priv) } catch (e: Exception) { null }
    }

    /**
     * Hardened 256-bit key material: SHA256(CSPRNG ‖ user-entropy pool ‖ jitter).
     * Fail-safe — even with an empty pool this is a full-entropy CSPRNG draw; the
     * pool + timing jitter only ever ADD entropy (anti-Coldcard, never a weak seed).
     */
    fun hardenedRandom32(pool: ByteArray): ByteArray {
        val csprng = secureRandom32()
        val jitter = System.nanoTime()
        val j = ByteArray(8) { ((jitter ushr (it * 8)) and 0xff).toByte() }
        return sha256(csprng + pool + j)
    }

    /** Mainnet P2PKH "1..." address for a pubkey (compressed or uncompressed). */
    fun p2pkhAddress(compressedPubkey: ByteArray): String {
        val h160 = RIPEMD160.hash(sha256(compressedPubkey))
        val payload = ByteArray(21)
        payload[0] = 0x00
        System.arraycopy(h160, 0, payload, 1, 20)
        val checksum = sha256(sha256(payload)).copyOfRange(0, 4)
        return base58Encode(payload + checksum)
    }

    /** Compressed WIF ("K"/"L"): Base58Check(0x80 || key || 0x01). */
    fun wifCompressed(priv: ByteArray): String {
        val payload = ByteArray(34)
        payload[0] = 0x80.toByte()
        System.arraycopy(priv, 0, payload, 1, 32)
        payload[33] = 0x01
        val checksum = sha256(sha256(payload)).copyOfRange(0, 4)
        return base58Encode(payload + checksum)
    }

    /** Uncompressed WIF ("5"): Base58Check(0x80 || key). */
    fun wifUncompressed(priv: ByteArray): String {
        val payload = ByteArray(33)
        payload[0] = 0x80.toByte()
        System.arraycopy(priv, 0, payload, 1, 32)
        val checksum = sha256(sha256(payload)).copyOfRange(0, 4)
        return base58Encode(payload + checksum)
    }

    /** Decode a WIF → (32-byte privkey, compressed?), or null if invalid. */
    fun decodeWif(wif: String): Pair<ByteArray, Boolean>? {
        val data = base58Decode(wif) ?: return null
        if (data.size != 37 && data.size != 38) return null      // 1+32(+1)+4
        if (data[0] != 0x80.toByte()) return null
        val payloadLen = data.size - 4
        val payload = data.copyOfRange(0, payloadLen)
        val checksum = data.copyOfRange(payloadLen, data.size)
        if (!sha256(sha256(payload)).copyOfRange(0, 4).contentEquals(checksum)) return null
        return data.copyOfRange(1, 33) to (payloadLen == 34)
    }

    /**
     * The SAME private key encoded with the OTHER compression flag. Used as a
     * distinct BDK change descriptor for single-key wallets (bdk 1.2.0 rejects
     * identical external==change). Round-trips through re-encoding, so a decode
     * bug returns null (fail-closed) rather than a wrong — unspendable — key.
     */
    fun otherFormatWif(wif: String): String? {
        val (key, compressed) = decodeWif(wif) ?: return null
        val reencoded = if (compressed) wifCompressed(key) else wifUncompressed(key)
        if (reencoded != wif) return null                        // guard: same key guaranteed
        return if (compressed) wifUncompressed(key) else wifCompressed(key)
    }

    /**
     * The public key hex (in the WIF's own compression format) for a WIF, verified
     * to reproduce [expectedAddress]. Returns null on a bad WIF OR any mismatch, so
     * a caller NEVER caches a public key that watches/spends the wrong script — it
     * falls back to WIF-derivation at spend time. This is the pubkey used for the
     * watch-only descriptor (sync needs only the public key; the WIF stays sealed).
     */
    fun validatedPubkeyHex(wif: String, expectedAddress: String): String? {
        val (priv, compressed) = decodeWif(wif) ?: return null
        val pub = (if (compressed) compressedPubkey(priv) else uncompressedPubkey(priv)) ?: return null
        if (p2pkhAddress(pub) != expectedAddress) return null    // fail-closed: key must match the saved address
        return pub.toHex()
    }

    /**
     * The SAME public key in the OTHER compression format — PURE public EC math
     * (no private key needed), so it works with the vault locked. Used to build the
     * distinct change descriptor (bdk rejects identical external==change). Null on
     * a malformed pubkey (fail-closed).
     */
    fun otherFormatPubkeyHex(pubHex: String): String? {
        val bytes = hexToBytes(pubHex) ?: return null
        return try {
            when (bytes.size) {
                33 -> secp.pubkeyParse(bytes).toHex()            // compressed → 65-byte uncompressed
                65 -> secp.pubKeyCompress(bytes).toHex()         // uncompressed → 33-byte compressed
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun hexToBytes(s: String): ByteArray? = runCatching {
        require(s.length % 2 == 0)
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
    }.getOrNull()

    fun base58Decode(s: String): ByteArray? {
        var num = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)
        for (c in s) {
            val d = base58.indexOf(c)
            if (d < 0) return null
            num = num.multiply(base).add(java.math.BigInteger.valueOf(d.toLong()))
        }
        var bytes = if (num.signum() == 0) ByteArray(0) else num.toByteArray()
        if (bytes.size > 1 && bytes[0].toInt() == 0) bytes = bytes.copyOfRange(1, bytes.size)  // strip sign byte
        var zeros = 0
        for (c in s) { if (c == '1') zeros++ else break }
        return ByteArray(zeros) + bytes
    }

    fun base58Encode(bytes: ByteArray): String {
        val digits = ArrayList<Int>()
        for (b in bytes) {
            var carry = b.toInt() and 0xff
            for (i in digits.indices) {
                carry += digits[i] shl 8
                digits[i] = carry % 58
                carry /= 58
            }
            while (carry > 0) { digits.add(carry % 58); carry /= 58 }
        }
        val sb = StringBuilder()
        for (b in bytes) { if (b.toInt() == 0) sb.append('1') else break }
        for (i in digits.indices.reversed()) sb.append(base58[digits[i]])
        return sb.toString()
    }

    fun isValidPattern(s: String): Boolean = s.isNotEmpty() && s.all { it in base58Set }

    /** Expected attempts for an n-char vanity (≈ 58^n after the fixed "1"). */
    fun expectedAttempts(n: Int): Double = Math.pow(58.0, n.toDouble())

    /** Verifies the pipeline against known vectors. false ⇒ DO NOT ship. */
    fun selfTest(): Boolean {
        if (RIPEMD160.hash(ByteArray(0)).toHex() != "9c1185a5c5e9fc54612808977ee8f548b2258d31") return false
        if (RIPEMD160.hash("abc".toByteArray()).toHex() != "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc") return false
        val one = ByteArray(32).also { it[31] = 1 }
        val pub = compressedPubkey(one) ?: return false
        val unc = uncompressedPubkey(one) ?: return false
        // WIF decode round-trips + recovers the key with correct compression.
        val decC = decodeWif("KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn")
        val decU = decodeWif("5HpHagT65TZzG1PH3CSu63k8DbpvD8s5ip4nEB3kEsreAnchuDf")
        if (decC == null || !decC.first.contentEquals(one) || !decC.second) return false
        if (decU == null || !decU.first.contentEquals(one) || decU.second) return false
        if (otherFormatWif("KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn") != "5HpHagT65TZzG1PH3CSu63k8DbpvD8s5ip4nEB3kEsreAnchuDf") return false
        // Watch-only pubkey: validated-derive from WIF + other-format via EC (de)compression.
        if (validatedPubkeyHex("KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn", "1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH") != pub.toHex()) return false
        if (validatedPubkeyHex("KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn", "1EHNa6Q4Jz2uvNExL497mE43ikXhwF6kZm") != null) return false  // wrong address ⇒ null
        if (otherFormatPubkeyHex(pub.toHex()) != unc.toHex()) return false
        if (otherFormatPubkeyHex(unc.toHex()) != pub.toHex()) return false
        return p2pkhAddress(pub) == "1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH" &&
            wifCompressed(one) == "KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn" &&
            p2pkhAddress(unc) == "1EHNa6Q4Jz2uvNExL497mE43ikXhwF6kZm" &&
            wifUncompressed(one) == "5HpHagT65TZzG1PH3CSu63k8DbpvD8s5ip4nEB3kEsreAnchuDf"
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
