"""Base común de las entidades: un dispositivo por TV."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN
from .coordinator import AdbTvCoordinator


class AdbTvEntity(CoordinatorEntity[AdbTvCoordinator]):
    _attr_has_entity_name = True

    def __init__(self, coordinator: AdbTvCoordinator) -> None:
        super().__init__(coordinator)
        entry = coordinator.config_entry
        info = coordinator.data.info
        self._device_id = entry.unique_id or entry.entry_id
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, self._device_id)},
            name=entry.title,
            manufacturer=info.get("manufacturer"),
            model=info.get("model"),
            sw_version=info.get("appVersion"),
            configuration_url=coordinator.client.base_url,
        )
