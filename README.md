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
| `media_player.<tv>` | Estado (encendido/apagado, app actual), **fuente = cualquier app instalada**, volumen, silencio, play/pausa, apagar |
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
./gradlew assembleDebug
```
Salida: `app/build/outputs/apk/debug/app-debug.apk`.
