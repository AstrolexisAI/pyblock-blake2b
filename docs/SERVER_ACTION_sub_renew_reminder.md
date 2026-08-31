# 🟢 Push de recordatorio de vencimiento de suscripción (Pro/Whale LN)

Para: sesión del server. De: sesión apps. 2026-08-09.

## Contexto / decisión de producto
Las suscripciones Pro/Whale por Lightning son **time-based** (pago por período,
no auto-renuevan). Para no perder al usuario que se olvida, Bruno eligió
"recordatorio + recompra 1-tap": la app ya tiene el botón **RENEW / EXTEND**
(1 tap → invoice del mismo tier), falta que el server avise cuando está por vencer.

## Lo que hay que hacer
**Cron diario** que recorra los entitlements de suscripción activos y, para los
que vencen dentro de una ventana, mande un push (mismo pipeline APNs iOS /
UnifiedPush+nativo Android que ya usan):

- Ventana: **T-3 días** y **T-1 día** antes de `expires_at` (2 recordatorios máx).
- Dedupe: no reenviar el mismo (device, hito) — columna/flag tipo
  `sub_reminded_at` o set con TTL, para no spamear si el cron corre seguido.
- Solo a devices con push habilitado; respetar una pref nueva si la agregan
  (ver abajo), default ON.
- Payload:
  ```json
  { "pyblock": { "kind": "sub_renew", "tier": "whale", "expires_at": 1788880130 } }
  ```
  Título "PyBLØCK", cuerpo p.ej. "Tu Whale vence en 3 días — renová en 1 toque".

## Cliente (ya preparado / a preparar)
- Android push nativo (`DmPushService`/`PushNotifier`) y iOS (`NotificationManager`)
  van a rutear `kind:"sub_renew"` → abrir la app en Settings (sección PYBLØCK PRO).
  El deep-link lo agrego cuando confirmen el kind; por ahora con que llegue como
  notificación genérica alcanza (tap abre la app).
- iOS StoreKit auto-renueva, así que este recordatorio es **solo para el rail LN**
  (Android, y iOS si algún día vende por LN). Mandar `sub_renew` únicamente a
  entitlements cuyo origen es LN, no StoreKit, para no confundir a usuarios iOS
  cuya sub sí se renovó sola.

## Verificación (pegar en SERVER_DONE)
```
1. entitlement whale que vence en 3d → 1 push sub_renew, no duplicado si el cron re-corre
2. entitlement StoreKit (iOS) → NO recibe sub_renew
3. tras renovar (nuevo expires_at) → la ventana se re-arma para el próximo período
```

— sesión apps
