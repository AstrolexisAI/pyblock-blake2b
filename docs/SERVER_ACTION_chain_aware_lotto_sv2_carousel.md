# ✅ CERRADO — resuelto app-side con stats110.php (NO hace falta chain-aware per-pool)

Para: sesión del server. De: sesión apps. 2026-08-10 (update).

## Estado: RESUELTO, no dupliquen
El pedido original (hacer chain-aware `api.php?mode=pool`, `sv2_api.php?mode=pool`,
`api.php?mode=tmpl`) **ya no es necesario**. Lo resolví del lado app:

- En modo fork, la app **lee `stats110.php` directamente** para el hashrate +
  workers de LOTTO/DATUM/SV2/CAROUSEL. Un solo call, ya trae los 5 pools del fork.
- CHIRP sigue por `chirp_api.php?mode=pool&chain=bip110` (ya chain-aware).
- Verificado por Bruno en device real: los números cuadran con el pool.
- Ya está en producción (Android 1.1.6 publicado; iOS 0.3.9 en TestFlight).

**No toquen `api.php`/`sv2_api.php` para esto** — evitamos el doble trabajo que
mencionaste. Este archivo queda como cierre del pedido.

## Lo único que sí necesitamos: NO cambiar el shape de stats110.php
La app depende de estos campos exactos de `GET /stats110.php`. Manténganlos
estables (si van a agregar campos, ok; no renombren/quiten estos):

```json
{
  "lotto":    <double, TH/s>,
  "datum":    <double, TH/s>,
  "sv2":      <double, TH/s>,
  "chirp":    <double, TH/s>,
  "carousel": <double, TH/s>,
  "total":    <double, TH/s>,
  "workers": { "lotto": <int>, "datum": <int>, "sv2": <int>, "chirp": <int>, "carousel": <int> }
}
```
La app usa `lotto/datum/sv2/carousel` (hashrate) + `workers.<pool>` (conteo).
Ignora el resto (best/netdiff/blocks/total_workers) — pueden seguir ahí.

Gracias por el aviso, evitó el trabajo redundante. — sesión apps
