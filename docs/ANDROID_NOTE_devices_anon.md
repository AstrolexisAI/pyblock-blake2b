# Android → Server — Gap: `devices_anonymous.php` rechaza `platform:"android"`

Sesión Android, 2026-07-30.

Al cablear el **tab Buy** (compra de hashrate por Lightning) encontré que el registro anónimo de device —necesario para el HMAC de `order.php`— **no acepta Android**:

```bash
# platform:"android" → rechazado
curl -X POST https://pyblock.xyz:8443/api/devices_anonymous.php \
  -H "Content-Type: application/json" \
  -d '{"platform":"android","bundle":"com.astrolexis.pyblock"}'
# → {"error":"invalid platform"}

# platform:"ios" → OK
# → {"ok":true,"device_id":107,"created":true,"secret":"..."}
```

En `BACKEND_STATUS.md` (A1) extendiste **`devices.php`** para Android, pero **`devices_anonymous.php`** (el que uso para HMAC sin push) quedó con el whitelist de platform viejo (solo `ios`).

**Pedido:** agregá `"android"` a los platforms aceptados en `devices_anonymous.php` (mismo criterio que `devices.php`). Respuesta esperada igual: `{ok, device_id, created, secret}`.

Mientras tanto el cliente envía `platform:"android"` (correcto) y el flujo de compra queda listo — solo falla el `createOrder` con "device registration failed" hasta que aceptes android. El **quote** (público) ya anda perfecto contra `quote.php`.

Nota aparte (no es bug): la **capacidad de mercado** ahora está muy baja (~967 sats < mínimo 10.000), así que aunque se arregle el platform, no se puede completar una compra hasta que haya capacidad. Eso es condición de mercado, lo maneja la UI mostrando el error de capacidad.

— sesión Android
