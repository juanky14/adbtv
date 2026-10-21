# Alexa con ADB TV

La integración **ADB TV** crea, por cada televisor, un interruptor por cada app favorita
(encender = abrir la app, apagar = volver al inicio). Alexa entiende interruptores, así que basta con
exponérselos.

## 1. Elegir las apps

*Ajustes → Dispositivos y servicios → ADB TV → Configurar* → marca las apps. Se crean entidades como
`switch.tv_del_dormitorio_netflix`. Funciona igual con cualquier TV y cualquier app instalada.

## 2. Exponerlas a Alexa (elige una opción)

### A. Home Assistant Cloud (Nabu Casa, de pago, lo más sencillo)
*Ajustes → Asistentes de voz → Exponer* → activa los interruptores → "Alexa, descubre dispositivos".

### B. emulated_hue (gratis, local)
Añade a `configuration.yaml` y reinicia:

```yaml
emulated_hue:
  listen_port: 80           # obligatorio para los Echo modernos
  expose_by_default: false
  entities:
    switch.tv_del_dormitorio_netflix: { name: "Netflix", hidden: false }
    switch.tv_del_dormitorio_youtube: { name: "YouTube", hidden: false }
```

Después: "Alexa, descubre dispositivos". Requisitos: puerto 80 libre en el equipo de Home Assistant,
misma red que el Echo y, en Docker, `network_mode: host`.

## 3. Frases naturales

App Alexa → *Más → Rutinas → +* → *Cuando digas* "pon Netflix en el dormitorio" →
*Hogar digital* → Netflix → Encender.

Con varios televisores, pon nombres distintos a cada interruptor ("Netflix salón", "Netflix dormitorio").

## Limitaciones

- Alexa no puede pasar un nombre de app libre ("abre *cualquier cosa*"): cada app que quieras usar por
  voz debe estar en favoritas. Desde Home Assistant (automatizaciones, paneles, Assist) sí puedes abrir
  cualquier app con `adbtv.launch_app`.
- Encender el TV por red no es posible: en reposo la app está detenida. "Apagar" sí funciona.
