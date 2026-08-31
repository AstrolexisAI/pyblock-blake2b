package com.astrolexis.pyblock.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bitcoin peer map — GET /nodemap.php?data=1. */
@Serializable
data class NodeMapData(
    val ts: Int = 0,
    val anchors: Map<String, Anchor> = emptyMap(),
    val points: List<NodePoint> = emptyList(),
    @SerialName("supplier_count") val supplierCount: Int? = null,
    @SerialName("supplier_pings") val supplierPings: List<Double>? = null,
)

@Serializable
data class Anchor(val lat: Double = 0.0, val lon: Double = 0.0, val label: String = "")

@Serializable
data class NodePoint(
    val node: String = "",
    val net: String = "",
    val inbound: Boolean = false,
    val geo: Geo = Geo(),
    val ping: Double? = null,
    val subver: String? = null,
)

@Serializable
data class Geo(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val cc: String? = null,
    val country: String? = null,
)

/** A single closed coastline ring in lon/lat, columnar for fast projection. */
class LandRing(val lon: FloatArray, val lat: FloatArray)
