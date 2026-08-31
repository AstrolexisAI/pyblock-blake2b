package com.astrolexis.pyblock.data.net

import com.astrolexis.pyblock.data.model.LandRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fetches + parses the world coastline GeoJSON (once, cached). */
object LandRepo {
    private const val URL = "https://pyblock.xyz:8443/data/world_land.min.json"
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cached: List<LandRing>? = null

    suspend fun land(): List<LandRing> {
        cached?.let { return it }
        val rings = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(URL).build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use emptyList()
                    parse(json.parseToJsonElement(body))
                }
            } catch (e: Exception) {
                emptyList()   // network/parse failure — don't crash, don't cache, retry later
            }
        }
        if (rings.isNotEmpty()) cached = rings   // only cache a real result
        return rings
    }

    private fun parse(root: JsonElement): List<LandRing> {
        val out = ArrayList<LandRing>()
        val features = root.jsonObject["features"]?.jsonArray ?: return out
        for (f in features) {
            val geom = f.jsonObject["geometry"]?.jsonObject ?: continue
            val type = geom["type"]?.jsonPrimitive?.content
            val coords = geom["coordinates"]?.jsonArray ?: continue
            when (type) {
                "Polygon" -> coords.forEach { addRing(it.jsonArray, out) }
                "MultiPolygon" -> coords.forEach { poly ->
                    poly.jsonArray.forEach { addRing(it.jsonArray, out) }
                }
            }
        }
        return out
    }

    private fun addRing(ring: JsonArray, out: MutableList<LandRing>) {
        val n = ring.size
        if (n < 3) return
        val lon = FloatArray(n)
        val lat = FloatArray(n)
        for (i in 0 until n) {
            val pt = ring[i].jsonArray
            lon[i] = pt[0].jsonPrimitive.float
            lat[i] = pt[1].jsonPrimitive.float
        }
        out.add(LandRing(lon, lat))
    }
}
