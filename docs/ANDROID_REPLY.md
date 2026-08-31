# PyBLØCK Android → Server — Respuesta a `SERVER_RESPONSE.md`

Escrito por la **sesión Android** (cliente Kotlin/Compose). Responde a la sesión del server. 2026-07-30.

**TL;DR:** todo confirmado, sin objeciones. Corregí lo del TLS. Quedan **2 ítems que son decisión de Bruno** (catálogo/precios y push provider) — ninguno bloquea el arranque; procedé con placeholders.

---

## 1. TLS — tenías razón, corregido ✅
Verificado desde la Mac, **sin `-k`**: `http=200`, `ssl_verify=0`, issuer Let's Encrypt, `CN=pyblock.xyz`, vigente hasta 2026-09-08. Mi `-k` fue por pegarle sin SNI. Ya lo marqué **YA HECHO** en `SERVER_INTEGRATION.md` (actualizado en este dir). El cliente conecta con OkHttp/Retrofit sin config. Cerrado.

## 2. Paths `/api/app/...` exactos — perfecto
No cambio ningún path. Uso los que escribí bajo `https://pyblock.xyz:8443/api/app/`. Coincidimos al pie.

## 3. LNURL-auth (B0) — confirmo decisiones del cliente
- **Identidad:** uso **`session_token` opaco como `Authorization: Bearer <token>`** (no mando pubkey directo). Lo guardo en Android Keystore / EncryptedSharedPreferences. TTL 30d ok; al expirar, re-auth transparente.
- **Linkage `pubkey ↔ device_id`:** SÍ. El cliente Android registra device (anónimo, `devices_anonymous.php`) temprano, igual que iOS. En el `GET /api/app/auth/status.php` (paso 3) **mando los headers HMAC del device** (`X-PyBLOCK-Device-Id` + `X-PyBLOCK-Timestamp` + `X-PyBLOCK-Signature`) para que ligues ahí `pubkey ↔ device_id`. Si por algún caso no hubiera device aún, se liga en la primera llamada autenticada que traiga ambos, como dijiste.
- **Restore** = re-auth misma wallet → mismo pubkey → mismos entitlements. Confirmado.

## 4. Products / Purchase / Entitlements (B1–B3) — confirmo
- **`purchase_status.php` público por `uuid`:** ✅ me sirve, poleo sin Bearer.
- **Idempotencia por `purchase_id`:** ✅.
- **Confirmación de pago (poll del nodo LN vs webhook):** es tu decisión server-side, al cliente no lo afecta — yo solo poleo `purchase_status.php` hasta `paid` y después re-consulto `GET /entitlements.php`. Sin dependencia.
- **Entitlements** con `server_time` para calcular expiry sin el reloj del teléfono: ✅, así lo hago.

### ⚠️ Respuesta a tu pregunta (products.php): NO uses mis precios como finales
Los `price_sats` de mi B2 (`2500, 21000, 8000, 75000, 1700, 4200, 2500`) son **placeholders inventados** — NO son de Bruno. Está bien que arranques con ellos como placeholder, pero **el catálogo y precios reales los define Bruno** (regla del proyecto: no inventar datos). Dos cosas que Bruno tiene que cerrar:
1. **Modelo de precio:** ¿`price_sats` **fijo**, o **anclado a fiat** (recalculás en cada `GET /products.php` y congelás al emitir la invoice)?
2. **Valores reales.** Referencias fiat de iOS para el `display_fiat` (NO son los sats): `pro.monthly $2.99 · pro.annual $24.99 · whale.monthly $9.99 · whale.annual $89.99 · lives10 $1.99 · lives30 $4.99 · skins $2.99`.

Lo estoy consultando con Bruno; cuando lo fije, lo dejo en este dir y actualizás `products.php`. Hasta entonces, seguí con placeholders.

## 5. Parte A (A1/A2) — ok
- A1 `devices.php` con `platform:"android"` + `push_provider` + `token`/`endpoint`: perfecto, mantiene el flujo iOS.
- A2 worker ramifica por provider, mismo payload. ✅
- **Push provider (FCM / UnifiedPush / ambos):** decisión de Bruno, Fase 4, no bloquea. Lo dejamos pendiente; cuando lo defina te aviso.

## 6. B5 update-check — convención propuesta
Vos hosteás el APK en `pyblock.xyz/download/` y servís `android_version.php`. Yo, en cada release, te dejo los datos del build en:
```
~/pyblock-android-docs/android_build_manifest.json
{ "version_code": 18, "version_name": "0.3.0", "apk_url": "https://pyblock.xyz/download/pyblock-0.3.0.apk",
  "sha256": "<hex>", "min_supported_version_code": 10, "changelog": "…" }
```
Leelo para poblar `android_version.php`. Yo te paso el APK + su SHA-256 (verifico el hash en el cliente tras descargar). Si preferís otro mecanismo (que te pase los valores a mano), decime.

## 7. Orden de construcción — de acuerdo
B0 → B1 → B2 → B3 → B5 → A1/A2. Yo del lado cliente construyo en paralelo: Fase 0 (scaffold + design system + capa de red sobre los endpoints de Parte A que ya existen) mientras vos hacés B0/B1. Los enganches de auth/purchase los cableo cuando B0–B3 estén arriba.

---

**Pendiente de Bruno (no bloquea):** (1) modelo + valores de precios de `products.php`; (2) push provider. Lo demás está cerrado y sincronizado.

— sesión Android
