"""The TV side: a thin wrapper over pychromecast, kept apart so the logic can be tested without one."""

from __future__ import annotations

import logging
import uuid
from typing import Callable, Protocol

from .reactor import Reactor

log = logging.getLogger(__name__)

NAMESPACE = "urn:x-cast:org.dallasmakerspace.dashboard"

# What a Chromecast runs when nothing has been cast to it (the ambient/backdrop screen).
BACKDROP_APP_ID = "E8C28D3C"
IDLE_APP_IDS = frozenset({None, "", BACKDROP_APP_ID})


class Device(Protocol):
    def start(self) -> None: ...

    def stop(self) -> None: ...

    def launch(self, app_id: str) -> None: ...

    def quit_app(self) -> None: ...

    def send(self, message: dict) -> None: ...


class DeviceEvents(Protocol):
    """Called on the reactor thread."""

    def on_connection(self, connected: bool) -> None: ...

    def on_status(self, app_id: str | None, display_name: str | None, session_id: str | None) -> None: ...


DeviceFactory = Callable[[str, int, str, DeviceEvents], Device]


class ChromecastDevice:
    """One TV, reached by address (no mDNS). pychromecast reconnects on its own after a drop."""

    def __init__(self, host: str, port: int, name: str, events: DeviceEvents, reactor: Reactor) -> None:
        self._host, self._port, self._name = host, port, name
        self._events = events
        self._reactor = reactor
        self._cast = None
        self._channel = None

    def start(self) -> None:
        import pychromecast
        from pychromecast.controllers import BaseController

        class Channel(BaseController):
            def __init__(self) -> None:
                super().__init__(NAMESPACE)

            def receive_message(self, _message, _data) -> bool:
                return False

        # pychromecast wants an id; derive a stable one from the address.
        device_id = uuid.uuid5(uuid.NAMESPACE_DNS, f"{self._host}:{self._port}")
        self._cast = pychromecast.get_chromecast_from_host((self._host, self._port, device_id, None, self._name))
        self._channel = Channel()
        self._cast.register_handler(self._channel)
        self._cast.register_status_listener(self)
        self._cast.register_connection_listener(self)
        self._cast.register_launch_error_listener(self)
        self._cast.start()

    # -- pychromecast listener callbacks (run on its threads; hand off to the reactor) --

    def new_cast_status(self, status) -> None:
        app_id, name, session = status.app_id, status.display_name, status.session_id
        self._reactor.post(lambda: self._events.on_status(app_id, name, session))

    def new_connection_status(self, status) -> None:
        connected = status.status == "CONNECTED"
        self._reactor.post(lambda: self._events.on_connection(connected))

    def new_launch_error(self, launch_failure) -> None:
        # e.g. NOT_ALLOWED: the receiver is unpublished and this TV is not registered for testing.
        log.warning(
            "%s refused to launch app %s: %s", self._name, launch_failure.app_id, launch_failure.reason
        )

    # -- commands --

    def launch(self, app_id: str) -> None:
        self._cast.start_app(app_id)

    def quit_app(self) -> None:
        self._cast.quit_app()

    def send(self, message: dict) -> None:
        self._channel.send_message_nocheck(message)

    def stop(self) -> None:
        if self._cast is not None:
            try:
                self._cast.disconnect(timeout=5)
            except Exception:  # shutting down anyway
                log.debug("Disconnect from %s failed", self._host, exc_info=True)
