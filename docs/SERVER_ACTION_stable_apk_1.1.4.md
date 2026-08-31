# 🟢 Publicar APK ESTABLE 1.1.4 — paridad Settings iOS + cross-device

Para: sesión del server. De: sesión apps. 2026-08-09.

## El APK (ya subido a fedora)
- Archivo: **`~/pyblock-1.1.4.apk`** (home de `curly` en fedora).
- Tamaño: **55761278** bytes.
- **SHA-256:** `ea1df5c9d7b6113bedc53cd3b9cd9158a5437a8974583db3a33ee7edaf8c3907`
- Firmado con la llave release de siempre (cert `e8071857…3cb1d6`).
- versionCode **39**, versionName **1.1.4**.

## Qué trae (sobre 1.1.3)
- **Settings con paridad iOS**: THEME en grid de swatches (era lista); paywall de
  suscripción con toggle MONTHLY/ANNUAL + tarjetas PRO/WHALE + botón GET (sats);
  lives/skins movidos a "ARCADE EXTRAS".
- **LINKED DEVICES** (entitlements cross-device): mostrar/ingresar código de
  pairing + lista de dispositivos del grupo + unlink por-device.
- **Suscripción LN**: botón RENEW/EXTEND (1-tap) + texto claro "no auto-renewal";
  push de recordatorio de vencimiento (ya deployado server-side).
- **About** ahora muestra la versión real (BuildConfig) — era "v0.1.0" fijo.

Depende de endpoints ya live: `account/link_start|link_claim|unlink`, y
`account/status.php` (si aún no está, la lista de devices se oculta sola y el
unlink self igual funciona — no bloquea).

## Lo que hay que hacer (igual que releases previos)
1. Copiar al webroot: `download/pyblock-1.1.4.apk` (verificá SHA post-copia).
2. `api/app/android_version.php` → latest 39 / "1.1.4", sha `ea1df5c9…3907`, changelog:
   "PyBLØCK 1.1.4 — Ajustes renovados: temas en grilla, suscripción más clara (Pro/Whale con precio en sats, renovación en 1 toque, sin cargos automáticos), y CROSS-DEVICE: comprá una vez y usá tu tier en todos tus dispositivos con un código."
3. Manifests estable + beta → 39 / 1.1.4.
4. Landing → `pyblock-1.1.4.apk` + SHA visible.
5. Borrar `pyblock-1.1.3.apk` del webroot cuando el manifest responda 1.1.4.

## Verificación (pegar en SERVER_DONE)
```bash
curl -sI https://pyblock.xyz:8443/download/pyblock-1.1.4.apk        # 200 + 55761278
curl -s  https://pyblock.xyz:8443/download/pyblock-1.1.4.apk | sha256sum   # ea1df5c9…3907
curl -s  https://pyblock.xyz:8443/api/app/android_version.php       # latest 39 / 1.1.4
```

— sesión apps
