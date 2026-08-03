# SparkPost API Contract (M0 spike findings)

Validated live against the production Dallas Makerspace SparkPost account on
2026-08-02. This document is the authoritative contract for the
`SparkPostClient` / `EmailNotifier` implementation (milestone M1) and the
event-webhook ingestion (milestone M4).

## Account Facts

- Customer ID: `41388`, account "Dallas Makerspace", active, standard service
- Plan: `100K-premier` ($75/mo, 100k emails/month, overage limit 300k)
- Sending domain: `dallasmakerspace.org` (verified; already used in production
  by the calendar app via **SMTP relay**)
- TFA required on account; API key auth is via a dedicated REST API key
- Sandbox flag: real sends use `"options": { "sandbox": false }`

## Authentication

- Header: `Authorization: <api_key>` (no scheme prefix)
- API base: `https://api.sparkpost.com/api/v1`
- Invalid/unknown key → `401 {"errors":[{"message":"Unauthorized."}]}`
- Note: two candidate keys supplied for this spike returned 401; the working
  key was created in the dashboard (Account → API Keys) with Transmissions,
  Suppression List, and Webhooks read/write.

## Error Contract

All errors share one shape, `errors` is always an array:

```json
{ "errors": [ { "message": "At least one valid recipient is required", "code": 5002 } ] }
```

| Status | Meaning | Observed examples |
|---|---|---|
| 400 | Validation / bad request | invalid recipient (`code 5002`), missing webhook `name`, invalid target |
| 401 | Bad/unknown API key | `{"message":"Unauthorized."}` |
| 404 | Resource not found | suppression entry absent, unknown webhook id |
| 420 | Sending-limit exceeded (2101–2105) | hourly/daily/sandbox/monthly plan limits — **no messages sent** |
| 429 | API rate limit (not on transmissions POSTs) | docs: wait 1–5 s, incremental backoff |

There are **no rate-limit response headers**. Transmissions POSTs are *not*
API-rate-limited (sending limits are enforced via 420 instead).

## Transmissions (send email)

`POST /api/v1/transmissions`

```json
{
  "recipients": [ { "address": { "email": "member@example.com" } } ],
  "content": {
    "from": { "name": "DMS Notifications", "email": "no-reply@dallasmakerspace.org" },
    "subject": "Subject line",
    "text": "Plain-text body",
    "html": "<p>HTML body</p>"
  },
  "options": { "sandbox": false }
}
```

Success → `200`:

```json
{ "results": { "total_rejected_recipients": 0, "total_accepted_recipients": 1, "id": "7669471586629462294" } }
```

- `results.id` is the transmission id; it is **not** a per-message id and
  there is **no server-side idempotency** — the caller must dedupe (our
  `notifications.dedupe_key`).
- `campaign_id` on the transmission body can tag a group of sends for
  metrics/event correlation later.
- A single transmission accepts multiple recipients, but we will send
  one-per-notification to keep per-recipient status accurate.

## Suppression List

- Check a recipient: `GET /api/v1/suppression-list/{email}` → `200` with the
  record when suppressed, `404` when **not** suppressed.
- List/filter: `GET /api/v1/suppression-list?from=<date>&to=<date>` (date
  params, not email search).
- Suppression sources: bounce handling and the unsubscribe webhook events
  (SparkPost enforces; we mirror to `notification_suppressions`).

## Event Webhooks

- `POST /api/v1/webhooks` — create. Requires `name` (≤24 chars) and
  **live-validates the target by POSTing to it during creation; the target
  must return 2xx** (405 from example.com, and unroutable hosts, are
  rejected). Use a real endpoint when registering in M4.
- Registered events for this account tenant (`spc`): `delivery`, `bounce`,
  `open`, `click`. Event webhook batch POST bodies are JSON arrays of
  `msys.<event_type>`-wrapped objects (e.g. `msys.message_event`,
  `msys.bounce_event`).
- Test event: `POST /api/v1/webhooks/{id}/validate` with a
  `{"message": [ ...events... ]}` body — POSTs the sample events to the
  target and reports the target's response.
- Delivery status: `GET /api/v1/webhooks/{id}/batch-status` (empty until real
  deliveries occur). **There are no `/test` or `/batch` endpoints** — both
  return 404; the API's link relations
  (`urn.msys.webhooks.validate`, `urn.msys.webhooks.batches`) are the
  authoritative route map.
- Update `PUT /api/v1/webhooks/{id}`; delete `DELETE /api/v1/webhooks/{id}` → `204`.
- The target URL must be stable and public at registration time; the service
  webhook path will be `POST /webhook/sparkpost` (public, like existing
  webhook routes), registered against the live deployment URL in M4.

## EmailNotifier Contract (M1)

```kotlin
interface Notifier {
    val channel: Channel
    suspend fun send(notification: PendingNotification): SendResult
}
```

`EmailNotifier` (SparkPost REST, Ktor `HttpClient`):

- Config (`app.notifications.sparkpost`): `api-key` (env `SPARKPOST_API_KEY`),
  `from-email` (`no-reply@dallasmakerspace.org`), `from-name`.
- Request: single recipient, `sandbox=false`, subject + `text` and `html`
  bodies; caller supplies the `dedupe_key` for retry safety (DB unique
  constraint is the enforcement point, not SparkPost).
- Success: HTTP 200 with `total_accepted_recipients >= 1` → `SENT`; if
  `total_rejected_recipients > 0` → treat as failure.
- Retry classification:
  - **Transient → retry (backoff):** 420 (sending limit), 429, network/5xx
  - **Permanent → dead-letter:** 400 (validation, e.g. invalid address), 401
  - 404/unknown → dead-letter with error recorded
- Timeouts: connect ~10 s, request ~30 s (SparkPost responds in seconds).
- Suppression check before send: `GET /suppression-list/{email}` (404 = ok to
  send) — or rely on SparkPost's own suppression enforcement and treat a
  422/420-style rejection accordingly; decision deferred to M1, default to
  relying on SparkPost enforcement (cheaper, no extra API call).

## M4 Webhook Ingestion Notes

- Register one webhook with events `delivery`, `bounce`, `open`, `click`
  against `https://<service-host>/webhook/sparkpost`.
- Handler (WebhookRouter pattern): parse batch JSON array, map
  `msys.*_event.type` → status updates:
  `delivery` → SENT, `bounce` (permanent) → mark failed + add suppression,
  `click`/`open` → informational (read-tracking for the unified view).
- Correlate via `transmission_id` (we store it on `notifications` when sent);
  events lacking a matching notification id are ignored/logged.
