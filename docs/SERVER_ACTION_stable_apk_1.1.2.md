# 🟢 Publicar APK ESTABLE 1.1.2 — el release del CHAT (⚠ requiere whale_lounge policy)

Para: sesión del server. De: sesión apps. 2026-08-09.

## El APK (ya subido a fedora)
- Archivo: **`~/pyblock-1.1.2.apk`** (home de `curly` en fedora).
- Tamaño: **55728262** bytes.
- **SHA-256:** `e801c728b1cef7ed580a20d3a79a2e433479d7aef5910cd9e61f4bcbc844e586`
- Firmado con la llave release de siempre (cert `e8071857…3cb1d6`, verificado con apksigner).
- versionCode **37**, versionName **1.1.2**.

## Qué trae (sobre 1.1.1) — todo chat
1. **Scroll con foco en el último mensaje** (comunidad + DMs) + pill "↓ N" leyendo historia.
2. **Push de DMs cifrados**: el registro de device ahora manda `nostr_pubkey` +
   pref `nostr_dm` (matchea el SERVER_DONE_dm_push que ya deployaron ✅). Tap → inbox.
3. **Whale Lounge** 🐋: 2º canal NIP-28 (`a054a2c57f0f49a9aa1ac12ea82ba5c4638881da3b7368a28fdbfefeb88beeb5`),
   gate de UI por tier. **⚠ IMPORTANTE: implementar `SERVER_ACTION_whale_lounge.md`
   (write-policy strfry) ANTES o junto con publicar este APK** — sin eso el lounge
   solo está gateado client-side.
4. **Chat Flair**: colores de nombre vía kind-0 `"color"` (gratis con Pro+; el relay no cambia).
5. **Share cards**: `pyblock:block?…` / `pyblock:stack?…` en el canal → tarjetas con CTA a Buy.
6. **Quick-send** ⚡21K/⚡100K en DMs sobre PayNym.

## Lo que hay que hacer (igual que 1.1.1)
1. **Primero**: el write-policy del whale lounge (SERVER_ACTION_whale_lounge.md).
2. Copiar al webroot: `download/pyblock-1.1.2.apk` (verificá SHA post-copia).
3. `api/app/android_version.php` → latest 37 / "1.1.2", sha `e801c728…e586`, changelog:
   "PyBLØCK 1.1.2 — CHAT 2.0: push de DMs cifrados, Whale Lounge 🐋 (sala exclusiva), colores de nombre (Pro), share cards de bloques y hashrate, quick-send ⚡ por PayNym, y scroll que no pierde el último mensaje."
4. `download/android_build_manifest.json` + `download/android_beta_manifest.json` → 37 / 1.1.2.
5. Landing → botón a `pyblock-1.1.2.apk` + SHA visible.
6. Borrar `pyblock-1.1.1.apk` del webroot cuando el manifest responda 1.1.2.

## Verificación (pegar en SERVER_DONE)
```bash
curl -sI https://pyblock.xyz:8443/download/pyblock-1.1.2.apk        # 200 + content-length: 55728262
curl -s  https://pyblock.xyz:8443/download/pyblock-1.1.2.apk | sha256sum   # e801c728…e586
curl -s  https://pyblock.xyz:8443/api/app/android_version.php       # latest 37 / 1.1.2
# + verificación del whale lounge policy (npub free rechazado en canal whale)
```

— sesión apps
