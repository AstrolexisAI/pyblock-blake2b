package com.astrolexis.pyblock.ui.blake

import com.astrolexis.pyblock.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrolexis.pyblock.data.blake.BlakeMiner
import com.astrolexis.pyblock.data.blake.BlakeRentals
import com.astrolexis.pyblock.data.wallet.WalletStore
import com.astrolexis.pyblock.ui.Haptics
import com.astrolexis.pyblock.ui.components.clickableNoRipple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** MINER — the user's own mining, keyed by the payout address used as stratum username.
 *  Pools hit, workers with live rate and last share, 1d/7d/30d line, blocks that paid this
 *  address, and connect info. Mirrors iOS MinerView. */
@Composable
fun BlakeMinerSheet(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("pyblockb.miner", android.content.Context.MODE_PRIVATE) }
    val wallets by WalletStore.wallets.collectAsState()

    var address by remember { mutableStateOf(prefs.getString("address", "") ?: "") }
    var manual by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var showPick by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<BlakeMiner.Stats?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf("1d") }
    var history by remember { mutableStateOf<List<BlakeMiner.Point>>(emptyList()) }
    var connect by remember { mutableStateOf<BlakeMiner.Connect?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    var copied by remember { mutableStateOf<String?>(null) }
    // The Primes this address is on (own gateway). Read from the Prime itself — see BlakeMiner.primes.
    var primes by remember { mutableStateOf<List<BlakeMiner.PrimeMatch>>(emptyList()) }

    // Your own gateway (DATUM). The list is the user's; the pool never sees it.
    LaunchedEffect(Unit) { com.astrolexis.pyblock.data.blake.OwnGateways.init(ctx) }
    val gateways by com.astrolexis.pyblock.data.blake.OwnGateways.all.collectAsState()
    var addingGateway by remember { mutableStateOf(false) }
    var gwProduct by remember { mutableStateOf("carousel") }
    var gwInput by remember { mutableStateOf("") }
    var gwError by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf<String?>(null) }
    var probeResult by remember { mutableStateOf<Map<String, Pair<Boolean, String>>>(emptyMap()) }

    fun select(a: String) { address = a; stats = null; loaded = false; editing = false; showPick = false; prefs.edit().putString("address", a).apply() }
    val scanWallet: suspend () -> Unit = {
        scanning = true
        found = BlakeMiner.findMining(wallets.take(40).map { it.address })
        if (found.size == 1 && address.isEmpty()) select(found[0])
        scanning = false
    }

    LaunchedEffect(address) {
        if (address.isEmpty()) return@LaunchedEffect
        loaded = false
        while (true) {
            BlakeMiner.stats(address)?.let { s -> stats = s; s.connect?.let { connect = it } }
            history = BlakeMiner.history(address, range)
            primes = BlakeMiner.primes(address)
            loaded = true
            delay(30_000)   // server caches 20 s; samples every 3 min
        }
    }
    LaunchedEffect(range) { if (address.isNotEmpty()) history = BlakeMiner.history(address, range) }
    LaunchedEffect(Unit) {
        if (connect == null) connect = BlakeMiner.connect()
        if (address.isEmpty() && wallets.isNotEmpty()) scanWallet()
    }

    fullSheet(stringResource(R.string.blk_miner), onClose) {
        // ---- Address ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            sectionTitle(stringResource(R.string.blk_address))
            Spacer(Modifier.weight(1f))
            if (wallets.isNotEmpty()) Text(if (scanning) stringResource(R.string.blk_scanning) else stringResource(R.string.blk_find_mine), style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp,
                modifier = Modifier.clickableNoRipple { if (!scanning) { Haptics.tap(); scope.launch { scanWallet() } } })
            Spacer(Modifier.width(12.dp))
            Text(if (editing) stringResource(R.string.blk_done) else stringResource(R.string.blk_change), style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp,
                modifier = Modifier.clickableNoRipple { Haptics.tap(); editing = !editing })
        }
        if (address.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(com.astrolexis.pyblock.data.blake.AddressCheck.grouped(address), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg)
            if (!com.astrolexis.pyblock.data.blake.AddressCheck.isValid(address)) {
                Text(stringResource(R.string.blk_this_is_not_a_valid_payout_address_the_p),
                    style = Blake.mono(7f), color = Blake.danger)
            }
        }
        if (editing || address.isEmpty()) {
            if (found.isNotEmpty()) {
                Spacer(Modifier.height(6.dp)); Text(stringResource(R.string.blk_mining_from_your_wallet), style = Blake.mono(8f), color = Blake.faint)
                found.forEach { a ->
                    Text(a, style = Blake.mono(9f), color = if (a == address) Blake.ok else Blake.pp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickableNoRipple { Haptics.tap(); select(a) })
                }
            }
            if (wallets.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.blk_pick_from_wallet), style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp, modifier = Modifier.clickableNoRipple { showPick = !showPick })
                if (showPick) wallets.take(40).forEach { w ->
                    Text(w.address, style = Blake.mono(9f), color = Blake.pp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickableNoRipple { Haptics.tap(); select(w.address) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(value = manual, onValueChange = { manual = it }, singleLine = true,
                    textStyle = Blake.mono(10f).copy(color = Blake.fg), cursorBrush = SolidColor(Blake.pp),
                    modifier = Modifier.weight(1f).border(1.dp, Blake.line, RectangleShape).padding(9.dp),
                    decorationBox = { inner -> if (manual.isEmpty()) Text(stringResource(R.string.blk_other_address), style = Blake.mono(10f), color = Blake.faint); inner() })
                Spacer(Modifier.width(8.dp))
                val ok = com.astrolexis.pyblock.data.blake.AddressCheck.isValid(manual)
                Text(stringResource(R.string.blk_use), style = Blake.mono(9f, FontWeight.ExtraBold), color = if (ok) Blake.pp else Blake.faint, letterSpacing = 1.sp,
                    modifier = Modifier.clickableNoRipple { if (ok) { Haptics.tap(); select(com.astrolexis.pyblock.data.blake.AddressCheck.normalize(manual)); manual = "" } })
            }
            // On this pool the payout address IS the stratum username, so an unchecked address here
            // is a week of mining that pays nobody. Say what's wrong while it costs nothing.
            com.astrolexis.pyblock.data.blake.AddressCheck.problem(manual)?.let {
                Text(it, style = Blake.mono(7f), color = Blake.danger)
            }
        }
        Spacer(Modifier.height(18.dp))

        val s = stats
        when {
            address.isEmpty() -> Text(stringResource(R.string.blk_pick_the_payout_address_you_mine_to_it_i), style = Blake.mono(9f), color = Blake.ppDim)
            !loaded -> Text(stringResource(R.string.blk_reading_the_gateways), style = Blake.mono(10f), color = Blake.pp)
            s == null -> Text(stringResource(R.string.blk_can_t_reach_the_server), style = Blake.mono(10f), color = Blake.danger)
            s.isMining || primes.isNotEmpty() -> {
                Column(Modifier.fillMaxWidth().blakeCard()) {
                    Row(Modifier.fillMaxWidth()) {
                        BlakeStat(BlakeRentals.th(s.totalTh1m), stringResource(R.string.blk_hashrate_now))
                        Spacer(Modifier.weight(1f))
                        BlakeStat("${s.workersOnline}", "workers online", Blake.fg, alignEnd = true)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth()) {
                        BlakeStat(BlakeRentals.th(s.totalTh1d), stringResource(R.string.blk_s_24h_average), Blake.ppDim)
                        Spacer(Modifier.weight(1f))
                        BlakeStat("${s.blocks.size}", "blocks paid", Blake.fg, alignEnd = true)
                    }
                }
                // The address API now lists the Primes itself (chirp_prime / carousel_prime); the
                // standalone card stays only for a product the API doesn't report yet.
                primes.filter { m -> s.pools.none { it.pool == m.product + "_prime" } }.forEach { m -> Spacer(Modifier.height(14.dp)); primeCard(m) }
                s.pools.forEach { p -> Spacer(Modifier.height(14.dp)); poolCard(p, primes.firstOrNull { it.product + "_prime" == p.pool }) }
                Spacer(Modifier.height(14.dp))
                // ---- Chart ----
                Column(Modifier.fillMaxWidth().blakeCard()) {
                // Rig alerts: a push when this address stops submitting shares. PRO. The server
                // sees the shares, so it decides "quiet"; the switch only says this device wants it.
                run {
                    val pro = com.astrolexis.pyblock.data.store.EntitlementsStore.isPro
                    var rigAlerts by remember { mutableStateOf(com.astrolexis.pyblock.data.nostr.Nostr.rigAlerts(ctx)) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickableNoRipple {
                            if (!pro) { toast(ctx, ctx.getString(R.string.blk_rig_alerts_are_pro)); return@clickableNoRipple }
                            rigAlerts = !rigAlerts
                            com.astrolexis.pyblock.data.nostr.Nostr.setRigAlerts(ctx, rigAlerts)
                            com.astrolexis.pyblock.data.net.PushRepo.syncAddressesAsync(ctx)
                        }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.blk_alert_me_when_a_rig_goes_quiet), style = Blake.mono(9f, FontWeight.ExtraBold), color = if (pro) Blake.fg else Blake.ppDim, letterSpacing = 1.sp)
                            Text(if (pro) stringResource(R.string.blk_a_push_if_this_address_stops_submitting_) else stringResource(R.string.blk_pro_a_push_before_a_day_of_hash_is_lost), style = Blake.mono(7f), color = Blake.faint)
                        }
                        Text(if (!pro) "PRO" else if (rigAlerts) stringResource(R.string.blk_on) else stringResource(R.string.blk_off), style = Blake.mono(9f, FontWeight.ExtraBold), color = if (rigAlerts && pro) Blake.ok else Blake.pp)
                    }
                }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        sectionTitle(stringResource(R.string.blk_hashrate))
                        Spacer(Modifier.weight(1f))
                        val isPro = com.astrolexis.pyblock.data.store.EntitlementsStore.isPro
                        listOf("1d", "7d", "30d", "1y").forEach { r ->
                            val locked = r == "1y" && !isPro     // a year of history is PRO
                            Text(r, style = Blake.mono(9f, FontWeight.ExtraBold), color = if (range == r) Blake.pp else if (locked) Blake.ppDim else Blake.faint, letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = 6.dp).clickableNoRipple { if (locked) toast(ctx, ctx.getString(R.string.blk_a_year_of_history_is_pro)) else { Haptics.tap(); range = r } })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val v = history.map { it.hashrateTh ?: 0.0 }
                    val mx = v.maxOrNull() ?: 0.0
                    if (v.size > 1 && mx > 0) {
                        sparkline(v, Blake.pp, 90.dp, fill = true)
                        Spacer(Modifier.height(6.dp))
                        Row { Text(stringResource(R.string.blk_peak, BlakeRentals.th(mx)), style = Blake.mono(7f), color = Blake.faint); Spacer(Modifier.weight(1f)); Text(stringResource(R.string.blk_samples_3_min_each, v.size), style = Blake.mono(7f), color = Blake.faint) }
                    } else Text(stringResource(R.string.blk_no_samples_in_this_range_yet), style = Blake.mono(8f), color = Blake.faint)
                }
                Spacer(Modifier.height(18.dp))
                blocksList(s.blocks)
            }
            else -> {
                Text(stringResource(R.string.blk_no_shares_from_this_address_yet_point_a_), style = Blake.mono(9f), color = Blake.ppDim)
                if (s.blocks.isNotEmpty()) { Spacer(Modifier.height(18.dp)); blocksList(s.blocks) }
            }
        }

        // ---- Your own gateway (DATUM) ----
        // DATUM is the same product with the user's node in front: their gateway builds and
        // publishes the block, PyBLØCK only dictates the coinbase. Rigs point at their machine, so
        // the endpoint is theirs to keep here — a LAN address is the normal case.
        Spacer(Modifier.height(22.dp))
        Column(Modifier.fillMaxWidth().blakeCard()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnsuzRune(15.dp, Blake.datum); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.blk_datum_your_own_gateway), style = Blake.mono(11f, FontWeight.ExtraBold), color = Blake.datum, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text(if (addingGateway) stringResource(R.string.blk_cancel) else stringResource(R.string.blk_add_2), style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp, letterSpacing = 1.sp,
                    modifier = Modifier.clickableNoRipple { Haptics.tap(); addingGateway = !addingGateway; gwError = null })
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.blk_your_node_builds_and_publishes_the_block), style = Blake.mono(8f), color = Blake.faint)
            gateways.forEach { g ->
                Column(Modifier.fillMaxWidth().hairline().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.product.uppercase(), style = Blake.mono(8f, FontWeight.ExtraBold), color = poolColor(g.product), letterSpacing = 1.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (copied == g.stratumUrl) stringResource(R.string.blk_copied_2) else g.target, style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).clickableNoRipple {
                                Haptics.tap(); clip.setText(AnnotatedString(g.stratumUrl)); copied = g.stratumUrl
                                scope.launch { delay(1500); if (copied == g.stratumUrl) copied = null }
                            })
                        Text(if (probing == g.id) stringResource(R.string.blk_testing) else stringResource(R.string.blk_test), style = Blake.mono(8f, FontWeight.ExtraBold), color = if (probing == g.id) Blake.faint else Blake.datum, letterSpacing = 1.sp,
                            modifier = Modifier.clickableNoRipple {
                                if (probing == null) { Haptics.tap(); probing = g.id
                                    scope.launch { probeResult = probeResult + (g.id to com.astrolexis.pyblock.data.blake.OwnGateways.probe(g.host, g.port)); probing = null; Haptics.tap() } }
                            })
                        Spacer(Modifier.width(10.dp))
                        Text("✕", style = Blake.mono(11f), color = Blake.faint, modifier = Modifier.clickableNoRipple { Haptics.tap(); com.astrolexis.pyblock.data.blake.OwnGateways.remove(ctx, g.id); probeResult = probeResult - g.id })
                    }
                    probeResult[g.id]?.let { (ok, text) -> Text(text, style = Blake.mono(7f), color = if (ok) Blake.ok else Blake.danger) }
                }
            }
            if (addingGateway) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    listOf("carousel", "chirp", "wavicles").forEach { p ->
                        val on = gwProduct == p
                        Text(p.uppercase(), style = Blake.mono(8f, FontWeight.ExtraBold), color = if (on) Blake.bg else poolColor(p), letterSpacing = 1.sp,
                            modifier = Modifier.then(if (on) Modifier.background(poolColor(p), RectangleShape) else Modifier).border(1.dp, poolColor(p).copy(alpha = if (on) 1f else 0.5f), RectangleShape)
                                .padding(horizontal = 8.dp, vertical = 4.dp).clickableNoRipple { Haptics.tap(); gwProduct = p })
                    }
                }
                Spacer(Modifier.height(8.dp))
                sheetField(gwInput, "192.168.1.20:23334", androidx.compose.ui.text.input.KeyboardType.Uri) { gwInput = it }
                gwError?.let { Text(it, style = Blake.mono(8f), color = Blake.danger) }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.blk_save_gateway), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.bg, letterSpacing = 1.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(Blake.datum, RectangleShape).padding(vertical = 9.dp).clickableNoRipple {
                        val parsed = com.astrolexis.pyblock.data.blake.OwnGateways.parse(gwInput)
                        if (parsed == null) { gwError = ctx.getString(R.string.blk_write_it_as_host_port_the_installer_prin); return@clickableNoRipple }
                        val problem = com.astrolexis.pyblock.data.blake.OwnGateways.problem(parsed.first, parsed.second)
                        if (problem != null) { gwError = problem; return@clickableNoRipple }
                        Haptics.success()
                        com.astrolexis.pyblock.data.blake.OwnGateways.save(ctx, com.astrolexis.pyblock.data.blake.OwnGateway(product = gwProduct, host = parsed.first, port = parsed.second))
                        gwInput = ""; gwError = null; addingGateway = false
                    })
            }
            // The one-liner that leaves a gateway on the machine with the node. WAVICLES has its
            // own gateway config in its tab; the installer covers CAROUSEL and CHIRP.
            Spacer(Modifier.height(8.dp))
            val cmd = "curl -fsSL https://b.pyblock.xyz:8443/gateway | sh -s -- ${if (gwProduct == "wavicles") "carousel" else gwProduct} ${address.ifEmpty { "<your address>" }}"
            Text(stringResource(R.string.blk_run_this_on_the_machine_with_your_blake2), style = Blake.mono(7f), color = Blake.faint)
            Spacer(Modifier.height(4.dp))
            Text(if (copied == cmd) stringResource(R.string.blk_copied) else cmd, style = Blake.mono(8f), color = Blake.datum,
                modifier = Modifier.fillMaxWidth().border(1.dp, Blake.datum.copy(alpha = 0.4f), RectangleShape).padding(8.dp).clickableNoRipple {
                    Haptics.tap(); clip.setText(AnnotatedString(cmd)); copied = cmd
                    scope.launch { delay(1500); if (copied == cmd) copied = null }
                })
            if (gwProduct == "wavicles") Text(stringResource(R.string.blk_the_wavicles_gateway_config_is_in_the_wa), style = Blake.mono(7f), color = Blake.wave)
        }

        // ---- Connect ----
        Spacer(Modifier.height(22.dp))
        sectionTitle(stringResource(R.string.blk_connect_a_miner))
        Spacer(Modifier.height(6.dp))
        val c = connect
        if (c != null) {
            val host = c.host ?: "b.pyblock.xyz"
            val copyRow: (String, String) -> Unit = { _, v -> Haptics.tap(); clip.setText(AnnotatedString(v)) }
            Box(Modifier.clickableNoRipple { copyRow("HOST", host) }) { labelValue(stringResource(R.string.blk_host), host) }
            val user = if (address.isEmpty()) (c.username ?: "<payout address>[.worker]") else "$address.rig1"
            Box(Modifier.clickableNoRipple { copyRow("USERNAME", user) }) { labelValue(stringResource(R.string.blk_username), user) }
            labelValue(stringResource(R.string.blk_password), c.password ?: "x")
            c.pools.forEach { p ->
                val target = "$host:${p.port ?: 0}"
                Row(Modifier.fillMaxWidth().hairline().clickableNoRipple {
                    Haptics.tap(); clip.setText(AnnotatedString(target)); copied = target
                    scope.launch { delay(1500); if (copied == target) copied = null }
                }.padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.label ?: (p.pool ?: "").uppercase(), style = Blake.mono(9f, FontWeight.ExtraBold), color = poolColor(p.pool ?: ""), letterSpacing = 1.sp)
                            Spacer(Modifier.width(6.dp)); Text((p.kind ?: "").uppercase(), style = Blake.mono(7f), color = Blake.faint)
                            p.minDiff?.let { Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.blk_diff, it.toInt()), style = Blake.mono(7f), color = Blake.faint) }
                        }
                        p.split?.let { Text(it, style = Blake.mono(7f), color = Blake.faint) }
                    }
                    Text(if (copied == target) stringResource(R.string.blk_copied_2) else target, style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp)
                }
            }
            c.vardiff?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Blake.mono(7f), color = Blake.faint) }
            c.miners.keys.sorted().forEach { name ->
                val url = c.miners[name] ?: return@forEach
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp).clickableNoRipple {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }, verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp)
                    Spacer(Modifier.weight(1f))
                    Text("↗", style = Blake.mono(10f), color = Blake.ppDim)
                }
            }
        } else Text("⟳", style = Blake.mono(9f), color = Blake.faint)
    }
}

@Composable
private fun poolCard(p: BlakeMiner.PoolEntry, match: BlakeMiner.PrimeMatch? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // A Prime entry: the address mines through its own gateway; the port is the DATUM id.
    val isPrime = p.pool.endsWith("_prime")
    Column(Modifier.fillMaxWidth().blakeCard()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (p.live == true) Blake.ok else Blake.faint, CircleShape))
            Spacer(Modifier.width(6.dp))
            if (isPrime) { AnsuzRune(12.dp, Blake.datum); Spacer(Modifier.width(6.dp)) }
            // One line, always: the name wrapped as "CHIRP-/PRIME" once. The rune and the colour
            // already say Prime, so there is no tag on the right.
            Text(p.label ?: p.pool.uppercase(), style = Blake.mono(11f, FontWeight.ExtraBold), color = poolColor(p.pool), letterSpacing = 2.sp, maxLines = 1, softWrap = false)
            p.port?.takeIf { it > 0 }?.let { Spacer(Modifier.width(6.dp)); Text(":$it", style = Blake.mono(9f), color = Blake.faint) }
            Spacer(Modifier.weight(1f))
            if (!isPrime) p.sharePct?.takeIf { it > 0 }?.let { Text("%.1f%% of pool".format(java.util.Locale.US, it), style = Blake.mono(8f), color = Blake.ppDim) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            kpi("1m", p.hashrate1m); Spacer(Modifier.weight(1f)); kpi("5m", p.hashrate5m); Spacer(Modifier.weight(1f)); kpi("1h", p.hashrate1h); Spacer(Modifier.weight(1f)); kpi("1d", p.hashrate1d)
        }
        match?.sharePercent?.let { pct ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.blk_share_of_the_window), style = Blake.mono(8f), color = Blake.faint, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Text(String.format(java.util.Locale.US, "%.1f%%", pct), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg)
                match.payoutSats?.takeIf { it > 0 }?.let { sats -> Spacer(Modifier.width(6.dp)); Text("· " + stringResource(R.string.blk_would_receive, Blake.btc(sats)), style = Blake.mono(8f), color = Blake.ppDim) }
            }
        }
        if (isPrime) { Spacer(Modifier.height(6.dp)); Text(stringResource(R.string.blk_the_port_is_the_datum_id_your_rigs_point), style = Blake.mono(7f), color = Blake.faint) }
        if (p.workers.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Blake.line))
            p.workers.forEach { w ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (w.name.isNullOrEmpty()) stringResource(R.string.blk_default_worker) else w.name, style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg)
                        val rej = (w.sharesDiffRej ?: 0.0).toLong()
                        Text(stringResource(R.string.blk_diff_acc, shortAgent(w.agent), (w.vdiff ?: 0.0).toLong(), (w.sharesDiffAcc ?: 0.0).toLong(), if (rej > 0) " · rej $rej" else ""),
                            style = Blake.mono(7f), color = Blake.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(BlakeRentals.th(w.hashrateTh), style = Blake.mono(10f, FontWeight.ExtraBold), color = if ((w.hashrateTh ?: 0.0) > 0) Blake.ok else Blake.faint)
                        Text(w.lastshare?.let { stringResource(R.string.blk_share, relTimeSecs(ctx, it)) } ?: "no share yet", style = Blake.mono(7f), color = Blake.faint)
                    }
                }
            }
        }
    }
}

@Composable
private fun kpi(l: String, v: Double?) {
    Column {
        Text(BlakeRentals.th(v), style = Blake.mono(10f, FontWeight.ExtraBold), color = if ((v ?: 0.0) > 0) Blake.fg else Blake.faint)
        Text(l, style = Blake.mono(7f), color = Blake.faint)
    }
}

@Composable
private fun blocksList(list: List<BlakeMiner.MinerBlock>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    sectionTitle(stringResource(R.string.blk_blocks_paid_to_this_address))
    Spacer(Modifier.height(6.dp))
    if (list.isEmpty()) Text(stringResource(R.string.blk_none_yet), style = Blake.mono(8f), color = Blake.faint)
    else list.sortedByDescending { it.height }.take(50).forEach { b ->
        Row(Modifier.fillMaxWidth().hairline().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#${b.height}", style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg)
            Spacer(Modifier.width(10.dp))
            Text((b.pool ?: "").uppercase(), style = Blake.mono(8f, FontWeight.ExtraBold), color = poolColor(b.pool ?: ""), letterSpacing = 1.sp)
            if (b.role == "finder") { Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.blk_found), style = Blake.mono(7f, FontWeight.ExtraBold), color = Blake.ok, letterSpacing = 1.sp) }
            Spacer(Modifier.weight(1f))
            b.rewardSats?.let { Text("+${Blake.btc(it.coerceAtLeast(0))} ${Blake.RUNE}", style = Blake.mono(9f, FontWeight.ExtraBold), color = Blake.pp) }
            Spacer(Modifier.width(8.dp))
            Text(b.time?.let { relTimeSecs(ctx, it) } ?: "", style = Blake.mono(7f), color = Blake.faint)
        }
    }
}

/** A Prime the address is on: its own gateway, the block built by its node. The house numbers above
 *  don't include it (server-side gap, asked for), so this card is the truth for that path. */
@Composable
private fun primeCard(m: BlakeMiner.PrimeMatch) {
    Column(Modifier.fillMaxWidth().blakeCard()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (m.live) Blake.ok else Blake.faint, CircleShape))
            Spacer(Modifier.width(6.dp))
            AnsuzRune(12.dp, Blake.datum); Spacer(Modifier.width(6.dp))
            Text(if (m.product == "chirp") "CHIRP-PRIME" else "CAROUSEL-PRIME", style = Blake.mono(11f, FontWeight.ExtraBold), color = poolColor(m.product), letterSpacing = 2.sp, maxLines = 1, softWrap = false)
            m.port?.let { Spacer(Modifier.width(6.dp)); Text(":$it", style = Blake.mono(9f), color = Blake.faint) }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            BlakeStat(m.lastShareS?.let { stringResource(R.string.blk_s_ago, it.toString()) } ?: "—", stringResource(R.string.blk_last_share), if (m.live) Blake.ok else Blake.faint)
            Spacer(Modifier.weight(1f))
            BlakeStat(m.accepted?.toString() ?: "—", stringResource(R.string.blk_accepted), Blake.fg)
            Spacer(Modifier.weight(1f))
            BlakeStat(m.connectedS?.let { dur(it) } ?: "—", stringResource(R.string.blk_connected), Blake.ppDim, alignEnd = true)
        }
        m.sharePercent?.let { pct ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.blk_share_of_the_window), style = Blake.mono(8f), color = Blake.faint, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Text(String.format(java.util.Locale.US, "%.1f%%", pct), style = Blake.mono(10f, FontWeight.ExtraBold), color = Blake.fg)
                m.payoutSats?.takeIf { it > 0 }?.let { sats ->
                    Spacer(Modifier.width(6.dp))
                    Text("· " + stringResource(R.string.blk_would_receive, Blake.btc(sats)), style = Blake.mono(8f), color = Blake.ppDim)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.blk_prime_pool_your_rig_s_own_hashrate_isn_t, m.primeHashrateGhs?.let { BlakeRentals.th(it / 1000) } ?: "—"), style = Blake.mono(7f), color = Blake.faint)
    }
}
private fun dur(s: Int): String = when {
    s < 3600 -> "${s / 60}m"
    s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"
    else -> "${s / 86400}d ${(s % 86400) / 3600}h"
}

private fun poolColor(p: String): Color = when (p) {
    "chirp" -> Blake.ok
    "wavicles" -> Blake.wave
    "datum", "chirp_prime", "carousel_prime" -> Blake.datum
    "carousel", "lotto", "lotto_asic" -> Blake.pp
    else -> Blake.ppDim
}
private fun shortAgent(a: String?): String = if (a.isNullOrEmpty()) "miner" else if (a.length > 26) a.take(26) + "…" else a


private fun toast(ctx: android.content.Context, msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
