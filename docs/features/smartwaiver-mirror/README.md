# SmartWaiver local mirror

SmartWaiver may shorten how long it keeps data, so the portal keeps its own copy. New waivers are mirrored
when the webhook arrives, and a multi-day backfill copies everything since 2014. Reports and the profile
kiosk-photo button read the local copy once `smartwaiver.local-reads.enabled` is on (section 3).

Status and decisions: [`PENDING.md`](PENDING.md). This file is the design and the runbook.

## 1. What is stored

Everything lives in the legacy `dms-makermanager` database (the app's `DB_MASTER` connection), in
`smartwaiver_data` plus child tables created by
[`service/docs/sql/smartwaiver-mirror-migration.sql`](../../../service/docs/sql/smartwaiver-mirror-migration.sql).

| Data | Where |
| --- | --- |
| Waiver: names, email, dob, dates, template, kiosk/verified/minor flags, tags, address, phone, emergency contact, referral answers | typed columns on `smartwaiver_data` |
| Participants (name, dob, minor flags) | `smartwaiver_participants` |
| Guardian (name, relationship, phone) | `smartwaiver_guardians` |
| Kiosk photo metadata and the S3 key | `smartwaiver_photos`; the image is in S3 |
| Check-ins | `smartwaiver_checkins` |
| Backfill progress | `smartwaiver_backfill_state` |

Kiosk photos go to a dedicated private bucket (`dms-smartwaiver-archive`, SSE-S3, versioned, noncurrent versions
expire after 30 days) under `smartwaiver/{waiverId}/photos/{photoId}.{ext}`. The DB stores the key relative to
the `smartwaiver/` prefix.

**Deliberately not stored anywhere:** driver's license number/state, insurance carrier/policy, client IP, gender,
marketing opt-ins, typed signatures, signature images, the signed PDF, and the raw API response. The waiver model
(`SmartwaiverFullWaiver`) is an allowlist, so those fields are never deserialized; `SmartWaiverMirrorRepositoryTest`
proves none reaches a column. PDFs and signatures were left out because they hold the most sensitive data (ID and
insurance numbers, signatures) and nothing uses them; the mirror is built so a PDF stage can be added later and the
backfill re-run.

## 2. How a waiver gets mirrored

`SmartWaiverMirrorService.mirrorWaiver(waiverId)` is the one path (webhook queue, backfill, repairs). It fetches the
waiver, stores its kiosk photos (skipping objects that already exist, deleting only what this call uploaded if the DB
write fails), then writes the rows in one transaction. Re-running it is safe. Rows carry `mirrorVersion`; bumping
`MIRROR_VERSION` makes the Enrich stage re-mirror everything.

Writes are select-then-update-or-insert because the service user has no DELETE and Exposed 0.41 has no upsert.

## 3. The two flags and what they need

- `smartwaiver.mirror.enabled` (Site Config, boolean, **default off**): the webhook queue uses the mirror, check-in
  events are queued, and the backfill is allowed to start. **Needs the migration applied first**, otherwise every
  waiver fails. With it off the original minimal path runs unchanged.
- `smartwaiver.local-reads.enabled` (Site Config, boolean, **default off**): the `signed-waivers` and
  `recent-signed-waivers` reports and the profile kiosk photos read the mirror instead of calling SmartWaiver. The
  two reports also take `source=local` or `source=api` (on the data-viz URL) to force a side, so the two can be
  compared. Turn it on only after the backfill finished and `/cron/smartwaiver-reconcile` says `RESULT: OK`.
  Every kiosk photo view is logged by the UI as `Kiosk waiver photos viewed: viewer=... subject=...`.

## 4. Runbook

1. **Apply the migration** as a DB admin (the service user has no DDL rights). It is re-runnable and backs the table
   up first (`smartwaiver_data_backup_20261017`):
   `mariadb -h <dms-master host> -u <admin> -p < service/docs/sql/smartwaiver-mirror-migration.sql`.
   Check the output of its last steps (unique key on `waiverId`, row counts, the five new tables, grants).
2. Prod `.env.service` needs `SMARTWAIVER_OBJECT_STORE_BUCKET=dms-smartwaiver-archive` (already added) and a deploy.
3. Turn on `smartwaiver.mirror.enabled` in Site Config. New waivers are now mirrored in full.
4. Turn on SmartWaiver's check-in webhook (one `PUT /v4/webhooks/configure` with `checkinWebhook=true`, keeping the
   other settings). The payload for check-in events is not documented well, so any event containing "checkin" only
   queues a catch-up that reads recent check-ins from the API.
5. **Try a small batch first**: `python backfill_smartwaiver.py start --limit 20 ...` fully mirrors 20 of the
   already-stored waivers (oldest first; no discovery, no check-ins), then stops. Check one in the DB and the S3
   bucket. A later `start` without `--limit` carries on from where the trial stopped. The CLI checks that the
   service accepted the limit and stops the run if it did not (an older build ignores it).
6. **Run the backfill**: `python backfill_smartwaiver.py start --tunnel dms-docker-h0-root --client
   jenkins-member-refresh --log-file backfill.log` with `SW_API_KEY` set to that client's key. It prints a plan
   (the three steps, their sizes, one overall estimate), then two live lines: progress and what it is doing now.
   It makes one API call every 5 s (about 100k calls in all), which measured out at roughly five days; it waits for
   the next Chicago day if it ever reaches the daily limit.
   **Ctrl+C stops the copy on the server too** (it keeps its place; the next `start` continues). Use
   `--keep-running` to close the window and leave it running. `attach` re-opens the view, and `pause`, `resume`,
   `stop`, `status` and `errors` work from any terminal.
7. **Reconcile**: `GET /cron/smartwaiver-reconcile` compares SmartWaiver's counts with the mirror per year (and per
   month where a year differs). It must report `RESULT: OK` before anything is cut over. Also re-run the last check
   in the migration script (removed legacy rows that must exist in `smartwaiver_participants`).
8. **Compare, then cut over**: call the two reports with `source=local` and `source=api` and compare; then turn on
   `smartwaiver.local-reads.enabled`. Once it has run cleanly for a while, the live-read client methods can go.

How it keeps going on its own over several days:

- **Resume.** Progress is saved after every unit (one transaction). After a deploy or restart of the production
  service (the only one with `SMARTWAIVER_BACKFILL_AUTO_RESUME=true`; a local dev service pointed at the same database
  must never resume it, or it takes the run over from production) a run that was running, waiting or paused is picked up again (a paused one stays paused until `resume`). A stopped run is saved
  as IDLE and a trial as TRIAL; neither is resumed.
- **One copy only.** The saved run records which copy owns it. A deploy briefly runs two containers; whichever
  started last owns the run and the other ends at its next checkpoint without writing. `stop` releases the run, so
  it also stops a copy running in another container.
- **Errors.** A failed step is retried after 30 s, 1, 2, 5 and then every 10 minutes, for about a day without any
  progress (so an overnight VPN or database outage is waited out). Only a rejected API key, a configuration error,
  or running out of that day ends the run as FAILED; fix the cause and `start` again to continue. At startup the
  check for an interrupted run is also retried, since the database may not answer for a moment after a deploy.
- **Known limits.** A pause sent while the job is waiting or retrying is saved only when that wait ends. If a deploy
  is rolled back after the new container claimed the run, the older container ends as superseded and nothing runs
  until the next `start`.
- **Failed waivers.** One that fails is tried again in a second pass; any still failing are listed by `errors`
  (in memory) and the log, and `start --retry` runs just the waiver-copying step again. A waiver SmartWaiver answers
  404 for is marked gone (`mirrorVersion` 0, photo status GONE) and no longer counts as work to do.
- **Refuses** to start in mock mode, because local `.env` files point at the real database.

Pacing knobs (env, all optional): `SMARTWAIVER_BACKFILL_DAILY_BUDGET`, `SMARTWAIVER_BACKFILL_INTERVAL_MS`,
`SMARTWAIVER_SEARCH_INTERVAL_MS`, `SMARTWAIVER_REQUEST_INTERVAL_MS`. A 429 pauses every API caller for the
`retryAfter` the API gives and halves the backfill's pace until the next Chicago day.

## 5. Things worth knowing

- The legacy table held one row **per participant** on some multi-person waivers (94 waiverIds appear more than
  once; 64 of those differ in name or dob). The migration keeps one row per waiver; the Enrich stage recreates the
  others in `smartwaiver_participants`, and the backup keeps the originals.
- `smartwaiver_hooks` holds about 86k unprocessed rows from 2018-2024 that the original system never marked. The
  queue only looks at the last 30 days, so they are ignored.
- `checkinCount` on `smartwaiver_data` is a legacy column (the original system maintained it). The mirror leaves it
  alone on existing rows and writes 1 on new ones; real check-ins are in `smartwaiver_checkins`.
- Photos exist for most waivers from 2016 on. A waiver reports its photo count, so the photo call is skipped when it
  is 0.
- The SmartWaiver API allows 600 requests overall and 100/min for searches; a search stops at 30k results, hence
  monthly windows.

## 6. Still to do

See [`PENDING.md`](PENDING.md).
