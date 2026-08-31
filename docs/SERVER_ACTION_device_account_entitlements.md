# 🟠 ACCIÓN REQUERIDA — entitlements por cuenta de DEVICE (no gatear compra con LNURL-auth)

Para: sesión del server (backend PHP `pyblock.xyz:8443`). De: sesión Android. 2026-07-30.
Contexto: decisión de producto de Bruno. **Alta prioridad** (define compatibilidad de wallets para publicar), pero NO urgente-bloqueante como el DER — el login LNURL ya anda.

## Por qué
LNURL-auth (LUD-04) lo soportan Alby/Zeus/Phoenix/Breez/SBW… pero **NO** lo soportan varias de las wallets más usadas: **Wallet of Satoshi, Cash App, Strike, Muun, BlueWallet**. Si la compra de Pro/Whale/vidas/skins exige login LNURL-auth (Bearer), esos usuarios **no pueden comprar**. Pagar un invoice BOLT11 **no requiere** LNURL-auth en ninguna wallet.

**Fix de diseño: separar identidad de la wallet que paga.** La cuenta pasa a ser el **device anónimo** que YA existe para la compra de hashrate (Parte A, auth HMAC). LNURL-auth queda como **vínculo opcional** para portabilidad/restore, no como gate.

## Cambios pedidos (server)

### 1. `purchase.php` y `entitlements.php` (y `purchase_status.php`) aceptan AUTH POR HMAC de device
Hoy: `Authorization: Bearer <session_token>` (cuenta = pubkey LNURL).
Agregar un **segundo modo de auth**, el HMAC que ya usás en `order.php`/`nicehash_order.php`:
```
firma = HMAC_SHA256( secret, "METHOD\nPATH\nTIMESTAMP\nSHA256(BODY)" )
Headers: X-PyBLOCK-Device-Id, X-PyBLOCK-Timestamp, X-PyBLOCK-Signature
```
Resolución de cuenta:
- Si viene **Bearer** → cuenta = pubkey (como hoy).
- Si viene **HMAC** → cuenta = `device_id`.
- Si ese `device_id` está linkeado a una pubkey (via LNURL-auth, B0 ya lo contempla) → misma cuenta subyacente (comparten entitlements).

`purchase.php` (HMAC): body `{ "product_id": "..." }` (el HMAC ya firma el body). Respuesta idéntica: `{ ok, purchase_id, invoice, amount_sats, expires_at }`. Al pagar, acredita el efecto a la **cuenta de device**.
`entitlements.php` (HMAC): GET sin body → firma sobre `SHA256("")`. Devuelve los entitlements de esa cuenta de device.

### 2. Merge device_id ↔ pubkey al hacer LNURL-auth (link opcional)
Cuando un `device_id` X hace LNURL-auth a pubkey P:
- P sin cuenta previa → atar P a la cuenta de X (X queda restaurable via wallet).
- P y X ambos con entitlements → **merge**: `tier` = el más alto, `tier_expires_at` = el más lejano, `skins` = unión, `lives_balance` = el mayor (no sumar, evita duplicar). Es tu criterio, pero que no pierda nada el usuario.

### 3. El par `device_id + secret` debe ser PORTABLE (para el backup de clave)
El restore del cliente es 100% client-side: la app exporta `device_id + secret` como frase/QR y los reinyecta en otro teléfono. Para que funcione, **el server NO debe atar `device_id`/`secret` a un fingerprint de hardware ni rotarlo/invalidarlo** al verlo desde otra IP/dispositivo. El mismo `device_id + secret` tiene que seguir firmando HMAC válido desde cualquier lado. (Si hoy ya es así — que lo es para hashrate — no hay nada que hacer; solo confirmámelo.)

**No hace falta endpoint nuevo de restore.** El cliente reinyecta creds → las llamadas HMAC resuelven a ese `device_id` → devolvés sus entitlements. Simple.

## Cómo verificar
- `entitlements.php` firmado con HMAC de un `device_id` anónimo (sin Bearer) → `{ ok, tier, ... }` de esa cuenta (no 401).
- `purchase.php` firmado con HMAC + `{product_id:"pro.monthly"}` → invoice; al pagar, `entitlements.php` (mismo device HMAC) muestra `tier:"pro"`.
- Luego LNURL-auth a una pubkey nueva desde ese device → `entitlements.php` con Bearer de esa pubkey muestra el mismo `pro` (merge).

## Cliente (lo hago yo, para que sepas cómo consume)
- `buy()` deja de forzar login: usa identidad de device (HMAC) por defecto; cualquier wallet paga el bolt11.
- "SIGN IN WITH LIGHTNING" se reetiqueta a **"LINK WALLET (optional)"** (restore via wallet compatible).
- Nuevo **"BACKUP ACCOUNT"** (exporta `device_id+secret` como frase/QR) + **"RESTORE"** (import).

Avisá cuando `purchase.php`/`entitlements.php` acepten HMAC y confirmes el punto 3. Re-testeo compra con Wallet of Satoshi (sin LNURL-auth) en el device.

— sesión Android
