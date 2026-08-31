# 🟠 Reemplazar APK beta → 1.1.0-beta2 (fix crash R8)

Para: sesión del server. De: sesión apps. 2026-08-05.

## Por qué
El beta anterior (1.1.0-beta, code 2) **crasheaba al toque** en un S22 Ultro / Android 16 al abrir el generador de wallet. Causa: R8 (minify del release) ofuscaba las bindings nativas del wallet (JNA/BDK/secp256k1, que no traen reglas consumer). Fix: keep rules agregadas + minify OFF para la beta. Nueva build **1.1.0-beta2 (versionCode 3)**.

## APK (ya en fedora)
- Archivo: **`~/pyblock-1.1.0-beta2.apk`**
- Tamaño: 57,366,517 bytes
- **SHA-256:** `878ff299b519064048ab240f5620ebd46063e1e75cc06c82554d37c7296e8f97`
- Firmado con la llave release de siempre (`CN=PyBLOCK, O=AstroLexis LLC`).
- versionCode **3**, versionName **1.1.0-beta2**.

## Qué hacer
1. **Servir** el APK nuevo en:
   `https://pyblock.xyz:8443/download/pyblock-1.1.0-beta2.apk`
   (verificá el SHA post-copia).
2. **Actualizar la landing `/beta`** para que el botón de descarga apunte al **beta2** (`pyblock-1.1.0-beta2.apk`) y el SHA mostrado sea `878ff299…8f97`. El link anterior (`pyblock-1.1.0-beta.apk`) podés dejarlo o borrarlo — la gente que ya lo bajó debe actualizar a beta2.
3. **Actualizar `android_beta_manifest.json`** → version_code 3, version_name "1.1.0-beta2", apk_url al beta2, sha256 `878ff299…8f97` (mantené el changelog con la advertencia de wallet/montos chicos).
4. **NO tocar** `android_build_manifest.json` (sigue en 1.0.0, sin force-update).

## Verificación
- `curl -sI …/download/pyblock-1.1.0-beta2.apk` → 200 + content-length 57366517.
- `curl -s …/pyblock-1.1.0-beta2.apk | sha256sum` → `878ff299…8f97`.
- `/beta` apunta al beta2.

Avisá cuando esté servido y le paso el link actualizado a Bruno para reprobar el crash. 🙏

— sesión apps
