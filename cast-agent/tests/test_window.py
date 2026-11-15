from datetime import datetime, time
from zoneinfo import ZoneInfo

from cast_agent.window import in_window, next_boundary, parse_clock

CT = ZoneInfo("America/Chicago")


def at(hour: int, minute: int = 0, day: int = 16) -> datetime:
    return datetime(2026, 11, day, hour, minute, tzinfo=CT)


def test_no_bounds_means_always_on():
    assert in_window(at(3), None, None)
    assert next_boundary(at(3), None, None) is None


def test_a_daytime_window():
    start, end = time(8, 0), time(21, 30)

    assert not in_window(at(7, 59), start, end)
    assert in_window(at(8, 0), start, end)
    assert in_window(at(21, 29), start, end)
    assert not in_window(at(21, 30), start, end)


def test_an_overnight_window():
    start, end = time(18, 0), time(6, 0)

    assert in_window(at(23), start, end)
    assert in_window(at(5, 59), start, end)
    assert not in_window(at(6, 0), start, end)
    assert not in_window(at(12), start, end)


def test_next_boundary_is_the_soonest_change():
    start, end = time(8, 0), time(21, 30)

    assert next_boundary(at(7), start, end) == at(8)
    assert next_boundary(at(9), start, end) == at(21, 30)
    assert next_boundary(at(22), start, end) == at(8, day=17)


def test_parse_clock():
    assert parse_clock("08:05") == time(8, 5)
    assert parse_clock("") is None
    assert parse_clock(None) is None
    assert parse_clock("8am") is None
