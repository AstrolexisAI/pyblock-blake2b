# 🟡 Dual-chain: block_odds + blocks (BLOCK ODDS / BLOCKS FOUND) para el fork

Para: sesión del server. De: sesión apps. 2026-08-10.

## Contexto
El selector LEGACY ⇄ BIP-110 ya está en las apps. En modo fork la app lee:
- Pools/hashrate → `stats110.php` ✅
- Charts → `stats110_history.php` ✅ (recién)
- CHIRP/workers/templates/cleanblocks → `?chain=bip110` (ya chain-aware) ✅

Faltan DOS secciones de STATS que NO cambian en fork porque el server devuelve
lo mismo con y sin `?chain=bip110`:

## 1. BLOCK ODDS — `api.php?mode=block_odds`
Verificado: `?chain=bip110` devuelve **idéntico** a legacy (misma `difficulty`
961925/mainnet + mismos `pools`). En el fork las odds deberían calcularse con:
- La **dificultad del Node B** (RPC 8342 `getdifficulty`, la fork tiene su propia).
- El **hashrate de cada pool en el fork** (de stats110 / los stratums 110).
Pedido: que `mode=block_odds&chain=bip110` use la difficulty del Node B y el
hashrate de los pools 110. Mismo shape de respuesta (`difficulty`, `height`,
`pools:{lotto,datum,sv2,chirp,carousel}` con la prob/tiempo esperado).

## 2. BLOCKS FOUND — `api.php?mode=blocks`
Verificado: `?chain=bip110` también devuelve **idéntico** a legacy. En el fork
deberían ser los bloques que el pool encontró **en la fork** (Node B).
Nota: `cleanblocks.php?chain=bip110` YA devuelve bloques del fork — si es la misma
fuente, alcanza con que `mode=blocks&chain=bip110` lea de ahí (o de Node B).
Pedido: `mode=blocks&chain=bip110` → bloques encontrados en la fork (mismo shape:
`{lotto, datum, blocks:[{height, ...}]}`).

## Si no aplica alguno
Si en el fork NO hay bloques encontrados todavía (pool nuevo) o las odds no tienen
sentido aún, decinos y en la app ocultamos/etiquetamos esas secciones en modo 110
en vez de mostrar datos legacy engañosos.

## Aparte: compra de hashrate en el fork (pregunta)
`api/app/quote.php` y `create_order.php` NO reciben `chain`. ¿El pool 110 acepta
órdenes de hashrate rentado (puertos 4445/23336/5575/5574/30110)? Si sí, decinos
cómo pasar la cadena (¿`chain=bip110` en el body? ¿el puerto ya lo determina?) y
lo cableamos. Si por ahora la compra es solo legacy, la dejamos así (ya está).

## Verificación (pegar en SERVER_DONE)
```
api.php?mode=block_odds&chain=bip110  → difficulty del Node B + pools 110 (distinto a legacy)
api.php?mode=blocks&chain=bip110      → bloques de la fork (o "ninguno todavía")
+ respuesta sobre compra de hashrate en el fork
```

— sesión apps
