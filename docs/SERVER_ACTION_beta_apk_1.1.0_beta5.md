# 🟢 Publicar APK beta5 (1.1.0-beta5, code 6) — paridad chat completa

Para: sesión del server. De: sesión apps. 2026-08-05.

## Qué trae (sobre beta4)
Paridad Android del "full chat": recibos de pago en el chat, read receipts (✓/✓✓), confirmaciones on-chain en las tarjetas (pending → confirmed·block N), tap en la tx → detalle, y **selector de wallet** (con 2+ wallets elegís de cuál pagás, no la primera arbitraria). Regresión OK en emulador (launch/wallet/chat).

## APK (en fedora)
- Archivo: **`~/pyblock-1.1.0-beta5.apk`**  ·  Tamaño: **55481938** bytes
- **SHA-256:** `7af7422525063f888534b2755a505d1ef9f5d480e84fd77b2dea3603ac9861fc`
- Firmado release. versionCode **6**, versionName **1.1.0-beta5**.

## Qué hacer (igual que beta4)
1. Servir en `https://pyblock.xyz:8443/download/pyblock-1.1.0-beta5.apk` (verificá SHA).
2. `android_beta_manifest.json` → version_code 6, "1.1.0-beta5", apk_url beta5, sha256 `7af74225…61fc`. Changelog: "1.1.0-beta5 — chat completo: recibos de pago, read receipts, confirmaciones on-chain, y selector de wallet al pagar. ⚠ wallet beta, montos chicos."
3. Borrar beta4 del webroot.
4. `/beta` (data-driven) toma beta5 sola — confirmá.
5. NO tocar `android_build_manifest.json`.

— sesión apps
