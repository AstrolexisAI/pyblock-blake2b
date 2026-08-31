package com.astrolexis.pyblock.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// CAROUSEL detail models (port 30000 — rotating supplier templates).
// Mirrors the iOS Carousel models + templates.php contract.

/** Live rotation feed — GET /templates.php?carrousel=1.
 *  `recent` is [[supplierName, unixTs], ...] newest last. */
@Serializable
data class CarouselRing(
    val live: Boolean = false,
    val miners: Int = 0,
    val hashrate: Double = 0.0,
    val recent: List<JsonArray> = emptyList(),
) {
    /** Decoded (supplier, epoch seconds) pairs, newest first. */
    val rotations: List<Rotation>
        get() = recent.mapNotNull { pair ->
            if (pair.size != 2) return@mapNotNull null
            val name = (pair[0] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return@mapNotNull null
            val ts = (pair[1] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            Rotation(name, ts)
        }.reversed()

    data class Rotation(val supplier: String, val atEpochSeconds: Long)
}

/** One sovereign template supplier. Parsed from the `suppliers` HTML
 *  fragment of templates.php?data=1 (the per-row data-* attributes are the
 *  stable contract; the markup around them is presentational). */
data class TemplateSupplier(
    val name: String,
    val port: Int,
    val address: String,
    val txs: Int,
    val height: Int,
    val coinbaseSats: Long,
    val feesSats: Long,
    val miners: Int,
    val fresh: Boolean,
    val live: Boolean,
    /** Supplier is reachable but temporarily not publishing (server "hold"). */
    val paused: Boolean,
    val jobs: Int,
)

/** Aggregates + parsed supplier table from the same payload. */
data class SuppliersSnapshot(
    val totalShares: Int,
    val accepted: Int,
    val rejected: Int,
    val supplierCount: Int,
    val miners: Int,
    val hashrateStr: String,
    val suppliers: List<TemplateSupplier>,
)

/** Raw wire shape of GET /templates.php?data=1. `suppliers` is an HTML
 *  fragment; the structured fields ride in its data-* attributes. */
@Serializable
data class SuppliersRaw(
    val total: Int = 0,
    val acc: Int = 0,
    val rej: Int = 0,
    val nsup: Int = 0,
    val miners: Int = 0,
    @SerialName("hashrate_str") val hashrateStr: String = "",
    val suppliers: String = "",
) {
    fun toSnapshot(): SuppliersSnapshot = SuppliersSnapshot(
        totalShares = total,
        accepted = acc,
        rejected = rej,
        supplierCount = nsup,
        miners = miners,
        hashrateStr = hashrateStr,
        suppliers = parseSuppliers(suppliers),
    )
}

/** Extract TemplateSupplier rows from the HTML fragment. One row per
 *  `det-btn`. Each data-* attribute is read independently so a missing or
 *  empty field on any one node — e.g. a just-booted supplier with no
 *  template yet — degrades that field to a default instead of silently
 *  dropping the whole runner. */
private fun parseSuppliers(html: String): List<TemplateSupplier> {
    // Split into rows so each det-btn is paired with its own live dot +
    // jobs cell (those live in the surrounding <td>s, not the button).
    val rows = html.split("<tr>")

    fun attr(name: String, s: String): String? =
        Regex("$name=\"([^\"]*)\"").find(s)?.groupValues?.get(1)

    val result = mutableListOf<TemplateSupplier>()
    for (row in rows) {
        // Only rows carrying a det-btn are real supplier rows. Isolate the
        // det-btn tag so attribute reads can't pick up the sibling
        // mine-btn's data-name / data-port / data-fresh.
        val detIdx = row.indexOf("class=\"det-btn\"")
        if (detIdx < 0) continue
        val detTag = row.substring(detIdx).substringBefore(">")

        val name = attr("data-name", detTag)?.takeIf { it.isNotEmpty() } ?: continue

        // jobs = FIRST NUMERIC td; live = dot class. Positional indexing breaks
        // when the name cell carries a title attribute (`<td title="…">` doesn't
        // match the plain `<td>` split, shifting every index — iOS's second-td
        // read lands on the "100%" acceptance cell and shows 0). Scanning for
        // the first integer cell is layout-proof.
        val jobs = row.split("<td>").drop(1)
            .firstNotNullOfOrNull { it.substringBefore("<").trim().toIntOrNull() } ?: 0

        result += TemplateSupplier(
            name = name,
            port = attr("data-port", detTag)?.toIntOrNull() ?: 0,
            address = attr("data-fulladdr", detTag) ?: "",
            txs = attr("data-txs", detTag)?.toIntOrNull() ?: 0,
            height = attr("data-h", detTag)?.toIntOrNull() ?: 0,
            coinbaseSats = attr("data-cb", detTag)?.toLongOrNull() ?: 0L,
            feesSats = attr("data-fees", detTag)?.toLongOrNull() ?: 0L,
            miners = attr("data-miners", detTag)?.toIntOrNull() ?: 0,
            fresh = attr("data-fresh", detTag) == "1",
            live = row.contains("dot live"),
            paused = row.contains("dot hold"),
            jobs = jobs,
        )
    }
    return result
}
