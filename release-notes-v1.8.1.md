**Nuevo logo** y el botón Inicio lleva siempre al inicio sencillo.

## Archivos
- **`AdbTvServer-v1.8.1.apk`**: app para el televisor o el Fire TV.
- **`adbtv-integracion-v1.8.1.zip`**: integración de Home Assistant para instalarla a mano (con HACS no hace falta).

## Nuevo logo
- Logo de ADB TV en el banner (mosaico a bloque completo en el inicio y en el launcher del sistema) y en la
  cabecera de la app.
- Icono nuevo: la tele del logo sobre fondo negro.

## Inicio sencillo
- Con **Inicio: sí**, el botón Inicio del mando abre directamente el inicio de ADB TV, sin pasar antes
  por el de la tele.
- **Mantener pulsado Inicio** abre el inicio original durante 2 minutos.
- "Salir al menú principal" desde las apps también acaba en el inicio sencillo: ahora se reconocen todas
  las pantallas de inicio del sistema (incluidos los alias).
- Inicio desde el mando web vuelve al inicio sencillo aunque se estuviera viendo el original.
- Con **Inicio: no**, todo sigue yendo al inicio de la tele.
- En Fire TV, Fire OS puede gestionar el botón Inicio antes que la app: si aún se ve un instante el inicio
  de Amazon, la redirección lo sustituye enseguida por el inicio sencillo.

## Home Assistant
- Los interruptores de apps favoritas dan la orden por buena unos segundos, para que Alexa no diga que
  el dispositivo no responde mientras la app se abre.
- Al desmarcar una app de favoritas se borra su interruptor (antes quedaba como no disponible).

## Actualizar
Instala encima de la 1.8.0; se conservan PIN, permisos y favoritas. Si el botón Inicio sigue abriendo el
inicio de la tele, desactiva y vuelve a activar el servicio de accesibilidad de ADB TV (necesita el nuevo
permiso para leer las teclas del mando). En Home Assistant, actualiza la integración desde HACS y reinicia.
