"""Un interruptor por app favorita: encender = abrir la app, apagar = volver al inicio.

Pensado para asistentes de voz (Alexa, Google) que no saben elegir "fuentes":
"Alexa, enciende Netflix".
"""

from __future__ import annotations

import time
from typing import Any

from homeassistant.components.switch import SwitchEntity
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import AdbTvConfigEntry
from .api import AdbTvError
from .const import CONF_FAVORITES
from .entity import AdbTvEntity

# Lanzar una app puede tardar >10 s y el sondeo posterior va con el debounce de 10 s
# del coordinator. emulated_hue sólo espera 2 s al cambio de estado y, si luego Alexa
# lee "apagado", dice que el dispositivo no responde aunque la app se haya abierto.
# Así que damos por buena la orden durante este margen o hasta que el TV la confirme.
ASSUMED_STATE_SECONDS = 30


async def async_setup_entry(
    hass: HomeAssistant, entry: AdbTvConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    coordinator = entry.runtime_data
    favorites = entry.options.get(CONF_FAVORITES, [])
    _async_remove_stale_switches(hass, entry, favorites)
    labels = {a["pkg"]: a["label"] for a in coordinator.data.apps}
    async_add_entities(
        AdbTvAppSwitch(coordinator, pkg, labels.get(pkg, pkg)) for pkg in favorites
    )


@callback
def _async_remove_stale_switches(
    hass: HomeAssistant, entry: AdbTvConfigEntry, favorites: list[str]
) -> None:
    """Borra del registro los interruptores de apps que ya no son favoritas.

    Al desmarcar una app bastaba con no volver a crear su entidad, pero entonces
    quedaba registrada como huérfana (no disponible) y los asistentes de voz
    seguían anunciándola. Borrarla del registro emite un evento `remove` que, con
    Home Assistant Cloud, hace que Alexa la elimine sola.

    La lista sale de `entry.options`, no del TV, así que es seguro aunque el
    televisor esté apagado: nunca borra por falta de respuesta.
    """
    registry = er.async_get(hass)
    device_id = entry.unique_id or entry.entry_id
    expected = {f"{device_id}_app_{pkg}" for pkg in favorites}
    for entity in er.async_entries_for_config_entry(registry, entry.entry_id):
        if (
            entity.domain == Platform.SWITCH
            and f"{device_id}_app_" in entity.unique_id
            and entity.unique_id not in expected
        ):
            registry.async_remove(entity.entity_id)


class AdbTvAppSwitch(AdbTvEntity, SwitchEntity):
    _attr_icon = "mdi:application"

    def __init__(self, coordinator, pkg: str, label: str) -> None:
        super().__init__(coordinator)
        self._pkg = pkg
        self._attr_name = label
        self._attr_unique_id = f"{self._device_id}_app_{pkg}"
        self._assumed: tuple[bool, float] | None = None  # (estado, caduca)

    @property
    def _reported_on(self) -> bool:
        return self.coordinator.data.foreground == self._pkg

    @property
    def is_on(self) -> bool:
        if self._assumed is not None:
            return self._assumed[0]
        return self._reported_on

    @callback
    def _handle_coordinator_update(self) -> None:
        if self._assumed is not None and (
            self._reported_on == self._assumed[0] or time.monotonic() > self._assumed[1]
        ):
            self._assumed = None
        super()._handle_coordinator_update()

    @property
    def available(self) -> bool:
        return self.coordinator.data.online

    async def _call(self, coro: Any, assumed: bool) -> None:
        # Publicar el estado antes de esperar al TV: es lo que lee Alexa.
        self._assumed = (assumed, time.monotonic() + ASSUMED_STATE_SECONDS)
        self.async_write_ha_state()
        try:
            await coro
        except AdbTvError as err:
            self._assumed = None
            self.async_write_ha_state()
            raise HomeAssistantError(f"ADB TV: {err}") from err
        await self.coordinator.async_request_refresh()

    async def async_turn_on(self, **kwargs: Any) -> None:
        await self._call(self.coordinator.client.launch(self._pkg), True)

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._call(self.coordinator.client.key("HOME"), False)
