Primera versión pública de **ADB TV**: control de televisores Android desde la red local y desde Home Assistant.

## Archivos
- **`AdbTvServer-v1.5.0.apk`**: app para el televisor (Android 7+, pensada para Android TV).
- **`adbtv-integracion-v1.5.0.zip`**: integración de Home Assistant para instalarla a mano (con HACS no hace falta).

## ADB TV Server (APK)
- Se conecta a sí misma por ADB (usuario `shell`) si el TV tiene ADB por red, o lo activa con root.
- Panel web `http://IP-DEL-TV:8080` protegido con PIN:
  - **Terminal** shell.
  - **Apps**: instalar `.apk` / `.apks` / `.xapk`, abrir, detener, desactivar, borrar datos, desinstalar, restaurar apps de sistema, descargar APK.
  - **Archivos**: navegar, subir, descargar, vista previa, copiar/cortar/pegar, renombrar, borrar, instalar APKs.
- API HTTP y anuncio mDNS `_adbtv._tcp` para el descubrimiento automático.
- Arranque automático al encender el TV.

## Integración de Home Assistant
- Descubrimiento automático de cada TV con la APK; solo hay que introducir el PIN.
- `media_player` por TV: todas las apps como fuentes, volumen, silencio, play/pausa, apagar y app actual.
- Servicios `adbtv.launch_app`, `adbtv.send_key` y `adbtv.shell` (con respuesta).
- Apps favoritas como interruptores, para Alexa o Google ("Alexa, enciende Netflix").

## Instalación
- **Integración**: HACS → Repositorios personalizados → `https://github.com/juanky14/adbtv` (Integración).
- **APK**: instalar en el TV y activar *Depuración USB/ADB* en Opciones de desarrollador. Ver el README.

## Notas
- Requiere Home Assistant 2024.11 o posterior.
- En Android 9 y anteriores, sin ADB por red ni root, la app funciona con permisos limitados.
- Si tenías instalada una compilación de pruebas (firmada con la clave debug), desinstálala antes de instalar esta.
