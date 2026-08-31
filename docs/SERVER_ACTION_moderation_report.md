# 🟡 Endpoint moderation_report.php — recibir reportes/blocks del chat (Apple 1.2)

Para: sesión del server. De: sesión apps. 2026-08-11.

## Por qué (urgente, bloquea App Store)
Apple rechazó por Guideline 1.2 (user-generated content): el chat necesita que
al **reportar contenido** o **bloquear un usuario**, el hecho llegue AL
DEVELOPER. La app ya oculta el contenido localmente al instante; falta que el
server reciba y registre el aviso.

## Endpoint `POST /api/app/moderation_report.php` (HMAC device auth)
Mismo auth que el resto de `api/app/*` (X-PyBLOCK-Device-Id + HMAC).
```json
→ { "kind": "block" | "report",
    "target_pubkey": "<hex npub del usuario>",
    "message_id": "<id del evento nostr>"   // presente solo en report
  }
← { "ok": true }
```
- Persistir cada aviso: `{ts, device_id, kind, target_pubkey, message_id}` en
  una tabla/log (`data/moderation_reports.db` o un .log). Con eso podés revisar
  y, si querés, accionar (p.ej. banear el npub del relay strfry si acumula
  reportes).
- Idempotente / tolerante: si falta message_id en un report, igual registrar.
- Rate-limit suave (anti-abuso del propio reporte).
- **Nice-to-have**: si un npub junta N reportes de devices distintos, alertarte
  (push admin) o auto-quitarlo del relay. No bloqueante para Apple — con
  registrar alcanza.

## Estado app
iOS ya llama este endpoint (fire-and-forget) al reportar/bloquear (commit
`8319866`). Si el endpoint no existe aún, la app no rompe (el hide local ya
ocurrió), pero para cumplir con Apple conviene que exista y registre. Android
va a llamar el mismo endpoint (paridad).

## Verificación (pegar en SERVER_DONE)
```
POST moderation_report.php {kind:block, target_pubkey:X}      → {ok:true}, fila registrada
POST moderation_report.php {kind:report, target_pubkey:X, message_id:Y} → {ok:true}, fila registrada
```

— sesión apps
