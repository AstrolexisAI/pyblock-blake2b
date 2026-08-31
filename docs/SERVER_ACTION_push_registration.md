# 🟠 Push — UnifiedPush (soberano, SIN FCM/Google)

Para: sesión del server. De: sesión Android. 2026-07-30. (Actualiza la versión previa — Bruno descartó FCM: la app es 100% soberana, sin Google Play Services.)

Transporte único: **UnifiedPush** (distributor tipo ntfy en el teléfono, open source, sin Google).

## Contrato que manda el cliente
`POST /api/devices.php`, **firmado con HMAC del device anónimo existente** (headers `X-PyBLOCK-Device-Id/Timestamp/Nonce/Signature`), body:
```jsonc
{
  "platform": "android",
  "bundle": "com.astrolexis.pyblock",
  "push_provider": "unifiedpush",
  "endpoint": "https://ntfy.sh/UP...",   // URL de entrega del distributor
  "preferences": { "lotto_block": true, "bip110_block": true, "my_address": true }
}
```

## Acción / confirmación server
1. **`devices.php` debe aceptar el HMAC del device y ATTACHAR el `endpoint` a ESE `device_id`** (no crear un device nuevo) — para que el push quede en la misma cuenta que entitlements/órdenes. Confirmá.
2. **Worker de push:** `POST` al `endpoint` con el payload iOS `{ "pyblock": { "kind","height","hash" } }` (el cliente ya lo parsea y muestra la notificación). `kind` ∈ {lotto_block, bip110_block, my_address}.
3. **NADA de FCM** — sin service account, sin Google. Ignorá el pedido anterior de `fcm-service-account.json`.

## Opcional (on-brand, tu decisión)
- **ntfy self-hosted en pyblock.xyz:** en vez de `ntfy.sh` público, podés levantar un ntfy propio (`ntfy.pyblock.xyz`) para que la entrega sea 100% tuya. El usuario igual instala la app ntfy pero apunta a tu server. Si lo hacés, avisá y lo pre-configuro como default en el cliente. Si no, ntfy.sh público anda igual.

Estado cliente: UnifiedPush **cableado, compilado, falla-seguro** (sin distributor = no-op). Cuando confirmes el punto 1 pruebo el registro de endpoint on-device con ntfy instalado.

— sesión Android
