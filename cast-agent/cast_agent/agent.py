"""Reconciles the TVs the service lists with the connections and controllers kept for them."""

from __future__ import annotations

import logging
import threading
from datetime import datetime
from typing import Callable
from zoneinfo import ZoneInfo

from .controller import TargetController
from .device import DeviceFactory
from .models import Target
from .reactor import Reactor
from .service import ServiceApi

log = logging.getLogger(__name__)

CHICAGO = ZoneInfo("America/Chicago")
SYNC_RETRY = 30.0
STREAM_BACKOFF_START = 1.0
STREAM_BACKOFF_MAX = 60.0


class Agent:
    def __init__(
        self,
        service: ServiceApi,
        reactor: Reactor,
        device_factory: DeviceFactory,
        app_id: str,
        now: Callable[[], datetime] = lambda: datetime.now(CHICAGO),
        wall_ms: Callable[[], int] | None = None,
    ) -> None:
        self._service = service
        self._reactor = reactor
        self._device_factory = device_factory
        self._app_id = app_id
        self._now = now
        self._wall_ms = wall_ms or (lambda: int(self._now().timestamp() * 1000))
        self._controllers: dict[int, TargetController] = {}
        self._stop = threading.Event()

    # ---- reactor thread ----

    def reconcile(self) -> None:
        """Makes the running controllers match the service's list of enabled TVs."""
        try:
            wanted = {t.id: t for t in self._service.targets()}
        except Exception:
            log.warning("Could not load the TV list; will try again", exc_info=True)
            self._reactor.call_later(SYNC_RETRY, self.reconcile)
            return
        for target_id in list(self._controllers):
            if target_id not in wanted:
                log.info("TV %s was removed or turned off", target_id)
                self._controllers.pop(target_id).stop(quit_ours=True)
        for target in wanted.values():
            self._apply(target)
        for controller in self._controllers.values():
            controller.refresh_token()  # a revoke shows up as a changed list; hand out fresh keys

    def _apply(self, target: Target) -> None:
        existing = self._controllers.get(target.id)
        if existing is not None and existing.target.address == target.address:
            existing.update(target)
            return
        if existing is not None:
            existing.stop(quit_ours=True)
        controller = TargetController(
            target, self._device_factory, self._service, self._reactor, self._app_id, self._now, self._wall_ms
        )
        self._controllers[target.id] = controller
        controller.start()

    def handle(self, event: dict) -> None:
        kind = event.get("type")
        if kind == "reload":
            committee = event.get("committeeId")
            for controller in self._controllers.values():
                if committee is None or controller.target.committee_id == committee:
                    controller.reload()
        elif kind == "targets-changed":
            self.reconcile()

    def shutdown(self) -> None:
        self._stop.set()
        for controller in self._controllers.values():
            controller.stop(quit_ours=False)  # leave the TVs as they are; the next start picks them up
        self._controllers.clear()

    # ---- event stream thread ----

    def start(self) -> None:
        self._reactor.post(self.reconcile)
        threading.Thread(target=self._stream, name="events", daemon=True).start()

    def _stream(self) -> None:
        backoff = STREAM_BACKOFF_START
        while not self._stop.is_set():
            try:
                connected = False
                for event in self._service.events():
                    if not connected:
                        connected = True
                        backoff = STREAM_BACKOFF_START
                        self._reactor.post(self.reconcile)  # catch up on edits made while disconnected
                    self._reactor.post(lambda e=event: self.handle(e))
            except Exception:
                log.warning("Event stream dropped; reconnecting in %.0fs", backoff, exc_info=True)
            if self._stop.wait(backoff):
                return
            backoff = min(backoff * 2, STREAM_BACKOFF_MAX)
