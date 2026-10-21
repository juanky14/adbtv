"""Cliente HTTP de la API de ADB TV Server (la APK instalada en el televisor)."""

from __future__ import annotations

import asyncio
from typing import Any

import aiohttp


class AdbTvError(Exception):
    """Error genérico de la API."""


class CannotConnect(AdbTvError):
    """El TV no responde (apagado, en reposo o fuera de la red)."""


class InvalidAuth(AdbTvError):
    """PIN incorrecto."""


class AppNotFound(AdbTvError):
    """No hay ninguna app instalada que coincida con el nombre pedido."""


class AdbTvClient:
    """Habla con http://<tv>:8080/api/… usando el PIN que muestra la app."""

    def __init__(self, session: aiohttp.ClientSession, host: str, port: int, pin: str) -> None:
        self._session = session
        self.host = host
        self.port = port
        self._pin = pin

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    async def _request(
        self, method: str, path: str, data: dict[str, Any] | None = None,
        params: dict[str, Any] | None = None, timeout: float = 15,
    ) -> Any:
        try:
            async with self._session.request(
                method,
                f"{self.base_url}{path}",
                data=data,
                params=params,
                headers={"X-Token": self._pin},
                timeout=aiohttp.ClientTimeout(total=timeout),
            ) as resp:
                if resp.status == 401:
                    raise InvalidAuth
                body = await resp.json(content_type=None)
                if resp.status == 404 and path == "/api/launch":
                    raise AppNotFound(body.get("error") if isinstance(body, dict) else str(body))
                if resp.status >= 400:
                    raise AdbTvError(body.get("error") if isinstance(body, dict) else str(body))
                return body
        except (aiohttp.ClientError, asyncio.TimeoutError, OSError) as err:
            raise CannotConnect(str(err)) from err

    async def info(self) -> dict[str, Any]:
        return await self._request("GET", "/api/info")

    async def launchable(self) -> list[dict[str, str]]:
        return await self._request("GET", "/api/launchable")

    async def launch(self, app: str) -> dict[str, Any]:
        """Abre una app por paquete ("com.netflix.ninja") o por nombre ("netflix")."""
        return await self._request("POST", "/api/launch", data={"app": app}, timeout=30)

    async def key(self, key: str, times: int = 1) -> dict[str, Any]:
        """Pulsa una tecla del mando: HOME, BACK, VOLUME_UP, MEDIA_PLAY_PAUSE…"""
        return await self._request("POST", "/api/key", data={"key": key, "times": str(times)})

    async def shell(self, command: str, timeout: int = 30) -> dict[str, Any]:
        return await self._request(
            "POST", "/api/exec", data={"cmd": command, "timeout": str(timeout)}, timeout=timeout + 10
        )
