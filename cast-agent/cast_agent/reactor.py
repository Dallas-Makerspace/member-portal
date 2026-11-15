"""A single-threaded event loop with timers.

Everything that decides what a TV should do runs here, one callback at a time, so the TV logic
needs no locks. Other threads (pychromecast's, the event stream's) only `post` work to it.
"""

from __future__ import annotations

import heapq
import itertools
import logging
import threading
import time
from typing import Callable

log = logging.getLogger(__name__)


class Handle:
    """A scheduled callback that can be cancelled."""

    def __init__(self) -> None:
        self.cancelled = False

    def cancel(self) -> None:
        self.cancelled = True


class Reactor:
    def __init__(self, clock: Callable[[], float] = time.monotonic) -> None:
        self._clock = clock
        self._heap: list[tuple[float, int, Handle, Callable[[], None]]] = []
        self._counter = itertools.count()
        self._cond = threading.Condition()
        self._running = False

    def now(self) -> float:
        return self._clock()

    def call_later(self, delay: float, fn: Callable[[], None]) -> Handle:
        handle = Handle()
        with self._cond:
            heapq.heappush(self._heap, (self._clock() + max(0.0, delay), next(self._counter), handle, fn))
            self._cond.notify()
        return handle

    def post(self, fn: Callable[[], None]) -> Handle:
        return self.call_later(0.0, fn)

    def run_due(self) -> float | None:
        """Runs everything that is due; returns seconds until the next timer, or None if idle."""
        while True:
            with self._cond:
                while self._heap and self._heap[0][2].cancelled:
                    heapq.heappop(self._heap)
                if not self._heap:
                    return None
                when, _, handle, fn = self._heap[0]
                wait = when - self._clock()
                if wait > 0:
                    return wait
                heapq.heappop(self._heap)
            try:
                fn()
            except Exception:  # one bad callback must not stop the loop
                log.exception("Callback failed")

    def run_forever(self) -> None:
        self._running = True
        while self._running:
            wait = self.run_due()
            with self._cond:
                if not self._running:
                    break
                self._cond.wait(timeout=wait)

    def stop(self) -> None:
        with self._cond:
            self._running = False
            self._cond.notify_all()
