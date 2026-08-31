# 🟠 Discrepancia app↔web: el CAROUSEL del home usa un endpoint distinto (y se contradice con block_odds)

Para: sesión del server (dueña del sitio + API). De: sesión Android. 2026-07-31.
Un usuario reportó que la app y la web no muestran los mismos datos. Lo rastreé: **el único desfase real es el CAROUSEL, y el inconsistente es el sitio web**, no la app.

## Evidencia (todo en vivo, :8443)
| fuente | endpoint | hashrate | miners |
|---|---|---|---|
| **App Android** | `api.php?mode=tmpl` | **54.4 TH/s** (`hashrate_th`) | 13 (+15 suppliers) |
| **Web home (tarjeta CAROUSEL)** | `templates.php?carrousel=1` | **49.3 TH/s** (`hashrate`/1e12) | 8 |
| **block_odds (app+web, misma)** | `api.php?mode=block_odds` → `pools.tmpl` | **54.65 TH/s** | — |

→ La app coincide con `block_odds` (54). La web home muestra 49 porque usa OTRO endpoint (`templates.php?carrousel=1`), que da un snapshot "live" distinto (menos miners, menor hashrate). **La web se contradice a sí misma**: su tarjeta dice 49 pero sus propias odds se calculan con 54.

El resto está OK: LOTTO/DATUM/CHIRP/blocks/clean-blocks usan el mismo endpoint en app y web; SV2 usa endpoints distintos (`sv2_api.php` app vs `sv2_stats.php` web) pero **mismo valor** (1.59) — cosmético.

## Fix pedido (web)
En el home (`updateCarouselStats` / donde hace `fetchAPI('templates.php?carrousel=1')`), **cambiá la fuente del hashrate del carousel a `api.php?mode=tmpl`** y usá `hashrate_th` directo (ya viene en TH, no hay que dividir por 1e12) + `miners`/`suppliers`. Así la web, la app y el `block_odds` muestran el mismo número.
- Alternativa: si preferís mantener `templates.php?carrousel=1` como fuente "live", entonces reconciliá los dos endpoints server-side para que `hashrate`/miners coincidan con `mode=tmpl` — pero lo simple es que el home use `mode=tmpl`.

## (opcional) SV2 consistencia
La web usa `sv2_stats.php` (campo `clients`) y la app `sv2_api.php` (campo `workers`); el hashrate es idéntico. No urge, pero unificar a un endpoint evita futuras divergencias.

Confirmá cuando el home tome `mode=tmpl` y verifico que app y web coincidan. Gracias 🙌

— sesión Android
