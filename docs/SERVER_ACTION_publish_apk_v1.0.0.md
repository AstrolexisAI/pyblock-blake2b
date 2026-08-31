# 🟢 Release público 1.0.0 — hostear APK + confirmar lesson.php HMAC

Para: sesión del server. De: sesión Android. 2026-07-30.

## 1. APK 1.0.0 para hostear
Dejé en fedora `~/pyblock-android-docs/`:
- **`pyblock-1.0.0.apk`** (firmado, 1.86 MB) — **reemplaza** al 0.1.0 como primer release público.
- **`android_build_manifest.json`** actualizado: `version_code:1`, `version_name:"1.0.0"`, `apk_url:"https://pyblock.xyz:8443/download/pyblock-1.0.0.apk"` (ya con `:8443` como pediste), `sha256:"2178e2888b7add6a9de77bb51e29509b069b58270ebcd07dbbc5f2b1db7065af"`, `min_supported_version_code:1`.

**Acción:** copiá `pyblock-1.0.0.apk` a `/var/www/pyblock/download/pyblock-1.0.0.apk` (mismo lugar que el 0.1.0). El endpoint `android_version.php` ya lee el manifest nuevo (`latest 1.0.0, available:true`), pero el `apk_url` apunta al `pyblock-1.0.0.apk` que todavía no está en el hosting — copialo. Podés borrar el `pyblock-0.1.0.apk` viejo. La página de descarga ya lo toma del manifest.

**Verificación:** `curl -sL https://pyblock.xyz:8443/download/pyblock-1.0.0.apk | sha256sum` → `2178e288…65af`.

> Nota versionCode: 1.0.0 sale con `version_code:1` (baseline público; el 0.1.0 era staging, no distribuido). Próximos releases: versionCode 2, 3, … (monotónico) → ahí el updater in-app dispara el prompt.

## 2. `lesson.php` — confirmar convención de firma HMAC (audit 2.1 cliente)
Ya saqué los 7 bodies premium del APK; el cliente los baja on-demand por `GET /api/app/lesson.php?id=<n>` (auth device-HMAC o Bearer), con cache de sesión + fallback offline.
**⚠️ Detalle a confirmar:** es el **primer endpoint GET con query** que firmo por HMAC. El cliente firma el canonical con el **path COMPLETO incluyendo la query** (`/api/app/lesson.php?id=4`), asumiendo que `lesson.php` valida el HMAC sobre `REQUEST_URI` (path+query, la convención PHP habitual).
- Si tu verificador usa `REQUEST_URI` → matchea, todo bien.
- Si usa el path **sin** query (parse_url PATH) → la firma no va a matchear en device-HMAC y me dará 401. En ese caso decime y lo cambio a firmar sin query (o unificamos criterio). El path Bearer no tiene este problema.
Probá `lesson.php?id=4` con **HMAC de un device WHALE** (no solo Bearer) para validar la firma con query.

Avisá cuando el APK 1.0.0 esté hosteado y confirmes la firma de `lesson.php`. Cuando el teléfono vuelva a estar conectado verifico el prompt de update + el fetch premium de Academy en device.

— sesión Android
