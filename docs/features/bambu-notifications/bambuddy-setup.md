# BamBuddy provider setup for portal notifications

How to configure the DMS BamBuddy instance so the member portal receives
Bambu printer notifications (design: [`design.md`](design.md)).

**Prerequisite — database migration first:** apply
`service/docs/sql/bambu-notifications-migration.sql` to the Member Portal
database **before** setting the secret, deploying, or enabling any flag
(design §17 step 1). Without it the watch/offline tables don't exist and
every webhook fails, and the scheduler logs one warning at startup and then
idles until the next event.

Do the rest **after** the service is deployed with
`BAMBUDDY_WEBHOOK_SECRET` set (the `.env.template` placeholder is rejected
like an unset value), and after the `bambu-notifications.enabled` site
config has been piloted. It ships as a pilot (the early-access group), not
fully off; the scheduler and receiver run in that state.

## 1. Create the webhook provider in BamBuddy

In BamBuddy's notification settings, create one provider:

| Setting | Value |
|---|---|
| Provider type | `webhook` |
| Payload format | **generic** (never `slack` — it is human chat text, not machine-parseable) |
| Webhook URL | `http://<member-portal-service-host>:8081/webhook/bambuddy` — the path is exactly `/webhook/bambuddy` on the **service** (not the UI). BamBuddy runs on the makerspace LAN and the service is reachable inside the network, so use the service's internal host/port from the deployment (8081 is the service's default port); confirm the concrete host with whoever runs the deployment before saving |
| Printer filter (`printer_id`) | *All printers* (null) — one provider for the whole fleet |
| Auth header (`auth_header`) | `Bearer <secret>` where `<secret>` is the exact value of the service's `BAMBUDDY_WEBHOOK_SECRET` environment variable. The portal also accepts the bare secret with no `Bearer ` prefix, but configure the `Bearer ` form. Never put this value in a ticket, doc, or chat — read it from the service's secret store |
| Attach photo (`attach_photo`) | **OFF** — payloads stay lean; the portal proxies camera snapshots itself |
| Title / message fields (`field_title` / `field_message`) | Leave at BamBuddy defaults. They are human-readable text for chat providers; the portal never parses them — it reads only `event`, `printer` and `filename` (plus display variables like `duration` / `filament_grams` / `reason`) from the generic payload |

## 2. Event toggles

Turn **ON** exactly these four:

- `print_complete`
- `print_failed`
- `print_stopped`
- `printer_offline` (off by default in BamBuddy — must be enabled explicitly)

Leave everything else at its default or OFF for this provider — in
particular `print_start`, the `print_progress` thresholds, first-layer /
bed-cooled events, and all queue events. The portal returns 200 for events
it doesn't use, so extras would not break anything, but they add traffic
and log noise for no benefit.

## 3. BamBuddy-side delivery settings

- **Quiet hours: OFF.** The portal owns member preferences, batching and
  caps; BamBuddy-side quiet hours would silently drop events the portal
  is designed to handle.
- **Digest / batching in BamBuddy: OFF.** Same reason — one event, one
  POST, immediately.
- Delivery is fire-and-forget: BamBuddy sends a single POST and treats
  HTTP 200/201/202/204 as success; there are **no retries**. A portal
  outage loses events — this is accepted by design; ephemeral watches
  carry TTLs whose expiry check is the backstop. Failures appear only in
  BamBuddy's notification log (`last_error` on the provider), so check
  there first if alerts stop.

## 4. Verify

1. In the portal, pilot-enable `bambu-notifications.enabled` for a tester
   and arm a "notify me" watch on a short test print from the Bambu
   status page.
2. Let the print finish. The tester should receive the "print finished"
   email within ~5 minutes (the notification worker's hold window), and
   BamBuddy's notification log should show a successful delivery for the
   `print_complete` event.
3. Power off (or disconnect) one printer for longer than the offline hold
   (`bambu-notifications.offline-hold-minutes`, default 5) with a
   tester subscribed to committee alerts ("Notify me" button on the
   status page): exactly one offline email should arrive after the hold +
   re-check. Power it back on within the hold on a second test: no
   email should arrive (the alert is suppressed at release).

## 5. Switching off

Either remove/disable this provider in BamBuddy (stops events at the
source), or set the portal's `bambu-notifications.enabled` site config
fully off (the portal will 200-and-ignore events), or unset
`BAMBUDDY_WEBHOOK_SECRET` in the service environment (the portal will
reject events with 401 — BamBuddy's log will show errors, which is the
loudest and therefore most visible off-switch).
