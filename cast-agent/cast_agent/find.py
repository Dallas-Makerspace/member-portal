"""Looks for a Cast device by its name on the TV subnets (for setting up a TV whose address is unknown).

    python -m cast_agent.find "South_work_area"
    python -m cast_agent.find --list

The subnets come from SWEEP_RANGES (comma-separated CIDRs) or --range. A TV that is asleep does not
answer, so wake it first.
"""

from __future__ import annotations

import argparse
import os
import sys

from .identity import find_devices


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("name", nargs="?", help="friendly name to look for (case-insensitive, substring)")
    parser.add_argument("--list", action="store_true", help="list every Cast device found")
    parser.add_argument("--range", action="append", help="CIDR to sweep; repeatable (default: SWEEP_RANGES)")
    args = parser.parse_args()

    ranges = args.range or [r for r in os.environ.get("SWEEP_RANGES", "").split(",") if r.strip()]
    if not ranges:
        print("No ranges: pass --range or set SWEEP_RANGES", file=sys.stderr)
        return 2
    if not args.name and not args.list:
        parser.error("give a name or --list")

    found = find_devices(ranges)
    wanted = (args.name or "").lower()
    shown = 0
    for host, identity in sorted(found.values()):
        if args.list or wanted in (identity.name or "").lower():
            print(f"{host}\t{identity.name}\t{identity.device_id}")
            shown += 1
    if not shown:
        print(f"Swept {len(found)} Cast device(s); none matched. Is the TV awake?", file=sys.stderr)
    return 0 if shown else 1


if __name__ == "__main__":
    sys.exit(main())
