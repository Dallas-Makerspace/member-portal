from cast_agent.agent import Agent

from .conftest import APP, BACKDROP, FakeDevice, make_target


def build_agent(clock, reactor, service):
    devices: dict[str, FakeDevice] = {}

    def factory(host, port, name, events):
        device = FakeDevice(host, port, name, events)
        devices[host] = device
        return device

    agent = Agent(service, reactor, factory, APP, clock.now, clock.wall_ms)
    return agent, devices


def test_reconcile_starts_new_tvs_and_stops_removed_ones(clock, reactor, service):
    agent, devices = build_agent(clock, reactor, service)
    service.target_list = [make_target(id=1, host="a.example.internal"), make_target(id=2, host="b.example.internal")]

    agent.reconcile()
    assert set(devices) == {"a.example.internal", "b.example.internal"}
    assert all(d.started for d in devices.values())

    service.target_list = [make_target(id=1, host="a.example.internal")]
    agent.reconcile()

    assert devices["b.example.internal"].stopped
    assert not devices["a.example.internal"].stopped


def test_a_changed_address_reconnects_but_changed_hours_do_not(clock, reactor, service):
    agent, devices = build_agent(clock, reactor, service)
    service.target_list = [make_target(host="a.example.internal")]
    agent.reconcile()
    first = devices["a.example.internal"]

    service.target_list = [make_target(host="a.example.internal", display_start="08:00", display_end="20:00")]
    agent.reconcile()
    assert not first.stopped

    service.target_list = [make_target(host="c.example.internal")]
    agent.reconcile()
    assert first.stopped and "c.example.internal" in devices


def test_reload_goes_only_to_the_committee_that_changed(clock, reactor, service):
    agent, devices = build_agent(clock, reactor, service)
    service.target_list = [
        make_target(id=1, committee_id=9, host="a.example.internal"),
        make_target(id=2, committee_id=4, host="b.example.internal"),
    ]
    agent.reconcile()
    for host, device in devices.items():
        device.events.on_connection(True)
        device.events.on_status(APP, "Dashboard", "s1")
    reactor.run_due()
    for device in devices.values():
        device.sent.clear()

    agent.handle({"type": "reload", "committeeId": 9})

    assert devices["a.example.internal"].sent == [{"type": "reload"}]
    assert devices["b.example.internal"].sent == []


def test_a_failed_target_load_is_retried(clock, reactor, service):
    agent, devices = build_agent(clock, reactor, service)

    def boom():
        raise RuntimeError("service down")

    service.targets = boom
    agent.reconcile()
    assert devices == {}

    del service.targets  # works again
    service.target_list = [make_target()]
    from .conftest import advance

    advance(clock, reactor, 31)

    assert "tv-1.example.internal" in devices


def test_shutdown_leaves_the_tvs_showing_the_dashboard(clock, reactor, service):
    agent, devices = build_agent(clock, reactor, service)
    service.target_list = [make_target()]
    agent.reconcile()
    device = devices["tv-1.example.internal"]
    device.events.on_connection(True)
    device.events.on_status(APP, "Dashboard", "s1")

    agent.shutdown()

    assert device.stopped and device.quits == 0
