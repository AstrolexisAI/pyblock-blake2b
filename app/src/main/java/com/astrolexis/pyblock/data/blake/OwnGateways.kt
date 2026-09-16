package com.astrolexis.pyblock.data.blake

import android.content.Context
import com.astrolexis.pyblock.R
import com.astrolexis.pyblock.data.store.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** A gateway the miner runs themselves — `curl … /gateway | sh -s -- carousel <address>` leaves a
 *  stratum endpoint on that machine. Same product as the house pools: our node dictates the
 *  coinbase, the block is published by THEIR node. A LAN address is the normal case here. */
data class OwnGateway(val id: String = UUID.randomUUID().toString(), val product: String, val host: String, val port: Int) {
    val target get() = "$host:$port"
    val stratumUrl get() = "stratum+tcp://$target"
}

object OwnGateways {
    private const val PREFS = "pyblockb.owngateways"
    private const val KEY = "v1"
    private val _all = MutableStateFlow<List<OwnGateway>>(emptyList())
    val all: StateFlow<List<OwnGateway>> = _all.asStateFlow()
    @Volatile private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        _all.value = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> val o = arr.getJSONObject(i)
                OwnGateway(o.getString("id"), o.getString("product"), o.getString("host"), o.getInt("port")) }
        }.getOrDefault(emptyList())
    }

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        _all.value.forEach { g -> arr.put(JSONObject().put("id", g.id).put("product", g.product).put("host", g.host).put("port", g.port)) }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun save(ctx: Context, g: OwnGateway) {
        val list = _all.value.toMutableList()
        val i = list.indexOfFirst { it.id == g.id }.takeIf { it >= 0 } ?: list.indexOfFirst { it.target == g.target }
        if (i >= 0) list[i] = g else list.add(g)     // same endpoint twice is one entry
        _all.value = list; persist(ctx)
    }

    fun remove(ctx: Context, id: String) { _all.value = _all.value.filter { it.id != id }; persist(ctx) }

    /** "host:port", "stratum+tcp://host:port" or a bare host. null when there is no port. */
    fun parse(raw: String): Pair<String, Int>? {
        var s = raw.trim()
        for (p in listOf("stratum+tcp://", "stratum://", "tcp://")) if (s.lowercase().startsWith(p)) s = s.substring(p.length)
        s = s.substringBefore('/')
        val colon = s.lastIndexOf(':'); if (colon < 0) return null
        val port = s.substring(colon + 1).toIntOrNull() ?: return null
        return s.substring(0, colon) to port
    }

    /** Why this can't be used, in one sentence, or null when the shape is fine. Shape only — the
     *  probe answers whether the machine is up. */
    fun problem(host: String, port: Int): String? {
        val h = host.trim()
        if (h.isEmpty()) return AppStrings.get(R.string.blk_needs_a_host_or_an_ip_address)
        if (h.contains(' ')) return AppStrings.get(R.string.blk_a_host_never_has_a_space_in_it)
        if (port !in 1..65535) return AppStrings.get(R.string.blk_the_port_has_to_be_between_1_and_65535)
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            return if (parts.all { (it.toIntOrNull() ?: 999) <= 255 }) null else AppStrings.get(R.string.blk_that_isn_t_a_valid_ip_address)
        }
        if (h.contains(':')) return null
        val ok = "abcdefghijklmnopqrstuvwxyz0123456789.-"
        return if (h.lowercase().all { it in ok }) null else AppStrings.get(R.string.blk_a_host_is_letters_digits_dots_and_dashes)
    }

    /** Connect, send mining.subscribe, read one line. The question that finds nine problems in ten:
     *  port closed, wrong IP, gateway still syncing. */
    suspend fun probe(host: String, port: Int, timeoutMs: Int = 6000): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                sock.soTimeout = timeoutMs
                sock.getOutputStream().write("{\"id\":1,\"method\":\"mining.subscribe\",\"params\":[\"PyBLOCKb\"]}\n".toByteArray())
                sock.getOutputStream().flush()
                val line = BufferedReader(InputStreamReader(sock.getInputStream())).readLine()
                    ?: return@withContext false to AppStrings.get(R.string.blk_connected_but_nothing_came_back_is_the_g)
                if (line.contains("\"result\"") && !line.contains("\"result\":null")) true to AppStrings.get(R.string.blk_it_answers_mining_subscribe_point_your_r)
                else false to AppStrings.get(R.string.blk_it_answered_but_not_like_a_stratum, line.take(60))
            }
        } catch (e: java.net.SocketTimeoutException) {
            false to AppStrings.get(R.string.blk_no_answer_in_s_port_closed_or_wrong_ip, (timeoutMs / 1000).toString())
        } catch (e: Exception) {
            false to AppStrings.get(R.string.blk_can_t_connect, e.message ?: e::class.java.simpleName)
        }
    }
}
