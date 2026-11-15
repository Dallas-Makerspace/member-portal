from __future__ import annotations

import logging
import os
import signal
import sys

from .agent import Agent
from .device import ChromecastDevice
from .reactor import Reactor
from .service import HttpService


def main() -> int:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    log = logging.getLogger("cast_agent")

    api_key = os.environ.get("CAST_AGENT_API_KEY", "")
    app_id = os.environ.get("RECEIVER_APP_ID", "").strip()
    if not api_key:
        log.error("CAST_AGENT_API_KEY is not set")
        return 2
    if not app_id:
        log.error("RECEIVER_APP_ID is not set (the Cast receiver's App ID from the Google Cast console)")
        return 2

    service = HttpService(
        os.environ.get("SERVICE_URL", "http://member-profile-service:8081"),
        api_key,
        os.environ.get("CAST_AGENT_CLIENT", "cast-agent"),
    )
    reactor = Reactor()

    def make_device(host: str, port: int, name: str, events):
        return ChromecastDevice(host, port, name, events, reactor)

    ranges = [r.strip() for r in os.environ.get("SWEEP_RANGES", "").split(",") if r.strip()]
    if not ranges:
        log.warning("SWEEP_RANGES is not set: a TV whose address changes will not be found again")
    agent = Agent(service, reactor, make_device, app_id, sweep_ranges=ranges)

    def stop(_signum, _frame) -> None:
        reactor.post(agent.shutdown)
        reactor.post(reactor.stop)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    agent.start()
    log.info("Cast agent running")
    reactor.run_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
