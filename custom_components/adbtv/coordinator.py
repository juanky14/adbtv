"""Sondeo periódico del estado del TV."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import timedelta
import logging
import time
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator

from .api import AdbTvClient, CannotConnect, InvalidAuth
from .const import APPS_REFRESH_SECONDS, DOMAIN, SCAN_INTERVAL_SECONDS

_LOGGER = logging.getLogger(__name__)


@dataclass
class TvState:
    online: bool = False
    info: dict[str, Any] = field(default_factory=dict)
    apps: list[dict[str, str]] = field(default_factory=list)

    @property
    def foreground(self) -> str | None:
        return self.info.get("foreground") if self.online else None


class AdbTvCoordinator(DataUpdateCoordinator[TvState]):
    """Cuando el TV no responde no marcamos error: lo mostramos como apagado."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry, client: AdbTvClient) -> None:
        super().__init__(
            hass,
            _LOGGER,
            config_entry=entry,
            name=DOMAIN,
            update_interval=timedelta(seconds=SCAN_INTERVAL_SECONDS),
        )
        self.client = client
        self._state = TvState()
        self._apps_at = 0.0

    async def _async_update_data(self) -> TvState:
        try:
            info = await self.client.info()
            if not self._state.apps or time.monotonic() - self._apps_at > APPS_REFRESH_SECONDS:
                self._state.apps = await self.client.launchable()
                self._apps_at = time.monotonic()
        except InvalidAuth as err:
            raise ConfigEntryAuthFailed("El PIN del TV ha cambiado") from err
        except CannotConnect:
            self._state.online = False
            return self._state
        self._state.online = True
        self._state.info = info
        return self._state

    def refresh_apps_soon(self) -> None:
        """Fuerza recargar la lista de apps en el próximo sondeo (tras instalar algo)."""
        self._apps_at = 0.0
