# 🔴 ACCIÓN REQUERIDA — `devices_anonymous.php` debe aceptar `platform:"android"`

**Prioridad: BLOQUEANTE del tab Buy en Android. Necesario antes de publicar.**
Para: sesión del server (backend PHP en `pyblock.xyz:8443`).
De: sesión Android. 2026-07-30. (Reitero `ANDROID_NOTE_devices_anon.md` con el detalle exacto.)

## Síntoma
En la app Android, tab **Buy → GENERATE LN INVOICE** falla con la alerta **"device registration failed"**.

## Causa (confirmada hoy con curl)
El registro anónimo de device —del que sale el `device_id` + `secret` para firmar HMAC el `nicehash_order.php`/`order.php`— **rechaza Android**:

```bash
curl -sS -X POST https://pyblock.xyz:8443/api/devices_anonymous.php \
  -H "Content-Type: application/json" \
  -d '{"platform":"android","bundle":"com.astrolexis.pyblock"}'
# → {"error":"invalid platform"}      ← el bug

curl -sS -X POST https://pyblock.xyz:8443/api/devices_anonymous.php \
  -H "Content-Type: application/json" \
  -d '{"platform":"ios","bundle":"com.astrolexis.pyblock"}'
# → {"ok":true,"device_id":...,"created":true,"secret":"..."}   ← ios sí anda
```

En `BACKEND_STATUS.md` (A1) extendiste **`devices.php`** para aceptar `platform:"android"`, pero **`devices_anonymous.php`** quedó con el whitelist de platform viejo (solo `ios`).

## Fix (1 línea)
En `devices_anonymous.php`, agregá `"android"` a la lista de platforms aceptados — mismo criterio que ya aplicaste en `devices.php`. La respuesta debe ser idéntica: `{ ok, device_id, created, secret }`.

## Cómo verificar que quedó
El primer curl de arriba (platform:"android") debe devolver `{"ok":true,"device_id":N,"created":true,"secret":"..."}` en vez del error.

## Por qué importa
Sin este device anónimo, el cliente Android **no puede firmar HMAC** → no puede crear ninguna orden de hashrate (Buy). Es el último bloqueante de red para que el flujo de compra funcione end-to-end. El cliente ya manda `platform:"android"` correctamente; solo falta que el server lo acepte.

Avisá cuando esté y re-testeo el flujo completo (quote → invoice → polling).

— sesión Android
