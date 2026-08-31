# 🟢 Entitlements cross-device: link de devices por código corto (spec + endpoints)

Para: sesión del server. De: sesión apps. 2026-08-09.

## El problema (lo vivió Bruno hoy)
Compró WHALE en el Android y el iPhone no se enteró: los entitlements son
por-device (`device:<id>`), y `account_devices` solo se puebla con LNURL-auth
(que iOS ni tiene). Cualquier usuario con 2+ devices choca con esto. Objetivo:
**un tier comprado en cualquier device vale en todos los devices del usuario.**

## Diseño: pairing por código corto (sin wallet, sin cuentas nuevas)
El device que YA tiene el tier (A) muestra un código corto; el otro (B) lo
ingresa. Quedan linkeados al MISMO account. Modelo de datos: reutilizar
`account_devices` tal cual — el "account" de un grupo linkeado es el pseudo-pubkey
`device:<id_canónico>` (el menor id del grupo, ver merge más abajo).

### Endpoint 1 — `POST /api/app/account/link_start.php` (HMAC device auth)
El device A pide un código de pairing:
```json
→ {}                            (autenticado con X-PyBLOCK-Device-Id + HMAC)
← { "ok": true, "code": "XK7Q2P", "expires_in": 300 }
```
- Código: 6 chars A-Z2-9 (sin 0/O/1/I), TTL **5 min**, **un solo uso**, guardado
  hasheado (sha256) en una tabla `link_codes(code_hash, device_id, expires_at, used)`.
- Rate-limit: máx 5 códigos/hora por device.

### Endpoint 2 — `POST /api/app/account/link_claim.php` (HMAC device auth)
El device B canjea el código:
```json
→ { "code": "XK7Q2P" }
← { "ok": true, "account": "device:176", "devices": 2,
    "entitlements": [ { "kind": "whale", "expires_at": 1789227530 } ] }
```
Errores: `{"ok":false,"error":"bad_code|expired|used|rate_limited"}` (respuesta
uniforme para código inválido/expirado — no filtrar cuál).

### Merge de cuentas (el caso general)
Al claimear, unificar los grupos de A y B:
1. `group(X)` = {X} ∪ devices en `account_devices` con el mismo pubkey que X.
2. Nuevo pubkey canónico = `device:<min(id)>` del grupo unido.
3. Upsert `account_devices(pubkey_canónico, device_id)` para TODOS los devices
   del grupo (incluido el canónico — fila propia, simplifica queries).
4. **Re-atribuir entitlements**: `UPDATE entitlements SET pubkey=<canónico>
   WHERE pubkey IN (pubkeys viejos del grupo)`. Sin duplicar: si hay dos del
   mismo kind, conservar el de mayor `expires_at`.

### Resolución de entitlements (afecta endpoints existentes)
Donde hoy se responde entitlements por `device:<id>` (el endpoint que consume
`EntitlementsStore.refresh` de Android y lo que use iOS):
`entitlements efectivos de X = entitlements(pubkey de X) ∪ entitlements(pubkey
canónico del grupo de X)`. Con el merge re-atribuyendo, alcanza con: buscar el
pubkey canónico de X en account_devices (o `device:<X>` si no está linkeado) y
devolver sus entitlements.

### Efectos derivados (ya existentes, verificar que sigan)
- **whale_npubs generator**: ya expande `account_devices` para pubkeys no-device;
  agregar que para pubkey `device:<id>` TAMBIÉN expanda su grupo via
  account_devices (hoy solo toma el id embebido). Resultado esperado post-link:
  los npubs de TODOS los devices del grupo whale entran a la whitelist (y el
  extra manual del iPhone de Bruno se puede borrar).
- **Compras nuevas** (LN Android / StoreKit iOS): al escribir el entitlement,
  usar el pubkey canónico del grupo del device (no `device:<propio>`) si está
  linkeado.
- **Unlink** (v2, no bloquea): endpoint para sacar un device del grupo.

## Seguridad
- Código corto + TTL 5min + single-use + hasheado + rate-limit → fuerza bruta
  inviable (30^6 ≈ 7e8 / ventana de 5min con rate-limit por IP y device).
- Ambos lados autenticados por HMAC de device ya existente — el código solo
  autoriza el "matrimonio", no reemplaza auth.
- Al linkear, el server puede notificar por push al device A ("Device linked:
  <platform>") — nice-to-have anti-abuso.

## Verificación (pegar en SERVER_DONE)
```
1. A (device 176, whale) link_start → código
2. B (device 3) link_claim → ok, entitlements incluye whale
3. endpoint de entitlements de B → whale ✓
4. whale_npubs.txt → contiene npubs de ambos SIN el extra manual
5. código reusado → "used"; código inventado → "bad_code"
```

Cuando den el DONE, las apps agregan la UI (Settings → LINKED DEVICES: mostrar
código / ingresar código) — el shape de arriba es el contrato.

— sesión apps
