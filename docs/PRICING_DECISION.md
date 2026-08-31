# PyBLØCK Android — Decisión de precios (Bruno, 2026-07-30)

Para `products.php` / `purchase.php` (B2/B3). Cierra la pregunta abierta en `SERVER_RESPONSE.md` §4 y `ANDROID_REPLY.md` §4.

## Modelo: **ANCLADO A FIAT**

El precio **autoritativo es en USD**. El server:
1. En cada `GET /api/app/products.php`, **recalcula `price_sats` en vivo** desde el precio de BTC del momento (usá la misma fuente que ya usás para las quotes de hashrate, ej. mempool.guide `/api/v1/prices` → USD).
2. En `POST /api/app/purchase.php`, **congela el `price_sats`** cotizado y emite la invoice por ese monto. La invoice manda: el usuario paga los sats congelados aunque BTC se mueva mientras paga.

Resultado: el usuario paga siempre **~lo mismo en dólares** (paridad con iOS), y los sats flotan.

## Catálogo (valores fiat reales, de iOS)

| id | kind | período / grant | **price_usd (ancla)** |
|---|---|---|---|
| `pro.monthly`   | subscription   | 30d  | **2.99**  |
| `pro.annual`    | subscription   | 365d | **24.99** |
| `whale.monthly` | subscription   | 30d  | **9.99**  |
| `whale.annual`  | subscription   | 365d | **89.99** |
| `lives10`       | consumable     | +10 vidas | **1.99** |
| `lives30`       | consumable     | +30 vidas | **4.99** |
| `skins`         | nonconsumable  | unlock all skins | **2.99** |

## Shape actualizado de `products.php`

```jsonc
GET /api/app/products.php
→ {
  "ok": true,
  "btc_usd": 98000.00,            // el precio de BTC usado para el cálculo (para transparencia/cache)
  "products": [
    { "id": "pro.annual", "kind": "subscription", "period_days": 365,
      "price_usd": 24.99,          // ANCLA autoritativa
      "price_sats": 25500,          // derivado en vivo (redondeado)
      "display_fiat": "$24.99",     // = price_usd formateado
      "grants_tier": "pro" },
    // ... idem resto
  ]
}
```
- `price_usd` es la fuente de verdad; `price_sats` es derivado y **puede cambiar entre consultas** (es lo esperado).
- `purchase.php` congela el `price_sats` del momento en la invoice + lo guarda en el registro de la compra.
- Redondeo de sats: a tu criterio (redondear a sats enteros; opcional redondear a la decena para prolijidad).

## Nota
Estos son los **valores reales de Bruno** (de la app iOS), no placeholders. Reemplazan los `price_sats` inventados de `SERVER_INTEGRATION.md` B2. Si Bruno ajusta algún precio USD más adelante, se actualiza acá.

— sesión Android (por decisión de Bruno)
