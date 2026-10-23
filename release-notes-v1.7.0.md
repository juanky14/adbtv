**Modo permisos**: control del televisor sin ADB, para **Fire OS 8** (Fire TV Stick 4K Max 2.ª gen y demás modelos actuales).

## Archivos
- **`AdbTvServer-v1.7.0.apk`**: app para el televisor o el Fire TV.
- **`adbtv-integracion-v1.7.0.zip`**: integración de Home Assistant para instalarla a mano (con HACS no hace falta).

## Por qué
Fire OS 8 corta las conexiones ADB que salen del propio dispositivo, así que la app no podía conectarse a sí
misma (`No se pudo conectar a adbd: EOFException`). Ahora lo detecta y pasa al modo permisos.

## Modo permisos
Se conceden **una sola vez** desde un PC con ADB (no caducan al reiniciar); el comando aparece en la pantalla
de la app y en el README:

```
adb shell "pm grant com.juanc.adbtv android.permission.WRITE_SECURE_SETTINGS; appops set com.juanc.adbtv SYSTEM_ALERT_WINDOW allow; appops set com.juanc.adbtv GET_USAGE_STATS allow; appops set com.juanc.adbtv REQUEST_INSTALL_PACKAGES allow; appops set com.juanc.adbtv MANAGE_EXTERNAL_STORAGE allow; pm grant com.juanc.adbtv android.permission.READ_EXTERNAL_STORAGE; pm grant com.juanc.adbtv android.permission.WRITE_EXTERNAL_STORAGE"
```

| Función | |
|---|---|
| Abrir cualquier app, Inicio, Atrás, multimedia, volumen | ✅ |
| Apagar y encender la pantalla (también desde Home Assistant) | ✅ |
| App abierta y estado de la pantalla | ✅ |
| Instalar `.apk` / `.apks` / `.xapk` y desinstalar | ✅ con confirmación en la tele |
| Gestor de archivos (almacenamiento interno y USB) | ✅ |
| Flechas y OK | ⚠️ solo en apps con vistas estándar (no en el inicio de Fire TV) |
| Terminal | ⚠️ con permisos de la app, no de shell |
| Detener, desactivar, borrar datos, restaurar apps del sistema | ❌ necesitan ADB |

Probado en Fire TV Stick 4K Max (2.ª gen), Fire OS 8.1.8.5.

## Otros cambios
- La app ya no reintenta conectar a ADB en cada petición tras un fallo.
- Corregidos los falsos enlaces simbólicos en el gestor de archivos.

## Actualizar
Instala encima de la 1.6.0. En Home Assistant, actualiza la integración desde HACS y reinicia.
