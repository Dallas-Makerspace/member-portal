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
- Widgets show aggregate data only — never member names.

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

## Adding a committee

Add its name (or slug) to `committees.dashboard-committees` and make sure its chair group exists in
`common-models/.../Committee.kt`. Group-count widgets are limited to groups the committee owns
(`Committee.ownsGroup`).

## Not built yet

- Tool status widgets (needs a place to record state; today only two tools are defined).
- Deleting replaced/unused pictures.
- A way to see the live TV image from the editor (the preview renders the saved dashboard in the portal).
