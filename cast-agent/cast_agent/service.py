"""The portal service's cast-agent API."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Iterable, Iterator, Protocol

import requests

from .models import Target

log = logging.getLogger(__name__)

CONNECT_TIMEOUT = 5
READ_TIMEOUT = 15
# The service sends a heartbeat every 15 seconds; a stream silent for this long is dead and is replaced.
STREAM_IDLE_TIMEOUT = 60


@dataclass(frozen=True)
class Token:
    value: str
    expires_at_ms: int


class ServiceApi(Protocol):
    def targets(self) -> list[Target]: ...

    def token(self, target_id: int) -> Token: ...

    def report(self, target_id: int, app: str | None, connected: bool) -> None: ...

    def identify(self, target_id: int, device_id: str, name: str | None) -> None: ...

    def move(self, target_id: int, host: str) -> None: ...

    def discovered(self, devices: list[dict]) -> None: ...

    def events(self) -> Iterator[dict]: ...


def parse_sse(lines: Iterable[str]) -> Iterator[dict]:
    """Yields the JSON of each `data:` event in a server-sent-event stream; ignores comments."""
    data: list[str] = []
    for raw in lines:
        line = raw.rstrip("\r\n")
        if line == "":
            if data:
                try:
                    yield json.loads("\n".join(data))
                except ValueError:
                    log.warning("Ignoring an event that isn't JSON")
                data = []
        elif line.startswith("data:"):
            data.append(line[5:].lstrip())
        # ": connected" comments and other fields are ignored


class HttpService:
    def __init__(self, base_url: str, api_key: str, client: str) -> None:
        self._base = base_url.rstrip("/")
        self._session = requests.Session()
        self._session.headers.update({"X-Api-Key": api_key, "X-Api-Client": client})

    def _call(self, method: str, path: str, **kwargs) -> dict:
        response = self._session.request(
            method, f"{self._base}{path}", timeout=(CONNECT_TIMEOUT, READ_TIMEOUT), **kwargs
        )
        response.raise_for_status()
        return response.json()

    def targets(self) -> list[Target]:
        return [Target.from_json(item) for item in self._call("GET", "/cast-agent/targets").get("data") or []]

    def token(self, target_id: int) -> Token:
        data = self._call("POST", f"/cast-agent/targets/{target_id}/token")["data"]
        return Token(data["token"], int(data["expiresAtMs"]))

    def report(self, target_id: int, app: str | None, connected: bool) -> None:
        self._call("POST", f"/cast-agent/targets/{target_id}/status", json={"app": app, "connected": connected})

    def identify(self, target_id: int, device_id: str, name: str | None) -> None:
        self._call("POST", f"/cast-agent/targets/{target_id}/identity", json={"deviceId": device_id, "deviceName": name})

    def move(self, target_id: int, host: str) -> None:
        self._call("POST", f"/cast-agent/targets/{target_id}/host", json={"host": host})

    def discovered(self, devices: list[dict]) -> None:
        """Tells the service which Cast devices a network sweep found (set up for a TV or not)."""
        self._call("POST", "/cast-agent/discovered", json={"devices": devices})

    def events(self) -> Iterator[dict]:
        """Streams events until the connection drops (then the generator ends or raises)."""
        with self._session.get(
            f"{self._base}/cast-agent/events",
            stream=True,
            headers={"Accept": "text/event-stream"},
            timeout=(CONNECT_TIMEOUT, STREAM_IDLE_TIMEOUT),
        ) as response:
            response.raise_for_status()
            yield from parse_sse(response.iter_lines(decode_unicode=True, chunk_size=1))
