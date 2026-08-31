package com.astrolexis.pyblock.data.util

/** Minimal Bech32 (BIP-173) encoder — used for NIP-19 `npub…` identities. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private fun polymod(values: List<Int>): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if (((b ushr i) and 1) != 0) chk = chk xor gen[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): List<Int> {
        val out = ArrayList<Int>()
        for (c in hrp) out.add(c.code ushr 5)
        out.add(0)
        for (c in hrp) out.add(c.code and 31)
        return out
    }

    private fun checksum(hrp: String, data: List<Int>): List<Int> {
        val values = hrpExpand(hrp) + data + listOf(0, 0, 0, 0, 0, 0)
        val mod = polymod(values) xor 1
        return (0 until 6).map { (mod ushr (5 * (5 - it))) and 31 }
    }

    /** Regroup 8-bit bytes into 5-bit groups (padding on). */
    fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): List<Int>? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (b in data) {
            val value = b.toInt() and 0xff
            if (value ushr from != 0) return null
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) { bits -= to; out.add((acc ushr bits) and maxv) }
        }
        if (pad) { if (bits > 0) out.add((acc shl (to - bits)) and maxv) }
        else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) return null
        return out
    }

    fun encode(hrp: String, bytes: ByteArray): String {
        val data = convertBits(bytes, 8, 5, true) ?: return ""
        val combined = data + checksum(hrp, data)
        val sb = StringBuilder(hrp).append('1')
        for (d in combined) sb.append(CHARSET[d])
        return sb.toString()
    }
}
