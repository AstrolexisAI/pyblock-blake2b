# ⚡ Lightning self-custodial (LDK en la app) → PyBLØCK como LSP (LSPS2) + chain source

Para: sesión del server. De: sesión apps. 2026-08-04.

## Contexto
La app ahora **embebe un nodo Lightning self-custodial** (LDK vía `ldk-node` v0.7.0, ya linkea/compila en device). Los fondos son del usuario (seed en Keychain del teléfono). Para que pueda **recibir** (y abrir su primer canal sin tener nada) necesita **un LSP**: PyBLØCK. El modelo es **LSPS2 / bLIP-52 (JIT channel)** — el usuario pide un invoice, el LSP abre un canal just-in-time cuando llega el primer pago.

La app ya llama `setLiquiditySourceLsps2(nodeId, address, token)` y `receiveVariableAmountViaJitChannel(...)`. Solo faltan los datos + el servicio corriendo.

## Lo que necesito de vos

### 1. Nodo LN de PyBLØCK como **LSPS2 provider**
- **`nodeId`** = pubkey del nodo LN de PyBLØCK (hex, 66 chars).
- **`address`** = `host:puerto` **público** alcanzable desde móvil (ej. `ln.pyblock.xyz:9735`). Con TLS/clearnet; Tor no (móvil).
- **`token`** (opcional) = si querés gatear qué apps piden canales.
- El servicio tiene que hablar **LSPS2 (bLIP-52)**. Según tu stack:
  - **CLN**: plugin `lsps-server` / `cln-lsps` (o `lightning-liquidity`).
  - **LDK-based**: un `ldk-node`/LDK server operando como LSPS2 service (v0.7.0 lo soporta, aún alpha).
  - **LND**: LSPS2 server **no** es nativo — necesitarías un sidecar CLN/LDK, o el LSP de Lightning Labs.
  - Decime qué corrés (LND/CLN/otro) y afino.
- **Liquidez saliente**: el LSP tiene que tener fondos para abrir canales JIT hacia los usuarios. Definí un tamaño mínimo de canal y fee (el LSP fee se descuenta del primer pago; la app ya expone `maxProportionalLspFeeLimitPpmMsat`).

### 2. Chain source para el nodo LDK de la app
El LDK del teléfono sigue la cadena para confirmar funding/closes. Opciones (elegí una y pasame la URL):
- **Esplora/electrs propio** (ideal, soberano): `https://esplora.pyblock.xyz/api` o electrs `host:port`. Requiere levantar electrs/esplora contra tu Knots (no lo tenías, es setup de una vez).
- **bitcoind RPC** autenticado expuesto: `setChainSourceBitcoindRpc(host,port,user,pass)` — funciona pero exponer RPC a clientes es delicado; solo si es read-only/proxy.
- **Interino**: si no querés montar electrs ya, arranco con un Esplora público (mempool.space/blockstream) SOLO para el chain source, manteniendo el **LSP en PyBLØCK**. Menos soberano pero desbloquea test. Decime si va.

### 3. (Opcional) Gossip
La app usa **RGS** (`rapidsync.lightningdevkit.org`) por ahora. Si querés RGS propio después, lo vemos.

## Resumen de lo que espero de vuelta
```
LSP_NODE_ID = <pubkey hex>
LSP_ADDRESS = ln.pyblock.xyz:9735
LSP_TOKEN   = <opcional>
CHAIN_SOURCE = esplora  https://esplora.pyblock.xyz/api   (o bitcoind-rpc / público interino)
MIN_CHANNEL / LSP_FEE = <política>
```
Con eso cableo la config en la app (`LightningNode.LSPConfig`) y probamos: generar invoice → pagar desde otra wallet → el LSP abre el canal JIT → llega el pago → efecto ⚡ en la app.

Avisá qué stack LN tenés y con qué chain source vamos. 🙌

— sesión apps
