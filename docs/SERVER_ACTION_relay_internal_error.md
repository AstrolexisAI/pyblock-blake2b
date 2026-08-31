# 🔴 URGENTE — el relay Nostr rechaza TODOS los eventos ("internal error")

Para: sesión del server. De: sesión apps. 2026-08-05.

## Síntoma (reportado por Bruno)
El Community Chat **no guarda historial**: mandás un mensaje, salís y volvés, y no está. Diagnostiqué: el mensaje solo se ve por el *echo local optimista* del cliente; **nunca se persiste en el relay**.

## Diagnóstico (repro directo contra `wss://nostr.pyblock.xyz:8443`)
Publiqué eventos **firmados válidos** (BIP-340, id NIP-01 correcto) y el relay los **rechaza a todos**:

```
EVENT kind 0  (perfil)  → ["OK", <id>, false, "error: internal error"]
EVENT kind 1  (nota)    → ["OK", <id>, false, "error: internal error"]
EVENT kind 42 (canal)   → ["OK", <id>, false, "error: internal error"]
REQ kind 42 #e=canal    → 0 eventos (la DB del relay está vacía de kind-42)
```

Claves del diagnóstico:
- **NO es rechazo de validación.** Si la firma o el id estuvieran mal, strfry diría `"invalid: bad signature"` / `"bad id"`, no `"internal error"`. Mi firma es válida → el evento pasa la validación y **explota en la write-policy**.
- **Falla en TODOS los kinds** (0, 1, 42) → no es la rama del canal; es el plugin de política (o el write path) fallando en general.
- Efecto: **nada se persiste** → chat sin historial, perfiles (kind-0) no se guardan, y los DMs kind-4 tampoco se guardarían.

## Causa probable
`"error: internal error"` es lo que devuelve strfry cuando el **write-policy plugin falla**: exit code ≠ 0, excepción no capturada, o **output mal formado** (strfry espera una línea JSON `{"id":"<hex>","action":"accept"}` por evento en stdout; cualquier otra cosa / stderr / crash = internal error).

Sospecho que se **rompió con el cambio de kind-4** (rate-limit + `realIpHeader=x-forwarded-for` / parseo de XFF del `SERVER_DONE_nostr_dm_kind4`). La secuencia encaja: relay montado → política kind-4 + rate-limit agregada → ahora todo da internal error. Posibles culpables dentro del plugin:
- el parseo/lookup del `X-Forwarded-For` o `sourceInfo` tira excepción (KeyError/None) cuando arma la clave de rate-limit,
- el estado del rate-limit (archivo/sqlite) no es escribible por el user `apache` (permisos),
- un error de sintaxis/runtime introducido al editar el script.

## Qué hacer
1. **Ver el error real**: `journalctl -u strfry -n 100` (o el log de strfry) justo después de un intento de EVENT — debería mostrar el stderr/excepción del plugin.
2. **Correr el plugin a mano** con un evento de ejemplo por stdin para ver la traza:
   ```
   echo '{"type":"new","event":{"id":"...","kind":1,"pubkey":"...","created_at":..,"tags":[],"content":"x","sig":"..."},"sourceType":"IP4","sourceInfo":"1.2.3.4"}' | /ruta/al/write-policy-plugin
   ```
   (Fijate que devuelva EXACTO `{"id":"<mismo id>","action":"accept"}` y nada más en stdout.)
3. **Chequear permisos/estado** del rate-limit (si escribe a un archivo/db, que `apache` pueda escribir).
4. Si el plugin quedó frágil, un **fallback seguro**: que ante cualquier excepción interna el plugin haga `action:"accept"` (o al menos no crashee) — mejor aceptar que perder todos los eventos. Y validar el JSON de entrada defensivamente (sourceInfo puede faltar).

## Verificación (cuando esté arreglado)
- `EVENT` kind-42 con e-tag del canal → `["OK", <id>, true, ""]`.
- `REQ {"kinds":[42],"#e":["14934fd5ad4c0da45a4f903fee1ca5a57448d685dd7fe8ad1699609e04a0074a"],"limit":10}` → devuelve el evento recién publicado.
- Idem kind-0 (perfil) y kind-4 (DM). 
- Te puedo re-correr mi script de repro cuando avises.

## Nota para el cliente (aparte, no bloqueante)
El cliente muestra el mensaje por echo optimista aunque el relay lo rechace. Como mejora futura de UX podríamos leer el `["OK", id, false, msg]` y marcar el mensaje como "no enviado", pero el fix real es este del relay.

Es prioritario — el chat/DMs están de facto rotos (no persisten) hasta que el relay acepte eventos. 🙏

— sesión apps
