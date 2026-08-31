# 🟢 Publicar APK ESTABLE 1.1.1 — rebrand ANTI-SPAM

Para: sesión del server. De: sesión apps. 2026-08-09.

## El APK (ya subido a fedora)
- Archivo: **`~/pyblock-1.1.1.apk`** (home de `curly` en fedora).
- Tamaño: **55711874** bytes.
- **SHA-256:** `56c50ec859fa281d0216079f340b6d22621f2902a71e72dd891eb7271ecf6361`
- Firmado con la **llave release de siempre** (`CN=PyBLOCK, O=AstroLexis LLC`, cert SHA-256 `e80718578e4a6671c16a5e9b6f28e6d8fb6c362aa3cbeb2b87507429bf3cb1d6` — verificado con apksigner).
- versionCode **36**, versionName **1.1.1**.

## Qué trae (sobre 1.1.0)
Solo rebrand, sin cambios funcionales: el badge de STATS ahora dice **ANTI-SPAM** (antes
SPAM-FILTERED) y se eliminó toda mención "BIP-110" de la UI y del contenido de Academy —
la marca pasa a ser "anti-spam" a secas. Wire keys (`bip110_block`) intactas, no toca API.

## Lo que hay que hacer
1. **Copiar** al webroot: `https://pyblock.xyz:8443/download/pyblock-1.1.1.apk` (verificá el SHA-256 post-copia).
2. **Actualizar `api/app/android_version.php`** (update-check in-app) a:
   ```json
   {
     "ok": true,
     "available": true,
     "latest_version_code": 36,
     "latest_version_name": "1.1.1",
     "apk_url": "https://pyblock.xyz:8443/download/pyblock-1.1.1.apk",
     "sha256": "56c50ec859fa281d0216079f340b6d22621f2902a71e72dd891eb7271ecf6361",
     "min_supported_version_code": 1,
     "changelog": "PyBLØCK 1.1.1 — ANTI-SPAM rebrand: nuevo badge en STATS y textos actualizados en toda la app. Sin cambios funcionales sobre 1.1.0."
   }
   ```
3. **Actualizar `download/android_build_manifest.json`** (canal estable) con los mismos valores
   (version_code 36 / "1.1.1" / apk_url / sha256 / changelog).
4. **Actualizar `download/android_beta_manifest.json`** apuntando TAMBIÉN a este APK (channel "beta",
   version_code 36) — beta sigue convergida a estable; sin force-update.
5. **Academy content del server**: subí junto con este doc **`~/academy_lessons_1.1.1.json`** —
   reemplaza el que sirve el server. Cambios: lección 5 pasa de "BIP-110 & Clean Blocks" a
   "Clean Blocks" (body sin BIP-110, ahora "anti-spam Bitcoin Knots node"), y la lección de
   nodos también pierde la mención BIP-110. Mismo shape, solo texto.
6. **Landing de descarga**: botón principal → `pyblock-1.1.1.apk` + SHA-256 visible.
7. Borrar `pyblock-1.1.0.apk` del webroot cuando confirmes que el manifest nuevo responde.

## Verificación (pegar resultados en SERVER_DONE)
```bash
curl -sI https://pyblock.xyz:8443/download/pyblock-1.1.1.apk        # 200 + content-length: 55711874
curl -s  https://pyblock.xyz:8443/download/pyblock-1.1.1.apk | sha256sum   # 56c50ec8…6361
curl -s  https://pyblock.xyz:8443/api/app/android_version.php       # latest 36 / 1.1.1
curl -s  https://pyblock.xyz:8443/download/android_build_manifest.json
```

— sesión apps
