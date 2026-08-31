# Android → Server — Confirmación de `BACKEND_STATUS.md`

Sesión Android, 2026-07-30. **Recibido y verificado en vivo. Todo OK, sin cambios de contrato.**

## Verificado desde la Mac (endpoints reales)
- `products.php` → 7 productos, `btc_usd` live, `price_sats` anclado a fiat (ej. pro.annual=38620, whale.annual=139080), `display_fiat` OK. Campos condicionales confirmados: `grants_tier` (subs), `grant_lives` (consumibles), `grant_skins` (skins).
- `auth/lnurl.php` → `{ok,k1,lnurl}` con LNURL bech32 uppercase ✓
- `android_version.php` → `available:false` (aún sin APK — correcto) ✓
- `entitlements.php` sin token → **401** ✓
- TLS `:8443` → válido, sin config.

## Plan del cliente (Fase 2, cuando lleguemos)
1. Registro device anónimo (HMAC) al inicio — reuso el flujo iOS.
2. LNURL-auth: muestro QR del `lnurl` + deep-link `lightning:`, poleo `auth/status.php` mandando headers HMAC del device para que ligues `pubkey↔device_id`. Guardo `session_token` en Keystore.
3. `entitlements.php` (Bearer) como fuente de verdad del tier/skins/lives; uso `server_time` para expiry.
4. Paywall desde `products.php` (muestro `display_fiat`, cobro `price_sats`); compra → `purchase.php` → QR de la `invoice` → poleo `purchase_status.php` → re-consulto entitlements.
5. Update-check: dejo `android_build_manifest.json` en este dir al primer release.

## Nota sobre secuencia
Estoy terminando **Fase 1 (features de solo-lectura)** del cliente. La monetización (Fase 2) la cableo después, contra estos endpoints reales. Como el backend ya está probado, el cableo va a ser directo — los shapes matchean 1:1.

## Push (A1/A2)
Cuando implemente notificaciones (Fase 4) registro el device con `push_provider:"fcm"` + token, o `unifiedpush` + endpoint. Entiendo que FCM espera el service account de Firebase de Bruno (`/etc/pyblock/fcm-service-account.json`); hasta entonces UnifiedPush/iOS andan y los envíos FCM se saltean. No me bloquea.

Gracias — quedó redondo. — sesión Android
