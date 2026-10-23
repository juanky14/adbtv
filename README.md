# ADB TV

Control total de televisores Android desde la red local, en dos piezas:

- **ADB TV Server** (`AdbTvServer.apk`): se instala en el televisor. Se conecta a sí misma por ADB
  (usuario `shell`) y ofrece un panel web y una API protegidos con un PIN.
- **Integración ADB TV para Home Assistant** (`custom_components/adbtv`): descubre solos los televisores
  con la APK y permite abrir cualquier app, enviar teclas o comandos, y usarlo con Alexa.

## 1. En el televisor

1. Instala `AdbTvServer.apk` (`adb install -r AdbTvServer.apk`, o desde un USB con un gestor de archivos).
2. Activa *Opciones de desarrollador → Depuración USB/ADB*. En muchos Android TV esto ya abre ADB por red.
   Si no, ejecuta una vez desde un PC `adb tcpip 5555` (o usa root: la app lo activa sola).
3. Abre **ADB TV Server** y acepta el diálogo "¿Permitir depuración?" marcando *Permitir siempre*.
4. La pantalla muestra la IP, el PIN y el estado de ADB. La app arranca sola al encender el TV.

> Android 9 y anteriores no tienen depuración inalámbrica: sin ADB por red ni root, la app funciona
> con permisos limitados (no puede instalar, gestionar apps ni ver archivos del sistema).

### Amazon Fire TV
Compatible con Fire TV Stick, Stick 4K / 4K Max, Lite, Cube y televisores con Fire TV integrado
(Fire OS 5, 6, 7 y 8). **No** funciona en los modelos con Vega OS (no ejecutan apps Android).

1. *Ajustes → Mi Fire TV → Acerca de* → pulsa **7 veces** sobre el nombre del dispositivo hasta que
   aparezcan las *Opciones para desarrolladores* (en modelos antiguos ya están visibles).
2. *Ajustes → Mi Fire TV → Opciones para desarrolladores* → activa **Depuración ADB**.
3. Instala la APK con la app **Downloader** (de la Appstore de Amazon): introduce la URL de la APK de la
   [última Release](https://github.com/juanky14/adbtv/releases/latest) y, cuando lo pida, permite a
   Downloader instalar apps desconocidas.
4. Abre **ADB TV Server** desde *Tus apps y canales*.

**Fire OS 8 bloquea que una app use ADB sobre su propio dispositivo**, así que ahí la app funciona en
**modo permisos**. Hay que concederlos **una sola vez** desde un PC con ADB (no caducan al reiniciar):

```
adb connect IP-DEL-FIRE-TV:5555
adb shell "pm grant com.juanc.adbtv android.permission.WRITE_SECURE_SETTINGS; appops set com.juanc.adbtv SYSTEM_ALERT_WINDOW allow; appops set com.juanc.adbtv GET_USAGE_STATS allow; appops set com.juanc.adbtv REQUEST_INSTALL_PACKAGES allow; appops set com.juanc.adbtv MANAGE_EXTERNAL_STORAGE allow; pm grant com.juanc.adbtv android.permission.READ_EXTERNAL_STORAGE; pm grant com.juanc.adbtv android.permission.WRITE_EXTERNAL_STORAGE"
```

(La pantalla de la app y el registro muestran este mismo comando si falta algún permiso.) La app activa
sola su servicio de accesibilidad.

| En modo permisos | |
|---|---|
| Abrir cualquier app, Inicio, Atrás, multimedia, volumen | ✅ |
| Apagar y **encender** la pantalla (también desde Home Assistant) | ✅ |
| App abierta y estado de la pantalla | ✅ |
| Instalar `.apk` / `.apks` / `.xapk` | ✅ con confirmación en la tele |
| Desinstalar | ✅ con confirmación en la tele |
| Gestor de archivos (almacenamiento interno y USB) | ✅ |
| Flechas y OK | ⚠️ solo en apps con vistas estándar; no en el inicio de Fire TV ni apps Compose |
| Terminal | ⚠️ con los permisos de la app, no como usuario shell |
| Detener, desactivar, borrar datos, restaurar apps del sistema | ❌ necesitan ADB |

El volumen solo llega a la tele si admite HDMI-CEC. En Fire OS 5/6/7 la app puede usar ADB local con
normalidad (acepta el diálogo "¿Permitir depuración USB?" marcando *Permitir siempre*); si no, también
recurre al modo permisos.

### Inicio sencillo (sin anuncios)
Una pantalla de inicio minimalista: reloj, tus apps favoritas en mosaicos grandes, *Todas las apps* y
*Ajustes*. Mantén pulsado **OK** sobre una app para añadirla o quitarla de favoritas.

- Actívalo con el botón **Inicio sencillo** de la app, desde el panel web o con `POST /api/home enabled=1`.
- En Fire TV (donde el inicio de Amazon no se puede desactivar) necesita el modo permisos: el servicio
  de accesibilidad abre el inicio sencillo cada vez que aparece el de Amazon. Los Ajustes de Fire TV
  siguen funcionando, y *Todas las apps → Inicio original* muestra el de Amazon durante 2 minutos.

### Debloat de Fire TV (opcional)
Desactiva publicidad, telemetría y extras con `pm disable-user --user 0 <paquete>` desde un PC. Fire OS
protege algunos (p. ej. `com.amazon.ftvads.deeplinking`, `com.amazon.device.metrics`): a esos se les puede
prohibir funcionar en segundo plano con `cmd appops set <paquete> RUN_ANY_IN_BACKGROUND ignore`.
Si desactivas el salvapantallas de Amazon (`com.amazon.ftv.screensaver`), apaga también el salvapantallas
(`settings put secure screensaver_enabled 0`); si no, el Fire TV se suspende en cuanto debería mostrarlo.

### Panel web: `http://IP-DEL-TV:8080`
- **Terminal**: comandos shell como `adb shell`.
- **Apps**: instalar `.apk` / `.apks` / `.xapk` (arrastrando), abrir, detener, desactivar, borrar datos,
  desinstalar o restaurar apps de sistema, descargar el APK.
- **Archivos**: explorar `/sdcard`, USB, `/data/local/tmp`…; subir, descargar, vista previa,
  copiar/cortar/pegar, renombrar, borrar, instalar APKs.

## 2. En Home Assistant

### Instalación
- **HACS**: *HACS → ⋮ → Repositorios personalizados* → URL de este repositorio, tipo *Integración* →
  instalar **ADB TV** → reiniciar Home Assistant.
- **Manual**: copia `custom_components/adbtv` a `/config/custom_components/` y reinicia.

### Configuración
Con la app abierta en el TV aparece *"Nuevo dispositivo encontrado: <nombre del TV>"* en
*Ajustes → Dispositivos y servicios*. Pulsa *Configurar* e introduce el PIN. Si no aparece (redes con
mDNS bloqueado), añade la integración **ADB TV** a mano con la IP y el PIN. Cada TV es un dispositivo.

### Qué obtienes
| Entidad / servicio | Uso |
|---|---|
| `media_player.<tv>` | Estado (encendido/apagado, app actual), **fuente = cualquier app instalada**, volumen, silencio, play/pausa, apagar y encender (este último en Fire TV y en los TV que siguen en red en reposo) |
| `switch.<tv>_<app>` | Una por app favorita (*Configurar* en la integración). Encender = abrir, apagar = inicio |
| `adbtv.launch_app` | Abrir una app por nombre (`netflix`, `prime video`) o paquete |
| `adbtv.send_key` | Pulsar teclas del mando (`HOME`, `BACK`, `DPAD_UP`, `VOLUME_UP`…) |
| `adbtv.shell` | Ejecutar un comando y obtener su salida |

Ejemplo de automatización:
```yaml
action: adbtv.launch_app
target:
  entity_id: media_player.tv_del_dormitorio
data:
  app: netflix
```

### Alexa
Ver [home-assistant/README.md](home-assistant/README.md): las apps favoritas se exponen como
interruptores ("Alexa, enciende Netflix").

## API de la APK
Todas las rutas requieren la cabecera `X-Token: <PIN>`.

| Ruta | Descripción |
|---|---|
| `GET /api/info` | Id, nombre, modelo, versión, app en primer plano |
| `GET /api/launchable` | Apps que se pueden abrir |
| `POST /api/launch` `app=<nombre o paquete>` (`dry=1` solo resuelve) | Abrir una app |
| `POST /api/key` `key=HOME&times=1` | Pulsar una tecla |
| `POST /api/exec` `cmd=…&timeout=30` | Comando shell |
| `GET /api/apps`, `POST /api/app`, `POST /api/install` | Gestión de apps |
| `/api/fs/*` | Gestor de archivos |

La APK se anuncia por mDNS como `_adbtv._tcp` (TXT: `id`, `model`, `manufacturer`).

## Seguridad
El panel usa HTTP sin cifrar con un PIN de 6 dígitos (10 fallos bloquean la IP 60 s). Úsalo solo en tu
red local y no abras el puerto 8080 a Internet. Cambia el PIN desde la app del TV si lo has compartido.

## Compilar la APK
```
./gradlew assembleRelease
```
Salida: `app/build/outputs/apk/release/app-release.apk`.

### Firma
Las versiones publicadas se firman con una clave propia. Android solo instala una actualización
si está firmada con **la misma clave** que la versión instalada.

- En local, Gradle lee `keystore.properties` (fuera de git):
  ```
  storeFile=keystore/adbtv-release.jks
  storePassword=…
  keyAlias=adbtv
  keyPassword=…
  ```
- En CI, las mismas claves como variables de entorno: `ADBTV_KEYSTORE`, `ADBTV_KEYSTORE_PASSWORD`,
  `ADBTV_KEY_ALIAS`, `ADBTV_KEY_PASSWORD`.
- Sin clave, el release se firma con la clave debug (válido para pruebas, no para publicar).

## Licencia
[MIT](LICENSE)
