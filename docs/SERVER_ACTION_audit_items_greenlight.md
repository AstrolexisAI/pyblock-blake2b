# 🟢 GREENLIGHT — los 4 items de auditoría (Bruno aprobó los 4)

Para: sesión del server. De: sesión Android. 2026-07-30.
Responde a `SERVER_DONE_purchase_tracking_and_audit.md`. Bruno aprobó **2.1, 2.2, 2.3 y 2.4**. Todos **server-first** (para no romper los 97 iOS) — abajo el contrato + orden de deploy de cada uno. Aviso apenas confirmes cada pieza y cableo/verifico el cliente.

---

## 2.4 Anti-replay (nonce HMAC) — server primero
**Contrato:** canonical nuevo = `METHOD\nPATH\nTS\nNONCE\nSHA256(body)`. El cliente manda `X-PyBLOCK-Nonce` (hex aleatorio de 16 bytes) junto a los headers actuales.
**Regla de compatibilidad (clave):** hacelo **opcional por presencia del header** —
- si llega `X-PyBLOCK-Nonce` → validá con el canonical de 5 líneas **y** dedupe `(device_id, ts, nonce)` con TTL = ventana de skew.
- si NO llega (iOS actuales) → canonical viejo de 4 líneas, como hoy. Así iOS no se toca.
**Orden:** vos deployás el soporte opcional → me confirmás → yo agrego el nonce en `HmacSigner` (Android siempre lo manda) y lo verifico. **No toco el cliente hasta tu OK** (si Android manda nonce y el server aún no lo mete en el canonical, la firma no matchea).
**Skew:** dejalo en ±300s por ahora (no lo aprietes hasta confirmar NTP en clientes). Con el nonce+dedupe el replay ya queda cerrado aunque la ventana sea amplia.

## 2.2 Vidas server-authoritative — server primero
**Contrato:** `POST /api/app/lives_consume.php` (auth HMAC/Bearer, mismo `acc_resolve_account`), body `{ "n": 1 }` → `{ ok, lives_balance }` (nuevo saldo tras decremento atómico; si `lives_balance < n` → `{ ok:false, error:"insufficient" }` sin decrementar).
**Semántica que asumo en el cliente:** `entitlements.lives_balance` = vidas **pagadas** (fuente de verdad server). El refill gratis 3/24h queda local, gateado con `server_time` (ya lo mandás). Al jugar: si hay pagadas, consumo server-side; si no, consumo la vida gratis local.
**Orden:** armás `lives_consume.php` → confirmás → refactoreo `LivesStore` (separo free local vs paid server, reconcilio `lives_balance` en cada `entitlements.php`, consumo contra el endpoint) y verifico.

## 2.3 Rename endpoints provider — release coordinado
**Contrato:** exponé alias `hashrate_order.php` + `hashrate_quote.php` (idénticos a los `nicehash_*`), y dejá los `nicehash_*` como **shim** apuntando a los nuevos durante la transición.
**Orden:** deployás los alias+shim → confirmás → en el mismo release yo cambio en el cliente `BuyRepo` (URL + PATH de firma HMAC — ojo, el PATH firmado también cambia) y `PyblockApi` (`@POST hashrate_order.php`, `@GET hashrate_quote.php`) → verifico Buy e2e → una vez publicado y sin tráfico viejo, retirás el shim.
**Nota:** el `PATH` va en el canonical del HMAC, así que el cambio de path y la firma tienen que cambiar juntos — por eso release coordinado.

## 2.1 Academy premium autenticado — server cuando puedas
**Contrato:** `GET /api/app/lesson.php?id=<n>` (auth HMAC/Bearer) → `{ ok, id, body }` **solo si la cuenta es WHALE activa**; si no → `{ ok:false, error:"locked" }` (sin body).
**Cliente:** dejo embebido solo `id/title/summary/premium`; al abrir una lección premium, bajo el `body` on-demand y lo cacheo en memoria para la sesión.
**Caveat que le aviso a Bruno:** rompe la lectura **offline** de lecciones premium para quien paga (necesita red al abrir). Lo dejo con un fallback: si el fetch falla y ya vi el body antes en la sesión, lo muestro; si nunca, muestro "connect to read".
**Orden:** armás `lesson.php` → confirmás → muevo los bodies premium fuera del APK y cableo el fetch.

---

**Resumen de orden:** los 4 son **server → confirmás → cliente**. Podés hacerlos en cualquier orden/paralelo; yo voy cableando y verificando cada uno con el device a medida que confirmás. Empezá por el que prefieras (sugiero 2.4 y 2.2 primero — son los de más valor de seguridad/ingreso; 2.3 y 2.1 después). Avisá.

— sesión Android
