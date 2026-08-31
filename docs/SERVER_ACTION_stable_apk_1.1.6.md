# 🟢 Publicar APK ESTABLE 1.1.6 — cross-device completo + selector dual-chain BIP-110

Para: sesión del server. De: sesión apps. 2026-08-10.

## Reemplaza al 1.1.5 (que nunca se publicó)
1.1.6 = 1.1.5 (cross-device link/unlink + paridad Settings) **+ el selector
dual-chain BIP-110**. Publicar directo 1.1.6, saltando el 1.1.5.

## El APK (ya subido a fedora)
- Archivo: **`~/pyblock-1.1.6.apk`**  ·  Tamaño: **55777662** bytes.
- **SHA-256:** `f44fc0d3a8d6695f876c9d6bfcc27e94774797bf8acf23cd7f828ceac6a53a03`
- Firmado con la llave release de siempre (cert `e8071857…3cb1d6`).
- versionCode **41**, versionName **1.1.6**.

## Qué trae (sobre 1.1.4 publicado)
- **Cross-device**: LINKED DEVICES con lista de dispositivos del grupo + unlink
  por-device (consume `account/status.php`), alineado al shape real del server.
- **Settings paridad iOS**: theme en grilla, paywall de suscripción, RENEW/EXTEND,
  About dinámico.
- **Selector dual-chain BIP-110** (NUEVO): toggle LEGACY ⇄ BIP-110 en STATS
  (acento púrpura). En modo fork la app manda `?chain=bip110` a los endpoints de
  pool (interceptor central, excluye la cuenta) y **lee `stats110.php`** para el
  hashrate de LOTTO/DATUM/SV2/CAROUSEL (los per-pool aún no son chain-aware; solo
  chirp_api). Port map fork en miner setup (pool110: 4445/23336/5575/5574/30110).
  Compra de hashrate queda en legacy.

## Lo que hay que hacer (igual que releases previos)
1. Copiar al webroot: `download/pyblock-1.1.6.apk` (verificá SHA post-copia).
2. `api/app/android_version.php` → latest 41 / "1.1.6", sha `f44fc0d3…3a03`, changelog:
   "PyBLØCK 1.1.6 — CROSS-DEVICE: comprá una vez y usá tu tier en todos tus dispositivos (link + gestión). Y nuevo selector de cadena LEGACY ⇄ BIP-110 en STATS para ver los pools de la fork en tiempo real."
3. Manifests estable + beta → 41 / 1.1.6.
4. Landing → `pyblock-1.1.6.apk` + SHA visible.
5. Borrar `pyblock-1.1.4.apk` (y el 1.1.5 si llegó a subirse) del webroot cuando el manifest responda 1.1.6.

## Verificación (pegar en SERVER_DONE)
```bash
curl -sI https://pyblock.xyz:8443/download/pyblock-1.1.6.apk        # 200 + 55777662
curl -s  https://pyblock.xyz:8443/download/pyblock-1.1.6.apk | sha256sum   # f44fc0d3…3a03
curl -s  https://pyblock.xyz:8443/api/app/android_version.php       # latest 41 / 1.1.6
```

— sesión apps
