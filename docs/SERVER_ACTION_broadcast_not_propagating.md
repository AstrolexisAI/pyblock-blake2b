# 🔴 URGENTE: broadcast desde la app NO propaga — diagnóstico en el nodo

Para: sesión del server. De: sesión apps. 2026-08-04.

## Síntoma
La app (wallet vanity) firma y broadcastea una tx **por el propio nodo de PyBLØCK** (Kyoto/BDK → peer `179.27.118.130:8333`, v2 transport). El cliente devuelve el txid (parece "✓ enviado"), pero la tx **no aparece en NINGÚN mempool**:
- `mempool.space/api/tx/<txid>` → **404**
- `pyblock.xyz:8443/api/wallet_mempool.php?address=<addr>` → `txs:[]`
- Fondos intactos (no se gastó nada) ✅

**TXID a investigar:** `95e37d0af830f2509ce47b200661f40e6ce9fc3948adae87bc07597d9cf4bd6f`
**Address del wallet (P2PKH legacy):** `1ELiKKGevWKHdEFTqgX9LVVBZ9rZoK9BfX`

## Qué necesito que chequees en el nodo
1. **¿El nodo vio esa tx?**
   - `bitcoin-cli getmempoolentry 95e37d0af830f2509ce47b200661f40e6ce9fc3948adae87bc07597d9cf4bd6f`
   - `bitcoin-cli getrawtransaction 95e37d…f4bd6f true`
   - **`grep 95e37d0af830 ~/.bitcoin/debug.log`** (o donde esté) → razón de rechazo si la vio y la tiró.
2. **¿El nodo acepta relay de txs de sus peers en :8333?** Sospecha principal:
   - `bitcoin-cli getnetworkinfo` → mirá `"localrelay"` y flags.
   - Verificá que **NO** esté `blocksonly=1` ni `-connect` que corte relay. Si el nodo está en blocksonly, **rechaza toda tx entrante de peers** → el broadcast de la app muere silencioso. Ese sería el culpable.
   - `getpeerinfo` → para conexiones entrantes, `"relaytxes": true` debe estar.
3. **Política de relay/fee:**
   - `bitcoin-cli getmempoolinfo` → `minrelaytxfee` / `mempoolminfee`. La app mandó ~**2 sat/vB**; si el nodo tiene un mínimo más alto, la rechaza.
   - Knots a veces trae políticas de datacarrier/standardness más estrictas — pero es una P2PKH normal (`1…` → `1…`), nada raro.
4. **(Ideal) testmempoolaccept:** si tenés el hex crudo (no lo tengo del lado app todavía), `bitcoin-cli testmempoolaccept '["<hex>"]'` da el veredicto exacto. Si no, con el debug.log alcanza.

## Hipótesis ordenadas
1. **`blocksonly` / tx-relay deshabilitado** en el nodo → no acepta txs de peers. **La más probable** (el nodo sirve compact filters para bloques, pero puede no relayar txs). → habilitar relay de txs para que el broadcast de wallets funcione.
2. minrelaytxfee > 2 sat/vB → subir el fee del lado app (configurable, fácil).
3. Firma inválida (formato de key) → improbable, el balance se ve bien; pero si el debug.log dice `mandatory-script-verify-flag-failed`, es esto y lo arreglo del lado app.

Con lo que salga del **debug.log** sé exactamente qué pasa. Avisá 🙏

— sesión apps
