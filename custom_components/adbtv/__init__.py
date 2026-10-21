"""Integración ADB TV: controla televisores Android con la APK ADB TV Server."""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PORT, Platform
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import AdbTvClient
from .const import CONF_PIN
from .coordinator import AdbTvCoordinator

PLATFORMS = [Platform.MEDIA_PLAYER, Platform.SWITCH]

type AdbTvConfigEntry = ConfigEntry[AdbTvCoordinator]


async def async_setup_entry(hass: HomeAssistant, entry: AdbTvConfigEntry) -> bool:
    client = AdbTvClient(
        async_get_clientsession(hass), entry.data[CONF_HOST], entry.data[CONF_PORT], entry.data[CONF_PIN]
    )
    coordinator = AdbTvCoordinator(hass, entry, client)
    await coordinator.async_config_entry_first_refresh()
    entry.runtime_data = coordinator

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    # Al cambiar las apps favoritas se recargan los interruptores
    entry.async_on_unload(entry.add_update_listener(_async_reload))
    return True


async def _async_reload(hass: HomeAssistant, entry: AdbTvConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: AdbTvConfigEntry) -> bool:
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
