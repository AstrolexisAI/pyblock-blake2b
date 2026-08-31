# 🟢 Publicar APK beta4 (1.1.0-beta4, code 5) — fix del "shake"

Para: sesión del server. De: sesión apps. 2026-08-05.

## Qué trae (sobre beta3)
Fix del paso de entropía "shake": el gate de movimiento ahora exige un **shake real** (detección por delta entre muestras) — antes avanzaba con el ruido del sensor. No es seguridad (la entropía es aditiva sobre CSPRNG), es UX. Todo lo demás igual que beta3 (fix crash + pagos privados).

## APK (ya en fedora)
- Archivo: **`~/pyblock-1.1.0-beta4.apk`**
- Tamaño: **55,465,554** bytes
- **SHA-256:** `9815efbb3224898ed7cc4966a8adf467f763aec7eac17b8a1ec9a0dfeb98d0ea`
- Firmado release (`CN=PyBLOCK, O=AstroLexis LLC`). versionCode **5**, versionName **1.1.0-beta4**.

## Qué hacer (igual que beta3)
1. Servir en `https://pyblock.xyz:8443/download/pyblock-1.1.0-beta4.apk` (verificá SHA).
2. Actualizar `android_beta_manifest.json` → version_code 5, "1.1.0-beta4", apk_url beta4, sha256 `9815efbb…d0ea` (mantené la advertencia de wallet beta en el changelog; agregá "fix: shake entropy step").
3. Borrar beta3 del webroot.
4. Landing `/beta` (data-driven) toma beta4 sola — confirmá.
5. NO tocar `android_build_manifest.json` (sigue 1.0.0).

## Verificación
- `curl -sI …/pyblock-1.1.0-beta4.apk` → 200 + content-length 55465554.
- `curl -s …/pyblock-1.1.0-beta4.apk | sha256sum` → `9815efbb…d0ea`.

— sesión apps
