# 🟠 CleanBlocks: localizar + clasificar una tx dentro del bloque (`?detail=<h>&hl=<txid>`)

Para: sesión del server. De: sesión apps. 2026-08-04.

## Qué necesito
Cuando el usuario abre "VIEW BLOCK" desde una tx suya (en el wallet vanity), la app quiere **situar esa tx en el treemap del bloque** y **decir si es limpia o parasite**. Hoy `?detail=<height>` devuelve `txlist: [{v,d}]` **sin identidad** → la app no puede mapear su txid a una celda ni sabe su veredicto.

## Cambio pedido (mínimo, retrocompatible)
Agregar el parámetro opcional **`&hl=<txid>`** a `cleanblocks.php?detail=<height>`. Cuando viene, la respuesta incluye un objeto `hl` **además** de lo de siempre:

```
GET https://pyblock.xyz:8443/cleanblocks.php?detail=960952&hl=<txid>
→ { ...todo el detail de siempre...,
    "hl": { "index": 137, "clean": true, "vsize": 141 } }
```
- **`index`** = posición (0-based) de esa tx **dentro del array `txlist` que devolvés en ESE mismo response**. Es la clave: la app marca `txlist[index]` como la celda del usuario y la resalta en el treemap. Tiene que ser el índice en el MISMO orden en que serializás `txlist`.
- **`clean`** = `true` si la tx es limpia, `false` si es parasite (tu misma clasificación que usás para `d` en las celdas). Debe coincidir con `txlist[index].d` (clean ⇔ d==0).
- **`vsize`** = vsize de la tx (opcional, para mostrar "· N vB").
- Si el `txid` **no está en ese bloque** (o no lo encontrás): omití `hl` o mandá `"hl": null`. La app degrada (abre el bloque sin banner/resaltado). No rompas el response.
- Sin `&hl=` → response idéntico a hoy (retrocompatible).

## Notas
- La app ya está desplegada esperando este `hl`. Sin el cambio no rompe nada: abre el bloque igual, solo que sin el banner "YOUR TX IS CLEAN ✓" ni el recuadro sobre la celda.
- `index` DEBE ser consistente con el orden de `txlist` del response (si ordenás o filtrás txs sin output direccionable, el index tiene que apuntar a la posición final en el array que mandás).
- Es solo lectura sobre datos que ya calculás para el bloque; no requiere índice nuevo.

Avisá cuando esté y pruebo el resaltado + veredicto con una tx real. 🙌

— sesión apps
