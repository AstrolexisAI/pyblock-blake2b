# 🟠 Relay Nostr propio de PyBLØCK → `wss://nostr.pyblock.xyz`

Para: sesión del server. De: sesión apps. 2026-08-05.

## Contexto
La app iOS tiene un **Community Chat sobre Nostr** (canal dedicado NIP-28). Hoy usa relays públicos (damus/nos.lol). Queremos un **relay propio de PyBLØCK** para: control del canal (retención, moderación), privacidad, y ser la base de coordinación del futuro **PayJoin**. La app **ya está desplegada apuntando a `wss://nostr.pyblock.xyz`** como relay primario (con 2 públicos de fallback) — apenas lo montes, empieza a andar.

## Lo que hay que montar
Un relay Nostr accesible por **WSS (TLS)** en `nostr.pyblock.xyz`.

### 1. DNS
- `nostr.pyblock.xyz` → A/AAAA al server (mismo que `pyblock.xyz` → `179.27.118.130`, o donde prefieras correrlo).

### 2. Relay software (recomendado: **strfry**)
- **strfry** (C++, rápido, el estándar): https://github.com/hoytech/strfry
- Alternativas: `nostr-rs-relay` (Rust) o `khatru` (Go). Elegí la que te sea cómoda; strfry es la más usada.
- Corre en un puerto local (ej. `127.0.0.1:7777`), texto plano ws; el TLS lo pone el reverse proxy.
- Config mínima (`strfry.conf`): habilitar writes, límites de rate razonables, retención (ej. guardar todo o expirar a X días).

### 3. TLS / reverse proxy (WSS)
- **Caddy** (lo más simple, TLS automático Let's Encrypt):
  ```
  nostr.pyblock.xyz {
      reverse_proxy 127.0.0.1:7777
  }
  ```
- O nginx con `proxy_pass` + `Upgrade`/`Connection` headers para WebSocket + certbot.
- Resultado: `wss://nostr.pyblock.xyz` responde el WebSocket del relay.

### 4. (Opcional) Política del relay
- Aceptar al menos **kinds 0 (perfiles), 1, 42 (channel messages)**. La app usa 0 y 42.
- Si querés que sea **solo para el canal PyBLØCK**, podés filtrar por el `e` tag del canal (`14934fd5ad4c0da45a4f903fee1ca5a57448d685dd7fe8ad1699609e04a0074a`) con un write-policy plugin de strfry. No es obligatorio para arrancar.
- Rate limit por IP para evitar spam.

## Verificación
- `wscat -c wss://nostr.pyblock.xyz` y mandar un `["REQ","test",{"kinds":[1],"limit":1}]` → debe responder (EVENT/EOSE).
- O desde la app: abrir Community Chat → el indicador debería decir **connected** contra el relay propio.

## Notas
- La app **ya apunta ahí** (primario) + 2 públicos de respaldo. Si querés que el chat sea **100% soberano** (solo PyBLØCK, sin públicos), avisá y saco los públicos del cliente.
- Este relay es también la capa de coordinación del **PayJoin** que viene después.

Avisá cuando `wss://nostr.pyblock.xyz` esté arriba y verifico la conexión desde la app. 🙌

— sesión apps
