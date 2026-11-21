# Committee TV dashboard

Committee chairs build a dashboard in the portal (pages, layout presets, widgets, announcements); a small
**cast agent** keeps it showing on the committee's Chromecast / Google TV devices. v1 is enabled for
Woodshop only.

## How it fits together

```
Chair/admin ──editor (UI)──▶ UI ──API key──▶ service  :service:dashboards ──▶ MariaDB / AD / calendar / S3
                                               ▲  events (SSE) + token mint   (API client "cast-agent")
cast-agent (docker-h0) ──CASTV2 :8009──▶ TV ──loads──▶ https://<portal>/tv/receiver
                  └── Cast message {token | reload} ──▶ receiver page
receiver ── Bearer token ──▶ UI /tv/api/* ──API key + X-Dashboard-Token──▶ service
```

- **Service module** `service/modules/dashboards` — tables, validation, rendering, tokens, picture storage,
  the editor/TV/cast-agent routes. Access: a committee's chairs (members of its chair AD group) and the
  Infrastructure group, behind the `committees.dashboard-enabled` flag and the `committees.dashboard-committees`
  list; the service re-checks on every call (the UI only hides buttons).
- **UI** — `/committees/{slug}/dashboard` (editor), `/committees/{slug}/dashboard-api/...` (proxy for the
  editor), `/tv/receiver` (public TV page), `/tv/api/dashboard` and `/tv/api/images/{file}` (token-authorised).
  The TV page deliberately has no header, session, Bootstrap, `app.js` or page loader.
- **Cast agent** `cast-agent/` — Python + pychromecast. One connection per TV, addressed by host/IP (no mDNS).

## Why the UI hosts the TV page

A custom Cast receiver must load over HTTPS, and the service is internal and plain HTTP. So the public UI hosts
the page, and it authenticates with a **device token** the agent hands the receiver over the Cast message
channel (never in a URL):

- HMAC-SHA256 `payload.signature`, payload = TV id, committee id, generation, expiry (24 h). Secret:
  `DASHBOARD_TOKEN_SECRET` on the service (empty = tokens off; the service still starts).
- **Revoke** (TVs tab) bumps the TV's generation. The service re-checks the target row (cached 5 min, cleared
  on revoke), so a revoked token stops working at once on this instance, and the agent hands out a fresh one.
- Counts and classes are aggregate. The **member list** card is the one widget that names people, and only those
  a chair picked: up to 20 usernames (stored in the widget config), added one by one with the member picker or
  from a committee group of at most 10 enabled members (a snapshot taken when added; later group changes are not
  picked up). The service returns display name and photo for them; an uploaded photo is fetched with the TV's
  token from `/tv/api/avatars/{username}`, and only for someone on that committee's lists. A list too long for the
  card scrolls without end (repeated once, wraps at the bottom).
- Group-count widgets count enabled accounts only.
- **Tool status** card lists the tools in use right now (with who is using each) or those under maintenance. It
  reads the tool interlock system (RFID Interlock) through its GraphQL API, signing in with a service account
  (`INTERLOCK_BASE_URL` / `INTERLOCK_EMAIL` / `INTERLOCK_PASSWORD`; empty = the card says tool status is
  unavailable). Only queries are sent. The operator is matched to a member by RFID badge number (operator names
  repeat and have no email): the tool's newest in-activity gives the operator id, the operator record gives the
  badge, and AD's `employeeID` gives the username (tried as given and without the interlock's zero padding).
  Tool lists are cached 30 s and badge matches 1 h. There is no push source, so a page with this card asks the
  service again about once a minute; that is the one place the TV refreshes on a timer.

## Nothing polls

- Editor save → service emits an event on `GET /cast-agent/events` (SSE) → the agent sends `reload` to the
  affected TVs.
- The receiver reloads on `reload` and on one deadline timer from the payload's `nextChangeAtMs` (an
  announcement starting/ending, a class starting, the 30-minute group-count cache). Failures retry with backoff.
- The agent reacts to status pushes from the TVs; its timers are deadlines it can name (reclaim wait, display
  hours, token refresh).

## What each TV does (`cast-agent/cast_agent/controller.py`)

1. Outside its **display hours** (America/Chicago): our receiver is stopped if it is running.
2. Inside them, an **idle** TV (no app, or the Chromecast backdrop) gets the receiver launched.
3. If someone else is casting, the TV is left alone — unless **switch back after N minutes** is set; then it
   is taken back once that long has passed with the same other app on screen (a different app resets the wait).
4. A new connection is not acted on until the TV has said what it is showing.
5. When our receiver is running it gets the token a few times (1 s, 4 s, 12 s); the receiver ignores a token it
   already has. A new token is sent an hour before the old one expires.

## The editor's preview

Two views in one panel, so a chair is never unsure what they are looking at:

- **On the TV**: the last saved version, rendered by `GET .../dashboard/preview`. This is what the TVs show
  (they reload on save).
- **With my changes**: the unsaved pages, rendered by `POST .../dashboard/preview` (same validation as saving,
  nothing stored) and handed to the preview frame with `DmsTv.showDraft`. The first edit switches to it, later
  edits refresh it after a short pause, and saving switches back. It is outlined and labelled "Unsaved preview".
  An invalid draft keeps the last good preview and says what to fix.

Both views follow the page selected on the left and show it alone, even if it is hidden or empty (`pagesToShow`
with `pin`). "On the TV" finds the saved page by its id (`GET .../dashboard/preview/{pageId}`), so it survives
reordering; a page that is not saved yet has no saved version and says so. The preview is its own copy of the
dashboard, not a mirror of any TV, so it cannot know which page a TV is showing.

**Play all pages** is optional: it cycles the pages at their set times, as a TV does, with a readout of the
page and the seconds until the next one (`DmsTv.status()`). It also plays for people who asked for reduced
motion, since they pressed it. Selecting a page, switching views or pressing Stop ends it. The editor loads the
receiver with `?manual=1` so it waits for the pages the editor sends instead of loading the saved ones first.

## TVs / Displays page

Resources → **TVs / Displays** (`/displays`, open to any signed-in member) is a read-only list of every
TV set up for a committee: status (online, offline, not seen yet, disabled), what it is showing (the committee
dashboard with its page titles, idle, or another app), last update, display hours ("On until 2:00 AM", overnight
aware) and the take-back time. A TV's address is not shown. It comes from `GET /displays` on
the service (`DashboardService.displays`) and `DisplayStatus` turns the agent's last report into wording. The status
is the agent's last report (connect, disconnect or app change), not a ping, so "Last update" is the last change.
Devices the cast agent's sweep found but nobody set up are listed too. At each sweep the agent also reads what
each one is running (app name, status text and, for apps that share it, the media's title, artist, state and web
address) and stores it in `cast_devices`; the page says it in words ("Spotify", "Idle (ambient screen)", "Nothing
running") with a "Media" link when there is a web address. Only http(s) addresses become links. One "updated" time
at the top of the page is the newest sweep. A snapshot, so it can be up to the sweep interval old.

## Pictures

Text cards can carry a JPEG/PNG. The service decodes, shrinks to 1600 px, and re-encodes as JPEG; it is stored
as `{committeeId}/{uuid}.jpg` under `StorageNamespace.DASHBOARD_IMAGES` in the **shared object-store bucket**
(`OBJECT_STORE_BUCKET`, same as profile photos). A card stores only the file name. The editor reads pictures
through the session, a TV through its token (`/tv/api/images/{file}`). Replaced pictures are not deleted yet.

## Setup checklist

1. **Database**: run `service/docs/sql/committee-dashboard-migration.sql` on `member_profile` (re-runnable).
2. **Service env** (server `.env.service`): `DASHBOARD_TOKEN_SECRET`, `DASHBOARD_PORTAL_URL` (the public portal
   URL, used for the QR code), and an API client with role `CAST_AGENT`
   (`API_CLIENT_n_NAME=cast-agent`, `_KEY`, `_ROLE=CAST_AGENT`, bump `API_CLIENT_COUNT`).
3. **Register the receiver** in the [Google Cast SDK Developer Console](https://cast.google.com/publish)
   (one-time $5 developer fee):
   - Add a **Custom Receiver** whose URL is `https://<portal host>/tv/receiver`; note the **App ID**.
   - **Publish** it. An unpublished receiver only works on devices whose serial numbers are registered in the
     console; a published one works on any Cast device, so **no per-TV registration** is needed
     ([Google's registration docs](https://developers.google.com/cast/docs/registration)). Publishing is safe:
     the page shows nothing without a token. Allow a little time for it to propagate, then restart the TV.
4. **Agent env** on docker-h0 (`~/member-profile/actions-runner-service/.env.cast-agent`): set
   `RECEIVER_APP_ID` (the file already holds the service URL and API key). The deploy workflow skips the
   deploy until it is set.
5. **TVs**: give each a DHCP reservation (they are addressed by IP), then add it on the committee's
   dashboard → **TVs** tab (host/IP, optional display hours and switch-back minutes).
6. Turn the flag on for the pilot (`committees.dashboard-enabled`; defaults to the IT early-access group).

## Running the agent

- **Host**: docker-h0 — on the Member VLAN, routed to the IoT VLAN, 4 CPU / 32 GB, already runs the CD runner.
  It reaches every TV's port 8009 directly (checked 2026-11-15, including from a container on the bridge
  network), so no extra NIC or mDNS is needed. TVs in standby may not answer; the agent just keeps retrying.
- **Deploy**: `.github/workflows/cast-agent-cd.yml` (tests → image → `docker run` on the runner). Manual:
  `cd cast-agent && docker compose up -d --build` with a `.env` (see `.env.example`).
- **Tests**: `cd cast-agent && pip install -r requirements-dev.txt && pytest`.
- **Logs**: `docker logs cast-agent`. The TVs tab shows each TV's last status ("Dashboard", "idle", app name,
  or nothing if it never connected).

## When a TV's address changes

TVs are on DHCP and addressed by IP, so the agent can recognise one by what it is instead:

- On each connection the agent asks the device at the address who it is
  (`http://<ip>:8008/setup/eureka_info?options=detail`: friendly name plus a stable `ssdp_udn`) and records it
  on the TV (`cast_targets.device_id/device_name`; the TVs tab shows "Recognised as ..."). It never drives a
  device whose id doesn't match the recorded one: that address now belongs to something else, so it lets go.
- A TV unreachable for 10 minutes (or found to be someone else) is searched for: the agent probes the Cast
  port across `SWEEP_RANGES` (comma-separated CIDRs in the agent's env), looks each hit up, and matches by id.
  If found, it tells the service the new address (`POST /cast-agent/targets/{id}/host`), which moves the TV and
  the agent reconnects. If not found (asleep, off), it searches again after 1, 2, 4 ... minutes, capped at 1 h,
  so it never becomes a steady poll; a TV that reconnects by itself stops being searched for.
- A chair can also enter the TV's **name on the network** (its Cast name, e.g. in the Cast menu) when adding it.
  A TV known only by name is found by name (a never-connected TV with a wrong or guessed address is searched
  for after 10 minutes), and a device at the address with a different name is left alone. This is how to add a
  TV that is off and whose address is unknown: enter any address plus its name, and the agent locates it once it
  powers on (within the search gap, at most an hour).
- Editing a TV's host in the editor clears the recorded id (a different address may be a different TV); the name stays.
- A sleeping or powered-off TV answers neither ARP nor the Cast port, so it cannot be located from the network
  until it is on.
- To find a TV by name when setting it up (it must be awake):
  `docker run --rm -e SWEEP_RANGES=... <agent image> python -m cast_agent.find "<name>"` (or `--list`).

## Adding a committee

Add its name (or slug) to `committees.dashboard-committees` and make sure its chair group exists in
`common-models/.../Committee.kt`. Group-count widgets are limited to groups the committee owns
(`Committee.ownsGroup`).

## Not built yet

- Tool status widgets (needs a place to record state; today only two tools are defined).
- Deleting replaced/unused pictures.
- A way to see the live TV image from the editor (the preview renders the saved dashboard in the portal).
