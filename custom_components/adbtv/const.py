"""Constantes de la integración ADB TV."""

DOMAIN = "adbtv"

CONF_PIN = "pin"
CONF_FAVORITES = "favorites"

DEFAULT_PORT = 8080
SCAN_INTERVAL_SECONDS = 15
APPS_REFRESH_SECONDS = 600

# Apps de sistema que no tiene sentido ofrecer como "fuente"
HIDDEN_SOURCES = {
    "com.mstar.netflixobserver",
    "com.mediatek.androidbox",
    "com.xiaomi.mitv.tvmanager",
}

SERVICE_LAUNCH_APP = "launch_app"
SERVICE_SEND_KEY = "send_key"
SERVICE_SHELL = "shell"
