from concurrent.futures import Future

from cast_agent.agent import SWEEP_BACKOFF_START, Agent
from cast_agent.controller import LOST_AFTER, TargetController
from cast_agent.identity import Identity

from .conftest import APP, BACKDROP, FakeDevice, advance, make_target


class HoldingHooks:
    """Lets a test decide when (and with what) the identity lookup answers."""

    def __init__(self) -> None:
        self.verifies = 0
        self.lost_calls = 0

    def verify(self, controller) -> None:
        self.verifies += 1

    def lost(self, controller) -> None:
        self.lost_calls += 1


def build(clock, reactor, service, hooks, target=None):
    devices: list[FakeDevice] = []

    def factory(host, port, name, events):
        device = FakeDevice(host, port, name, events)
        devices.append(device)
        return device

    controller = TargetController(
        target or make_target(), factory, service, reactor, APP, clock.now, clock.wall_ms, hooks=hooks
    )
    controller.start()
    return controller, devices[0]


def test_nothing_is_launched_until_the_tv_has_been_identified(clock, reactor, service):
    hooks = HoldingHooks()
    controller, device = build(clock, reactor, service, hooks)

    controller.on_connection(True)
    controller.on_status(BACKDROP, "Backdrop", None)
    assert device.launches == []

    controller.on_identity(Identity("aaa", "Shop TV"))
    assert device.launches == [APP]


def test_the_first_identity_is_recorded_once(clock, reactor, service):
    controller, device = build(clock, reactor, service, HoldingHooks())

    controller.on_connection(True)
    controller.on_identity(Identity("aaa", "Shop TV"))
    controller.on_connection(True)
    controller.on_identity(Identity("aaa", "Shop TV"))

    assert service.identified == [(1, "aaa", "Shop TV")]


def test_a_tv_that_will_not_say_who_it_is_is_trusted(clock, reactor, service):
    controller, device = build(clock, reactor, service, HoldingHooks())

    controller.on_connection(True)
    controller.on_status(BACKDROP, "Backdrop", None)
    controller.on_identity(None)

    assert device.launches == [APP]
    assert service.identified == []


def test_a_different_device_at_the_address_is_left_alone_and_reported_lost(clock, reactor, service):
    hooks = HoldingHooks()
    controller, device = build(clock, reactor, service, hooks, make_target(device_id="aaa"))

    controller.on_connection(True)
    controller.on_status(BACKDROP, "Backdrop", None)
    controller.on_identity(Identity("zzz", "Another TV"))
    advance(clock, reactor, 3600)

    assert device.launches == [] and device.quits == 0
    assert device.stopped
    assert hooks.lost_calls == 1
    assert controller.wrong_device


def test_a_tv_unreachable_for_a_while_is_reported_lost_but_a_reconnect_cancels_it(clock, reactor, service):
    hooks = HoldingHooks()
    controller, device = build(clock, reactor, service, hooks, make_target(device_id="aaa"))

    controller.on_connection(False)
    advance(clock, reactor, LOST_AFTER - 10)
    controller.on_connection(True)
    advance(clock, reactor, 3600)
    assert hooks.lost_calls == 0

    controller.on_connection(False)
    advance(clock, reactor, LOST_AFTER + 1)
    assert hooks.lost_calls == 1


class InlinePool:
    """Runs work immediately, so tests don't need threads."""

    def submit(self, fn, *args):
        future: Future = Future()
        try:
            future.set_result(fn(*args))
        except Exception as error:  # pragma: no cover - mirrors a real pool
            future.set_exception(error)
        return future

    def shutdown(self, *args, **kwargs) -> None:
        pass


def build_agent(clock, reactor, service, finder, lookup=lambda host: None, ranges=("192.0.2.0/24",)):
    devices: dict[str, FakeDevice] = {}

    def factory(host, port, name, events):
        device = FakeDevice(host, port, name, events)
        devices[host] = device
        return device

    agent = Agent(
        service,
        reactor,
        factory,
        APP,
        clock.now,
        clock.wall_ms,
        sweep_ranges=ranges,
        lookup=lookup,
        finder=finder,
        pool=InlinePool(),
    )
    return agent, devices


def test_a_missing_tv_is_found_by_its_id_and_moved(clock, reactor, service):
    service.target_list = [make_target(host="old.example.internal", device_id="aaa")]
    found = {"aaa": ("192.0.2.77", Identity("aaa", "Shop TV"))}
    agent, devices = build_agent(clock, reactor, service, finder=lambda ranges: found)
    agent.reconcile()
    devices["old.example.internal"].events.on_connection(False)
    advance(clock, reactor, LOST_AFTER + 5)

    assert service.moves == [(1, "192.0.2.77")]
    assert "192.0.2.77" in devices
    assert devices["old.example.internal"].stopped


def test_a_tv_that_is_not_found_is_searched_for_again_with_growing_gaps(clock, reactor, service):
    service.target_list = [make_target(host="old.example.internal", device_id="aaa")]
    sweeps: list[float] = []

    def finder(ranges):
        sweeps.append(clock.t)
        return {}

    agent, devices = build_agent(clock, reactor, service, finder=finder)
    agent.reconcile()
    devices["old.example.internal"].events.on_connection(False)
    advance(clock, reactor, LOST_AFTER + 1)
    advance(clock, reactor, 24 * 3600)

    gaps = [b - a for a, b in zip(sweeps, sweeps[1:])]
    assert len(sweeps) >= 4
    assert gaps[0] == SWEEP_BACKOFF_START
    assert gaps == sorted(gaps) and gaps[1] > gaps[0]  # the wait grows, so it never becomes a steady poll
    assert service.moves == []


def test_a_tv_with_no_recorded_id_is_not_searched_for(clock, reactor, service):
    service.target_list = [make_target(host="old.example.internal")]
    sweeps: list[int] = []

    def finder(ranges):
        sweeps.append(1)
        return {}

    agent, devices = build_agent(clock, reactor, service, finder=finder)
    agent.reconcile()
    devices["old.example.internal"].events.on_connection(False)

    advance(clock, reactor, LOST_AFTER + 60)

    assert sweeps == []


def test_a_tv_that_comes_back_on_its_own_stops_being_searched_for(clock, reactor, service):
    service.target_list = [make_target(host="old.example.internal", device_id="aaa")]
    sweeps: list[int] = []

    def finder(ranges):
        sweeps.append(1)
        return {}

    agent, devices = build_agent(clock, reactor, service, finder=finder)
    agent.reconcile()
    device = devices["old.example.internal"]
    device.events.on_connection(False)
    advance(clock, reactor, LOST_AFTER + 1)
    assert len(sweeps) == 1

    device.events.on_connection(True)
    advance(clock, reactor, 24 * 3600)

    assert len(sweeps) == 1


def test_the_agent_asks_the_device_who_it_is_when_a_tv_connects(clock, reactor, service):
    service.target_list = [make_target(host="a.example.internal")]
    asked: list[str] = []

    def lookup(host):
        asked.append(host)
        return Identity("aaa", "Shop TV")

    agent, devices = build_agent(clock, reactor, service, finder=lambda r: {}, lookup=lookup)
    agent.reconcile()
    device = devices["a.example.internal"]
    device.events.on_connection(True)
    device.events.on_status(BACKDROP, "Backdrop", None)
    reactor.run_due()

    assert asked == ["a.example.internal"]
    assert service.identified == [(1, "aaa", "Shop TV")]
    assert device.launches == [APP]
