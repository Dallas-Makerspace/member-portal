# SmartWaiver mirror: status and pending work

Last updated: 2026-10-17. Design and runbook: [`README.md`](README.md).

## Done (on `master`, deployed)

- Access gap closed: `/backend-api` no longer proxies `members/*/kiosk-waiver-photos`, and
  `data-viz/recent-signed-waivers` needs `MANAGE_BADGES` or `waiver-report.allowed-usernames` (same gate as its page).
- API client: shared HTTP client, per-category throttle, 429 `retryAfter` handling, no PII in logs, waiver-id
  validation; webhook queue lookup parameterized; queue processing single-flight.
- Cut-over code (off by default): `LocalWaiverReads` serves the two reports and the profile kiosk photos from the
  mirror when `smartwaiver.local-reads.enabled` is on (reports also accept `source=local|api`); the UI logs every
  kiosk photo view with viewer and subject.
- Mirror: allowlisted model, `SmartWaiverMirrorService`, `KioskPhotoMirror`, `SmartWaiverMirrorRepository`,
  check-in sync, webhook path behind `smartwaiver.mirror.enabled`, backfill runner + routes + CLI, reconciliation.
- S3: bucket `dms-smartwaiver-archive` created and tested; `MemberPortalObjectStore` IAM policy v2 covers it;
  `SMARTWAIVER_OBJECT_STORE_BUCKET` is in prod `.env.service`.

## Needs a person

1. **Apply `service/docs/sql/smartwaiver-mirror-migration.sql` to the prod `dms-makermanager` database** as a DB
   admin (master user `Sadmin`; the service user `member_portal_svc` has only SELECT everywhere, INSERT on
   `smartwaiver_data`, INSERT/UPDATE on `smartwaiver_hooks`). Not applied yet. The script also grants the service
   user what the mirror needs. Tested twice on a throwaway MariaDB 10.11.
2. After a deploy of this code, turn on `smartwaiver.mirror.enabled`, then run the backfill (README, runbook).
3. Decide when to turn on SmartWaiver's check-in webhook.
4. After the backfill and a clean reconcile: compare the reports with `source=local` vs `source=api`, then turn on
   `smartwaiver.local-reads.enabled`.

## Not built yet

- Removing the live-read client methods (`getWaivers`, `getWaiverDetails`, `getWaiversByName` for the reports and
  photos) once `smartwaiver.local-reads.enabled` has been on for a while. `getWaiver`, `getWaiverPhotos`,
  `getCheckins` and the counts stay: the mirror itself uses them.
- The `/data-viz/*` routes in the service have no per-report permission (the service only knows the calling client).
  The UI proxy gate covers the waiver report; other reports are still reachable by any logged-in member.

## Follow-ups (not blocking)

- Board-approved retention and destruction policy for waiver data, and privacy-notice wording on the waiver.
- A written rule that kiosk photos are never used for face recognition, and a decision on showing photos from waivers
  signed as a minor.
- Ask SmartWaiver in writing for their retention policy, notice period and export terms; ask the liability insurer
  whether it requires a retention period for signed waivers (that decides if a PDF evidence archive is needed, which
  would be a separate write-only bucket the app cannot read).
- Separate the S3 principal from the Bedrock one, and keep prod AWS keys out of dev `.env` files.
- Lifecycle/erasure: versioning keeps a deleted photo as a noncurrent version for 30 days.
