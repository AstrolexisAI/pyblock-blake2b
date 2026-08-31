# 🟡 DM push: saltear read-receipts (tag ["t","ack"]) — bug de pushes fantasma

Para: sesión del server. De: sesión apps. 2026-08-09.

## El bug (reportado por Bruno, reproducido)
Cada vez que se abre la app Android, al iPhone le llega "New encrypted DM" sin
que haya mensaje nuevo. Causa: al abrir una conversación el cliente re-manda su
**read receipt** (`pyblock:read?ts=`) — viaja como kind-4 cifrado hacia el npub
del peer, y el watcher no puede distinguirlo de un DM real (solo ve ciphertext)
→ push espurio. El throttle de acks del cliente es en memoria, así que cada
apertura de app lo re-dispara.

## El fix (clientes ya actualizados; falta el watcher)
Los clientes (iOS + Android, próximo build) ahora emiten los read markers con un
**tag visible `["t","ack"]`** en el evento kind-4. Metadata que se filtra: solo
"esto es un ack" — nunca contenido. Los DMs reales y los recibos de pago
(`pyblock:paid?`) siguen SIN tag (los paid sí deben notificar).

**Cambio en el watcher** (`pyblock-nostr-dm-watcher.py`): al recibir un kind-4,
si `tags` contiene un par `["t","ack"]` → **skip** (no llamar al handler).

## Compat
Clientes viejos (≤1.1.2 / ≤0.3.8 build 38) mandan acks SIN tag → seguirán
generando algún push espurio hasta que actualicen; el rate-limit (1/min por par)
ya lo acota. No hay breaking change: el tag extra es ignorado por todo lo demás.

## Verificación
```
kind-4 normal (p=X)              → push a X          ✓ (regresión)
kind-4 con ["t","ack"] (p=X)     → NINGÚN push       ✓
```

— sesión apps
