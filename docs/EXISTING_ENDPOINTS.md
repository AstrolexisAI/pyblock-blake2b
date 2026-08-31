# PyBLØCK — Inventario de endpoints existentes (contrato iOS, reutilizado por Android)

Generado del código de la app iOS (`/Users/curly/pyblock-ios/App/Sources`). Shapes exactos que la app **espera** hoy. La app Android consume estos mismos. Companion de `SERVER_INTEGRATION.md` (Parte A).

- Base: `https://pyblock.xyz:8443` · Externos: `https://mempool.guide`
- Nombres de campo son **exactos** (incluye renames de CodingKeys, ej. `hashrate_th`, `seconds_per_block`).

---

## Pool stats

### `GET /api.php?mode=pool` → LOTTO
`Workers:Int, hashrate1m:Double(TH/s), hashrate5m, hashrate15m, hashrate1d, hashrate7d:Double, accepted, rejected, bestshare, sps1m:Double`

### `GET /api.php?mode=datum` → DATUM (OCEAN relay)
`timestamp:Int, datetime:String, Workers:Int, hashrate1m:Double, earnings:String(BTC), unpaid:String(BTC), last_share:Int`

### `GET /api.php?mode=blocks` → bloques recientes
`lotto:Int, datum:Int, last_update:Int, blocks:[{ height:Int, hash:String, tag:String, protocol:String, relay:String?, confirmed:Bool, timestamp:Int }]`

### `GET /api.php?mode=workers` → todos los miners
`{ [address:String]: { Workers:Int, worker:[{ workername:String, hashrate1m:String("18.8K"/"163G"), hashrate5m, hashrate1hr, hashrate1d, hashrate7d:String, lastshare:Int, shares:Double?, bestshare:Double?, bestever:Double? }] } }`

### `GET /sv2_api.php?mode=pool` → SV2
`hashrate:Double(TH/s), workers:Int, blocks:Int, bestdiff:Double?`

### `GET /sv2_api.php?mode=workers` → leaderboard SV2
`miners:[{ address:String, hashrate:Double, workers:Int, channels:[{ worker:String, hashrate:Double?, shares:Int? }]?, best_diff:Double?, share_pct:Double? }]`

### `GET /sv2_api.php?mode=search&address=<addr>` → un miner SV2
`SV2Worker` (como arriba) o `{error:String}` / nil si no existe.

### `GET /chirp_api.php?mode=pool` → CHIRP
`hashrate:Double, workers:Int, blocks:Int, candidates:Int, bestdiff:Double?, min_days:Double, min_power:Double`

### `GET /chirp_api.php?mode=miners` → leaderboard CHIRP
`[{ address:String, days:Double, power:Double, weight:Double, eligible:Bool }]`

### `GET /api.php?mode=tmpl` → CAROUSEL stats
`hashrate_th:Double, miners:Int, suppliers:Int, bestdiff:Double?`

### `GET /api.php?mode=block_odds` → odds de todos los pools
`difficulty:Double, height:Int, updated:Int, pools:{ [name]: { hashrate_ths:Double, workers:Int, seconds_per_block:Double, odds:{ "24h":Double, "7d":Double, "30d":Double, "1y":Double } } }`
Nota: CAROUSEL viene bajo la key **`tmpl`** (no `carousel`).

---

## History / charts (series de hashrate)

Formato `HashratePoint`: `{ timestamp:Int, hashrate1m:Double(TH/s) }`

- `GET /api.php?mode=history&range=1d|7d|30d` → LOTTO
- `GET /api.php?mode=datum_history&range=…` → DATUM
- `GET /api.php?mode=tmpl_history&range=…` → CAROUSEL
- `GET /api/miner_history.php?address=<addr>&range=1d|7d|30d` → por dirección
- `GET /sv2_api.php?mode=history&hours=1|24|168` → SV2, formato `{ ts:Int, hashrate_th:Double, workers:Int? }`
- `GET /chirp_api.php?mode=history&hours=1|24|168` → CHIRP, mismo formato que SV2

---

## Clean Blocks — `/cleanblocks.php`

### `GET ?data=1` → feed (~50 bloques + tip)
`tip:Int?, blocks:[CBBlock]`
`CBBlock = { height:Int, hash:String?, txs:Int, time:Int, pool:String?, ours:FlexBool?(0/1/false/true), fees_sats:Int, vsize:Int, parasite_ratio:Double?, clean:Bool, coinbase_sig:String?, breakdown:{ inscriptions:Int, runes:Int, opreturn:Int, baremultisig:Int }?, pending:Int?(0/1) }`

### `GET ?older=<height>&count=15` → paginado hacia atrás → `{ blocks:[CBBlock] }`
### `GET ?pending=1` → candidato de mempool (next block) → `CBDetail` o nil
### `GET ?detail=<height>` → detalle + treemap
`CBBlock` + `{ clean_n:Int, dirty_n:Int, clean_vb:Int, dirty_vb:Int, clean_fees_sats:Int, dirty_fees_sats:Int, coinbase_hex:String?, txlist:[{ v:Int(vsize), d:Int(1=dirty) }]? }`
### `GET ?stats=1` → rollup 24h
`{ blocks:Int, clean_blocks, parasite_blocks, ours_blocks, total_tx, clean_tx, parasite_tx, insc, runes, opret, bare, total_vb, parasite_vb, clean_fees, parasite_fees : Int }`

---

## Templates / Carousel / Node map

### `GET /template_data.php` → agregados del template
`height:Int, txCount:Int, blockUsage:Double, totalWeight:Int, totalVBytes:Int, totalFeeBTC:String, minFeeRate, medianFeeRate, maxFeeRate, p10Fee, p90Fee:Double`
### `GET /template_txs.php` → txs para treemap → `[{ txid:String, vbytes:Double, feerate:Double, fee:Int }]`
### `GET /templates.php?carrousel=1` → rotación live → `{ live:Bool, miners:Int, hashrate:Double, recent:[[supplier:String?, ts:Int?]] }`
### `GET /templates.php?data=1` → snapshot suppliers (JSON con fragmento HTML; los `data-*` de cada fila son el contrato estable)
`{ total, acc, rej, nsup, miners:Int, hashrate_str:String, suppliers:"<html>" }` → cada fila: `data-name, data-port, data-fulladdr, data-txs, data-h(height), data-cb(coinbaseSats), data-fees, data-miners, data-fresh(0/1)` + clases CSS `dot live` / `dot hold`.
### `GET /nodemap.php?data=1` → mapa de nodos
`ts:Int, anchors:{ [region]:{ lat:Double, lon:Double, label:String } }, points:[{ node:String, net:String, inbound:Bool, geo:{ lat, lon:Double, cc:String?, country:String? }, ping:Double?, subver:String? }], supplier_count:Int?, supplier_pings:[Double]?`
### `GET /data/world_land.min.json` → GeoJSON de costas (~87KB, cachear agresivo)

---

## OCEAN fallback

### `GET /api/ocean_search_full.php?address=<addr>` → o nil (4xx→nil, no tira error)
`address:String, earnings:{ shares_in_window:String?, estimated_rewards_window:String?, estimated_earnings_next_block:String? }, workers:{ count:Int, active:Int }, hashrate:{ current, avg_24h, min_24h, max_24h:Double, last_update:String(ISO8601), data_points:Int, history:[{ timestamp:String(ISO), hashrate:Double }] }, lifetime:{ share_log_percent:Double?, estimated_earnings_per_day:String?, lifetime_earnings:String? }, unpaid:{ unpaid_earnings:String?, estimated_payout_next_block:String?, onchain_threshold:String?, blocks_found:Int? }`

---

## Compra de hashrate (Lightning) — familia `/api/app/`

Estados de orden: `pending_payment | paid | submitted | active | settled | expired | failed`

### `GET /api/app/quote.php?port=&amount_sats=&hashrate_phs=` (pública)
`{ ok:Bool, quote:{ amount_sats:Int, fee_sats:Int, total_sats:Int, duration_h:Double, fee_pct:Double, price_sats_phs_h:Double, price_source:String }?, max_amount_sats:Int?, errors:[String]? }`

### `POST /api/app/order.php` (HMAC; self-heal en 403)
body `{ btc_address:String, port:Int, amount_sats:Int, hashrate_phs:Double }`
→ `{ ok:Bool, order_id:String?, invoice:String?(BOLT11), total_sats:Int?, expires_at:Int?, status:String?, errors:[String]? }`

### `GET /api/app/status.php?id=<order_id>` (pública)
`{ ok:Bool, order:OrderStatusPayload?, errors:[String]? }`
`OrderStatusPayload = { id:String, btc_address:String, port:Int, amount_sats:Int, fee_sats:Int, total_sats:Int, hashrate_phs:Double, duration_h:Double, ln_invoice:String, status:String, consumed_sat:Int, created_at:Int, updated_at:Int, expires_at:Int, bid_started_at:Int?, error_msg:String? }`

### `GET /api/app/orders_by_address.php?address=<addr>` (pública) → `{ ok, orders:[OrderStatusPayload]?, count:Int?, errors? }`
### `GET /api/app/orders_by_device.php` (HMAC) → mismo shape; rehidrata tras reinstall

### Proveedores alternos (mismo patrón invoice):
- `GET /api/app/mrr_rigs.php` → `{ ok, rigs:[{ id:String, ph:Double, sat_ph_h:Int, sat_ph_day:Int, min_hours:Int, name:String }]?, max_sats:Int?, errors? }`
- `GET /api/app/mrr_quote.php?rig_id=&hours=` → `{ ok, quote:{ rig_id, rig_name:String, hashrate_phs, duration_h, min_hours, price_sats_phs_h:Double, amount_sats, fee_sats, total_sats:Int, fee_pct:Double }?, max_sats?, errors? }`
- `POST /api/app/mrr_order.php` (HMAC) body `{ btc_address, port, rig_id, hours }` → shape de order.php
- `GET /api/app/nicehash_quote.php?hashrate_phs=&hours=` → `{ ok, quote:{ hashrate_phs, duration_h, price_sats_phs_h:Double, amount_sats, fee_sats, total_sats:Int, fee_pct:Double }?, max_sats?, errors? }`
- `POST /api/app/nicehash_order.php` (HMAC) body `{ btc_address, port, hashrate_phs, hours }` → shape de order.php

> ⚠️ Recordatorio: **0 menciones de NiceHash en la UI de la app**. El path del server se llama `nicehash_*` pero la app nunca muestra "NiceHash" ni nº de orden del proveedor.

### Renovación (recordatorios no-custodiales) — `/api/app/renewal.php` (HMAC)
- `POST` body `{ btc_address, port, amount_sats, hashrate_phs, cadence_days }` → `{ ok, plan:{ id:String, btc_address, port:Int, amount_sats:Int, hashrate_phs:Double, cadence_days:Int, next_fire_at:Int, enabled:Bool, created_at:Int }?, errors? }`
- `GET` → `{ ok, plans:[RenewalPlan]?, errors? }`
- `DELETE ?id=<plan_id>` → status

---

## Device / push / prefs

- `POST /api/devices.php` body `{ token, platform, bundle, environment, preferences:{ lotto_block, bip110_block, my_address : Bool } }` → `{ ok, device_id:Int, created:Bool, secret:String? }`
- `POST /api/devices_anonymous.php` body `{ platform, bundle }` → mismo shape (para HMAC sin push)
- `POST /api/app/addresses.php` (HMAC) body `{ addresses:[String] }` → status (full-replace lista trackeada)
- `POST /api/app/alert_prefs.php` (HMAC) body `{ hashprice_threshold:Int }` (sat/PH/h; 0=off) → status
- `POST /api/app/delete.php` (HMAC) → borra device + data

---

## Juego (leaderboard) — `/api/app/defender_scores.php`
- `POST` body `{ name:String, handle:String(16hex), score:Int, wave:Int, difficulty:String }` → `{ ok, rank:Int?, top:[DefenderScore]? }`
- `GET ?diff=<difficulty>` → `{ ok, top:[{ name:String, score:Int, wave:Int, difficulty:String, ts:Int }]? }`

---

## Externos (mempool.guide, Knots-aligned)
- `GET https://mempool.guide/api/v1/difficulty-adjustment` → `{ progressPercent, difficultyChange:Double, estimatedRetargetDate:Int(ms), remainingBlocks:Int, remainingTime:Int(ms) }`
- `GET https://mempool.guide/api/v1/prices` → `{ USD:Double, ... }` (cache 5 min)

---

## Auth HMAC-SHA256 (endpoints privados)
Aplica a: `order.php, orders_by_device.php, addresses.php, alert_prefs.php, delete.php, mrr_order.php, nicehash_order.php, renewal.php`.
Firma: `HMAC_SHA256(secret, "METHOD\nPATH\nTIMESTAMP\nSHA256(BODY)")`
Headers: `X-PyBLOCK-Device-Id`, `X-PyBLOCK-Timestamp`(unix s), `X-PyBLOCK-Signature`(hex).
`secret`+`device_id` de `devices.php` / `devices_anonymous.php`. En 403 → re-registrar anónimo y reintentar.
