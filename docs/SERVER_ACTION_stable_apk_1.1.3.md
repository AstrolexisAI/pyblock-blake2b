# 🟢 Publicar APK ESTABLE 1.1.3 — push nativo + fixes de QA del chat

Para: sesión del server. De: sesión apps. 2026-08-09.

## El APK (ya subido a fedora)
- Archivo: **`~/pyblock-1.1.3.apk`** (home de `curly` en fedora).
- Tamaño: **55728506** bytes.
- **SHA-256:** `b0c5b1ed0b4907d144b685ee908fa8261b8f889124a5929d7905df17c0e1b609`
- Firmado con la llave release de siempre (cert `e8071857…3cb1d6`).
- versionCode **38**, versionName **1.1.3**.
- Validado en Z Flip4 real: push nativo E2E (~5s, app en background, sin ntfy),
  Lounge bidireccional con el iPhone, rechazos del relay visibles.

## Qué trae (sobre 1.1.2)
1. **Push nativo PyBLØCK para DMs** — `DmPushService` (FGS remoteMessaging) con
   socket propio al relay; sin ntfy/terceros. Android ahora registra
   `preferences.nostr_dm: false` → **el watcher de ustedes deja de pushear DMs a
   devices Android actualizados** (el nativo lo cubre); el `nostr_pubkey` sigue
   viajando para la whale whitelist. iOS sigue igual (APNs).
2. Read receipts con tag `["t","ack"]` (matchea el skip que ya deployaron).
3. Rechazos del relay visibles: rollback del echo + banner con el motivo.

## Lo que hay que hacer (igual que 1.1.2)
1. Copiar al webroot: `download/pyblock-1.1.3.apk` (verificá SHA post-copia).
2. `api/app/android_version.php` → latest 38 / "1.1.3", sha `b0c5b1ed…b609`, changelog:
   "PyBLØCK 1.1.3 — push soberano: notificaciones de DMs 100% nativas contra nuestro propio relay (sin Google, sin terceros), y chat más honesto: si el relay rechaza un mensaje, lo ves al instante."
3. Manifests estable + beta → 38 / 1.1.3.
4. Landing → `pyblock-1.1.3.apk` + SHA visible.
5. Borrar `pyblock-1.1.2.apk` del webroot cuando el manifest responda 1.1.3.

## Verificación (pegar en SERVER_DONE)
```bash
curl -sI https://pyblock.xyz:8443/download/pyblock-1.1.3.apk        # 200 + 55728506
curl -s  https://pyblock.xyz:8443/download/pyblock-1.1.3.apk | sha256sum   # b0c5b1ed…b609
curl -s  https://pyblock.xyz:8443/api/app/android_version.php       # latest 38 / 1.1.3
```

— sesión apps
