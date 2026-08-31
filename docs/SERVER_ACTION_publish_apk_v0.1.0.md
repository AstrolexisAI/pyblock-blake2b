# 🟢 Fase 4 — APK firmado v0.1.0 LISTO para publicar + activar android_version

Para: sesión del server. De: sesión Android. 2026-07-30.

## Qué dejé en fedora `~/pyblock-android-docs/`
- **`pyblock-0.1.0.apk`** — APK release **firmado** (R8/minify, 1.86 MB). Firma: `CN=PyBLOCK, O=AstroLexis LLC, C=US`.
- **`android_build_manifest.json`** — metadata del build:
```json
{
  "version_code": 1, "version_name": "0.1.0",
  "apk_url": "https://pyblock.xyz/download/pyblock-0.1.0.apk",
  "sha256": "d0465f6c53e9b56f8c8b5e5b83c2377098273035bb1e9e867cb5b45f151c4627",
  "min_supported_version_code": 1,
  "changelog": "First public PyBLØCK Android release. …"
}
```

## Acciones server
1. **Hostear el APK:** copialo a la ruta pública `https://pyblock.xyz/download/pyblock-0.1.0.apk` (mismo archivo que subí). El **SHA-256** debe dar `d0465f6c53e9b56f8c8b5e5b83c2377098273035bb1e9e867cb5b45f151c4627` (ya lo verifiqué idéntico en fedora tras el scp). Publicá ese hash junto al link de descarga en el sitio.
2. **Activar `android_version.php`:** poblalo desde `android_build_manifest.json` → ahora debe devolver `available:true` con esos campos (hasta ahora devolvía `available:false`, correcto porque no había APK). El cliente ya consume `GET /api/app/android_version.php` al arrancar y muestra el prompt de actualización si `latest_version_code > su versionCode` (y fuerza si `< min_supported_version_code`).
3. **Página de descarga en el sitio:** un `pyblock.xyz/download` (o sección) con el link al APK + SHA-256 + instrucciones de sideload (habilitar "instalar apps desconocidas"). On-brand: soberano, sin Google Play.

## Verificación
- `curl https://pyblock.xyz:8443/api/app/android_version.php` → `{ok:true, available:true, latest_version_code:1, ...}`.
- `curl -sL https://pyblock.xyz/download/pyblock-0.1.0.apk | sha256sum` → `d0465f6c…`.

## Notas
- **versionCode=1 / versionName=0.1.0.** En cada release futuro te dejo un `android_build_manifest.json` nuevo con versionCode incrementado (estrictamente monotónico — es lo que dispara el updater) + su APK + SHA-256. Vos actualizás `android_version.php` y el hosting.
- Si Bruno quiere bumpear el versionName a 1.0.0 para el primer público, avisá y regenero APK+manifest.

Avisá cuando el APK esté hosteado y `android_version.php` en `available:true`, y verifico el prompt de update en el device (bajando temporalmente mi versionCode).

— sesión Android
