"""Display hours: when a TV should be showing the dashboard."""

from __future__ import annotations

from datetime import datetime, time, timedelta


def parse_clock(value: str | None) -> time | None:
    """`HH:mm` (as the editor stores it) to a time; blank or malformed means no bound."""
    if not value:
        return None
    try:
        hours, minutes = value.strip().split(":")[:2]
        return time(int(hours), int(minutes))
    except (ValueError, TypeError):
        return None


def in_window(now: datetime, start: time | None, end: time | None) -> bool:
    """Whether [now] is inside the hours; a missing bound means always on. Handles overnight."""
    if start is None or end is None:
        return True
    current = now.time().replace(second=0, microsecond=0)
    if start < end:
        return start <= current < end
    return current >= start or current < end


def next_boundary(now: datetime, start: time | None, end: time | None) -> datetime | None:
    """The next moment [in_window] can change, or None when the TV is always on."""
    if start is None or end is None:
        return None
    candidates = []
    for day in (now.date(), now.date() + timedelta(days=1)):
        for bound in (start, end):
            candidates.append(datetime.combine(day, bound, tzinfo=now.tzinfo))
    return min(c for c in candidates if c > now)
