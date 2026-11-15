from datetime import datetime

from cast_agent.controller import TOKEN_SEND_DELAYS, TargetController
from cast_agent.models import Target

from .conftest import APP, BACKDROP, CHICAGO, FakeDevice, advance, make_target


def build(clock, reactor, service, target: Target | None = None):
    devices: list[FakeDevice] = []

    def factory(host, port, name, events):
        device = FakeDevice(host, port, name, events)
        devices.append(device)
        return device

    controller = TargetController(
        target or make_target(), factory, service, reactor, APP, clock.now, clock.wall_ms
    )
    controller.start()
    return controller, devices[0]


def connect(controller, app_id=None, name=None, session=None):
    controller.on_connection(True)
    controller.on_status(app_id, name, session)


def test_an_idle_tv_gets_the_dashboard_once(clock, reactor, service):
    controller, device = build(clock, reactor, service)

    connect(controller, BACKDROP)
    controller.on_status(BACKDROP, "Backdrop", None)  # a repeat status must not launch again

    assert device.launches == [APP]


def test_the_token_is_sent_a_few_times_once_our_receiver_is_running(clock, reactor, service):
    controller, device = build(clock, reactor, service)
    connect(controller, BACKDROP)

    controller.on_status(APP, "Dashboard", "s1")
    advance(clock, reactor, 20)

    tokens = [m for m in device.sent if m["type"] == "token"]
    assert len(tokens) == len(TOKEN_SEND_DELAYS)
    assert len({m["token"] for m in tokens}) == 1  # the same token each time: the receiver ignores repeats
    assert service.token_calls == 1


def test_a_new_session_gets_the_token_again(clock, reactor, service):
    controller, device = build(clock, reactor, service)
    connect(controller, APP, "Dashboard", "s1")
    advance(clock, reactor, 20)
    before = len(device.sent)

    controller.on_status(APP, "Dashboard", "s2")  # the receiver restarted
    advance(clock, reactor, 20)

    assert len(device.sent) == before + len(TOKEN_SEND_DELAYS)


def test_a_tv_showing_something_else_is_left_alone_by_default(clock, reactor, service):
    controller, device = build(clock, reactor, service)

    connect(controller, "YOUTUBE", "YouTube", "y1")
    advance(clock, reactor, 24 * 3600)

    assert device.launches == []


def test_the_tv_is_taken_back_after_the_reclaim_time(clock, reactor, service):
    controller, device = build(clock, reactor, service, make_target(reclaim_after_minutes=30))
    connect(controller, "YOUTUBE", "YouTube", "y1")

    advance(clock, reactor, 29 * 60)
    assert device.launches == []
    advance(clock, reactor, 2 * 60)

    assert device.launches == [APP]


def test_another_app_starting_resets_the_reclaim_wait(clock, reactor, service):
    controller, device = build(clock, reactor, service, make_target(reclaim_after_minutes=30))
    connect(controller, "YOUTUBE", "YouTube", "y1")
    advance(clock, reactor, 20 * 60)

    controller.on_status("NETFLIX", "Netflix", "n1")
    advance(clock, reactor, 20 * 60)
    assert device.launches == []
    advance(clock, reactor, 11 * 60)

    assert device.launches == [APP]


def test_the_reclaim_is_dropped_when_the_other_app_ends(clock, reactor, service):
    controller, device = build(clock, reactor, service, make_target(reclaim_after_minutes=30))
    connect(controller, "YOUTUBE", "YouTube", "y1")
    advance(clock, reactor, 10 * 60)

    controller.on_status(BACKDROP, "Backdrop", None)  # idle again: launch right away
    assert device.launches == [APP]
    controller.on_status(APP, "Dashboard", "s1")
    advance(clock, reactor, 60 * 60)

    assert device.launches == [APP]


def test_outside_display_hours_the_dashboard_is_stopped_and_returns_at_the_start(clock, reactor, service):
    # It is 10:00; hours are 12:00-14:00, so the dashboard should not be running yet.
    controller, device = build(clock, reactor, service, make_target(display_start="12:00", display_end="14:00"))
    connect(controller, BACKDROP)
    assert device.launches == []

    advance(clock, reactor, 2 * 3600 + 5)  # now 12:00
    assert device.launches == [APP]
    controller.on_status(APP, "Dashboard", "s1")

    advance(clock, reactor, 2 * 3600)  # now 14:00: hours are over
    assert device.quits == 1


def test_a_stopped_dashboard_is_not_relaunched_outside_hours(clock, reactor, service):
    controller, device = build(clock, reactor, service, make_target(display_start="12:00", display_end="14:00"))
    connect(controller, APP, "Dashboard", "s1")

    assert device.quits == 1
    controller.on_status(BACKDROP, "Backdrop", None)
    advance(clock, reactor, 60)

    assert device.launches == []


def test_nothing_happens_while_the_tv_is_disconnected(clock, reactor, service):
    controller, device = build(clock, reactor, service)

    controller.on_status(BACKDROP, "Backdrop", None)  # a stale status before the connection is up
    advance(clock, reactor, 60)

    assert device.launches == []


def test_a_reload_is_forwarded_only_when_our_receiver_is_running(clock, reactor, service):
    controller, device = build(clock, reactor, service)
    connect(controller, "YOUTUBE", "YouTube", "y1")
    controller.reload()
    assert device.sent == []

    controller.on_status(APP, "Dashboard", "s1")
    advance(clock, reactor, 20)
    device.sent.clear()
    controller.reload()

    assert device.sent == [{"type": "reload"}]


def test_a_token_is_refreshed_before_it_expires(clock, reactor, service):
    controller, device = build(clock, reactor, service)
    connect(controller, APP, "Dashboard", "s1")
    advance(clock, reactor, 20)
    first = {m["token"] for m in device.sent}

    advance(clock, reactor, 23 * 3600 + 60)  # past the refresh lead of one hour before expiry

    assert {m["token"] for m in device.sent} - first
    assert service.token_calls == 2


def test_a_failing_token_call_is_retried(clock, reactor, service):
    service.fail_tokens = True
    controller, device = build(clock, reactor, service)
    connect(controller, APP, "Dashboard", "s1")
    advance(clock, reactor, 20)
    assert [m for m in device.sent if m["type"] == "token"] == []

    service.fail_tokens = False
    advance(clock, reactor, 60)

    assert any(m["type"] == "token" for m in device.sent)


def test_status_is_reported_only_when_it_changes(clock, reactor, service):
    controller, device = build(clock, reactor, service)

    connect(controller, BACKDROP)
    controller.on_status(BACKDROP, "Backdrop", None)
    controller.on_status(APP, "Dashboard", "s1")
    controller.on_status(APP, "Dashboard", "s2")
    controller.on_connection(False)

    assert service.reports == [(1, "idle", True), (1, "Dashboard", True), (1, None, False)]


def test_stopping_can_leave_or_clear_the_dashboard(clock, reactor, service):
    controller, device = build(clock, reactor, service)
    connect(controller, APP, "Dashboard", "s1")

    controller.stop(quit_ours=False)
    assert device.quits == 0 and device.stopped

    other, other_device = build(clock, reactor, service)
    connect(other, APP, "Dashboard", "s1")
    other.stop(quit_ours=True)
    assert other_device.quits == 1


def test_a_new_connection_is_not_acted_on_until_the_tv_says_what_it_is_showing(clock, reactor, service):
    controller, device = build(clock, reactor, service)

    controller.on_connection(True)
    advance(clock, reactor, 60)
    assert device.launches == []  # no status yet: it could be playing something

    controller.on_status("YOUTUBE", "YouTube", "y1")
    advance(clock, reactor, 60)
    assert device.launches == []
