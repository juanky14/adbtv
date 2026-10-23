**Inicio sencillo sin anuncios** y nuevo diseño sobrio de ADB TV.

## Archivos
- **`AdbTvServer-v1.8.0.apk`**: app para el televisor o el Fire TV.
- **`adbtv-integracion-v1.8.0.zip`**: integración de Home Assistant para instalarla a mano (con HACS no hace falta).

## Inicio sencillo
Una pantalla de inicio minimalista y sin anuncios: reloj, tus apps favoritas en mosaicos grandes,
*Todas las apps* y *Ajustes*.
- Todos los mosaicos ocupan el bloque completo, con esquinas redondeadas; la app seleccionada se eleva
  suavemente (sin bordes de color).
- **Reordenar favoritas**: mantén pulsado OK → *Mover*, flechas para colocarla, OK para terminar.
  Desde el mismo menú se quitan; en *Todas las apps* se añaden.
- **Sin scroll**: las apps se reparten en páginas que caben enteras en pantalla.
- *Todas las apps* oculta las apps técnicas del sistema que no son para la tele.
- Se activa con el botón *Inicio* de la app o con `POST /api/home enabled=1`.
- En **Fire TV** sustituye al inicio de Amazon (que Fire OS no deja desactivar) mediante el servicio de
  accesibilidad del modo permisos. Los Ajustes de Fire TV siguen funcionando y *Todas las apps → Inicio
  original* muestra el de Amazon durante 2 minutos.

## Nuevo diseño
- Logo nuevo: televisor de línea + "ADB TV", blanco sobre carbón (icono y banner). La app se llama "ADB TV".
- Pantalla de la app y panel web con paleta neutra, sin verde.

## Correcciones
- Fire OS 8 no avisa a las apps del arranque: el servidor ahora arranca con el servicio de accesibilidad.
- Abrir Ajustes en Fire TV ya no se interrumpe por la redirección del inicio.

## Actualizar
Instala encima de la 1.7.0; se conservan PIN, permisos y favoritas. En Home Assistant, actualiza la
integración desde HACS (sin cambios funcionales en esta versión) y reinicia.
