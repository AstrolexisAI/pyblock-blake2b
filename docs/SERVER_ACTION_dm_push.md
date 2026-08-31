# 🟢 Push de DMs — watcher del relay + routing npub→device

Para: sesión del server. De: sesión apps. 2026-08-09.

## Qué agregaron las apps (iOS 0.3.8-dev / Android 1.1.2-dev)
Los clientes ahora mandan en el registro de device (`POST /api/devices.php`):
- **`nostr_pubkey`** (top-level, hex x-only 64 chars): la identidad de chat del device.
  String vacío = borrar el mapping (opt-out o pref apagada). En iOS viaja en cada
  registro; en Android va con el registro del endpoint UnifiedPush.
- **`preferences.nostr_dm`** (bool, default true): nueva pref junto a `lotto_block`/
  `bip110_block`/`my_address`.

Payload de push esperado por ambas apps (mismo shape que los de bloques):
```json
{ "pyblock": { "kind": "nostr_dm" } }
```
Sin contenido ni sender — el relay solo ve ciphertext NIP-44, y el push es
deliberadamente genérico ("New encrypted DM"). El tap abre el inbox de DMs.

## Lo que hay que hacer
1. **`devices.php`**: aceptar y persistir `nostr_pubkey` (columna nueva, index) y la
   pref `nostr_dm`. Vacío → NULL (limpia el mapping). Un npub puede estar en varios
   devices (multi-device), un device tiene a lo sumo un npub.
2. **Watcher del relay** (systemd, junto a strfry en nostr.pyblock.xyz):
   suscripción websocket local a `{"kinds":[4]}`. Por cada evento:
   - extraer el p-tag (destinatario),
   - buscar devices con `nostr_pubkey = p_tag AND preferences.nostr_dm = 1`,
   - **excluir** devices cuyo npub == pubkey del autor del evento (no notificarse a uno mismo),
   - mandar `{"pyblock":{"kind":"nostr_dm"}}` por APNs (ios) / POST al endpoint UnifiedPush (android).
3. **Rate-limit obligatorio** (anti-flood y anti-metadata): máx **1 push por
   (sender→recipient) cada 60s**, y máx 10 pushes de DM por device por hora.
   Colapsar ráfagas (una conversación activa no debe ametrallar).
4. **No loggear** pares sender→recipient más allá de la ventana del rate-limit
   (TTL corto en memoria/redis) — minimizar metadata en reposo.
5. APNs: mismo pipeline que `lotto_block` (sandbox/production según environment
   del device). UnifiedPush: POST del JSON crudo al endpoint registrado.

## Verificación (pegar en SERVER_DONE)
```bash
# 1. registrar un device de prueba con nostr_pubkey y mandarle un DM desde otra identidad
# 2. confirmar push recibido en <5s, payload {"pyblock":{"kind":"nostr_dm"}}
# 3. mandar 5 DMs seguidos → confirmar que llega 1 solo push (rate-limit)
# 4. re-registrar con nostr_pubkey="" → confirmar que ya no llegan pushes de DM
```

## Nota de privacidad
El server ya conoce el device (push de bloques); esto agrega el vínculo
device↔npub, **solo con opt-in** (pref on por default pero borrable: la app manda
`nostr_pubkey:""` al apagar el toggle en iOS Settings). Android todavía no tiene
UI de toggles de notificaciones (pendiente de paridad) — defaults on.

— sesión apps
