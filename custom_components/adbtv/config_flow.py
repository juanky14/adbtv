"""Configuración desde la interfaz: descubrimiento automático o IP manual, más el PIN."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import callback
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.aiohttp_client import async_get_clientsession

try:  # HA 2025.1+
    from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo
except ImportError:  # versiones anteriores
    from homeassistant.components.zeroconf import ZeroconfServiceInfo

from .api import AdbTvClient, CannotConnect, InvalidAuth
from .const import CONF_FAVORITES, CONF_PIN, DEFAULT_PORT, DOMAIN, HIDDEN_SOURCES


class AdbTvConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    def __init__(self) -> None:
        self._host: str | None = None
        self._port: int = DEFAULT_PORT
        self._name: str | None = None

    async def _validate(self, host: str, port: int, pin: str) -> dict[str, Any]:
        client = AdbTvClient(async_get_clientsession(self.hass), host, port, pin)
        return await client.info()

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            try:
                info = await self._validate(user_input[CONF_HOST], user_input[CONF_PORT], user_input[CONF_PIN])
            except InvalidAuth:
                errors["base"] = "invalid_auth"
            except CannotConnect:
                errors["base"] = "cannot_connect"
            else:
                await self.async_set_unique_id(info["id"])
                self._abort_if_unique_id_configured(updates={CONF_HOST: user_input[CONF_HOST]})
                return self.async_create_entry(title=info.get("name") or user_input[CONF_HOST], data=user_input)

        schema = vol.Schema(
            {
                vol.Required(CONF_HOST, default=(user_input or {}).get(CONF_HOST, "")): str,
                vol.Required(CONF_PORT, default=DEFAULT_PORT): int,
                vol.Required(CONF_PIN): str,
            }
        )
        return self.async_show_form(step_id="user", data_schema=schema, errors=errors)

    async def async_step_zeroconf(self, discovery_info: ZeroconfServiceInfo) -> ConfigFlowResult:
        """La APK se anuncia como _adbtv._tcp con su id en los registros TXT."""
        device_id = discovery_info.properties.get("id")
        if not device_id:
            return self.async_abort(reason="not_supported")
        await self.async_set_unique_id(device_id)
        # Si el TV cambió de IP, se actualiza sin preguntar
        self._abort_if_unique_id_configured(updates={CONF_HOST: discovery_info.host, CONF_PORT: discovery_info.port})
        self._host = discovery_info.host
        self._port = discovery_info.port or DEFAULT_PORT
        self._name = discovery_info.name.split("._adbtv")[0]
        self.context["title_placeholders"] = {"name": self._name}
        return await self.async_step_zeroconf_confirm()

    async def async_step_zeroconf_confirm(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            try:
                info = await self._validate(self._host, self._port, user_input[CONF_PIN])
            except InvalidAuth:
                errors["base"] = "invalid_auth"
            except CannotConnect:
                errors["base"] = "cannot_connect"
            else:
                return self.async_create_entry(
                    title=info.get("name") or self._name,
                    data={CONF_HOST: self._host, CONF_PORT: self._port, CONF_PIN: user_input[CONF_PIN]},
                )
        return self.async_show_form(
            step_id="zeroconf_confirm",
            data_schema=vol.Schema({vol.Required(CONF_PIN): str}),
            description_placeholders={"name": self._name, "host": self._host},
            errors=errors,
        )

    async def async_step_reauth(self, entry_data: Mapping[str, Any]) -> ConfigFlowResult:
        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Se pide el PIN nuevo cuando se pulsa "Nuevo PIN" en el TV."""
        entry = self._get_reauth_entry()
        errors: dict[str, str] = {}
        if user_input is not None:
            try:
                await self._validate(entry.data[CONF_HOST], entry.data[CONF_PORT], user_input[CONF_PIN])
            except InvalidAuth:
                errors["base"] = "invalid_auth"
            except CannotConnect:
                errors["base"] = "cannot_connect"
            else:
                return self.async_update_reload_and_abort(entry, data_updates={CONF_PIN: user_input[CONF_PIN]})
        return self.async_show_form(
            step_id="reauth_confirm",
            data_schema=vol.Schema({vol.Required(CONF_PIN): str}),
            description_placeholders={"name": entry.title},
            errors=errors,
        )

    @staticmethod
    @callback
    def async_get_options_flow(config_entry) -> OptionsFlow:
        return AdbTvOptionsFlow()


class AdbTvOptionsFlow(OptionsFlow):
    """Elegir apps favoritas: cada una se crea como interruptor (útil para Alexa/Google)."""

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(data=user_input)

        coordinator = self.config_entry.runtime_data
        apps = {a["pkg"]: a["label"] for a in coordinator.data.apps if a["pkg"] not in HIDDEN_SOURCES}
        current = [p for p in self.config_entry.options.get(CONF_FAVORITES, []) if p in apps]
        if not apps:
            return self.async_abort(reason="tv_offline")
        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema(
                {vol.Optional(CONF_FAVORITES, default=current): cv.multi_select(apps)}
            ),
        )
