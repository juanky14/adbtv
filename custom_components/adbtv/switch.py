"""Un interruptor por app favorita: encender = abrir la app, apagar = volver al inicio.

Pensado para asistentes de voz (Alexa, Google) que no saben elegir "fuentes":
"Alexa, enciende Netflix".
"""

from __future__ import annotations

from typing import Any

from homeassistant.components.switch import SwitchEntity
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import AdbTvConfigEntry
from .api import AdbTvError
from .const import CONF_FAVORITES
from .entity import AdbTvEntity


async def async_setup_entry(
    hass: HomeAssistant, entry: AdbTvConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    coordinator = entry.runtime_data
    labels = {a["pkg"]: a["label"] for a in coordinator.data.apps}
    async_add_entities(
        AdbTvAppSwitch(coordinator, pkg, labels.get(pkg, pkg))
        for pkg in entry.options.get(CONF_FAVORITES, [])
    )


class AdbTvAppSwitch(AdbTvEntity, SwitchEntity):
    _attr_icon = "mdi:application"

    def __init__(self, coordinator, pkg: str, label: str) -> None:
        super().__init__(coordinator)
        self._pkg = pkg
        self._attr_name = label
        self._attr_unique_id = f"{self._device_id}_app_{pkg}"

    @property
    def is_on(self) -> bool:
        return self.coordinator.data.foreground == self._pkg

    @property
    def available(self) -> bool:
        return self.coordinator.data.online

    async def _call(self, coro: Any) -> None:
        try:
            await coro
        except AdbTvError as err:
            raise HomeAssistantError(f"ADB TV: {err}") from err
        await self.coordinator.async_request_refresh()

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._call(self.coordinator.client.launch(self._pkg))

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._call(self.coordinator.client.key("HOME"))
