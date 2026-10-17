#!/usr/bin/env python3
"""Watch and steer the SmartWaiver mirror backfill, which runs inside the member-portal service.

The job keeps going if this script exits or the connection drops; this is only a live view and a set
of controls. Standard library only.

    backfill_smartwaiver.py start [--restart]   start (or continue) the backfill, then watch it
    backfill_smartwaiver.py start --limit N     trial: fully mirror only N stored waivers, then stop
    backfill_smartwaiver.py attach              watch a running backfill (the default)
    backfill_smartwaiver.py status              print one snapshot and exit
    backfill_smartwaiver.py pause | resume      hold / release it at the next waiver
    backfill_smartwaiver.py stop                end the run, keeping its cursors
    backfill_smartwaiver.py errors              waivers that failed to mirror recently

Connecting
    Local service:   --url http://localhost:8081 (the default)
    Production:      --tunnel dms-docker-h0-root   (opens an ssh -L tunnel to the service container
                     and re-opens it if a blue/green deploy moves the container)
    Credentials:     the API key of a client with the CRON_EXECUTOR role (e.g. jenkins-member-refresh),
                     read from --key-file or SW_API_KEY (never printed), with that client's name via
                     --client or SW_API_CLIENT. The name and key must belong to the same client.
    Log file:        --log-file PATH appends the dated lines this prints, so the history survives a
                     closed terminal. The job also logs to the service log (Kibana).

Retrying failed waivers: a finished Enrich stage is not repeated; `start --restart` re-scans (about 150
calls) and mirrors only the waivers that still lack a full mirror.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE_PATH = "/cron/backfill-smartwaiver"
TUNNEL_PORT = 18081
RETRY_SECONDS = 5
STATUS_LOG_SECONDS = 60


def fmt_duration(seconds):
    if seconds is None:
        return "?"
    seconds = int(max(0, seconds))
    days, rest = divmod(seconds, 86400)
    hours, rest = divmod(rest, 3600)
    minutes, secs = divmod(rest, 60)
    if days:
        return f"{days}d {hours}h"
    if hours:
        return f"{hours}h {minutes}m"
    if minutes:
        return f"{minutes}m {secs}s"
    return f"{secs}s"


def stamp():
    return time.strftime("%H:%M:%S")


LOG_FILE = None


def log_line(text):
    """Append one dated line to the --log-file, if one was given."""
    if LOG_FILE:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {text}\n")


class Tunnel:
    """ssh -L to the service container; re-resolves the container's address when it has to reconnect."""

    def __init__(self, host):
        self.host = host
        self.proc = None

    def _container_address(self):
        cmd = [
            "ssh", self.host,
            "docker ps --filter name=member-profile-service --format '{{.Names}}' | head -n1 | "
            "xargs -r docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}'",
        ]
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=60).stdout.split()
        return out[0] if out else None

    def open(self):
        self.close()
        address = self._container_address()
        if not address:
            raise ConnectionError("no running member-profile-service container found")
        self.proc = subprocess.Popen(
            ["ssh", "-N", "-o", "ExitOnForwardFailure=yes", "-L", f"{TUNNEL_PORT}:{address}:8081", self.host],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(2)
        if self.proc.poll() is not None:
            raise ConnectionError("ssh tunnel exited right away")

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def close(self):
        if self.proc is not None and self.proc.poll() is None:
            self.proc.terminate()
        self.proc = None


class Client:
    def __init__(self, args):
        self.tunnel = Tunnel(args.tunnel) if args.tunnel else None
        self.base = f"http://localhost:{TUNNEL_PORT}" if self.tunnel else args.url.rstrip("/")
        key = os.environ.get("SW_API_KEY", "")
        if args.key_file:
            with open(args.key_file, encoding="utf-8") as f:
                key = f.read().strip()
        self.headers = {"X-Api-Key": key or "dummy-key-for-dev",
                        "X-Api-Client": args.client or os.environ.get("SW_API_CLIENT", "member-profile-ui")}

    def ensure_connection(self):
        if self.tunnel and not self.tunnel.alive():
            self.tunnel.open()

    def request(self, path, timeout=60):
        self.ensure_connection()
        req = urllib.request.Request(self.base + path, headers=self.headers)
        return urllib.request.urlopen(req, timeout=timeout)

    def text(self, path):
        try:
            with self.request(path) as resp:
                return resp.read().decode()
        except urllib.error.HTTPError as e:
            return f"HTTP {e.code}: {e.read().decode(errors='replace')}"

    def reset_tunnel(self):
        """Drop the tunnel so the next request re-resolves the container (a deploy may have moved it)."""
        if self.tunnel:
            self.tunnel.close()

    def close(self):
        if self.tunnel:
            self.tunnel.close()


def summary_line(p):
    state, stage = p.get("state"), p.get("stage") or "-"
    done, total = p.get("stageDone", 0), p.get("stageTotal")
    pct = f" ({100 * done / total:.1f}%)" if total else ""
    count = f"{done:,}/{total:,}" if total else f"{done:,}"
    if state == "WAITING":
        wake = p.get("waitingUntilEpoch")
        left = fmt_duration(wake - time.time()) if wake else "?"
        head = f"paused for daily budget, resuming {p.get('waitingUntil')} (in {left})"
    elif state == "PAUSED":
        head = "PAUSED (resume with: backfill_smartwaiver.py resume)"
    else:
        head = f"{state}"
    rate = p.get("requestsPerMinute1", 0.0)
    eta = fmt_duration(p.get("etaStageSeconds")) if p.get("etaStageSeconds") is not None else "?"
    slow = " SLOW-MODE" if p.get("slowMode") else ""
    note = f" | {p['message']}" if p.get("message") else ""
    return (f"{head} | {stage} {count}{pct} | {rate:.1f} req/min | today "
            f"{p.get('requestsToday', 0):,}/{p.get('dailyBudget', 0):,} | ETA stage {eta} | "
            f"mirrored {p.get('mirrored', 0):,} photos {p.get('photosStored', 0):,} "
            f"checkins {p.get('checkinsAdded', 0):,} failed {p.get('failed', 0)}{slow}{note}")


def attach(client):
    """Follow the progress stream until the run ends. Ctrl-C detaches; the backfill keeps running."""
    last = {}
    last_log = 0.0
    width = shutil.get_terminal_size((120, 20)).columns
    while True:
        try:
            with client.request(BASE_PATH + "/stream", timeout=60) as resp:
                for raw in resp:
                    line = raw.decode().strip()
                    if not line.startswith("data: "):
                        continue
                    p = json.loads(line[6:])
                    now = time.time()
                    changed = (p.get("state"), p.get("stage")) != (last.get("state"), last.get("stage"))
                    more_failures = p.get("failed", 0) > last.get("failed", 0)
                    more_429 = p.get("rateLimitHits", 0) > last.get("rateLimitHits", 0)
                    text = summary_line(p)
                    if changed or more_failures or more_429 or now - last_log >= STATUS_LOG_SECONDS:
                        note = (("  <- rate limited (429)" if more_429 else "")
                                + ("  <- new failure (run 'errors')" if more_failures else ""))
                        print(f"\r{' ' * (width - 1)}\r[{stamp()}] {text}{note}", flush=True)
                        log_line(text + note)
                        last_log = now
                    else:
                        print(f"\r[{stamp()}] {text}"[: width - 1].ljust(width - 1), end="", flush=True)
                    last = p
                    if p.get("state") in ("DONE", "FAILED"):
                        print()
                        if p.get("state") == "FAILED":
                            print(f"FAILED: {p.get('lastError')}")
                        return p.get("state") == "DONE"
        except KeyboardInterrupt:
            print("\ndetached; the backfill keeps running")
            return True
        except urllib.error.HTTPError as e:
            if 400 <= e.code < 500:
                # Wrong key or client name, no permission, ...: retrying won't help.
                print(f"\nERROR: the service answered HTTP {e.code}; check --client and the API key")
                return False
            print(f"\n[{stamp()}] service returned HTTP {e.code}; retrying in {RETRY_SECONDS}s")
            log_line(f"service returned HTTP {e.code}; retrying")
            time.sleep(RETRY_SECONDS)
        except (urllib.error.URLError, ConnectionError, OSError, subprocess.TimeoutExpired) as e:
            print(f"\n[{stamp()}] connection lost ({e}); retrying in {RETRY_SECONDS}s")
            log_line(f"connection lost ({e}); retrying")
            client.reset_tunnel()
            time.sleep(RETRY_SECONDS)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", nargs="?", default="attach",
                    choices=["start", "attach", "status", "pause", "resume", "stop", "errors"])
    ap.add_argument("--restart", action="store_true", help="with start: clear the cursors and begin again")
    ap.add_argument("--limit", type=int, help="with start: mirror only this many waivers as a trial, then stop")
    ap.add_argument("--url", default="http://localhost:8081")
    ap.add_argument("--tunnel", metavar="SSH_HOST", help="reach the service through ssh -L to its container")
    ap.add_argument("--key-file", help="file holding the API key (or set SW_API_KEY)")
    ap.add_argument("--client", help="X-Api-Client name (or set SW_API_CLIENT)")
    ap.add_argument("--log-file", help="append the printed status lines, with dates, to this file")
    args = ap.parse_args()
    global LOG_FILE
    LOG_FILE = args.log_file

    client = Client(args)
    try:
        if args.command in ("start",):
            query = [q for q in ("restart=true" if args.restart else "",
                                 f"limit={args.limit}" if args.limit else "") if q]
            reply = client.text(BASE_PATH + ("?" + "&".join(query) if query else ""))
            print(reply)
            log_line(f"start: {reply.splitlines()[0] if reply else ''}")
            if reply.startswith(("HTTP ", "Refusing")):
                return 1  # refused (flag off, mock mode, bad key): don't sit on an idle stream
            return 0 if attach(client) else 1
        if args.command == "attach":
            return 0 if attach(client) else 1
        if args.command == "status":
            print(summary_line(json.loads(client.text(BASE_PATH + "/status"))))
            return 0
        print(client.text(f"{BASE_PATH}/{args.command}"))
        return 0
    except (urllib.error.URLError, ConnectionError, OSError, ValueError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
