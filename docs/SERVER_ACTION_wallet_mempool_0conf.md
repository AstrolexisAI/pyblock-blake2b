# 🟠 Endpoint mempool 0-conf para wallets (vanity) — que la app vea fondos ANTES de confirmar

Para: sesión del server. De: sesión iOS/Android. 2026-08-03.

## Contexto
Las apps (iOS/Android) tienen un nodo cliente **compact block filters (BIP157/158)** que sincroniza **solo contra el nodo de PyBLØCK** (`179.27.118.130:8333`). Los filtros CBF **solo cubren bloques confirmados** → una tx entrante en **0-conf (mempool)** NO se ve hasta que se mina. Para mostrar fondos entrantes al instante (0-conf), la app pide al **propio nodo de PyBLØCK** las txs de mempool que tocan una dirección, y las inyecta con `applyUnconfirmedTxs` (BDK).

La app YA hace el fetch a este endpoint cada ~15s mientras la wallet está abierta. Si el endpoint no existe todavía, la app simplemente no muestra 0-conf (no rompe nada). Cuando lo montes, empieza a andar solo.

## Contrato del endpoint (lo que la app espera)
```
GET https://pyblock.xyz:8443/api/wallet_mempool.php?address=<dirección_base58_o_bech32>
```
Respuesta JSON (200):
```json
{
  "address": "1ELiKKGevWKHdEFTqgX9LVVBZ9rZoK9BfX",
  "txs": [
    { "txid": "abcd…", "hex": "0200000001…", "seen": 1785810000 }
  ]
}
```
- `hex` = transacción **cruda serializada** (`getrawtransaction <txid> false`). **Obligatorio** (la app la parsea con BDK).
- `seen` = unix time del primer avistaje en mempool (`getmempoolentry <txid>.time`). Opcional (si falta, la app usa "ahora").
- `txs` vacío o ausente = sin 0-conf para esa address. Perfecto.
- Devolver **solo txs no confirmadas** (las confirmadas ya las ve el CBF).
- Incluir txs que **pagan a** la address (outputs). Si es fácil, también las que **gastan de** ella (inputs) — mejora la UX de salidas 0-conf, pero no es crítico.

## Cómo obtenerlo en el nodo (Bitcoin Knots) — Core/Knots NO indexa direcciones
Opciones, de mejor a más simple:

1. **Recomendado — electrs / Fulcrum** apuntando al nodo. Indexa scripthash incluyendo mempool.
   - `scripthash = sha256(scriptPubKey)` invertido; para la address, derivás el scriptPubKey (P2PKH `1…` = `OP_DUP OP_HASH160 <h160> OP_EQUALVERIFY OP_CHECKSIG`).
   - Electrum: `blockchain.scripthash.get_mempool` → lista de txids no confirmados. Luego `getrawtransaction` por cada uno.
   - El .php actúa de proxy: recibe address → deriva scripthash → consulta electrs → arma el JSON.

2. **Índice propio vía ZMQ** (`zmqpubrawtx`): un daemon liviano suscrito a `rawtx` que parsea outputs, mantiene un map `address → [txid]` en memoria/redis con TTL, y expira al confirmarse. El .php lee de ahí. Sin dependencia de electrs.

3. **Fallback on-demand** (solo si el mempool es chico): `getrawmempool true` → por cada txid `getrawtransaction <txid> true`, revisar `vout[].scriptPubKey.address`. **Cachear** el barrido (TTL 5-10s) porque es O(mempool). Aceptable con pocos usuarios; no escala.

## Notas
- Privacidad: la address se envía **solo a tu server** (mismo nivel de confianza que usar la pool), nunca a un tercero. Es el trade-off consciente para tener 0-conf.
- Cuando la tx se mina, el CBF la toma como confirmada y reemplaza la entrada 0-conf (mismo txid). No hace falta que el endpoint la siga devolviendo.
- Rate: la app pollea cada 15s por wallet abierta. Poné cache corto para no golpear el nodo.

Avisá cuando esté arriba y hago una prueba real mandando sats y viendo el 0-conf aparecer al toque.

— sesión apps
