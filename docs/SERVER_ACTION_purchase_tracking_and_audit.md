# 🟠 ACCIÓN REQUERIDA — tracking de compras por device + items de auditoría

Para: sesión del server (backend PHP `pyblock.xyz:8443`). De: sesión Android. 2026-07-30.
Dos bloques: **(1) tracking de compras por teléfono en el admin dashboard** (pedido directo de Bruno, prioritario) y **(2) items server derivados de la auditoría pre-publicación del cliente**.

---

## 1. 🔴 PRIORITARIO — Compras registradas por teléfono + seguimiento en admin_hashrate dashboard

**Requerimiento (Bruno):** cada compra tiene que quedar **registrada al teléfono (device) que la hizo**, la invoice tiene que tener registrada esa compra, y el server tiene que **registrarla y llevar seguimiento en el dashboard `admin_hashrate`**.

Aplica a **ambos** flujos de pago Lightning:
- **Productos digitales** (`purchase.php`): Pro / Whale / lives / skins.
- **Hashrate** (`nicehash_order.php` / `order.php`): paquetes/custom.

Ambos ya vienen autenticados por **HMAC de device** (el cliente firma con `device_id + secret`), así que el server **ya sabe el `device_id` en el momento de crear la orden** — el pedido es **persistirlo y exponerlo**. Concretamente:

**a) Persistir por compra/orden** (tabla de purchases y de hashrate orders):
- `device_id` (comprador) — SIEMPRE, incluso si además hay `pubkey` linkeada.
- `pubkey` / account resuelta (si el device está linkeado via LNURL-auth).
- `product_id` (o params de hashrate: hashrate_phs, hours, port/pool, btc_address).
- `purchase_id` / `order_id`, `amount_sats`, `bolt11` (o su `payment_hash`), `created_at`, `expires_at`.
- `status`: `pending` | `paid` | `expired`, y `paid_at`.
- efecto aplicado: `tier_granted` + `tier_expires_at` (o lives/skin) — el resultado del pago.

**b) Vincular la invoice a la compra:** el `payment_hash` / memo del BOLT11 debe mapear inequívocamente al `purchase_id`/`order_id` y por ende al `device_id`. (En la app se vio que el memo ya dice p.ej. `PyBLOCK pro.monthly #f67940ea` — bueno; que ese id resuelva a device.)

**c) Dashboard `admin_hashrate`:** agregar/मostrar por device:
- lista de compras (digitales + hashrate) con device_id, producto, monto sats, estado, fecha, tier otorgado + vencimiento.
- filtro/orden por device_id, por estado (pending/paid/expired), por fecha.
- idealmente: total sats cobrado por device, suscripción activa sí/no + expiry, historial de renovaciones.

**d) Merge:** cuando un device linkea wallet (LNURL-auth) y se hace merge device↔pubkey, el historial de compras del device debe **seguir visible/atribuible** (no perderse en el merge). Mostrar ambas claves.

> Nota: el cliente NO necesita cambios para esto — ya manda `device_id` autenticado en cada compra. Es todo persistencia + UI de dashboard server-side. Si querés que el cliente mande algún campo extra (p.ej. un label de device legible), decime y lo agrego al request.

---

## 2. Items server de la auditoría pre-publicación

La auditoría del cliente (4 dimensiones) ya la resolví del lado app (allowBackup=false + EncryptedSharedPreferences, sanitizer de marca centralizado, R8 keep-rules, etc.). Estos quedan del lado server:

**2.1 [HIGH gating] Lecciones premium de Academy servidas autenticadas.**
Hoy el cliente trae los 7 `body` premium embebidos en el APK → un decompile los revela sin pagar (el gate es solo client-side). Para que el gate WHALE proteja contenido real: exponé el `body` de lecciones premium en un endpoint autenticado (p.ej. `GET /api/app/lesson.php?id=` con HMAC/Bearer) que devuelva el texto **solo si la cuenta es WHALE**. El cliente mandaría solo `id/title/summary/premium` embebidos y bajaría el body premium on-demand. *(Contenido de bajo secreto — educación Bitcoin genérica — así que es tu decisión si vale el round-trip. Lo dejo como recomendación.)*

**2.2 [MED] Vidas server-authoritative.**
Hoy `LivesStore` es client-side: el reloj de refill (3/24h) usa el reloj del device (adelantable) y las vidas **pagadas** (`lives10/30`) son contadores locales no reconciliados con `livesBalance` del server. Pedido: que el server sea la fuente de verdad de las vidas **pagadas** (consumir/otorgar contra el server, y que la app reconcilie `livesBalance` al arrancar). El refill gratis local es aceptable si las pagadas están protegidas; si querés, gatealo con `server_time` (ya lo mandás en Entitlements).

**2.3 [defensa] Renombrar endpoints con nombre de provider.**
`nicehash_order.php` / `nicehash_quote.php` filtran el provider por el path a cualquiera que decompile el APK (sideload sin ofuscar). Recomiendo renombrarlos a neutrales: `hashrate_order.php` / `hashrate_quote.php`. Si lo hacés, avisá y actualizo `BuyRepo`/`PyblockApi` en el cliente en el mismo release (coordinemos para no romper). No es user-visible, es defensa-en-profundidad.

**2.4 [confirmar] Ventana anti-replay del HMAC.**
El cliente firma `METHOD\nPATH\nTS\nSHA256(body)` sin nonce. Confirmá que el server (a) rechaza timestamps fuera de una ventana chica (±30–60s, ya vi skew 300s en `app_auth_device` — quizá apretarlo) y (b) idealmente cachea (device_id, ts, sig) para rechazar duplicados. Si querés que agregue un `X-PyBLOCK-Nonce` al canonical, decime y lo sumo a `HmacSigner` en ambos lados.

---

Avisá cuando esté (sobre todo el bloque 1 — tracking por device en el dashboard) y re-testeo compra + verifico que aparezca atribuida al device en `admin_hashrate`.

— sesión Android
