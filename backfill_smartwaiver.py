#!/usr/bin/env python3
"""Copy all SmartWaiver data into our own database, and watch it happen.

The copy runs inside the member-portal service and keeps its place, so it can be stopped and started
again at any time. This script starts it, shows what it is doing, and stops it. Standard library only.

    backfill_smartwaiver.py start               start (or continue) the full copy and watch it
    backfill_smartwaiver.py start --limit 20    trial: copy only 20 waivers, then stop
    backfill_smartwaiver.py start --retry       try again only the waivers that failed (skips the rest)
    backfill_smartwaiver.py start --restart     begin the whole copy again from the top
    backfill_smartwaiver.py attach              watch a copy that is already running
    backfill_smartwaiver.py status              show where it is and exit
    backfill_smartwaiver.py pause | resume      hold / release it
    backfill_smartwaiver.py stop                stop it now (it keeps its place)
    backfill_smartwaiver.py errors              waivers that could not be copied

Ctrl+C stops the copy on the server too, and the next `start` carries on where it left off. Use
--keep-running to make Ctrl+C only close this window and leave the copy running.

Connecting
    Local service:   --url http://localhost:8081 (the default)
    Production:      --tunnel dms-docker-h0-root   (opens an ssh tunnel to the service and re-opens it if
                     a deploy moves the service)
    Credentials:     the API key and name of a client allowed to run cron jobs, e.g. the Jenkins one:
                     SW_API_KEY (or --key-file) and --client jenkins-member-refresh. The key is never
                     printed. Putting it in an environment variable keeps it out of the process list.
    Log file:        --log-file PATH keeps a dated copy of what is printed (plain text, no colour).
    Colour:          on in a terminal; --no-color or the NO_COLOR environment variable turns it off.
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
LOG_FILE = None


def stamp():
    return time.strftime("%H:%M:%S")


def log_line(text):
    """Append one dated line to the --log-file, if one was given."""
    if LOG_FILE:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {text}\n")


LIVE_DRAWN = False
USE_ANSI = sys.stdout.isatty()
COLOR = USE_ANSI and "NO_COLOR" not in os.environ
if COLOR and os.name == "nt":
    os.system("")  # lets older Windows consoles interpret ANSI escape codes
CODES = {"bold": "1", "dim": "2", "red": "31", "green": "32", "yellow": "33", "blue": "34", "cyan": "36"}


def c(text, *styles):
    """Colour text for the terminal; plain when colour is off. Never used for the log file."""
    if not COLOR or not styles:
        return text
    return "\033[" + ";".join(CODES[s] for s in styles) + "m" + text + "\033[0m"


def say(text="", *styles):
    """Print a line right away (coloured on a terminal) and keep it, plain, in the log file."""
    print(c(text, *styles), flush=True)
    if text:
        log_line(text)


def clear_live():
    """Remove the two live lines so a permanent line can be printed in their place."""
    global LIVE_DRAWN
    if LIVE_DRAWN and USE_ANSI:
        sys.stdout.write("\r\033[2K\033[F\r\033[2K")
        sys.stdout.flush()
    LIVE_DRAWN = False


def style_for(part):
    """The colour of one part of the summary line, chosen by what it says."""
    if part.startswith(("Step ", "Getting ready")):
        return ("bold",)
    if "problems" in part:
        return ("bold", "red")
    if part.startswith(("daily call limit", "paused")) or "slowed down" in part or "continuing in" in part:
        return ("yellow",)
    if part.startswith("all done in"):
        return ("bold", "cyan")
    if part.startswith("this step"):
        return ("cyan",)
    if " of " in part and "%" in part:
        return ("green",)
    return ()


def colour_summary(text, width):
    """Fit [HH:MM:SS] + the summary into width, then colour each part (the overall estimate stays visible)."""
    parts = text.split(" · ")
    stamp_len = len(stamp()) + 3  # "[HH:MM:SS] "
    while len(parts) > 1 and stamp_len + len(" · ".join(parts)) > width:
        # Drop the least important (last) part first, but never the overall estimate.
        drop = next((i for i in range(len(parts) - 1, 0, -1) if not parts[i].startswith("all done in")), None)
        if drop is None:
            break
        parts.pop(drop)
    parts[0] = parts[0][: max(0, width - stamp_len)]
    return c(f"[{stamp()}]", "dim") + " " + c(" · ", "dim").join(c(p, *style_for(p)) for p in parts)


def redraw_live(text, activity):
    """Two live lines that update in place: the summary, and what is happening right now."""
    global LIVE_DRAWN
    if not USE_ANSI:
        return
    width = shutil.get_terminal_size((120, 20)).columns - 1
    if LIVE_DRAWN:
        sys.stdout.write("\r\033[2K\033[F")
    sys.stdout.write("\r\033[2K" + colour_summary(text, width) + "\n\033[2K" + c((activity or "")[:width], "dim"))
    sys.stdout.flush()
    LIVE_DRAWN = True


def duration(seconds):
    if seconds is None:
        return "unknown"
    seconds = int(max(0, seconds))
    days, rest = divmod(seconds, 86400)
    hours, rest = divmod(rest, 3600)
    minutes, secs = divmod(rest, 60)
    if days:
        return f"{days}d {hours}h"
    if hours:
        return f"{hours}h {minutes}m"
    if minutes:
        return f"{minutes}m"
    return f"{secs}s" if secs else "under a minute"


def short(seconds):
    return "about " + duration(seconds) if seconds is not None else "unknown"


class Tunnel:
    """ssh -L to the service container; looks the container up again whenever it has to reconnect."""

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
        say(f"Opening a secure connection to {self.host}...")
        address = self._container_address()
        if not address:
            raise ConnectionError("no running member-profile-service container found")
        self.proc = subprocess.Popen(
            ["ssh", "-N", "-o", "ExitOnForwardFailure=yes", "-L", f"{TUNNEL_PORT}:{address}:8081", self.host],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(2)
        if self.proc.poll() is not None:
            raise ConnectionError("the ssh tunnel closed right away")
        say("Connected.", "green")

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def close(self):
        if self.proc is not None and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
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

    def request(self, path, timeout=60):
        if self.tunnel and not self.tunnel.alive():
            self.tunnel.open()
        req = urllib.request.Request(self.base + path, headers=self.headers)
        return urllib.request.urlopen(req, timeout=timeout)

    def text(self, path):
        try:
            with self.request(path) as resp:
                return resp.read().decode()
        except urllib.error.HTTPError as e:
            return f"HTTP {e.code}: {e.read().decode(errors='replace')}"

    def reset_tunnel(self):
        """Drop the tunnel so the next request looks the service up again (a deploy may have moved it)."""
        if self.tunnel:
            self.tunnel.close()

    def close(self):
        if self.tunnel:
            self.tunnel.close()


def print_overview(p):
    """What the copy is going to do and how long it should take, shown once at the start."""
    plan = p.get("plan") or []
    if not plan:
        return
    trial = p.get("limit")
    say()
    say("Copying SmartWaiver data into our database" + (f" (trial: {trial} waivers only)" if trial else ""), "bold")
    say("What will happen:")
    for i, step in enumerate(plan, 1):
        units = step.get("units")
        size = f"{units:,} {step.get('unit')}" if units is not None else "size unknown"
        approx = "~" if step.get("approximate") and units is not None else ""
        line = f"  Step {i} of {len(plan)}: {step.get('title')} ({approx}{size}), {short(step.get('etaSeconds'))}"
        print(c(f"  Step {i} of {len(plan)}:", "bold", "cyan") + line[len(f"  Step {i} of {len(plan)}:"):], flush=True)
        log_line(line)
    pace = p.get("paceCallsPerMinute", 0)
    say(f"It makes about {pace:.0f} calls a minute to SmartWaiver and at most {p.get('dailyBudget', 0):,} a day,")
    say("and waits for the next day when it reaches that limit.")
    say(f"Estimated total time: {short(p.get('etaOverallSeconds'))}.", "bold", "cyan")
    say("Ctrl+C stops it; the next start carries on where it left off.", "dim")
    say()


def progress_line(p):
    """(summary, activity): the summary carries the overall estimate first so it is never cut off."""
    state = p.get("state")
    count = p.get("stageCount") or 0
    index = p.get("stageIndex") or 0
    where = f"Step {index} of {count}" if count and index else "Getting ready"
    overall = f"all done in {short(p.get('etaOverallSeconds'))}" if p.get("etaOverallSeconds") is not None else None
    if state == "WAITING":
        wake = p.get("waitingUntilEpoch")
        left = duration(wake - time.time()) if wake else "?"
        return " · ".join(x for x in (where, f"daily call limit reached, continuing in {left}", overall) if x), ""
    if state == "PAUSED":
        return f"{where} · paused (run 'resume' to continue)", ""
    total, done = p.get("stageTotal"), p.get("stageDone", 0)
    approx = "~" if p.get("stageApprox") else ""
    progress = None
    if total:
        pct = f" ({100 * done / total:.0f}%)"
        progress = f"{done:,} of {approx}{total:,} {p.get('stageUnit') or ''}{pct}"
    parts = [where, progress, overall,
             f"this step {short(p.get('etaStageSeconds'))}" if p.get("etaStageSeconds") is not None and total else None]
    if p.get("failed"):
        parts.append(f"{p['failed']} problems (run 'errors')")
    if p.get("slowMode"):
        parts.append("slowed down by SmartWaiver")
    return " · ".join(x for x in parts if x), p.get("activity") or ""


def summary_line(p):
    """The same idea for `status`, with the running totals."""
    summary, activity = progress_line(p)
    tail = (f" · calls today {p.get('requestsToday', 0):,} of {p.get('dailyBudget', 0):,}"
            f" · waivers copied {p.get('mirrored', 0):,} · photos {p.get('photosStored', 0):,}"
            f" · check-ins {p.get('checkinsAdded', 0):,}")
    note = f" · {p['message']}" if p.get("message") else ""
    return f"{p.get('state')}: {summary}{tail}{note}" + (f"\n  now: {activity}" if activity else "")


def stop_job(client):
    say("Stopping the copy on the server...")
    try:
        say(client.text(BASE_PATH + "/stop"))
    except (urllib.error.URLError, OSError, ConnectionError) as e:
        say(f"Could not reach the server to stop it ({e}). Run 'stop' again to be sure.")


def attach(client, expected_limit=None, keep_running=False):
    """Follow progress until the copy ends. Ctrl+C stops it too unless keep_running is set."""
    last, last_log, overview_shown, saw_activity = {}, 0.0, False, False
    while True:
        try:
            with client.request(BASE_PATH + "/stream", timeout=60) as resp:
                for raw in resp:
                    line = raw.decode().strip()
                    if not line.startswith("data: "):
                        continue
                    p = json.loads(line[6:])
                    # Checked on every frame: a copy that lost its limit (a restart) would run in full.
                    if expected_limit and p.get("limit") != expected_limit:
                        clear_live()
                        say(f"ERROR: the running copy does not have the trial limit of {expected_limit} "
                            "(the service may be an older version, or it was restarted).", "bold", "red")
                        say("Stopping it so it doesn't copy everything.", "yellow")
                        stop_job(client)
                        return False
                    if p.get("state") == "IDLE":
                        clear_live()
                        if saw_activity:
                            say(p.get("message") or "The copy was stopped (by 'stop', or another copy took over).")
                            return True
                        say("No copy is running. Start one with: start", "yellow")
                        return False
                    saw_activity = True
                    if p.get("plan") and not overview_shown:
                        clear_live()
                        print_overview(p)
                        overview_shown = True
                    now = time.time()
                    text, activity = progress_line(p)
                    changed = (p.get("state"), p.get("stageIndex")) != (last.get("state"), last.get("stageIndex"))
                    more_failures = p.get("failed", 0) > last.get("failed", 0)
                    more_429 = p.get("rateLimitHits", 0) > last.get("rateLimitHits", 0)
                    if changed or more_failures or more_429 or now - last_log >= STATUS_LOG_SECONDS:
                        note429 = "  <- SmartWaiver asked us to slow down" if more_429 else ""
                        notefail = "  <- a waiver could not be copied" if more_failures else ""
                        clear_live()
                        print(colour_summary(text, 10_000) + c(note429, "bold", "yellow") + c(notefail, "bold", "red"), flush=True)
                        log_line(text + note429 + notefail)
                        last_log = now
                    redraw_live(f"[{stamp()}] {text}", activity)
                    last = p
                    if p.get("state") in ("DONE", "FAILED"):
                        clear_live()
                        if p.get("state") == "FAILED":
                            say(f"The copy stopped because of an error: {p.get('lastError')}", "bold", "red")
                            say("Fix the problem and run 'start' again to continue where it stopped.", "yellow")
                        else:
                            say(p.get("message") or "Finished.", "bold", "green")
                        return p.get("state") == "DONE"
        except KeyboardInterrupt:
            clear_live()
            print()
            if keep_running:
                say("Closed this window. The copy keeps running on the server (use 'stop' to stop it).", "yellow")
            else:
                try:
                    stop_job(client)
                    say("Stopped. Run 'start' again to carry on where it left off.", "yellow")
                except KeyboardInterrupt:
                    say("Interrupted again; run 'stop' to be sure the copy is stopped.")
            return True
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                say(f"ERROR: the service answered HTTP {e.code}; check --client and the API key.", "bold", "red")
                return False
            if 400 <= e.code < 500:
                # Not a key problem and retrying won't help (e.g. 404: the service has no such page).
                say(f"ERROR: the service answered HTTP {e.code}; it may be an older version.", "bold", "red")
                return False
            clear_live()
            print(c(f"[{stamp()}] the service returned HTTP {e.code}; retrying in {RETRY_SECONDS}s", "yellow"))
            log_line(f"service returned HTTP {e.code}; retrying")
            time.sleep(RETRY_SECONDS)
        except (urllib.error.URLError, ConnectionError, OSError, subprocess.TimeoutExpired) as e:
            clear_live()
            print(c(f"[{stamp()}] lost the connection ({e}); retrying in {RETRY_SECONDS}s", "yellow"))
            log_line(f"lost the connection ({e}); retrying")
            client.reset_tunnel()
            time.sleep(RETRY_SECONDS)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", nargs="?", default="attach",
                    choices=["start", "attach", "status", "pause", "resume", "stop", "errors"])
    ap.add_argument("--restart", action="store_true", help="with start: begin again from the top")
    ap.add_argument("--retry", action="store_true", help="with start: retry only waivers that failed before")
    ap.add_argument("--limit", type=int, help="with start: trial of this many waivers, then stop")
    ap.add_argument("--keep-running", action="store_true", help="Ctrl+C only closes this window")
    ap.add_argument("--url", default="http://localhost:8081")
    ap.add_argument("--tunnel", metavar="SSH_HOST", help="reach the service through an ssh tunnel")
    ap.add_argument("--key-file", help="file holding the API key (or set SW_API_KEY)")
    ap.add_argument("--client", help="API client name (or set SW_API_CLIENT)")
    ap.add_argument("--log-file", help="keep a dated copy of what is printed in this file")
    ap.add_argument("--no-color", action="store_true", help="plain output, no colour")
    args = ap.parse_args()
    global LOG_FILE, COLOR
    LOG_FILE = args.log_file
    if args.no_color:
        COLOR = False
    if args.limit is not None and args.limit < 1:
        ap.error("--limit must be a whole number of 1 or more")

    client = Client(args)
    started = False
    try:
        if args.command == "start":
            say("Starting the SmartWaiver copy"
                + (f" (trial: {args.limit} waivers)" if args.limit is not None else "") + "...")
            query = [q for q in ("restart=true" if args.restart else "",
                                 "retry=true" if args.retry else "",
                                 f"limit={args.limit}" if args.limit is not None else "") if q]
            reply = client.text(BASE_PATH + ("?" + "&".join(query) if query else ""))
            if reply.startswith(("HTTP ", "Refusing")):
                say(f"Could not start: {reply}", "bold", "red")
                return 1  # refused (flag off, mock mode, bad key): don't sit on an idle stream
            started = True
            already = reply.startswith("ALREADY_RUNNING")
            say("A copy is already running; showing its progress." if already
                else "Started on the server. Planning the work (about 15 seconds)...")
            return 0 if attach(client, None if already else args.limit, args.keep_running) else 1
        if args.command == "attach":
            say("Connecting to the running copy...")
            return 0 if attach(client, None, args.keep_running) else 1
        if args.command == "status":
            reply = client.text(BASE_PATH + "/status")
            if reply.startswith("HTTP "):
                print(f"Could not get the status: {reply}")
                return 1
            print(summary_line(json.loads(reply)))
            return 0
        print(client.text(f"{BASE_PATH}/{args.command}"))
        return 0
    except KeyboardInterrupt:
        print()
        if args.command == "start" and not args.keep_running:
            stop_job(client)
        else:
            say("Interrupted.")
        return 130 if not started else 0
    except (urllib.error.URLError, ConnectionError, OSError, ValueError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
