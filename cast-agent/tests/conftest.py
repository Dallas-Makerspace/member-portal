"""Fakes shared by the tests: a controllable clock, a TV, and the portal service."""

from __future__ import annotations

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest

from cast_agent.models import Target
from cast_agent.reactor import Reactor
from cast_agent.service import Token

CHICAGO = ZoneInfo("America/Chicago")
APP = "TESTAPP1"
BACKDROP = "E8C28D3C"


class Clock:
    """Monotonic seconds for the reactor, plus a matching wall clock in Central time."""

    def __init__(self, start: datetime) -> None:
        self.t = 1000.0
        self._start = start
        self._t0 = self.t

    def monotonic(self) -> float:
        return self.t

    def now(self) -> datetime:
        return self._start + timedelta(seconds=self.t - self._t0)

    def wall_ms(self) -> int:
        return int(self.now().timestamp() * 1000)


class FakeDevice:
    def __init__(self, host: str, port: int, name: str, events) -> None:
        self.events = events
        self.started = False
        self.stopped = False
        self.launches: list[str] = []
        self.quits = 0
        self.sent: list[dict] = []

    def start(self) -> None:
        self.started = True

    def stop(self) -> None:
        self.stopped = True

    def launch(self, app_id: str) -> None:
        self.launches.append(app_id)

    def quit_app(self) -> None:
        self.quits += 1

    def send(self, message: dict) -> None:
        self.sent.append(message)


class FakeService:
    def __init__(self, clock: Clock) -> None:
        self._clock = clock
        self.token_calls = 0
        self.reports: list[tuple[int, str | None, bool]] = []
        self.target_list: list[Target] = []
        self.fail_tokens = False

    def targets(self) -> list[Target]:
        return list(self.target_list)

    def token(self, target_id: int) -> Token:
        if self.fail_tokens:
            raise RuntimeError("service down")
        self.token_calls += 1
        return Token(f"token-{self.token_calls}", self._clock.wall_ms() + 24 * 3600 * 1000)

    def report(self, target_id: int, app: str | None, connected: bool) -> None:
        self.reports.append((target_id, app, connected))

    def events(self):
        return iter(())


def make_target(**overrides) -> Target:
    values = dict(
        id=1,
        committee_id=9,
        name="Shop TV",
        host="tv-1.example.internal",
        port=8009,
        display_start=None,
        display_end=None,
        reclaim_after_minutes=None,
    )
    values.update(overrides)
    return Target(**values)


@pytest.fixture
def clock() -> Clock:
    return Clock(datetime(2026, 11, 16, 10, 0, tzinfo=CHICAGO))  # a Monday, 10:00 Central


@pytest.fixture
def reactor(clock: Clock) -> Reactor:
    return Reactor(clock.monotonic)


@pytest.fixture
def service(clock: Clock) -> FakeService:
    return FakeService(clock)


def advance(clock: Clock, reactor: Reactor, seconds: float) -> None:
    """Moves time forward, running timers as they come due."""
    end = clock.t + seconds
    while True:
        wait = reactor.run_due()
        if wait is None or clock.t + wait > end:
            clock.t = end
            reactor.run_due()
            return
        clock.t += wait
