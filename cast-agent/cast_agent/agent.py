"""Reconciles the TVs the service lists with the connections and controllers kept for them.

It also answers the controllers' two questions: "who is at this address?" (so a TV is never driven
on the strength of an address that now belongs to something else) and "this TV is gone, where is it?"
(a sweep of the TV subnets, matched by the TV's own id; the new address is reported to the service).
"""

from __future__ import annotations

import logging
import threading
from concurrent.futures import Future, ThreadPoolExecutor
from datetime import datetime
from typing import Callable, Iterable
from zoneinfo import ZoneInfo

from .controller import TargetController
from .device import DeviceFactory
from .identity import Identity, fetch_identity, find_devices, normalize_id
from .models import Target
from .reactor import Handle, Reactor
from .service import ServiceApi

log = logging.getLogger(__name__)

CHICAGO = ZoneInfo("America/Chicago")
SYNC_RETRY = 30.0
STREAM_BACKOFF_START = 1.0
STREAM_BACKOFF_MAX = 60.0
SWEEP_BACKOFF_START = 60.0  # while a TV stays missing: 1 min, 2, 4 ... capped, so it is never a steady poll
SWEEP_BACKOFF_MAX = 6 * 3600.0

Finder = Callable[[Iterable[str]], dict[str, tuple[str, Identity]]]


class Agent:
    def __init__(
        self,
        service: ServiceApi,
        reactor: Reactor,
        device_factory: DeviceFactory,
        app_id: str,
        now: Callable[[], datetime] = lambda: datetime.now(CHICAGO),
        wall_ms: Callable[[], int] | None = None,
        sweep_ranges: Iterable[str] = (),
        lookup: Callable[[str], Identity | None] = fetch_identity,
        finder: Finder = find_devices,
        pool: ThreadPoolExecutor | None = None,
    ) -> None:
        self._service = service
        self._reactor = reactor
        self._device_factory = device_factory
        self._app_id = app_id
        self._now = now
        self._wall_ms = wall_ms or (lambda: int(self._now().timestamp() * 1000))
        self._ranges = [r for r in sweep_ranges if r.strip()]
        self._lookup = lookup
        self._finder = finder
        self._pool = pool or ThreadPoolExecutor(max_workers=2, thread_name_prefix="agent")
        self._controllers: dict[int, TargetController] = {}
        self._missing: dict[int, TargetController] = {}
        self._sweeping = False
        self._sweep_timer: Handle | None = None
        self._sweep_delay = SWEEP_BACKOFF_START
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
            target,
            self._device_factory,
            self._service,
            self._reactor,
            self._app_id,
            self._now,
            self._wall_ms,
            hooks=self,
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
        self._pool.shutdown(wait=False, cancel_futures=True)

    # ---- the controllers' hooks (called on the reactor thread) ----

    def verify(self, controller: TargetController) -> None:
        """Asks the device at the TV's address who it is (off the reactor thread: it is a network call)."""
        future = self._pool.submit(self._lookup, controller.target.host)
        future.add_done_callback(lambda f: self._reactor.post(lambda: controller.on_identity(_value(f))))

    def lost(self, controller: TargetController) -> None:
        if not self._ranges or not normalize_id(controller.target.device_id):
            log.info("%s is unreachable; no sweep ranges or no recorded id, so it cannot be searched for", controller.target.name)
            return
        self._missing[controller.target.id] = controller
        self._request_sweep(0.0)

    # ---- finding a TV that moved ----

    def _request_sweep(self, delay: float) -> None:
        if self._sweeping or self._sweep_timer is not None:
            return
        self._sweep_timer = self._reactor.call_later(delay, self._start_sweep)

    def _start_sweep(self) -> None:
        self._sweep_timer = None
        self._drop_recovered()
        if not self._missing:
            self._sweep_delay = SWEEP_BACKOFF_START
            return
        log.info("Searching for %d missing TV(s)", len(self._missing))
        self._sweeping = True
        future = self._pool.submit(self._finder, self._ranges)
        future.add_done_callback(lambda f: self._reactor.post(lambda: self._sweep_done(_value(f))))

    def _sweep_done(self, found: dict[str, tuple[str, Identity]] | None) -> None:
        self._sweeping = False
        self._drop_recovered()
        moved = False
        for target_id, controller in list(self._missing.items()):
            hit = (found or {}).get(normalize_id(controller.target.device_id) or "")
            if hit is None:
                continue
            host = hit[0]
            try:
                self._service.move(target_id, host)
            except Exception:
                log.warning("Found %s at %s but could not record it", controller.target.name, host, exc_info=True)
                continue
            log.info("%s moved to %s", controller.target.name, host)
            del self._missing[target_id]
            moved = True
        if moved:
            self._sweep_delay = SWEEP_BACKOFF_START
            self.reconcile()
        if self._missing:
            delay = self._sweep_delay
            self._sweep_delay = min(delay * 2, SWEEP_BACKOFF_MAX)
            log.info("%d TV(s) still missing; searching again in %.0f min", len(self._missing), delay / 60)
            self._request_sweep(delay)

    def _drop_recovered(self) -> None:
        """Forget TVs that reconnected on their own or are no longer managed."""
        for target_id, controller in list(self._missing.items()):
            if controller.connected or self._controllers.get(target_id) is not controller:
                del self._missing[target_id]

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


def _value(future: Future):
    """A finished future's result, or None if it failed or was cancelled."""
    try:
        return future.result()
    except Exception:
        log.warning("Background lookup failed", exc_info=True)
        return None
