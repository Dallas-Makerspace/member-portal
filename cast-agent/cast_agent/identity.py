"""Recognising a TV by what it is, not where it is: used to find it again when its address changes.

Every Cast device answers `GET http://<ip>:8008/setup/eureka_info?options=detail` with its friendly
name and a stable id (`ssdp_udn`). A sweep of the TV subnets for open Cast ports plus that lookup
finds a TV that has moved.
"""

from __future__ import annotations

import ipaddress
import logging
import socket
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Callable, Iterable

import requests

log = logging.getLogger(__name__)

SETUP_PORT = 8008
CAST_PORT = 8009
LOOKUP_TIMEOUT = 3.0
PROBE_TIMEOUT = 1.0
WORKERS = 256


@dataclass(frozen=True)
class Identity:
    device_id: str
    name: str | None


def normalize_id(value: str | None) -> str | None:
    """`uuid:ABC...` and `abc...` are the same id."""
    if not value:
        return None
    return value.strip().lower().removeprefix("uuid:") or None


def fetch_identity(host: str, timeout: float = LOOKUP_TIMEOUT) -> Identity | None:
    """What the device at [host] says it is, or None if it doesn't answer (or won't say)."""
    try:
        response = requests.get(
            f"http://{host}:{SETUP_PORT}/setup/eureka_info", params={"options": "detail"}, timeout=timeout
        )
        response.raise_for_status()
        data = response.json()
    except (requests.RequestException, ValueError):
        return None
    device_id = normalize_id(data.get("ssdp_udn") or (data.get("device_info") or {}).get("ssdp_udn"))
    if device_id is None:
        return None
    return Identity(device_id, data.get("name"))


def normalize_name(value: str | None) -> str | None:
    return value.strip().casefold() if value and value.strip() else None


def is_same_device(known_id: str | None, actual: Identity, known_name: str | None = None) -> bool:
    """False only when we know who the TV should be (by id, else by name) and this is someone else."""
    expected = normalize_id(known_id)
    if expected is not None:
        return expected == actual.device_id
    wanted = normalize_name(known_name)
    return wanted is None or wanted == normalize_name(actual.name)


def match_device(
    found: dict[str, tuple[str, Identity]], known_id: str | None, known_name: str | None
) -> tuple[str, Identity] | None:
    """The device in [found] that is the TV we mean: by id if we have one, else by its name."""
    expected = normalize_id(known_id)
    if expected is not None:
        return found.get(expected)
    wanted = normalize_name(known_name)
    if wanted is None:
        return None
    return next((hit for hit in found.values() if normalize_name(hit[1].name) == wanted), None)


def hosts_in(ranges: Iterable[str]) -> list[str]:
    hosts: list[str] = []
    for cidr in ranges:
        try:
            hosts.extend(str(h) for h in ipaddress.ip_network(cidr.strip(), strict=False).hosts())
        except ValueError:
            log.warning("Ignoring an invalid sweep range: %r", cidr)
    return hosts


def _open(host: str, port: int, timeout: float) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def sweep(ranges: Iterable[str], port: int = CAST_PORT, timeout: float = PROBE_TIMEOUT) -> list[str]:
    """Every address in [ranges] with the Cast port open."""
    hosts = hosts_in(ranges)
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        flags = list(pool.map(lambda h: _open(h, port, timeout), hosts))
    return [h for h, is_open in zip(hosts, flags) if is_open]


def find_devices(
    ranges: Iterable[str],
    sweeper: Callable[[Iterable[str]], list[str]] = sweep,
    fetch: Callable[[str], Identity | None] = fetch_identity,
) -> dict[str, tuple[str, Identity]]:
    """Maps device id to (address, identity) for every Cast device found in [ranges]."""
    found: dict[str, tuple[str, Identity]] = {}
    open_hosts = sweeper(ranges)
    with ThreadPoolExecutor(max_workers=16) as pool:
        for host, identity in zip(open_hosts, pool.map(fetch, open_hosts)):
            if identity is not None:
                found[identity.device_id] = (host, identity)
    return found
