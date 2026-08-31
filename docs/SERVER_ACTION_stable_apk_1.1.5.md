# 🟢 Publicar APK ESTABLE 1.1.5 — fix lista LINKED DEVICES (shape de status.php)

Para: sesión del server. De: sesión apps. 2026-08-09.

## Por qué reemplaza al 1.1.4 (recién publicado)
El 1.1.4 se compiló ANTES de que existiera `account/status.php`, con un shape
adivinado (`id`/`this`/`anchor`). El status.php real usa
`device_id`/`is_self`/`last_seen_at` + `account:"device:<id>"` (sin campo anchor).
Con eso, la lista de LINKED DEVICES en el 1.1.4 se ve mal (ids en #0, no marca
"this device"). El 1.1.5 alinea el parseo (anchor derivado de `account`) —
verificado E2E por curl contra status.php: link → lista correcta → unlink self → colapso.
El resto del 1.1.4 (theme, paywall, renew, link, unlink self) ya andaba.

## El APK (ya subido a fedora)
- Archivo: **`~/pyblock-1.1.5.apk`**  ·  Tamaño: **55761274** bytes.
- **SHA-256:** `3d6c7f0b50375240600625cc9b5c1fdfc87b6f346c6d0793bbf4bb9cd0c9cf2c`
- Firmado con la llave release de siempre (cert `e8071857…3cb1d6`).
- versionCode **40**, versionName **1.1.5**.

## Lo que hay que hacer
1. Copiar al webroot: `download/pyblock-1.1.5.apk` (verificá SHA post-copia).
2. `api/app/android_version.php` → latest 40 / "1.1.5", sha `3d6c7f0b…cf2c`, changelog:
   "PyBLØCK 1.1.5 — mejoras de CROSS-DEVICE: lista de dispositivos vinculados con dueño y 'este dispositivo', y desvincular por dispositivo. (Sobre 1.1.4: temas en grilla, suscripción clara, renovar en 1 toque.)"
3. Manifests estable + beta → 40 / 1.1.5.
4. Landing → `pyblock-1.1.5.apk` + SHA visible.
5. Borrar `pyblock-1.1.4.apk` del webroot cuando el manifest responda 1.1.5.

## Verificación (pegar en SERVER_DONE)
```bash
curl -sI https://pyblock.xyz:8443/download/pyblock-1.1.5.apk        # 200 + 55761274
curl -s  https://pyblock.xyz:8443/download/pyblock-1.1.5.apk | sha256sum   # 3d6c7f0b…cf2c
curl -s  https://pyblock.xyz:8443/api/app/android_version.php       # latest 40 / 1.1.5
```

— sesión apps
