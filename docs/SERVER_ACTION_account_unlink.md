# 🟢 Cross-device: (1) limpiar device sintético de QA + (2) endpoint unlink

Para: sesión del server. De: sesión apps. 2026-08-09.

## Contexto
El pairing quedó validado E2E (código UVG-YJM del Flip device 176 claimeado por
un device sintético → heredó pro+whale ✓). Pero quedó un device de prueba
linkeado al grupo de Bruno, y falta el unlink para la UI.

## 1. Limpieza inmediata (QA)
Sacar del grupo `device:176` el device sintético **189** (anónimo, iOS, sin push
ni nostr_pubkey — creado por curl para el test):
- borrar su fila de `account_devices` (pubkey `device:176`, device_id `189`),
- borrar sus entitlements re-atribuidos si quedó alguno propio (no debería;
  el whale/pro son del 176),
- opcional: borrar la fila `devices` 189 entera de push_devices.db.
Meta: `entitlements` de Bruno vuelven a contar solo sus devices reales
(176 Flip + 3 iPhone si ya lo linkeó).

## 2. Endpoint `POST /api/app/account/unlink.php` (HMAC device auth)
Desvincular UN device del grupo (el que llama, o uno indicado):
```json
→ {}                         (auth = el device que se quiere ir)   → se saca a sí mismo
   ó { "device_id": 189 }    (solo si el caller es del mismo grupo) → saca a otro
← { "ok": true, "removed": 189, "devices_remaining": 1 }
```
Reglas:
- El device removido vuelve a su cuenta propia `device:<id>` (fila
  account_devices propia o ninguna), y **pierde los entitlements heredados**
  (los entitlements quedan atribuidos al pubkey canónico del grupo, no viajan
  con el device que se va — salvo que ESE device sea el dueño original de la
  compra: en ese caso mover la compra con él; para MVP, el dueño no puede
  auto-removerse si es el único con la compra → error `owner_cannot_leave`).
- Si tras el unlink el grupo queda con 1 device, colapsar a cuenta simple.
- El whale_npubs generator ya recalcula en ≤5min → el device removido sale de
  la whitelist del Lounge solo.
- Rate-limit razonable (p.ej. 10/hora/device).

## Verificación (pegar en SERVER_DONE)
```
1. device 189 sacado del grupo → entitlements(189)=free, group(176)=1 device
2. re-link 189 → whale; unlink 189 → free otra vez
3. owner (176) intenta auto-unlink siendo único dueño → owner_cannot_leave
```

Cuando den el DONE del unlink, agrego a la UI (Settings → LINKED DEVICES) un
"UNLINK THIS DEVICE" + lista de devices del grupo con su plataforma/última vez.
Contrato de arriba es el que voy a consumir.

— sesión apps
