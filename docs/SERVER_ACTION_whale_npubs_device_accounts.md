# 🔴 Whale Lounge: whitelist vacía para compras device-anónimas (bug, 1er whale real)

Para: sesión del server. De: sesión apps. 2026-08-09.

## Reproducción real (Bruno, hoy)
Compró WHALE por Lightning en Android (device 176), el Lounge se habilitó en la
UI, posteó… y el relay lo rechazó ("restricted"): el canal whale del relay está
vacío y `/opt/pyblock-nostr/data/whale_npubs.txt` tiene **0 bytes** aunque el
cron corre.

## Causa
La assumption que dejaron anotada en SERVER_DONE_whale_lounge falló, pero por
otro lado: para compras **device-anónimas** el entitlement queda como
`entitlements.pubkey = "device:176"` y **`account_devices` está vacía** (solo se
puebla al linkear cuenta wallet/LNURL-auth). El generador
`/usr/local/bin/pyblock-whale-npubs.php` resuelve npubs SOLO vía
`account_devices` → para `device:*` no encuentra nada → lista vacía.

Verificado:
```
entitlements:      device:176 | whale | expira 2026-09-08  ✓ activo
account_devices:   (vacía)
push_devices 176:  nostr_pubkey 7fb588a5f58e…3856          ✓ registrado
```

## Fix (patch al generador)
En el loop de whales, ANTES del lookup por `account_devices`, manejar el caso
`device:<id>`:

```php
foreach ($whales->fetchAll(PDO::FETCH_COLUMN) as $pk) {
    $deviceIds = [];
    if (str_starts_with((string)$pk, 'device:')) {
        $deviceIds[] = (int)substr((string)$pk, 7);      // cuenta anónima por device
    } else {
        $devQ->execute([$pk]);                            // cuenta wallet linkeada
        $deviceIds = array_map('intval', $devQ->fetchAll(PDO::FETCH_COLUMN));
    }
    foreach ($deviceIds as $did) { …lookup npub igual que hoy… }
}
```

## Verificación (pegar en SERVER_DONE)
```bash
php /usr/local/bin/pyblock-whale-npubs.php     # → "1 whale npubs"
grep -c . /opt/pyblock-nostr/data/whale_npubs.txt   # → 1 (7fb588a5…3856)
# kind-42 al canal whale firmado por 7fb588a5… → accept (Bruno re-postea desde la app)
```

## Nota apps (backlog nuestro, no de ustedes)
El cliente muestra el mensaje aunque el relay lo rechace (echo optimista sin
mirar el ["OK",id,false,…]) — lo vamos a arreglar para que el rechazo se vea.

— sesión apps
