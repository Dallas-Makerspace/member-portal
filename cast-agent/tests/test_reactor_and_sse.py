from cast_agent.reactor import Reactor
from cast_agent.service import parse_sse


def test_timers_run_in_order_when_due():
    now = [0.0]
    reactor = Reactor(lambda: now[0])
    ran: list[str] = []
    reactor.call_later(5, lambda: ran.append("late"))
    reactor.call_later(1, lambda: ran.append("early"))

    assert reactor.run_due() == 1
    now[0] = 2
    assert reactor.run_due() == 3
    now[0] = 10
    assert reactor.run_due() is None
    assert ran == ["early", "late"]


def test_a_cancelled_timer_does_not_run():
    now = [0.0]
    reactor = Reactor(lambda: now[0])
    ran: list[str] = []
    reactor.call_later(1, lambda: ran.append("x")).cancel()
    now[0] = 5

    assert reactor.run_due() is None
    assert ran == []


def test_a_failing_callback_does_not_stop_the_others():
    now = [0.0]
    reactor = Reactor(lambda: now[0])
    ran: list[str] = []

    def boom() -> None:
        raise RuntimeError("bad")

    reactor.post(boom)
    reactor.post(lambda: ran.append("after"))
    reactor.run_due()

    assert ran == ["after"]


def test_sse_yields_each_data_event_and_ignores_comments():
    lines = [
        ": connected\n",
        "\n",
        'data: {"type":"reload","committeeId":9}\n',
        "\n",
        "data: not json\n",
        "\n",
        'data: {"type":"targets-changed"}\n',
        "\n",
    ]

    assert list(parse_sse(lines)) == [{"type": "reload", "committeeId": 9}, {"type": "targets-changed"}]
