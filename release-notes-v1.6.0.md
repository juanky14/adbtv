Compatibilidad con **Amazon Fire TV** y con Android 5.1 o superior.

## Archivos
- **`AdbTvServer-v1.6.0.apk`**: app para el televisor o el Fire TV.
- **`adbtv-integracion-v1.6.0.zip`**: integración de Home Assistant para instalarla a mano (con HACS no hace falta).

## Novedades
- **Fire TV** (Fire OS 5, 6, 7 y 8): Fire TV Stick, Stick 4K / 4K Max, Lite, Cube y televisores con Fire TV.
  Basta con activar *Depuración ADB* en *Opciones para desarrolladores*; no hace falta PC ni root.
  Los modelos aparecen con su nombre comercial ("Fire TV Stick 4K Max" en lugar de `AFTKA`).
- **Android 5.1 o superior** (antes 7.0). Corregido un cierre al conectar por ADB en Android 7 o anterior
  (Fire OS 5/6 incluidos).
- **Home Assistant**:
  - Encender el TV desde Home Assistant en los dispositivos que siguen en red en reposo (Fire TV).
  - El estado pasa a *apagado* cuando la pantalla duerme.
  - Las pantallas de inicio de Fire TV y Google TV cuentan como *en espera*.
  - Detección de la app abierta en Android 11+ (Fire OS 8, Google TV).

## Actualizar
- **Integración**: HACS mostrará la actualización a 1.6.0. Reinicia Home Assistant después de actualizar.
- **APK**: instala encima de la 1.5.0; se conserva el PIN y la autorización ADB.

## Notas
- En Fire OS 5 (Android 5.1) el gestor de archivos puede no funcionar (el sistema no trae `find` ni `stat`).
- Los modelos con Vega OS no ejecutan apps Android.
- En Fire TV el control de volumen depende de que la tele admita HDMI-CEC.
- Primera versión con soporte de Fire TV: si encuentras algún problema, abre un *issue*.
