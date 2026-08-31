package com.astrolexis.pyblock.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.roundToInt

/** `ours` is true/false in most payloads but 0/1 in the pending one. */
object FlexBoolSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("FlexBool", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Boolean {
        val prim = (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive
        return prim.booleanOrNull ?: ((prim.intOrNull ?: 0) != 0)
    }
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

@Serializable
data class CBBreakdown(
    val inscriptions: Int = 0,
    val runes: Int = 0,
    val opreturn: Int = 0,
    val baremultisig: Int = 0,
)

@Serializable
data class CBBlock(
    val height: Int = 0,
    val hash: String? = null,
    val txs: Int = 0,
    val time: Int = 0,
    val pool: String? = null,
    @Serializable(with = FlexBoolSerializer::class) val ours: Boolean? = null,
    @SerialName("fees_sats") val feesSats: Int = 0,
    val vsize: Int = 0,
    @SerialName("parasite_ratio") val parasiteRatio: Double? = null,
    val clean: Boolean = false,
    @SerialName("coinbase_sig") val coinbaseSig: String? = null,
    val breakdown: CBBreakdown? = null,
    val pending: Int? = null,
) {
    val isOurs get() = ours ?: false
    val isPending get() = (pending ?: 0) != 0
    val cleanPct get() = (100 * (1 - (parasiteRatio ?: 0.0))).roundToInt()
    val satPerVb get() = if (vsize > 0) max(1, (feesSats.toDouble() / vsize).roundToInt()) else 0
}

@Serializable
data class CBDetail(
    val height: Int = 0,
    val hash: String? = null,
    val pool: String? = null,
    @Serializable(with = FlexBoolSerializer::class) val ours: Boolean? = null,
    val time: Int? = null,
    val txs: Int = 0,
    @SerialName("fees_sats") val feesSats: Int = 0,
    val vsize: Int = 0,
    @SerialName("clean_n") val cleanN: Int = 0,
    @SerialName("dirty_n") val dirtyN: Int = 0,
    @SerialName("clean_vb") val cleanVb: Int = 0,
    @SerialName("dirty_vb") val dirtyVb: Int = 0,
    @SerialName("clean_fees_sats") val cleanFeesSats: Int = 0,
    @SerialName("dirty_fees_sats") val dirtyFeesSats: Int = 0,
    val breakdown: CBBreakdown? = null,
    @SerialName("coinbase_hex") val coinbaseHex: String? = null,
    @SerialName("coinbase_sig") val coinbaseSig: String? = null,
    @SerialName("parasite_ratio") val parasiteRatio: Double? = null,
    val clean: Boolean? = null,
    val pending: Int? = null,
    val txlist: List<CBTx>? = null,
) {
    val isOurs get() = ours ?: false
    val isPending get() = (pending ?: 0) != 0
    val isClean get() = clean ?: (dirtyN == 0)

    fun asMinedBlock(): CBBlock = CBBlock(
        height = height, hash = hash, txs = txs, time = time ?: (System.currentTimeMillis() / 1000).toInt(),
        pool = pool, ours = ours, feesSats = feesSats, vsize = vsize,
        parasiteRatio = parasiteRatio, clean = isClean, coinbaseSig = coinbaseSig,
        breakdown = breakdown, pending = null,
    )
}

@Serializable
data class CBTx(val v: Int = 0, val d: Int = 0)

@Serializable
data class CBStats(
    val blocks: Int = 0,
    @SerialName("clean_blocks") val cleanBlocks: Int = 0,
    @SerialName("parasite_blocks") val parasiteBlocks: Int = 0,
    @SerialName("ours_blocks") val oursBlocks: Int = 0,
    @SerialName("total_tx") val totalTx: Int = 0,
    @SerialName("clean_tx") val cleanTx: Int = 0,
    @SerialName("parasite_tx") val parasiteTx: Int = 0,
    val insc: Int = 0,
    val runes: Int = 0,
    val opret: Int = 0,
    val bare: Int = 0,
    @SerialName("total_vb") val totalVb: Int = 0,
    @SerialName("parasite_vb") val parasiteVb: Int = 0,
    @SerialName("clean_fees") val cleanFees: Int = 0,
    @SerialName("parasite_fees") val parasiteFees: Int = 0,
)

@Serializable
data class CBBlocksResp(val tip: Int? = null, val blocks: List<CBBlock>? = null)
