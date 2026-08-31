package com.astrolexis.pyblock.data.crypto

/** RIPEMD-160 — required for Bitcoin HASH160 (RIPEMD160(SHA256(x))); not in the
 *  JDK. Pure function; verified against the standard vectors ("" → 9c1185a5…,
 *  "abc" → 8eb208f7…) in [VanityCrypto.selfTest]. Direct port of the audited
 *  iOS implementation. */
object RIPEMD160 {
    private val rl = intArrayOf(
        0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,
        7,4,13,1,10,6,15,3,12,0,9,5,2,14,11,8,
        3,10,14,4,9,15,8,1,2,7,0,6,13,11,5,12,
        1,9,11,10,0,8,12,4,13,3,7,15,14,5,6,2,
        4,0,5,9,7,12,2,10,14,1,3,8,11,6,15,13)
    private val rr = intArrayOf(
        5,14,7,0,9,2,11,4,13,6,15,8,1,10,3,12,
        6,11,3,7,0,13,5,10,14,15,8,12,4,9,1,2,
        15,5,1,3,7,14,6,9,11,8,12,2,10,0,4,13,
        8,6,4,1,3,11,15,0,5,12,2,13,9,7,10,14,
        12,15,10,4,1,5,8,7,6,2,13,14,0,3,9,11)
    private val sl = intArrayOf(
        11,14,15,12,5,8,7,9,11,13,14,15,6,7,9,8,
        7,6,8,13,11,9,7,15,7,12,15,9,11,7,13,12,
        11,13,6,7,14,9,13,15,14,8,13,6,5,12,7,5,
        11,12,14,15,14,15,9,8,9,14,5,6,8,6,5,12,
        9,15,5,11,6,8,13,12,5,12,13,14,11,8,5,6)
    private val sr = intArrayOf(
        8,9,9,11,13,15,15,5,7,7,8,11,14,14,12,6,
        9,13,15,7,12,8,9,11,7,7,12,7,6,15,13,11,
        9,7,15,11,8,6,6,14,12,13,5,14,13,13,7,5,
        15,5,8,11,14,14,6,14,6,9,12,9,12,5,15,8,
        8,5,12,9,12,5,14,6,8,13,6,5,15,13,11,11)
    private val KL = uintArrayOf(0x00000000u, 0x5A827999u, 0x6ED9EBA1u, 0x8F1BBCDCu, 0xA953FD4Eu)
    private val KR = uintArrayOf(0x50A28BE6u, 0x5C4DD124u, 0x6D703EF3u, 0x7A6D76E9u, 0x00000000u)

    private fun f(j: Int, x: UInt, y: UInt, z: UInt): UInt = when (j) {
        in 0..15 -> x xor y xor z
        in 16..31 -> (x and y) or (x.inv() and z)
        in 32..47 -> (x or y.inv()) xor z
        in 48..63 -> (x and z) or (y and z.inv())
        else -> x xor (y or z.inv())
    }
    private fun rotl(x: UInt, n: Int): UInt = (x shl n) or (x shr (32 - n))

    fun hash(message: ByteArray): ByteArray {
        var h0 = 0x67452301u; var h1 = 0xEFCDAB89u; var h2 = 0x98BADCFEu
        var h3 = 0x10325476u; var h4 = 0xC3D2E1F0u

        val bitLen = message.size.toLong() * 8
        val msg = ArrayList<Byte>(message.size + 72)
        msg.addAll(message.toList())
        msg.add(0x80.toByte())
        while (msg.size % 64 != 56) msg.add(0)
        for (i in 0 until 8) msg.add(((bitLen ushr (8 * i)) and 0xff).toByte())
        val m = msg.toByteArray()

        var offset = 0
        while (offset < m.size) {
            val X = UIntArray(16)
            for (i in 0 until 16) {
                val o = offset + i * 4
                X[i] = (m[o].toUInt() and 0xffu) or
                    ((m[o+1].toUInt() and 0xffu) shl 8) or
                    ((m[o+2].toUInt() and 0xffu) shl 16) or
                    ((m[o+3].toUInt() and 0xffu) shl 24)
            }
            var al = h0; var bl = h1; var cl = h2; var dl = h3; var el = h4
            var ar = h0; var br = h1; var cr = h2; var dr = h3; var er = h4
            for (j in 0 until 80) {
                val round = j / 16
                var t = al + f(j, bl, cl, dl) + X[rl[j]] + KL[round]
                t = rotl(t, sl[j]) + el
                al = el; el = dl; dl = rotl(cl, 10); cl = bl; bl = t
                t = ar + f(79 - j, br, cr, dr) + X[rr[j]] + KR[round]
                t = rotl(t, sr[j]) + er
                ar = er; er = dr; dr = rotl(cr, 10); cr = br; br = t
            }
            val t = h1 + cl + dr
            h1 = h2 + dl + er
            h2 = h3 + el + ar
            h3 = h4 + al + br
            h4 = h0 + bl + cr
            h0 = t
            offset += 64
        }
        val out = ByteArray(20)
        var i = 0
        for (h in uintArrayOf(h0, h1, h2, h3, h4)) {
            out[i++] = (h and 0xffu).toByte()
            out[i++] = ((h shr 8) and 0xffu).toByte()
            out[i++] = ((h shr 16) and 0xffu).toByte()
            out[i++] = ((h shr 24) and 0xffu).toByte()
        }
        return out
    }
}
