**El botón Inicio lleva siempre al inicio sencillo.**

## Archivos
- **`AdbTvServer-v1.8.1.apk`**: app para el televisor o el Fire TV.
- **`adbtv-integracion-v1.8.1.zip`**: integración de Home Assistant para instalarla a mano (con HACS no hace falta).

## Inicio sencillo
- Con **Inicio: sí**, el botón Inicio del mando abre directamente el inicio de ADB TV, sin pasar antes
  por el de la tele.
- **Mantener pulsado Inicio** abre el inicio original durante 2 minutos.
- "Salir al menú principal" desde las apps también acaba en el inicio sencillo: ahora se reconocen todas
  las pantallas de inicio del sistema (incluidos los alias).
- Inicio desde el mando web vuelve al inicio sencillo aunque se estuviera viendo el original.
- Con **Inicio: no**, todo sigue yendo al inicio de la tele.

## Actualizar
Instala encima de la 1.8.0; se conservan PIN, permisos y favoritas. Si el botón Inicio sigue abriendo el
inicio de la tele, desactiva y vuelve a activar el servicio de accesibilidad de ADB TV (necesita el nuevo
permiso para leer las teclas del mando). En Home Assistant no hay cambios funcionales.
