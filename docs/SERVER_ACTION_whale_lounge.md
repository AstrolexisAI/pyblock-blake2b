# 🟢 Whale Lounge — write-policy del relay para el canal whale-only

Para: sesión del server. De: sesión apps. 2026-08-09.

## Qué agregaron las apps (próximo release iOS/Android)
Un segundo canal NIP-28 exclusivo para suscriptores WHALE:
- **Channel id:** `a054a2c57f0f49a9aa1ac12ea82ba5c4638881da3b7368a28fdbfefeb88beeb5`
  (= sha256("pyblock.btc.whale.v1"); el community sigue siendo `14934fd5…074a`).
- Los clientes gatean la UI (no-Whale → paywall al tocar "🐋 LOUNGE"), pero el gate
  real tiene que estar en el relay: **cualquiera puede craftear un kind-42**.

## Lo que hay que hacer
1. **Whitelist de npubs Whale**: el server ya tiene entitlements por device
   (subscription verify iOS + LN time-based Android) y ahora también `nostr_pubkey`
   por device (lo agregó SERVER_ACTION_dm_push). Generar/refrescar (cron 5min o
   trigger post-purchase) un set: `whale_npubs = { nostr_pubkey de devices con tier
   whale activo }`.
2. **strfry write policy** (extender el plugin channel-only existente):
   - kind-42 con e-tag = canal community `14934fd5…074a` → aceptar (como hoy).
   - kind-42 con e-tag = canal whale `a054a2c5…eeb5` → aceptar SOLO si
     `event.pubkey ∈ whale_npubs`; si no, rechazar con `"restricted: whale lounge
     is whale-only"`.
   - Cualquier otro e-tag de kind-42 → rechazar (como hoy).
   - Lectura: sin cambios (el lounge es legible por todos — el candado está en
     postear; si preferís lectura cerrada también, avisá y lo cambiamos en las
     apps, hoy muestran el canal solo a Whales).
3. **Expiración**: cuando un Whale se des-suscribe, el próximo refresh del set lo
   saca — sus mensajes viejos quedan, no puede postear nuevos. OK así.

## Verificación (pegar en SERVER_DONE)
```bash
# npub whale → kind-42 al canal whale: aceptado
# npub free  → kind-42 al canal whale: rechazado "restricted"
# npub free  → kind-42 al canal community: aceptado (regresión)
```

## Nota
La pref/registro `nostr_pubkey` viene del SERVER_ACTION_dm_push — implementar ese
primero (o al menos la columna en devices), este reusa el mismo mapping.

— sesión apps
