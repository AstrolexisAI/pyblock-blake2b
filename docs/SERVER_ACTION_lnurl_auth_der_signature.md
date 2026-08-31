# 🔴 ACCIÓN REQUERIDA — `auth/callback.php` rechaza firmas DER (LNURL-auth roto)

**Prioridad: BLOQUEANTE de login/suscripciones en Android. Ningún usuario puede loguearse hoy.**
Para: sesión del server (backend PHP en `pyblock.xyz:8443`).
De: sesión Android. 2026-07-30. Reproducido en device real (Galaxy Z Flip 4 + wallet Phoenix) y con repro determinista (abajo).

## Síntoma
En la app Android → More → Settings → **PYBLØCK PRO → SIGN IN WITH LIGHTNING** → se escanea el QR con Phoenix (o cualquier wallet). La wallet firma el challenge y llama al callback, y el server devuelve:

```
Lnurl error = invalid signature from pyblock.xyz
```

El cliente y la wallet hacen todo bien. El bug está en la **verificación de firma del callback**.

## Causa raíz (confirmada con fuzzing determinista)
El endpoint `GET /api/app/auth/callback.php?tag=login&k1=..&action=login&sig=..&key=..`
**solo acepta una firma cruda de 64 bytes (`r‖s`)** y **rechaza la firma DER**, que es la que exige LUD-04 y la que envían TODAS las wallets reales.

Probé 5 variantes firmando el mismo `k1` con la misma linking key (secp256k1), contra el callback en vivo:

| # | Cómo se firmó `sig` | Respuesta del server |
|---|---|---|
| A | k1 crudo (32B) · **DER canónico (low-S)** · key comprimida ← **lo que manda Phoenix / LUD-04** | `{"status":"ERROR","reason":"invalid signature"}` |
| B | sha256(k1) · DER · comprimida | `invalid signature` |
| C | sha256(hex-ascii de k1) · DER · comprimida | `invalid signature` |
| **D** | **k1 crudo · raw 64B `r‖s` · comprimida** | **`{"status":"OK"}`** ✅ |
| E | k1 crudo · DER · key **sin** comprimir (04..) | `invalid signature` |

Solo **D** (raw 64 bytes) pasa. LUD-04 manda **DER**. Por eso ninguna wallet real entra.

## El fix (backend)
En `auth/callback.php`, al verificar `sig`:
1. **DER-decodificar** el `sig` entrante (hex → estructura ASN.1 `SEQUENCE{ INTEGER r, INTEGER s }`). No asumir 64 bytes crudos ni `r = substr(0,32), s = substr(32,32)`.
2. Verificar ECDSA/secp256k1 de esa firma contra el **mensaje = `k1` crudo (32 bytes)** — `k1` ya ES el digest, **no** se le vuelve a hacer sha256 (variantes B/C confirman que doble-hash tampoco es lo esperado).
3. La `key` viene **comprimida** (33 bytes, prefijo 02/03) — soportarla (variante A usa comprimida; es lo normal).

Sugerido: si usás `simplito/elliptic-php`, `$ec->verify($k1Hex, ['r'=>.., 's'=>..], $pubKey)` — construí `{r,s}` **desde el DER**, no cortando bytes. Con `BitWasp/bitcoin-php` o `mdanter/ecc` hay `DerSignatureSerializer` para parsear.

## Cómo verificar que quedó (repro determinista, sin wallet)
Con `k1` fresco de `auth/lnurl.php`, linking key de secret `0x1234…cdef`:
- La firma **DER** (variante A) debe pasar a devolver `{"status":"OK"}`.
- La firma raw-64 (variante D) puede seguir andando o no — es irrelevante; lo que importa es que **DER pase**.

Repro Python (ecdsa puro) usado por la sesión Android disponible si lo querés; básicamente:
`sk.sign_digest(k1_bytes, sigencode=sigencode_der_canonize)` debe autenticar.

O directo con la app: escanear el QR de login con Phoenix → debe quedar logueado (tier FREE) sin el error.

## Por qué importa
Sin esto, **nadie puede loguearse** → nadie puede comprar Pro/Whale/vidas/skins → toda la monetización Lightning de Android está caída. Es el bloqueante #1 para publicar. (El bloqueante previo de `devices_anonymous.php` android quedó resuelto; Buy/HMAC anda.)

Avisá cuando esté y re-testeo login + compra end-to-end con Phoenix en el device.

— sesión Android
