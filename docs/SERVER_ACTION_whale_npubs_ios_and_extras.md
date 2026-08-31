# 🟡 Whale whitelist: (1) whales iOS/StoreKit + (2) extras manuales para QA

Para: sesión del server. De: sesión apps. 2026-08-09.

## Contexto
QA del Lounge: Android (device 176, whale LN) postea OK ✅. El iPhone de Bruno
(npub `950362de450dd51edc0878d8b33e443fda283162451d10284ce65eb727df5966`,
device 3) es rechazado — esperable HOY (su "whale" es el debug override local),
pero destapa dos cosas:

## 1. ¿Los whales iOS reales entran a la whitelist? (verificar/arreglar)
El flujo iOS es StoreKit → `api/app/subscription/verify.php`. Sospecha: eso
escribe `push_devices.is_pro/pro_expires_at` y NO la tabla `entitlements` de
`app_accounts.db` que lee `pyblock-whale-npubs.php`. Si es así, **un whale
legítimo de iOS nunca podría postear en el Lounge**.
- Verificar dónde persiste verify.php el tier (¿distingue whale de pro?).
- Si no pasa por `entitlements`: o escribir también ahí
  (`pubkey='device:<id>', kind='whale', expires_at=…`) o hacer que el
  generador ademas mire `push_devices` (columna que distinga whale).

## 2. Extras manuales (QA / equipo / mods)
Que el generador merge-e un archivo manual que sobreviva la regeneración:
`/opt/pyblock-nostr/data/whale_npubs_extra.txt` (un npub hex por línea,
comentarios con `#`). Union con los npubs derivados de entitlements, mismo
formato de salida. Y agregar ahí el npub del iPhone de Bruno para QA:
```
# QA Bruno iPhone (device 3)
950362de450dd51edc0878d8b33e443fda283162451d10284ce65eb727df5966
```

## Verificación (pegar en SERVER_DONE)
```
php pyblock-whale-npubs.php → "2 whale npubs" (176 + extra iPhone)
kind-42 canal whale firmado por 950362de… → accept
+ respuesta al punto 1 (dónde persisten los whales iOS y cómo quedó resuelto)
```

— sesión apps
