# 🟠 Nodo neutrino en la app → conectar al nodo de PyBLØCK: SERVIR compact filters (BIP157/158)

Para: sesión del server. De: sesión Android/iOS. 2026-08-03.
Idea de Bruno: las apps (iOS + Android) van a tener un **nodo neutrino** (cliente liviano BIP157/158, vía BDK+Kyoto) que sincroniza la cadena **conectándose al nodo Bitcoin de PyBLØCK** (`bitcoin.node=neutrino`, `neutrino.connect=pyblock.xyz:8333`) — 100% soberano, sin peers de terceros. Sirve para: (A) que el usuario verifique sus payouts de la pool en privado, y (B) que **verifique él mismo que los bloques de PyBLØCK son BIP-110 clean** (don't trust, verify).

## Estado detectado
- `pyblock.xyz` → `179.27.118.130`. **Puerto P2P 8333 ABIERTO** ✅ (hay un nodo escuchando — asumo el Knots de la pool).

## Lo que necesita el nodo (Knots/Core) para que un cliente neutrino se conecte
El nodo tiene que **servir compact block filters** a los peers. En `bitcoin.conf`:
```
blockfilterindex=1        # construye el índice de filtros BIP158 (basic)
peerblockfilters=1        # sirve getcfilters/getcfheaders a los peers
listen=1
# 8333 ya está abierto públicamente ✅
```
Con eso el nodo anuncia el service bit **NODE_COMPACT_FILTERS (1<<6)** y responde `getcfheaders`/`getcfilters`. Sin `peerblockfilters=1`, un cliente neutrino (BDK/Kyoto/LND-neutrino) **no puede** pedirle los filtros aunque 8333 esté abierto.

## Acción / confirmación
1. ¿El nodo ya tiene `blockfilterindex=1` + `peerblockfilters=1`? Si sí, confirmámelo (y avisá si `blockfilterindex` ya terminó de construirse — puede tardar en indexar toda la historia). Si no, agregalos + reiniciá el nodo (el index se construye una vez).
2. Confirmá que el nodo en 8333 es el Knots de la pool (el que arma los templates BIP-110), no otro.
3. (Opcional) ¿v2 transport (BIP324) habilitado? El cliente puede usar v1; no bloquea.

**Cómo verificar que sirve filtros:** `bitcoin-cli getindexinfo` debe mostrar `basic block filter index` synced; y `getpeerinfo`/service flags deben incluir `NODE_COMPACT_FILTERS`. O desde afuera, un handshake BIP157 a 179.27.118.130:8333 debe devolver `cfheaders`.

Cuando confirmes que sirve filtros, apunto el spike del neutrino (bdk-swift ya integrado en iOS, linkea/compila) a `179.27.118.130:8333` y mido en el iPhone: tiempo de sync, MB, batería. Avisá.

— sesión apps
