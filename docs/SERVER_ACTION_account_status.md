# 🟢 Endpoint account/status.php — lista de devices del grupo (para UI unlink)

Para: sesión del server. De: sesión apps. 2026-08-09.

## Por qué
La UI de LINKED DEVICES ya hace link (código) y va a hacer unlink (endpoint
`unlink.php` ya live). Para mostrar QUÉ dispositivos están en el grupo (y ofrecer
unlink por-device), la app necesita leerlos.

## Endpoint `GET /api/app/account/status.php` (HMAC device auth)
```json
← { "ok": true,
    "linked": true,               // este device comparte grupo con >1 device
    "this_device": 176,
    "anchor": 176,                // device ancla (donde viven los entitlements)
    "tier": "whale",              // tier efectivo del grupo (conveniencia)
    "devices": [
      { "id": 176, "platform": "android", "last_seen": 1786290000, "this": true,  "anchor": true },
      { "id": 3,   "platform": "ios",     "last_seen": 1786294000, "this": false, "anchor": false }
    ] }
```
- Sin grupo (device suelto): `linked:false`, `devices` = solo él (o vacío), `anchor` = su propio id.
- `platform` desde `push_devices.platform`; `last_seen` desde `last_seen_at`.
- `anchor` = `device:<min id>` del grupo (el que no puede auto-removerse si hay >1 — matchea `owner_cannot_leave` de unlink.php).
- Rate-limit suave (es solo lectura).

## Cómo lo usa la app (contrato de unlink ya existente, sin cambios)
- Lista los `devices`; junto a cada uno que NO sea el anchor (o el propio si no es anchor) un botón "unlink".
- unlink propio: `POST unlink.php {}`; unlink de otro: `POST unlink.php {"device_id":N}`.
- Tras unlink, la app re-consulta status + refresca entitlements.
- Si `status.php` devuelve 404 (aún no desplegado), la app oculta la lista y deja
  solo "UNLINK THIS DEVICE" (self) — no bloquea el release.

## Verificación (pegar en SERVER_DONE)
```
device en grupo de 2 → status.linked=true, devices=[2], anchor correcto
device suelto        → status.linked=false, devices=[1]
```
— sesión apps
