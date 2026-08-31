# 🟠 Publicar APK BETA 1.1.0 (wallet) — canal beta, SIN force-update

Para: sesión del server. De: sesión apps. 2026-08-05.

## Contexto
Nueva build **beta** con features grandes para que la gente pruebe: **wallet self-custodial on-device** (nodo CBF, receive/send/0-conf), **chat Nostr + DMs NIP-44**, y **generador vanity con endurecimiento de entropía**. Es un **BETA** — la wallet aún no se validó on-chain con fondos reales, así que se distribuye como **link de descarga aparte**, NO como update forzado a los usuarios 1.0.0.

## El APK (ya subido a fedora)
- Archivo: **`~/pyblock-1.1.0-beta.apk`** (en el home de `curly` en fedora).
- Tamaño: 45,667,706 bytes.
- **SHA-256:** `24b96bbd9b20f58dae32a310fe90b9442224356b36364c5d64cc98b654ba61e3`
- Firmado con la **llave release de siempre** (`CN=PyBLOCK, O=AstroLexis LLC`, cert SHA-256 `e80718578e4a6671c16a5e9b6f28e6d8fb6c362aa3cbeb2b87507429bf3cb1d6`) → upgrade-compatible con 1.0.0.
- versionCode **2**, versionName **1.1.0-beta**.

## Lo que hay que hacer
1. **Copiar** el APK al webroot de descargas, como:
   `https://pyblock.xyz:8443/download/pyblock-1.1.0-beta.apk`
   Verificá el SHA-256 post-copia (debe coincidir con el de arriba).

2. **NO tocar `android_build_manifest.json`.** El updater in-app usa ese manifest (canal único); si lo actualizás a code 2, TODOS los usuarios 1.0.0 verían "update disponible" hacia una beta con wallet sin probar on-chain. Dejalo en 1.0.0.

3. **Canal beta separado** (opcional pero recomendado): publicar un manifest beta aparte en
   `https://pyblock.xyz:8443/download/android_beta_manifest.json`:
   ```json
   {
     "version_code": 2,
     "version_name": "1.1.0-beta",
     "apk_url": "https://pyblock.xyz:8443/download/pyblock-1.1.0-beta.apk",
     "sha256": "24b96bbd9b20f58dae32a310fe90b9442224356b36364c5d64cc98b654ba61e3",
     "channel": "beta",
     "changelog": "PyBLØCK 1.1.0 BETA — NUEVO: wallet self-custodial on-device (nodo CBF, recibir/enviar/0-conf), chat Nostr + DMs encriptados NIP-44, generador vanity con entropía endurecida. ⚠️ La wallet es BETA y todavía no fue probada on-chain: usá MONTOS CHICOS solamente, no confíes fondos grandes hasta que se valide. Reportá cualquier problema."
   }
   ```
   (La app todavía no lee este manifest beta; queda listo para cuando cableemos un opt-in de canal beta.)

4. **Landing de descarga** para compartir: una página/enlace simple (ej. `https://pyblock.xyz/beta` o una sección en el sitio) con el botón de descarga del APK, el SHA-256 para verificar, y **la advertencia bien visible**: *"Beta — wallet nueva, usá montos chicos."* Instrucción de sideload (permitir orígenes desconocidos).

## Verificación
- `curl -sI https://pyblock.xyz:8443/download/pyblock-1.1.0-beta.apk` → 200 + `content-length: 45667706`.
- `curl -s https://pyblock.xyz:8443/download/pyblock-1.1.0-beta.apk | sha256sum` → `24b96bbd…61e3`.
- `android_build_manifest.json` sigue en version_code 1 (sin cambios).

## Importante (seguridad)
La wallet mueve BTC real y **no se probó on-chain todavía**. La comunicación al usuario (landing + changelog) DEBE dejar claro que es beta y que usen montos chicos. No promover como release estable.

Avisá cuando el APK esté servido + el SHA verificado y te paso el link a la gente. 🙌

— sesión apps
