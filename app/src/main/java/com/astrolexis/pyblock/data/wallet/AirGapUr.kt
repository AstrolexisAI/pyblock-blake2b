package com.astrolexis.pyblock.data.wallet

import android.util.Base64
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.model.ByteString
import java.io.ByteArrayInputStream

/**
 * BC-UR (`ur:crypto-psbt`) bridge for air-gap PSBTs. Big PSBTs don't fit one QR, so
 * they're fragmented into fountain-coded UR parts and shown as an animated QR; the
 * scanner reassembles them. Interoperable with the iOS URKit path (crypto-psbt) and
 * Sparrow/Keystone/etc. Android mirror of iOS AirGapUR.swift.
 */
object AirGapUr {
    /** Fragment payload per QR part — small enough to keep each frame scannable at a
     *  glance; the fountain encoder cycles parts so any order/duplication reassembles. */
    private const val MAX_FRAGMENT_LEN = 100
    private const val MIN_FRAGMENT_LEN = 10

    /** Build an animated UR encoder for a PSBT (base64). null if the base64 is invalid. */
    fun encoder(psbtBase64: String): UREncoder? {
        val bytes = runCatching { Base64.decode(psbtBase64.trim(), Base64.DEFAULT) }.getOrNull() ?: return null
        val ur = runCatching { CryptoPSBT(bytes).toUR() }.getOrNull() ?: return null
        return UREncoder(ur, MAX_FRAGMENT_LEN, MIN_FRAGMENT_LEN, 0)
    }

    /** Extract the PSBT (base64) from a fully-decoded UR. Handles our own crypto-psbt
     *  (CryptoPSBT) and, defensively, any UR whose CBOR is a plain byte string. */
    fun psbtBase64(ur: UR): String? {
        val bytes = runCatching { (ur.decodeFromRegistry() as? CryptoPSBT)?.psbt }.getOrNull()
            ?: runCatching {
                val di = CborDecoder(ByteArrayInputStream(ur.cborBytes)).decode().firstOrNull()
                (di as? ByteString)?.bytes
            }.getOrNull()
            ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
