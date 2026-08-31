# 🟢 Academy — JSON de contenido LISTO + pedido de publicación en el sitio

Para: sesión del server. De: sesión Android. 2026-07-30.
Cierra el item 2.1 de la auditoría y agrega el pedido de Bruno: **publicar la Academy en el sitio**.

## 1. Contenido entregado
Dejé **`~curly/pyblock-android-docs/academy_lessons.json`** (7.6 KB, JSON válido, UTF-8). Formato:

```jsonc
{
  "version": 1,
  "lessons": [
    { "id": 1, "title": "What is Bitcoin?", "summary": "Money without a middleman",
      "premium": false, "body": "…texto completo con \n\n entre párrafos…" },
    …
    { "id": 10, "title": "Run Your Own Node", "summary": "Verify, don't trust",
      "premium": true, "body": "…" }
  ]
}
```
- **10 lecciones**, ids 1–10, en orden. `premium:false` para 1–3, `premium:true` para 4–10 (las 7 premium).
- Los `body` son el texto verbatim que hoy vive embebido en el cliente Android (`AcademyData.kt`) e iOS.

## 2. `lesson.php` (audit 2.1 — servir premium a la app)
Ya tenés el endpoint `GET /api/app/lesson.php?id=<n>` (HMAC/Bearer, devuelve body solo si WHALE activo). Cablealo a este JSON: para `id` premium, devolvé `{ ok:true, id, body }` **solo si WHALE**; si no, `{ ok:false, error:"locked" }`. Para `id` no-premium (1–3) podés devolver el body a cualquiera (el cliente igual los trae embebidos, pero por consistencia está bien servirlos).
→ Cuando confirmes que `lesson.php` lee este JSON, del lado cliente saco los 7 bodies premium del APK y los bajo on-demand (con fallback offline). Avisá.

## 3. Publicar la Academy en el sitio (pedido de Bruno)
Publicá la Academy como **página pública en pyblock.xyz** (educación + SEO). Sugerencia:
- Página `pyblock.xyz/academy` (o sección en el sitio existente) que renderice las **10 lecciones** desde el mismo `academy_lessons.json` — título, summary y body, con el look retro del sitio.
- **Decisión de Bruno a confirmar con vos:** ¿publicar las 10 completas en la web (incluidas las 7 "premium"), o en la web mostrar las 3 free completas + las premium como teaser (summary + CTA a la app/Whale)? Publicar las 7 premium completas en la web las vuelve públicas y afloja el gate WHALE de la app — pero como contenido educativo/marketing puede tener sentido. **Bruno pidió "publicar en el sitio"; confirmá con él el alcance (todas vs teaser) antes de indexar.**
- Fuente única de verdad: serví web y app desde el mismo JSON así no divergen.

Avisá cuando `lesson.php` lea el JSON (para cablear el cliente) y cuando la página esté publicada.

— sesión Android
