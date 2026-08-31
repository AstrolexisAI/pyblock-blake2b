# PyBLØCK Android ↔ Server — Integration Contract

Documento para diseñar el lado servidor (`pyblock.xyz:8443`, PHP) de forma que la app Android nativa (Kotlin/Compose, APK sideload, 100% Lightning) se comunique sin fricción.

Dos partes:
- **Parte A — Ya existe** (la app iOS lo consume hoy; Android lo reutiliza tal cual). No hay que rediseñarlo, solo **no romperlo** y aplicar los 2 ajustes marcados `⚠️ ANDROID`.
- **Parte B — Nuevo** (hay que construirlo para el modelo Lightning de Android): LNURL-auth, catálogo + invoices de productos digitales, entitlements time-based, y update-check.

Convenciones globales:
- Base URL: `https://pyblock.xyz:8443`
- Todo JSON. Éxito/fracaso: `{ "ok": true, ... }` / `{ "ok": false, "errors": ["msg"] }`
- Timestamps: Unix **segundos** (Int). Montos: **sats enteros** (Int). Nunca floats para dinero.
- **✅ TLS: OK, no hay bloqueante.** `:8443` sirve un cert **Let's Encrypt válido** (CN=pyblock.xyz, `ssl_verify=0`, vigente hasta 2026-09-08, autorrenovación certbot). Verificado desde la Mac sin `-k`. El `-k` de mis pruebas iniciales fue por pegarle por IP/`localhost` sin SNI (Apache devuelve el cert del vhost default → mismatch). Con hostname `pyblock.xyz` valida solo. **Android: OkHttp/Retrofit conectan sin config, sin pinning, sin excepciones.** (Corrección confirmada por la sesión del server, 2026-07-30.)

---

## PARTE A — Contrato existente (reutilizado)

La app iOS ya habla con 48 endpoints en `pyblock.xyz:8443` (+ 2 externos en `mempool.guide`). La app Android usa los mismos. Inventario completo con shapes exactos: ver `docs/EXISTING_ENDPOINTS.md` (generado del código iOS). Resumen de familias:

| Familia | Ejemplos de path | Auth | Uso |
|---|---|---|---|
| Pool stats | `/api.php?mode=pool\|datum\|blocks\|workers\|block_odds`, `/sv2_api.php`, `/chirp_api.php`, `/api.php?mode=tmpl` | pública | Stats de los 5 pools + odds |
| History/charts | `/api.php?mode=history\|datum_history\|tmpl_history&range=`, `/sv2_api.php?mode=history&hours=`, `/api/miner_history.php` | pública | Series de hashrate |
| Clean Blocks | `/cleanblocks.php?data=1\|older=\|pending=1\|detail=\|stats=1` | pública | Explorer de bloques |
| Templates/Carousel | `/template_data.php`, `/template_txs.php`, `/templates.php?carrousel=1\|data=1` | pública | Treemap + carousel |
| Node map | `/nodemap.php?data=1`, `/data/world_land.min.json` | pública | Mapa de nodos |
| OCEAN fallback | `/api/ocean_search_full.php?address=` | pública | Earnings OCEAN |
| **Compra hashrate (LN)** | `/api/app/quote.php`, `/order.php`, `/status.php`, `/orders_by_address.php`, `/orders_by_device.php`, `/mrr_*`, `/nicehash_*`, `/renewal.php` | mixta (ver auth) | **Flujo de pago LN de hashrate — ya existe, se reutiliza** |
| Device / push | `/api/devices.php`, `/api/devices_anonymous.php`, `/api/app/addresses.php`, `/api/app/alert_prefs.php`, `/api/app/delete.php` | mixta | Registro + prefs de push |
| Juego | `/api/app/defender_scores.php` | pública (handle) | Leaderboard Node Defender |

### Auth existente (HMAC-SHA256) — reutilizar tal cual
Endpoints escritos/privados (`order.php`, `orders_by_device.php`, `addresses.php`, `alert_prefs.php`, `delete.php`, `mrr_order.php`, `nicehash_order.php`, `renewal.php`) firman con:

```
firma = HMAC_SHA256( secret, "METHOD\nPATH\nTIMESTAMP\nSHA256(BODY)" )
```
Headers: `X-PyBLOCK-Device-Id`, `X-PyBLOCK-Timestamp` (unix s), `X-PyBLOCK-Signature` (hex).
El par `device_id` + `secret` sale de `POST /api/devices.php` (con push token) o `POST /api/devices_anonymous.php` (sin token). Self-heal: en 403 la app re-registra anónimo y reintenta.

> **La compra de hashrate ya es Lightning** (el `order.php`/`nicehash_order.php` devuelven `invoice` BOLT11). Android lo consume igual. Lo NUEVO (Parte B) es aplicar el mismo patrón LN a los **productos digitales** (Pro/Whale/vidas/skins) que en iOS son Apple IAP.

### ⚠️ ANDROID — 2 ajustes en Parte A

**A1. Registro de device para push Android.** `POST /api/devices.php` hoy asume `platform:"ios"` + APNs token. Extender:
```jsonc
// POST /api/devices.php
{
  "platform": "android",
  "bundle": "com.astrolexis.pyblock",
  "push_provider": "fcm" | "unifiedpush",   // NUEVO
  "token": "<FCM token>",                     // si fcm
  "endpoint": "https://ntfy.sh/....",         // si unifiedpush (URL de entrega)
  "preferences": { "lotto_block": true, "bip110_block": true, "my_address": true }
}
```
Respuesta igual que iOS: `{ ok, device_id, created, secret? }`.
El worker de push debe ramificar por `push_provider`: APNs (iOS) / FCM (Android c/ Google Play Services) / POST al `endpoint` (UnifiedPush/ntfy, para equipos de-Googled). **Decisión pendiente de Bruno:** soportar FCM, UnifiedPush, o ambos. Recomendado ambos (FCM default, UnifiedPush opcional on-brand).

**A2. Payload de push** — mismo shape que iOS (`{ "pyblock": { "kind": "...", "height", "hash", ... } }`), solo cambia el transporte.

---

## PARTE B — Nuevo (modelo Lightning digital de Android)

Objetivo: reemplazar StoreKit (Apple IAP) por Lightning para **Pro, Whale, packs de vidas y skins**. Identidad por **LNURL-auth**; entitlements **time-based** en el server como fuente de verdad.

### B0. Identidad — LNURL-auth (linking key = cuenta)

Login sin passwords: el usuario firma un challenge con su wallet Lightning; el server obtiene su **linking key** (pubkey secp256k1) que es el ID de cuenta permanente.

```jsonc
// 1) La app pide un challenge
GET /api/app/auth/lnurl.php
→ { "ok": true, "k1": "<32-byte hex>", "lnurl": "LNURL1..." }   // lnurl = bech32 de la callback URL con tag=login

// 2) La wallet del usuario abre el LNURL y golpea la callback (flujo LNURL-auth estándar):
GET /api/app/auth/callback.php?tag=login&k1=<hex>&sig=<hex>&key=<pubkey hex>
   → server verifica secp256k1( key, k1, sig ). Si ok, liga k1 ↔ key y marca autenticado.
   → { "status": "OK" }   // formato LNURL estándar

// 3) La app poolea hasta que el k1 quede autenticado y recibe un token de sesión
GET /api/app/auth/status.php?k1=<hex>
→ { "ok": true, "authenticated": true, "session_token": "<opaque>", "pubkey": "<hex>" }
```
- `session_token` (o el `pubkey`) se manda luego como `Authorization: Bearer <token>` en los endpoints de Parte B.
- **Vincular con el device HMAC existente:** al autenticar, ligar `pubkey ↔ device_id` para que órdenes de hashrate (Parte A) y entitlements (Parte B) sean la misma cuenta. Guardar la relación server-side.
- "Restore purchases" = simplemente volver a hacer LNURL-auth con la misma wallet → mismo `pubkey` → mismos entitlements. Sin cuentas ni emails.

### B1. Estado de entitlements (fuente de verdad)

```jsonc
GET /api/app/entitlements.php            // auth: Bearer
→ {
  "ok": true,
  "tier": "free" | "pro" | "whale",
  "tier_expires_at": 1790000000,          // unix s; null si free
  "skins": ["matrix","cyber","gold"],     // no-consumibles poseídos
  "lives_balance": 12,                     // consumible acumulado
  "server_time": 1789990000               // para que el cliente calcule expiry sin reloj propio
}
```
- `tier` se deriva de `max(now, tier_expires_at)`: si `tier_expires_at < now` ⇒ `free`.
- El cliente cachea esto; el server manda `server_time` para no depender del reloj del teléfono.

### B2. Catálogo de productos

Reemplaza la lista de StoreKit. Precios en **sats** (auto-cubierto vs BTC). Opcional `display_fiat` solo para mostrar.

```jsonc
GET /api/app/products.php
→ {
  "ok": true,
  "products": [
    { "id": "pro.monthly",  "kind": "subscription", "period_days": 30,  "price_sats": 2500, "display_fiat": "$2.99", "grants_tier": "pro" },
    { "id": "pro.annual",   "kind": "subscription", "period_days": 365, "price_sats": 21000,"display_fiat": "$24.99","grants_tier": "pro" },
    { "id": "whale.monthly","kind": "subscription", "period_days": 30,  "price_sats": 8000, "display_fiat": "$9.99", "grants_tier": "whale" },
    { "id": "whale.annual", "kind": "subscription", "period_days": 365, "price_sats": 75000,"display_fiat": "$89.99","grants_tier": "whale" },
    { "id": "lives10",      "kind": "consumable",    "grant_lives": 10,  "price_sats": 1700, "display_fiat": "$1.99" },
    { "id": "lives30",      "kind": "consumable",    "grant_lives": 30,  "price_sats": 4200, "display_fiat": "$4.99" },
    { "id": "skins",        "kind": "nonconsumable", "grant_skins": "all","price_sats": 2500,"display_fiat": "$2.99" }
  ]
}
```
- `price_sats` puede ser fijo, o calculado live desde un ancla fiat (tu call). Si es fiat-anclado, recalculalo en cada `GET` y **congelalo al emitir la invoice** (B3) para que el usuario pague lo cotizado.

### B3. Compra → invoice Lightning

```jsonc
POST /api/app/purchase.php                // auth: Bearer
{ "product_id": "whale.annual" }
→ {
  "ok": true,
  "purchase_id": "<uuid>",
  "invoice": "lnbc...",                    // BOLT11
  "amount_sats": 75000,
  "expires_at": 1789990600                 // expiry de la invoice
}
```

```jsonc
GET /api/app/purchase_status.php?id=<uuid>   // auth: Bearer (o público por uuid, tu call)
→ { "ok": true, "status": "pending" | "paid" | "expired" }
```
- Al detectar pago (webhook de tu nodo LN / poll), el server **aplica el efecto** y persiste:
  - `subscription` → extiende `tier_expires_at = max(now, tier_expires_at) + period_days`, setea `tier = grants_tier`.
  - `consumable` → `lives_balance += grant_lives`.
  - `nonconsumable` → agrega skins a `skins[]`.
- La app, tras `paid`, re-consulta `GET /entitlements.php` para refrescar estado. (Idempotencia: aplicar el efecto una sola vez por `purchase_id`.)

### B4. Suscripción time-based (Etapa 1) + NWC (Etapa 2)

- **Etapa 1 (time-based):** todo lo de B2/B3. Al acercarse `tier_expires_at`, la app avisa y el usuario re-paga (nueva invoice). El server **no** necesita nada extra: renovar = otra compra del mismo producto.
- **Etapa 2 (NWC auto-renew):** **client-side**, el server no cambia. La app guarda una conexión **Nostr Wallet Connect** del usuario (con presupuesto), y cuando `tier_expires_at` se acerca, la app pide la invoice a `POST /purchase.php` y **la paga sola** vía NWC. El server ve un pago normal. → No hay que almacenar la conexión NWC en el server (mejor privacidad).

### B5. Update-check del APK (sideload, no auto-actualiza)

```jsonc
GET /api/app/android_version.php
→ {
  "ok": true,
  "latest_version_code": 18,
  "latest_version_name": "0.3.0",
  "apk_url": "https://pyblock.xyz/download/pyblock-0.3.0.apk",
  "sha256": "<hex del APK>",              // la app verifica el hash tras descargar
  "min_supported_version_code": 10,       // si el device está por debajo, forzar update
  "changelog": "…"
}
```
- La app chequea al iniciar; si hay versión nueva, ofrece descargar el APK y verifica el `sha256` antes de instalar. Publicá también el `sha256` en la página de descarga para verificación manual.

---

## Checklist para la sesión del server

- [x] ~~TLS válido en `:8443`~~ **YA HECHO** (Let's Encrypt válido, verificado 2026-07-30).
- [ ] A1: extender `/api/devices.php` a `platform:"android"` + `push_provider` (FCM/UnifiedPush).
- [ ] A2: worker de push ramifica por provider.
- [ ] B0: LNURL-auth (`auth/lnurl.php`, `auth/callback.php`, `auth/status.php`) + ligar `pubkey ↔ device_id`.
- [ ] B1: store de entitlements por `pubkey` (tier+expiry, skins, lives) + `entitlements.php`.
- [ ] B2: `products.php` (precios en sats).
- [ ] B3: `purchase.php` + `purchase_status.php` + webhook/poll de tu nodo LN que aplica efectos idempotentes.
- [ ] B5: `android_version.php` + hosting del APK + publicar SHA-256.
- [ ] (Etapa 2) nada server-side para NWC — es client-side.

Cualquier cambio de nombres de campos/paths, avisame y actualizo el cliente para que matcheen exacto.
