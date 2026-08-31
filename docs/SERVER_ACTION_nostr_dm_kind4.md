# 🟠 Relay Nostr → permitir DMs encriptados (kind 4, NIP-44)

Para: sesión del server. De: sesión apps. 2026-08-05.

## Contexto
La app iOS ya tiene **DMs 1:1 encriptados end-to-end (NIP-44 v2)** sobre el mismo relay soberano (`wss://nostr.pyblock.xyz:8443`, strfry). Cada DM es un **evento kind 4** cuyo `content` es el payload NIP-44 (ChaCha20 + HMAC-SHA256; ni el relay ni nadie sin la clave puede leerlo) y lleva un tag `["p", <pubkey_destinatario>]` para ruteo.

**Problema:** la write-policy actual del relay solo acepta **kind-0 (perfiles) + eventos del canal PyBLØCK** (kind 42 con el `e` tag del canal). Por eso **rechaza los kind-4** y los DMs no se propagan. Hay que permitir kind 4.

## Lo que hay que cambiar (write-policy de strfry)
En el plugin/script de política, **además** de lo que ya acepta, aceptar:

- `kind == 4` **si** el evento tiene al menos un tag `p` (destinatario) y firma válida (strfry ya valida firma).

Pseudocódigo de la decisión (sumar a la policy existente):
```
accept if:
   kind == 0                                             # perfiles (ya)
   OR (kind == 42 AND e-tag == <channelId PyBLØCK>)      # canal (ya)
   OR (kind == 4  AND tiene tag "p")                     # DMs NIP-44 (NUEVO)
reject everything else
```

channelId (referencia, sin cambios): `14934fd5ad4c0da45a4f903fee1ca5a57448d685dd7fe8ad1699609e04a0074a`

## Rate limit (anti-spam)
Los kind-4 son abiertos por diseño (cualquiera puede escribirle a cualquiera). Para que no sea vector de spam:
- Rate limit por IP (ej. **≤ 20 kind-4 / min / IP**), reusando el rate limit que ya tengas.
- Opcional: límite de tamaño de `content` (ej. ≤ 64 KB) — los DMs normales son chicos.
- **No** hace falta (ni se puede) filtrar por contenido: está cifrado.

## Retención
- Mismos criterios que el resto. Si expirás eventos por antigüedad, los DMs viejos se pierden del relay (la app igual los tiene localmente una vez recibidos). Si podés, retené kind-4 igual que kind-42.

## Verificación
- Desde la app: Community Chat → tocar el avatar de otro usuario → **MESSAGE PRIVATELY** → mandar un mensaje. Debe aparecer del otro lado (segundo dispositivo/usuario).
- Con `wscat`: publicar un kind-4 con tag `p` → debe aceptar (`["OK", <id>, true, ""]`), no rechazar.
- Query: `["REQ","t",{"kinds":[4],"#p":["<pubkey>"],"limit":5}]` → debe devolver los DMs dirigidos a ese pubkey.

## Notas
- El relay **no** puede leer los DMs (NIP-44). Solo transporta bytes cifrados + routing por `p` tag.
- La app suscribe `{"kinds":[4],"#p":[myPubkey]}` (entrantes) + `{"kinds":[4],"authors":[myPubkey]}` (los míos, para historial/multi-device).

Avisá cuando la policy acepte kind-4 y verifico el ida-y-vuelta desde la app. 🙌

— sesión apps
