package com.astrolexis.pyblock.data.crypto

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Grinds independent full-entropy CSPRNG keys until the P2PKH address starts
 *  with "1"+pattern. Off the main thread, cancellable; publishes progress.
 *  Private key never leaves memory; cleared on reset. Compose-observable. */
class VanityGenerator {
    data class Match(val address: String, val wif: String)

    var running by mutableStateOf(false); private set
    var attempts by mutableIntStateOf(0); private set
    var rate by mutableDoubleStateOf(0.0); private set
    var match by mutableStateOf<Match?>(null); private set

    // A throw in the grind loop (native crypto, etc.) must NOT crash the app —
    // the handler stops the run instead of leaving an uncaught coroutine exception.
    private val scope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() +
            CoroutineExceptionHandler { _, _ -> running = false }
    )
    private var job: Job? = null

    /** @param compressed key format: false ⇒ "5" (uncompressed), true ⇒ "K"/"L".
     *  @param pool mixed user entropy (motion+touch); only ever ADDS entropy. */
    fun start(pattern: String, compressed: Boolean = false, pool: ByteArray = ByteArray(0)) {
        stop()
        match = null; attempts = 0; rate = 0.0; running = true
        val target = "1$pattern"
        job = scope.launch {
            val started = System.currentTimeMillis()
            var count = 0
            while (isActive) {
                var hit: Match? = null
                for (i in 0 until 1500) {
                    val priv = VanityCrypto.hardenedRandom32(pool)
                    val pub = if (compressed) VanityCrypto.compressedPubkey(priv)
                              else VanityCrypto.uncompressedPubkey(priv)
                    if (pub == null) continue
                    count++
                    val addr = VanityCrypto.p2pkhAddress(pub)
                    if (addr.startsWith(target)) {
                        val wif = if (compressed) VanityCrypto.wifCompressed(priv) else VanityCrypto.wifUncompressed(priv)
                        hit = Match(addr, wif)
                        break
                    }
                }
                val elapsed = (System.currentTimeMillis() - started) / 1000.0
                val n = count
                val r = if (elapsed > 0) n / elapsed else 0.0
                withContext(Dispatchers.Main) { attempts = n; rate = r }
                if (hit != null) {
                    withContext(Dispatchers.Main) { match = hit; running = false }
                    return@launch
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null; running = false }

    fun reset() { stop(); match = null; attempts = 0; rate = 0.0 }
}
