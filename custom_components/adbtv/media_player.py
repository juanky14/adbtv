"""Reproductor multimedia: el TV, con todas sus apps como fuentes."""

from __future__ import annotations

from typing import Any

import voluptuous as vol

from homeassistant.components.media_player import (
    MediaPlayerDeviceClass,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
)
from homeassistant.core import HomeAssistant, ServiceResponse, SupportsResponse
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import config_validation as cv, entity_platform
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import AdbTvConfigEntry
from .api import AdbTvError, AppNotFound
from .const import HIDDEN_SOURCES, SERVICE_LAUNCH_APP, SERVICE_SEND_KEY, SERVICE_SHELL
from .entity import AdbTvEntity

LAUNCHERS = {"com.google.android.tvlauncher", "com.google.android.leanbacklauncher", "com.mitv.tvhome.atv"}


async def async_setup_entry(
    hass: HomeAssistant, entry: AdbTvConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    async_add_entities([AdbTvMediaPlayer(entry.runtime_data)])

    platform = entity_platform.async_get_current_platform()
    platform.async_register_entity_service(
        SERVICE_LAUNCH_APP, {vol.Required("app"): cv.string}, "async_launch_app"
    )
    platform.async_register_entity_service(
        SERVICE_SEND_KEY,
        {vol.Required("key"): cv.string, vol.Optional("times", default=1): vol.All(int, vol.Range(1, 50))},
        "async_send_key",
    )
    platform.async_register_entity_service(
        SERVICE_SHELL,
        {vol.Required("command"): cv.string, vol.Optional("timeout", default=30): vol.All(int, vol.Range(1, 600))},
        "async_shell",
        supports_response=SupportsResponse.OPTIONAL,
    )


class AdbTvMediaPlayer(AdbTvEntity, MediaPlayerEntity):
    _attr_name = None  # usa el nombre del dispositivo
    _attr_device_class = MediaPlayerDeviceClass.TV
    _attr_supported_features = (
        MediaPlayerEntityFeature.TURN_OFF
        | MediaPlayerEntityFeature.VOLUME_STEP
        | MediaPlayerEntityFeature.VOLUME_MUTE
        | MediaPlayerEntityFeature.SELECT_SOURCE
        | MediaPlayerEntityFeature.PLAY
        | MediaPlayerEntityFeature.PAUSE
        | MediaPlayerEntityFeature.NEXT_TRACK
        | MediaPlayerEntityFeature.PREVIOUS_TRACK
    )

    def __init__(self, coordinator) -> None:
        super().__init__(coordinator)
        self._attr_unique_id = f"{self._device_id}_tv"

    # ---- estado ----
    @property
    def available(self) -> bool:
        return True  # apagado se muestra como "off", no como no disponible

    @property
    def state(self) -> MediaPlayerState:
        data = self.coordinator.data
        if not data.online:
            return MediaPlayerState.OFF
        return MediaPlayerState.IDLE if data.foreground in LAUNCHERS else MediaPlayerState.ON

    @property
    def _apps(self) -> dict[str, str]:
        return {a["pkg"]: a["label"] for a in self.coordinator.data.apps if a["pkg"] not in HIDDEN_SOURCES}

    @property
    def source_list(self) -> list[str]:
        return sorted(self._apps.values(), key=str.lower)

    @property
    def source(self) -> str | None:
        return self._apps.get(self.coordinator.data.foreground or "")

    @property
    def app_id(self) -> str | None:
        return self.coordinator.data.foreground

    @property
    def app_name(self) -> str | None:
        return self.source

    # ---- acciones ----
    async def _call(self, coro) -> Any:
        try:
            result = await coro
        except AppNotFound as err:
            raise ServiceValidationError(str(err)) from err
        except AdbTvError as err:
            raise HomeAssistantError(f"ADB TV: {err}") from err
        await self.coordinator.async_request_refresh()
        return result

    async def async_select_source(self, source: str) -> None:
        pkg = next((p for p, label in self._apps.items() if label == source), source)
        await self._call(self.coordinator.client.launch(pkg))

    async def async_launch_app(self, app: str) -> None:
        await self._call(self.coordinator.client.launch(app))

    async def async_send_key(self, key: str, times: int = 1) -> None:
        await self._call(self.coordinator.client.key(key, times))

    async def async_shell(self, command: str, timeout: int = 30) -> ServiceResponse:
        r = await self._call(self.coordinator.client.shell(command, timeout))
        return {"output": r.get("output", ""), "exit_code": r.get("exit")}

    async def async_turn_off(self) -> None:
        await self._call(self.coordinator.client.key("SLEEP"))

    async def async_volume_up(self) -> None:
        await self._call(self.coordinator.client.key("VOLUME_UP"))

    async def async_volume_down(self) -> None:
        await self._call(self.coordinator.client.key("VOLUME_DOWN"))

    async def async_mute_volume(self, mute: bool) -> None:
        await self._call(self.coordinator.client.key("VOLUME_MUTE"))

    async def async_media_play(self) -> None:
        await self._call(self.coordinator.client.key("MEDIA_PLAY"))

    async def async_media_pause(self) -> None:
        await self._call(self.coordinator.client.key("MEDIA_PAUSE"))

    async def async_media_next_track(self) -> None:
        await self._call(self.coordinator.client.key("MEDIA_NEXT"))

    async def async_media_previous_track(self) -> None:
        await self._call(self.coordinator.client.key("MEDIA_PREVIOUS"))
