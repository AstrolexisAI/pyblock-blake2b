# 🟢 Publicar APK beta3 (1.1.0-beta3, code 4) — fix crash + pagos privados

Para: sesión del server. De: sesión apps. 2026-08-05.

## Qué trae
1. **Fix del crash** de "Generate new wallet" (faltaba el permiso `ACCESS_NETWORK_STATE`) + hardening de runtime de una auditoría (guards de cripto nativa/Keystore, `catch(Throwable)`, abiFilters). **Validado en un emulador Android real**: la app abre, el generador de wallet abre, y el community chat abre + conecta + carga historial. Ambos crashes reportados resueltos.
2. **Pagos privados entre usuarios** (nuevo): dentro de los DMs encriptados podés **pedir** (payment request BIP-21) y **enviar** sats on-chain a un contacto (usa la wallet + el send con confirmación de 2 pasos ya existente). La address de recibo se comparte por perfil o por request.

## APK (ya en fedora)
- Archivo: **`~/pyblock-1.1.0-beta3.apk`**
- Tamaño: **55,465,554** bytes
- **SHA-256:** `b4ebf2b6868f220971f55472d38299d39624a0f3cd372e160e8c27394c996722`
- Firmado con la llave release de siempre (`CN=PyBLOCK, O=AstroLexis LLC`).
- versionCode **4**, versionName **1.1.0-beta3**.

## Qué hacer (igual que beta2)
1. **Servir** en `https://pyblock.xyz:8443/download/pyblock-1.1.0-beta3.apk` (verificá el SHA post-copia).
2. **Actualizar `android_beta_manifest.json`** → version_code 4, version_name "1.1.0-beta3", apk_url al beta3, sha256 `b4ebf2b6…6722`. Changelog sugerido (inglés, mantené la advertencia de wallet beta):
   `"1.1.0-beta3 — FIX: wallet generator no longer crashes. NEW: private on-chain payments between users inside encrypted DMs (request + pay). Plus Nostr chat with NIP-44 DMs and the entropy-hardened vanity generator. ⚠ The wallet moves real BTC and isn't fully validated on-chain yet — use small amounts only."`
3. **Borrar** el beta2 (`pyblock-1.1.0-beta2.apk`) del webroot para que no circule.
4. La landing `/beta` es data-driven → debería tomar el beta3 sola. Confirmá que muestra v1.1.0-beta3 + SHA `b4ebf2b6…6722`.
5. **NO tocar** `android_build_manifest.json` (sigue 1.0.0, sin force-update).

## Verificación
- `curl -sI …/download/pyblock-1.1.0-beta3.apk` → 200 + content-length 55465554.
- `curl -s …/pyblock-1.1.0-beta3.apk | sha256sum` → `b4ebf2b6…6722`.
- `/beta` muestra v1.1.0-beta3.

Avisá cuando esté servido y le paso el link a Bruno. 🙌

— sesión apps
