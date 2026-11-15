"""What one TV should be doing, and the steps to get it there.

Rules, in order:
  * Outside its display hours the TV is left to itself (our app is stopped if it is running).
  * Inside them, an idle TV gets our receiver launched.
  * If someone else is casting, the TV is left alone, unless "switch back after N minutes" is set:
    then it is taken back once that long has passed with the same other app still on screen.
  * Once our receiver is running it is handed a device token over the Cast message channel. The
    receiver ignores a token it already has, so a few repeats (it may not be listening yet) are harmless.

Nothing here polls: it reacts to status changes, and uses timers only for deadlines it can name
(a launch retry, the end of a reclaim wait, a display-hours boundary, a token about to expire).
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Callable

from . import window
from .device import IDLE_APP_IDS, Device, DeviceFactory
from .models import Target
from .reactor import Handle, Reactor
from .service import ServiceApi, Token

log = logging.getLogger(__name__)

TOKEN_SEND_DELAYS = (1.0, 4.0, 12.0)  # seconds after our receiver appears
LAUNCH_GRACE = 30.0  # don't launch again while a launch may still be starting
LAUNCH_RETRY = 60.0  # if the TV still isn't showing us after a launch, evaluate again
TOKEN_RETRY = 30.0
TOKEN_REFRESH_LEAD = 3600.0  # send a new token this long before the old one expires


class TargetController:
    def __init__(
        self,
        target: Target,
        device_factory: DeviceFactory,
        service: ServiceApi,
        reactor: Reactor,
        app_id: str,
        now: Callable[[], datetime],
        wall_ms: Callable[[], int],
    ) -> None:
        self.target = target
        self._service = service
        self._reactor = reactor
        self._app_id = app_id
        self._now = now
        self._wall_ms = wall_ms

        self.connected = False
        self._status_seen = False  # a connection is only acted on once the TV has said what it is showing
        self.app_id: str | None = None
        self.display_name: str | None = None
        self.session_id: str | None = None
        self._stopped = False
        self._launch_until = float("-inf")
        self._token: Token | None = None
        self._reported: tuple[bool, str | None] | None = None
        self._reclaim: Handle | None = None
        self._window_check: Handle | None = None
        self._launch_retry: Handle | None = None
        self._token_sends: list[Handle] = []
        self._token_refresh: Handle | None = None

        self._device: Device = device_factory(target.host, target.port, target.name, self)

    # ---- lifecycle ----

    def start(self) -> None:
        log.info("Managing %s (%s:%s)", self.target.name, self.target.host, self.target.port)
        self._device.start()

    def stop(self, quit_ours: bool = False) -> None:
        self._stopped = True
        self._cancel_all()
        if quit_ours and self.connected and self._is_ours():
            self._quit()
        self._device.stop()

    def update(self, target: Target) -> None:
        """New display hours or reclaim time for the same TV."""
        self.target = target
        self._evaluate()

    # ---- events from the device (reactor thread) ----

    def on_connection(self, connected: bool) -> None:
        self.connected = connected
        self._status_seen = False
        self.app_id = self.display_name = self.session_id = None
        if not connected:
            self._cancel_timers_for_session()
            self._cancel(self._reclaim)
            self._reclaim = None
        self._report()
        self._evaluate()

    def on_status(self, app_id: str | None, display_name: str | None, session_id: str | None) -> None:
        previous_app, previous_session = self.app_id, self.session_id
        self._status_seen = True
        self.app_id, self.display_name, self.session_id = app_id, display_name, session_id
        if app_id != previous_app and app_id != self._app_id:
            self._cancel(self._reclaim)  # a different app resets the "switch back" wait
            self._reclaim = None
        if self._is_ours() and (previous_app != app_id or previous_session != session_id):
            self._on_ours_started()
        if not self._is_ours():
            self._cancel_timers_for_session()
        self._report()
        self._evaluate()

    # ---- commands from the agent ----

    def reload(self) -> None:
        if self.connected and self._is_ours():
            self._send({"type": "reload"})

    def refresh_token(self) -> None:
        """Hands a running receiver a fresh token (after a revoke, or just before expiry)."""
        if self.connected and self._is_ours():
            self._token = None
            self._send_token()

    # ---- decisions ----

    def _evaluate(self) -> None:
        if self._stopped or not self.connected or not self._status_seen:
            return
        now = self._now()
        start, end = window.parse_clock(self.target.display_start), window.parse_clock(self.target.display_end)
        self._schedule_window_check(now, start, end)

        if not window.in_window(now, start, end):
            self._cancel(self._reclaim)
            self._reclaim = None
            if self._is_ours():
                self._quit()
            return
        if self._is_ours():
            self._cancel(self._reclaim)
            self._reclaim = None
        elif self.app_id in IDLE_APP_IDS:
            self._cancel(self._reclaim)
            self._reclaim = None
            self._launch()
        else:
            self._arm_reclaim()

    def _arm_reclaim(self) -> None:
        minutes = self.target.reclaim_after_minutes
        if not minutes or self._reclaim is not None:
            return
        log.info("%s is showing %s; taking it back in %s min", self.target.name, self.display_name, minutes)
        self._reclaim = self._reactor.call_later(minutes * 60, self._reclaim_due)

    def _reclaim_due(self) -> None:
        self._reclaim = None
        if self._stopped or not self.connected or self._is_ours():
            return
        now = self._now()
        if window.in_window(now, window.parse_clock(self.target.display_start), window.parse_clock(self.target.display_end)):
            log.info("Taking %s back", self.target.name)
            self._launch(force=True)

    def _launch(self, force: bool = False) -> None:
        if not force and self._reactor.now() < self._launch_until:
            return
        self._launch_until = self._reactor.now() + LAUNCH_GRACE
        try:
            self._device.launch(self._app_id)
            log.info("Launched the dashboard on %s", self.target.name)
        except Exception:
            log.exception("Could not launch on %s", self.target.name)
        self._cancel(self._launch_retry)
        self._launch_retry = self._reactor.call_later(LAUNCH_RETRY, self._evaluate)

    def _quit(self) -> None:
        try:
            self._device.quit_app()
            log.info("Stopped the dashboard on %s (outside its hours)", self.target.name)
        except Exception:
            log.exception("Could not stop the dashboard on %s", self.target.name)

    def _schedule_window_check(self, now: datetime, start, end) -> None:
        self._cancel(self._window_check)
        self._window_check = None
        boundary = window.next_boundary(now, start, end)
        if boundary is not None:
            delay = max(1.0, (boundary - now).total_seconds() + 1.0)
            self._window_check = self._reactor.call_later(delay, self._evaluate)

    # ---- token hand-off ----

    def _on_ours_started(self) -> None:
        self._cancel(self._launch_retry)
        self._cancel_timers_for_session()
        self._token_sends = [self._reactor.call_later(d, self._send_token) for d in TOKEN_SEND_DELAYS]

    def _send_token(self) -> None:
        if self._stopped or not self.connected or not self._is_ours():
            return
        token = self._current_token()
        if token is None:
            self._token_sends.append(self._reactor.call_later(TOKEN_RETRY, self._send_token))
            return
        self._send({"type": "token", "token": token.value})
        lead = (token.expires_at_ms - self._wall_ms()) / 1000.0 - TOKEN_REFRESH_LEAD
        self._cancel(self._token_refresh)
        self._token_refresh = self._reactor.call_later(max(60.0, lead), self.refresh_token)

    def _current_token(self) -> Token | None:
        if self._token is not None and self._token.expires_at_ms - self._wall_ms() > TOKEN_REFRESH_LEAD * 1000:
            return self._token
        try:
            self._token = self._service.token(self.target.id)
        except Exception:
            log.exception("Could not get a token for %s", self.target.name)
            self._token = None
        return self._token

    def _send(self, message: dict) -> None:
        try:
            self._device.send(message)
        except Exception:
            log.exception("Could not message %s", self.target.name)

    # ---- reporting ----

    def _report(self) -> None:
        shown = "Dashboard" if self._is_ours() else ("idle" if self.app_id in IDLE_APP_IDS else self.display_name or self.app_id)
        state = (self.connected, shown if self.connected else None)
        if state == self._reported:
            return
        self._reported = state
        try:
            self._service.report(self.target.id, shown if self.connected else None, self.connected)
        except Exception:
            log.warning("Could not report the status of %s", self.target.name, exc_info=True)

    # ---- small helpers ----

    def _is_ours(self) -> bool:
        return self.app_id == self._app_id

    @staticmethod
    def _cancel(handle: Handle | None) -> None:
        if handle is not None:
            handle.cancel()

    def _cancel_timers_for_session(self) -> None:
        for handle in self._token_sends:
            handle.cancel()
        self._token_sends = []
        self._cancel(self._token_refresh)
        self._token_refresh = None

    def _cancel_all(self) -> None:
        self._cancel_timers_for_session()
        for handle in (self._reclaim, self._window_check, self._launch_retry):
            self._cancel(handle)
        self._reclaim = self._window_check = self._launch_retry = None
