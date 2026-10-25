# Bambu printer notifications — design (v1)

**Status:** implemented (branch `feature/bambu-notifications`), including the
post-review fix pass described in §9/§10.3. Requirements are frozen in
[`requirements.md`](requirements.md) (panel synthesis + Revisions 2–6, 2026-10-22);
where this doc and the requirements file disagree, this doc follows the
coordinator's frozen-decision brief, which already folds in all revisions.
BamBuddy-side configuration is in [`bambuddy-setup.md`](bambuddy-setup.md).

**Scope in one paragraph.** Members click a button on the Bambu status page to
arm a one-shot watch (`EphemeralNotification`) for their own print or for a
printer becoming available. BamBuddy outbound webhooks (generic format) are the
only trigger source — there is no fleet polling. A watch that fires creates a
normal `Notification` through the existing `NotificationService` (email only).
A watch whose TTL expires is checked once against BamBuddy, may re-arm exactly
once, and otherwise ends with a "check yourself" notice — expiry is never
silent. Printer-offline webhooks feed one standing topic,
`bambu.committee-alerts`, whose subscribers are notified only if the printer
is still offline after a 5-minute hold and one re-check.

---

## 1. Architecture at a glance

```
BamBuddy ──POST /webhook/bambuddy (Authorization secret)──► service Routing.kt
                                                              │ (dedicated route, §6)
                                                              ▼
                                              BambuddyWebhookHandler (bambuddy module)
                                                ├─ print_complete/failed/stopped
                                                │    → BambuCorrelationService (1× GET printers + 1× GET queue)
                                                │    → EphemeralNotificationService.fireWatch(...)
                                                │    → NotificationService.create (consent override, §5)
                                                └─ printer_offline
                                                     → insert/refresh row in bambu_pending_offline_alerts (release = now + 5 min)

UI status page ──POST/DELETE/GET /backend-api/... (dedicated UI handlers)──► service /bambu/watches (§8, §13)
                                                              ▼
                                              EphemeralNotificationService.arm/cancel/list
                                                → bambu_ephemeral_notifications (ARMED, expires_at = now + TTL)

BambuNotificationScheduler (service Routing launch, event/deadline driven, §10)
   ├─ due ARMED watches → one BamBuddy check → fire real notice | overdue notice + re-arm once | expire + check-yourself notice
   ├─ due pending offline alerts → one re-check → suppress if back online | NotificationService.createForMembers to topic subscribers
   └─ purge tombstones / resolved pending rows older than retention (7 days, configurable)
```

### Component inventory (all new code, by module)

| Component | Module / package | Role |
|---|---|---|
| `EphemeralNotification` + enums | `:service:bambuddy` · `org.dallasmakerspace.bambuddy.notifications` | Watch entity (data class mirroring `Notification` style) |
| `EphemeralNotificationsTable`, `PendingOfflineAlertsTable`, `BambuCommitteeSubscriptionsTable` | `:service:bambuddy` · `…bambuddy.notifications.db` | Exposed tables (§3) |
| `EphemeralNotificationsRepository`, `PendingOfflineAlertsRepository`, `BambuCommitteeSubscriptionsRepository` | same | CRUD + atomic claims; username↔`profile_id` via `ProfileResolver` (pattern: `NotificationsRepository`) |
| `BambuTtl` | `:service:bambuddy` · `…bambuddy.notifications` | Pure TTL function (§8.3) |
| `BambuCorrelationService` | `:service:bambuddy` · `…bambuddy.notifications` | Payload → printer/queue/watch matching (§7) |
| `EphemeralNotificationService` | `:service:bambuddy` · `…bambuddy.notifications` | Arm / cancel / list / fire / expire / re-arm — the state machine (§9) |
| `BambuddyWebhookPayload`, `BambuddyWebhookHandler` | `:service:bambuddy` · `…bambuddy.notifications.webhook` | Payload model + receiver logic (§6). **Not** a `webhook.WebhookHandler` — see §6.1 |
| `BambuNotificationSweeper` | `:service:bambuddy` · `…bambuddy.notifications` | Expiry checks, offline-hold release, tombstone purge (§10) |
| `BambuNotificationScheduler`, `BambuWakeSignal`, `BambuScheduleRepository` | same · `…notifications.db` | Sleeps until the next stored deadline or an in-process wake, then runs the sweeper passes (§10.1) |
| `BambuCommitteeAlertsSubscriptionHandler` | `:service:bambuddy` · `…bambuddy.notifications.subscriptions` | `TopicSubscriptionHandler` for the committee topic (§11) |
| 5 topics + 6 templates | `:service:notifications` · `topics/` + `templates/` | §4. Registered `@IntoSet` in `NotificationsModule` (pattern: `SystemTestTemplate`) |
| `NotificationService` consent override | `:service:notifications` | One new field + one skipped branch (§5) |
| Receiver route, sweeper launch, watch routes, `AppComponent` getters | `:service` app · `plugins/Routing.kt`, `di/BambuddyModule.kt`, `di/AppComponent.kt` | Wiring only (§6, §8, §10) |
| Watch buttons + committee subscribe button | `:ui` · `server/bambustatus/`, `templates/bambu-printer-status.html` | §13 |

---

## 2. Module ownership and dependency decisions

**Decision: the watch domain lives in the existing `:service:bambuddy`
module. No new Gradle module.**

Layering argument:

- `notifications` is the generic platform (outbox, preferences, worker). It
  must not learn about printers: it currently depends on
  `core, activedirectory, calendar, config, members` and nothing
  feature-specific. Putting watches there would force
  `notifications → bambuddy`, inverting the platform/feature relationship.
- `bambuddy` is the feature module that already owns the BamBuddy client,
  models, status service and `/bambu/*` routes. Watches are a bambuddy
  feature that *consumes* the notifications platform, so the acyclic
  direction is `bambuddy → notifications`.
- The app-module alternative (producers like `CommitteeClassAnnouncer` live
  in `service/src/…/webhook/handlers/`) was rejected for the core domain:
  announcers are thin one-method producers, while this feature has an
  entity, three tables, a state machine and a background sweeper — that is
  feature-module weight, and per `CLAUDE.md` a module's `@Inject` classes
  get their Dagger factories generated in-module. The app module only wires
  (routes, launch loop, multibinding registration), exactly as it already
  does for `BambuddyStatusService`.

Dependency changes:

- `service/modules/bambuddy/build.gradle.kts`:
  `implementation(project(":service:notifications"))` and
  `implementation(project(":service:config"))` added (config is already a
  transitive dep of notifications; listing it directly matches repo style).
  No `settings.gradle.kts` change (no new module).
- `service/modules/notifications` gains **no** dependency on bambuddy. Its
  only changes are the topics/templates (§4) and the consent override (§5).
- Dagger: `BambuddyModule` (app) registers the committee subscription handler
  into the notifications handler map:
  `@Provides @Singleton @IntoMap @StringKey(TopicKeys.BAMBU_COMMITTEE_ALERTS)
  fun …(): TopicSubscriptionHandler`. Dagger merges `@IntoMap` contributions
  across all modules in `AppComponent`; the precedent for cross-module
  registration is `WebhookModule` registering notification templates.
  All other new bambuddy classes are concrete `@Singleton @Inject` classes
  needing no explicit `@Provides`. `AppComponent` gains
  `getBambuddyWebhookHandler()` and `getBambuNotificationScheduler()`.

---

## 3. Data model

Conventions followed: Exposed table objects (pattern:
`notifications/db/NotificationTables.kt`); rows keyed by `profile_id`
(FK to `profile.id`) with `ProfileResolver` as the username boundary;
timestamps stored in UTC (`LocalDateTime`, per `CLAUDE.md`); DDL ships as a
re-runnable migration file **and** is added to `service/docs/sql/create.sql`
(pattern: committee subscriptions exist in both places). New migration file:
`service/docs/sql/bambu-notifications-migration.sql`. There is no
`SchemaUtils` auto-migration in this repo — SQL files are the mechanism.

### 3.1 `bambu_ephemeral_notifications` — the watch

```sql
CREATE TABLE IF NOT EXISTS `bambu_ephemeral_notifications` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `profile_id` int(10) unsigned NOT NULL COMMENT 'Watch owner; FK to profile.id',
  `scope` varchar(32) NOT NULL COMMENT 'OWN_JOB or PRINTER_AVAILABLE',
  `printer_id` int(11) NOT NULL COMMENT 'BamBuddy printer id',
  `printer_name` varchar(128) NOT NULL COMMENT 'Printer name snapshot at arm time',
  `queue_id` int(11) DEFAULT NULL COMMENT 'OWN_JOB: the watched queue item. PRINTER_AVAILABLE: the blocking (currently printing) queue item',
  `filename` varchar(512) DEFAULT NULL COMMENT 'Queue archive_name snapshot; correlation key with the webhook payload',
  `job_name` varchar(512) DEFAULT NULL COMMENT 'Display name for emails (same value as filename in v1)',
  `trigger_events` varchar(128) NOT NULL COMMENT 'Comma-separated BamBuddy event types that can fire this watch',
  `status` varchar(16) NOT NULL COMMENT 'ARMED, FIRED, EXPIRED, CANCELLED',
  `rearm_count` int(11) NOT NULL DEFAULT 0 COMMENT '0 or 1; max 1 re-arm (Revision 6)',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `expires_at` timestamp NOT NULL COMMENT 'Current TTL deadline (UTC); rewritten on re-arm',
  `terminal_at` timestamp NULL DEFAULT NULL COMMENT 'When status left ARMED',
  `fired_notification_id` bigint(20) unsigned DEFAULT NULL COMMENT 'notifications.id produced on fire / overdue / expiry notice',
  `terminal_reason` varchar(255) DEFAULT NULL COMMENT 'Human-readable resolution note for support/debugging',
  PRIMARY KEY (`id`),
  KEY `bambu_watches_status_expires_IDX` (`status`,`expires_at`),
  KEY `bambu_watches_profile_status_IDX` (`profile_id`,`status`),
  KEY `bambu_watches_printer_status_IDX` (`printer_id`,`status`),
  CONSTRAINT `bambu_watches_profile_FK` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='One-shot Bambu print watches (EphemeralNotification); terminal rows are tombstones purged after retention.';
```

Notes:

- No unique constraint enforces "one ARMED watch per member+job": MariaDB
  cannot express a partial unique index, and terminal tombstones would
  collide with any `(profile_id, printer_id, queue_id)` unique key.
  Uniqueness of ARMED watches is enforced in the service inside the arm
  transaction (§8.2) — the same "check then insert" the codebase uses for
  dedupe keys, backstopped by the sweeper's/webhook's atomic claims.
- `trigger_events` for `OWN_JOB` = `print_complete,print_failed,print_stopped`;
  for `PRINTER_AVAILABLE` = the same three (they end the blocking job).
  Stored explicitly so a future event type doesn't require a migration.

### 3.2 `bambu_pending_offline_alerts` — the 5-minute offline hold

```sql
CREATE TABLE IF NOT EXISTS `bambu_pending_offline_alerts` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `printer_id` int(11) NOT NULL,
  `printer_name` varchar(128) NOT NULL,
  `offline_since` timestamp NOT NULL COMMENT 'Portal receipt time (UTC) of the first printer_offline webhook of this episode',
  `release_at` timestamp NOT NULL COMMENT 'offline_since + hold (default 5 min); sweeper acts at/after this',
  `status` varchar(16) NOT NULL COMMENT 'PENDING, SENT, SUPPRESSED',
  `resolved_at` timestamp NULL DEFAULT NULL,
  `notifications_created` int(11) DEFAULT NULL COMMENT 'Fan-out result when SENT (0 is valid: no subscribers)',
  PRIMARY KEY (`id`),
  KEY `bambu_offline_status_release_IDX` (`status`,`release_at`),
  KEY `bambu_offline_printer_IDX` (`printer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Held printer-offline committee alerts awaiting the still-offline re-check.';
```

**Why a dedicated table, not the notifications worker's 5-minute hold and
not the key/value store:** the worker hold (`worker.hold-seconds`) only
delays *sending* an already-created row — there is no hook to re-check and
suppress at release time, and a row created at webhook receipt would be sent
unconditionally. The hold here is a *pre-creation* hold: no `Notification`
exists until the re-check passes. The key/value store (`:service:keyvalue`)
is explicitly documented as "not for anything you query by field" — the
sweeper must query `status + release_at`, so it is the wrong tool. One row
per offline episode; the row's `id` is the episode identity used in dedupe
keys (§11.3).

### 3.3 `bambu_committee_subscriptions` — who gets offline alerts

```sql
CREATE TABLE IF NOT EXISTS `bambu_committee_subscriptions` (
  `profile_id` int(10) unsigned NOT NULL COMMENT 'The subscribing member profile',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`profile_id`),
  CONSTRAINT `bambu_committee_subs_profile_FK` FOREIGN KEY (`profile_id`) REFERENCES `profile` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Members subscribed to Bambu committee alerts (printer offline).';
```

A direct copy of the `committee_subscriptions` shape minus the committee id
(there is exactly one Bambu committee topic). A preference row cannot serve
as the subscriber list: `notification_preferences.enabled` defaults to
`true` when no row exists, so "subscribed" must be an explicit row in a
subscription table, as with both existing subscribe-button handlers.

---

## 4. Topics, templates, payloads (frozen contract)

Topic keys are added to `common-models` `TopicKeys` (the UI needs the
committee key for the subscribe button):

```kotlin
const val BAMBU_PRINT_FINISHED = "bambu.print.finished"
const val BAMBU_PRINT_ACTION_REQUIRED = "bambu.print.action-required"
const val BAMBU_PRINTER_AVAILABLE = "bambu.printer.available"
const val BAMBU_WATCH = "bambu.watch"
const val BAMBU_COMMITTEE_ALERTS = "bambu.committee-alerts"
```

(The brief's suggested committee key `bambu.committee-alerts` is adopted
verbatim; the dotted style is used for all five for consistency.)

| Topic object (`notifications/topics/`) | Key | `visibleInPreferences` | Templates (`notifications/templates/`) |
|---|---|---|---|
| `BambuPrintFinishedTopic` | `bambu.print.finished` | `false` — produced only with the click consent override, so a toggle would do nothing | `BambuPrintFinishedTemplate` (`bambu-print-finished`) |
| `BambuPrintActionRequiredTopic` | `bambu.print.action-required` | `false` — same reason | `BambuPrintActionRequiredTemplate` (`bambu-print-action-required`) |
| `BambuPrinterAvailableTopic` | `bambu.printer.available` | `false` — same reason | `BambuPrinterAvailableTemplate` (`bambu-printer-available`) |
| `BambuWatchTopic` | `bambu.watch` | `false` — lifecycle notices belong to a click, not a standing preference; a toggle would imply control over future watches that doesn't exist | `BambuWatchOverdueTemplate` (`bambu-watch-overdue`), `BambuWatchExpiredTemplate` (`bambu-watch-expired`) |
| `BambuCommitteeAlertsTopic` | `bambu.committee-alerts` | `true` | `BambuPrinterOfflineTemplate` (`bambu-printer-offline`) |

All topics: `allowInactiveMembers = false` (default). Each topic overrides
`combinedSubject(count)` in the existing style (e.g. `"$count 3D print updates"`).

**Channel: EMAIL only.** Every create passes `Channel.EMAIL`. No other
channel is wired — SMS/Discord/Discourse have only `MockNotifier`s.

**Payloads.** All payload data classes are `@Serializable`, live beside
their template (pattern: `SystemTestData`), and carry display-ready
strings. Times are formatted by the *producer* in `America/Chicago`
(e.g. `"Thu, Oct 22 at 3:42 PM CT"`) — templates stay dumb, matching
`TemplateSupport` conventions. Every payload includes `statusPageUrl` =
`https://members.dallasmakerspace.org/committees/3d-fab/printers`
(helper `bambuStatusUrl()` added to `TemplateSupport.kt` beside
`memberProfileUrl`). Job name is included in emails (Revision 4 decision 5).

| Template | Payload fields | Subject (single) |
|---|---|---|
| `bambu-print-finished` | `printerName`, `jobName`, `completedAtText`, `durationText?`, `filamentGramsText?`, `statusPageUrl` | `Your print finished on {printerName}` |
| `bambu-print-action-required` | `printerName`, `jobName`, `eventLabel` (`"failed"` / `"was stopped"`), `reason?`, `occurredAtText`, `statusPageUrl` | `Your print needs attention on {printerName}` |
| `bambu-printer-available` | `printerName`, `freedByJobName?`, `occurredAtText`, `statusPageUrl` | `{printerName} is available` |
| `bambu-watch-overdue` | `printerName`, `jobName?`, `scopeLabel`, `statusPageUrl` | `Your print on {printerName} is taking longer than expected` |
| `bambu-watch-expired` | `printerName`, `jobName?`, `scopeLabel`, `statusPageUrl` | `Please check your print on {printerName}` |
| `bambu-printer-offline` | `printerName`, `offlineSinceText`, `statusPageUrl` | `3D printer offline: {printerName}` |

Body copy rules (all templates): say what happened, which printer, what to
do next ("please collect your print and clear the bed" for finished —
never claim the bed is still occupied, per requirements §5), link to the
status page. HTML bodies escape every payload string with `escapeHtml`.
Each template also provides an inbox `card()` where it is a one-liner
(title = subject, body = first body line); `null` is acceptable for the
two watch templates in v1.

---

## 5. Click-consent vs. preference resolution (key contract)

How `NotificationService.create` resolves a create today
(`buildNotification`, in order): suppression → `SUPPRESSED`; inactive
member → `SKIPPED`; stored preference `enabled == false` → `SKIPPED`
("Disabled in notification preferences"); aggregation from preference
override or channel default → `IN_DIGEST` vs `QUEUED`. Note the default
when **no** preference row exists is *enabled* — so the silent-drop risk is
exactly one case: the member once explicitly disabled the topic (or a
future default change), then clicks "notify me" for a specific print and
reasonably expects that click to win. A one-shot click is newer, narrower
and more specific consent than a standing topic toggle.

**Change (the only change to the notifications platform core):**

- `CreateNotificationRequest` gains
  `val consentOverride: Boolean = false`.
- `NotificationService.createForMembers` gains a matching optional
  parameter `consentOverride: Boolean = false`, threaded to `createOne` /
  `insert`.
- In `buildNotification`, when `consentOverride` is true, the
  `!enabled → SKIPPED` branch is skipped. **Everything else is unchanged:**
  suppression still yields `SUPPRESSED`, inactive members still yield
  `SKIPPED`, and aggregation is still resolved from the member's
  preference — a member whose preference is *digest* still gets the
  finished/failed notice in their digest. That last point is required by
  frozen decision 9 / Revision 4.6: FAILED alerts are **not** exempt from
  the 5-minute hold/batching, per-topic aggregation (incl. digest) or the
  15/day immediate cap. The override defeats only the on/off drop, never
  the delivery-shaping rules.

**Who passes `true`:** `EphemeralNotificationService` for every
watch-produced create (finished, action-required, available, overdue,
expired-final). The click *is* the consent record; the watch row (owner,
`created_at`) is its audit trail.

**Who passes `false`:** the committee offline fan-out. Subscribing is
standing consent, and a subscriber who later disables
`bambu.committee-alerts` in Notification Preferences must actually stop
receiving it — the normal rules are the correct semantics there.

**Rejected alternative:** writing a preference row (`enabled = true`) at
click time. It would silently re-enable the topic for *all future* prints,
destroying the per-print consent model (Revision 5), and would race with
the member editing preferences in another tab.

---

## 6. Webhook receiver

### 6.1 Placement — a dedicated route, *not* the WebhookRouter

Verified constraints that force this:

1. `WebhookHandler.handleWebhook(body: String)` receives **only the body**
   — no headers — so a handler behind `WebhookRouter` cannot see the
   `Authorization` header BamBuddy sends. BamBuddy cannot send the
   `X-Webhook-Token` header the existing `notifications` webhook requires.
2. The generic `post("/webhook/*")` route in `Routing.kt` maps handler
   failure to HTTP 400 and **logs every request header** — routing
   BamBuddy through it would write the shared secret to the log on every
   event and would report errors for events we deliberately ignore.

**Design:** a dedicated route in `service` `plugins/Routing.kt`, registered
*before* `post("/webhook/*")`, outside `authenticate(...)` (BamBuddy has
no API key; the bearer secret is its credential):

```kotlin
post("/webhook/bambuddy") {
  val handler = AppComponentHolder.instance.getBambuddyWebhookHandler() // lazy, existing pattern
  val status = handler.handle(call.request.headers[HttpHeaders.Authorization], call.receiveText())
  call.respond(status) // plain status, no ApiResponse envelope — BamBuddy only reads the code
}
```

`BambuddyWebhookHandler.handle(authorizationHeader: String?, body: String):
HttpStatusCode` lives in the bambuddy module and contains all logic below,
so it is unit-testable without the Ktor test host. It does **not**
implement `WebhookHandler` and is **not** registered in `WebhookModule`.

### 6.2 Secret: config, comparison, failure mode

- `application.conf` under the existing `app.bambuddy` block:
  `webhook-secret = "dev-only-placeholder"` +
  `webhook-secret = ${?BAMBUDDY_WEBHOOK_SECRET}`; documented in
  `service/.env.template` beside `BAMBUDDY_BASE_URL`/`BAMBUDDY_API_KEY`.
- Resolution lives in ONE shared helper,
  `configuredBambuddyWebhookSecret(appConfig)` (bambuddy module,
  `BambuNotificationSupport.kt`, with the single
  `DEV_WEBHOOK_SECRET_PLACEHOLDER` constant): the handler calls it per
  request, and `Routing.kt`'s startup check calls it to warn once at boot
  ("BAMBUDDY_WEBHOOK_SECRET not set; /webhook/bambuddy will reject all
  calls"). The dev placeholder is rejected outside development; if the
  resolved secret is null the route responds **401 to everything**.
  Fail closed, never open.
- Comparison: accept the header value if it equals the secret **or**
  `Bearer <secret>` (BamBuddy sends its configured `auth_header` verbatim
  as the `Authorization` value; the setup doc tells the admin to configure
  `Bearer <secret>`, and accepting the bare value removes a whole class of
  misconfiguration). Compare with `MessageDigest.isEqual` over UTF-8 bytes
  (constant-time) after stripping at most one leading `"Bearer "`.
  Never log the header or the secret (see §15).

### 6.3 Payload model and validation

```kotlin
@Serializable
data class BambuddyWebhookPayload(
    val event: String,
    val printer: String? = null,        // printer NAME, not id
    val filename: String? = null,
    val title: String? = null,          // human text — logged at debug only, NEVER parsed
    val message: String? = null,        // human text — never parsed
    val timestamp: String? = null,      // naive local ISO from BamBuddy; receipt time is authoritative
    val reason: String? = null,
    val duration: String? = null,       // display string passthrough when present
    val filament_grams: Double? = null, // key name as sent by BamBuddy generic format
    val progress: Double? = null,
    val finish_photo_url: String? = null, // accepted and ignored in v1 (attach_photo is OFF)
)
```

Parsing uses `Json { ignoreUnknownKeys = true }` (the payload is flat but
carries extra variables; `image` — a potentially huge base64 string — is
*not* declared, so it is never decoded into a field; `attach_photo` is OFF
in the provider config regardless). Field-name spellings beyond
`event/printer/filename` must be re-verified against the live DMS BamBuddy
instance during implementation (the requirements panel's standing warning:
upstream main may be ahead of the DMS install). The handler must tolerate
any of the optional fields being absent without failing the event.

### 6.4 Response codes and work split

| Case | Code | Notes |
|---|---|---|
| Valid auth, handled event (incl. "no watch matched", "duplicate/offline already pending") | **200** | Business outcomes are never errors |
| Valid auth, unknown or uninteresting event (`print_start`, progress, queue events, …) | **200** | Ignored by design; log at info with event type only |
| Valid auth, known event but required field missing (e.g. no `printer` on a print event) | **200** | Log warn with event + missing field; nothing actionable for BamBuddy (it never retries) and an error code only pollutes its `last_error` |
| Malformed JSON body | **400** | The one client-error case: the payload is not BamBuddy-shaped at all |
| Missing/wrong `Authorization`; secret not configured | **401** | No body detail beyond "Unauthorized" |
| Feature fully off (§12) | **200** | Log "ignored, feature off" once per event |
| Body over ~256 KB (declared `Content-Length`, or more bytes than the cap when read; the read is bounded, so chunked bodies are covered) | **413** | Checked after auth, before the body is buffered |
| Unexpected internal failure (DB down, etc.), or any watch failing to fire (every correlated watch is still attempted first) | **500** | Deliberate: the event is lost either way (no retries), but 500 surfaces in BamBuddy's provider `last_error`, which an operator can see; a masked 200 would hide a portal outage |

**Inline vs deferred.** BamBuddy's POST is fire-and-forget with no stated
timeout, but the handler still returns promptly: inline work is auth →
parse → routing decision → for print events, the correlation lookup (§7,
two LAN reads) and watch firing (DB writes via `NotificationService.create`
— no email is sent inline; the worker owns sending) → for
`printer_offline`, one insert. Everything else — the offline fan-out, all
expiry work, all email delivery — is deferred to the sweeper / notification
worker. No `launch {}` fire-and-forget inside the handler: if correlation
fails because BamBuddy is unreachable, §7.4 defines the fallback inline,
so nothing is silently dropped by an unobserved coroutine.

---

## 7. Correlation (printer NAME + filename → the watched job)

### 7.1 What a watch already knows

Correlation is anchored on the **watch**, not on finding an owner from the
payload (the payload has neither). At arm time the watch stores
`printer_id`, `printer_name`, `queue_id` and `filename` (the queue item's
`archive_name`). The webhook therefore answers a narrow question: *did the
job this watch points at just end, in the way this event claims?*

### 7.2 `BambuCorrelationService` lookup

On a print event, perform **one** fresh lookup — deliberately bypassing
`BambuddyStatusService`'s 10 s fail-soft cache (a stale snapshot must not
decide a one-shot watch) — via `IBambuddyApiClient` directly:

1. `getPrinters()` → printer whose `name` equals the payload `printer`
   (exact match; if none, a *unique* case-insensitive match; zero or
   multiple → **no match**). → `printerId`.
2. `getQueue()` → rows for that `printerId`.
3. Candidate watches: `status = ARMED` and `printer_id = printerId` from
   `EphemeralNotificationsRepository`. For each candidate, verify:
   - `OWN_JOB`: its `queue_id` row in the fresh queue has
     `archive_name == payload.filename` (exact, trimmed) **and** a status
     consistent with the event: `print_complete → completed`,
     `print_failed → failed`, `print_stopped → failed | completed`
     (BamBuddy's queue vocabulary for a stopped job must be confirmed
     against the live instance; accept either terminal status — the
     *event type*, not the queue row, decides which template fires).
   - `PRINTER_AVAILABLE`: its blocking `queue_id` row is terminal
     (as above) for the same event — filename must also match that row's
     `archive_name`, since the event is about the blocking job ending.

### 7.3 The lingering-FINISH trap

`BambuddyStatusService` documents the trap this design must not re-create:
completed/failed queue rows are *permanent history* (every failure a
printer ever had sits at `position = 1`). Matching "the newest completed
row with this filename" would credit reprints and stale rows. This design
avoids it structurally: a watch fires only for **its own stored
`queue_id`**, and filename equality is a cross-check, not the selector.
Reprints of the same filename are distinct queue rows with distinct ids,
so two watches on two reprints never cross-fire. When several watches
legitimately match one event (an own-job watch and an available watch on
the same blocking job; two members' available watches on one printer),
**each fires independently** — they are different members' one-shot
consents, and §9's per-watch dedupe keeps them from colliding.

### 7.4 Failure and edge cases

| Case | Behaviour |
|---|---|
| Printer name not found / ambiguous | Log `correlation=no-printer`; 200; no notification |
| No ARMED watch matches | Log `correlation=no-watch`; 200. (Includes slicer-started ownerless jobs — correct: nobody clicked.) |
| Stored `queue_id` row has vanished from the queue (pruned) | Fallback: if **exactly one** ARMED watch on this printer has `filename == payload.filename`, fire it with `correlation=filename-only` logged; if more than one, fire none (ambiguous) and log |
| Queue row exists but still `printing`/`pending` (event/queue disagree, e.g. event raced ahead of queue state) | Do **not** fire; log `correlation=state-mismatch`. The watch stays ARMED — its TTL expiry check (§10) is the backstop that resolves the discrepancy |
| BamBuddy unreachable at receipt (either call throws) | The payload itself is first-hand evidence the event happened. Fire watches matching `printer_id + filename` from stored watch fields alone, log `correlation=unverified`. Losing a real finish notice is worse than firing on the sender's own event |
| Payload `filename` missing on a print event | Available watches on that printer cannot be filename-verified: fire an available watch only if its blocking queue row (fresh lookup) is terminal; own-job watches do not fire (cannot prove it's their job) — TTL backstop covers them. Log accordingly |

`printer_offline` needs no correlation: printer name → id as in step 1;
unknown printer → log + 200.

---

## 8. Watch creation

### 8.1 Service API (frozen)

All inside the existing `authenticate(ApiKeyAuthProvider.X_API_KEY)` block
alongside `bambuddyRoutes`, `authorize(Permission.MANAGE_MEMBERS)`. The
actor is **always** the `X-Username` header (set by the UI from the
session); any username in a body is ignored by construction — the request
DTO has no username field.

```
POST   /bambu/watches            → arm a watch
GET    /bambu/watches            → the actor's ARMED watches (own list)
DELETE /bambu/watches/{id}       → cancel (idempotent)
```

```kotlin
@Serializable data class CreateBambuWatchRequest(
    val scope: String,          // "OWN_JOB" | "PRINTER_AVAILABLE"
    val printerId: Int,
    val queueId: Int? = null,   // required for OWN_JOB; ignored for PRINTER_AVAILABLE (server resolves the blocking job)
)
@Serializable data class BambuWatchDTO(
    val id: Long, val scope: String, val printerId: Int, val printerName: String,
    val queueId: Int?, val jobName: String?, val status: String,
    val createdAt: String, val expiresAt: String, val rearmCount: Int,
)
```

Responses use `ApiResponse(Status.SUCCESS, …)` like the other
`/bambu/*` routes. Per-user feature-flag check (§12):
`FeatureFlagService.isEnabledForUsername(BAMBU_NOTIFICATIONS_ENABLED, actor)`
→ 403 when off. `BambuPrinterDetailDTO` is extended with
`currentQueueId: Int?` and `nextQueueId: Int?` (the queue ids behind
`currentJobName`/`nextJobName`, from the same rows `BambuddyStatusService`
already selects) so the UI can name the job a click refers to; this is an
additive, backward-compatible DTO change.

### 8.2 Arm-time validation (all server-side, against a fresh snapshot)

`EphemeralNotificationService.arm(actor, request)` takes a fresh
correlation-grade snapshot (`getPrinters` + per-printer `getPrinterStatus`
+ `getQueue`) rather than the 10 s page cache, then:

**OWN_JOB**

1. Printer exists → else 404 `Printer not found`.
2. Queue row `queueId` exists, belongs to `printerId` → else 404
   `That print is no longer in the queue`.
3. Row's `created_by_username == actor` → else 403
   `You can only watch your own prints`. (Case: usernames are compared
   exactly as stored; the status page surfaces the same value.)
4. Row status is `printing` or `pending` → if `completed`/`failed`,
   409 `That print has already finished`; other terminal states likewise 409.
5. Existing ARMED own-job watch by actor for `(printer_id, queue_id)` →
   return it with 200 (idempotent re-click; no duplicate, no TTL reset).
6. TTL from §8.3 (remaining for `printing`; estimate for `pending`).
   No usable estimate → 422 with the §8.3 message; nothing is armed.

**PRINTER_AVAILABLE**

1. Printer exists → else 404.
2. Effective state (`BambuddyStatusService.effectiveState` rules: null or
   `connected == false` ⇒ OFFLINE) must be a printing state
   (`RUNNING, PREPARE, SLICING, PAUSE`) with a `printing` queue row (the
   blocking job). Otherwise 409 with a state-specific message:
   IDLE → `This printer is already available`; FINISH →
   `The last print on this printer finished — check the status page`;
   FAILED → `This printer needs attention — check the status page`;
   OFFLINE → `This printer is offline`.
   *Rationale:* an available-watch promises "tell me when this busy
   printer frees up". Arming one on an already-free (or down) printer
   would create a watch that can only ever end in a check-yourself notice.
3. Existing ARMED available watch by actor for `(printer_id, blocking
   queue_id)` → return it (idempotent). A member may hold available
   watches on several printers, and a new blocking job later is a new
   watch (new `queue_id`).
4. TTL from the blocking job's remaining time (§8.3).

There is **no per-member cap** on ARMED watches (Revision 5.2) and **no
auto-arm** anywhere (Revision 5.1): these two endpoints, called from the
two buttons, are the only creation paths.

### 8.3 TTL — exact function and fallback chain

All times in **minutes**. Computed at arm time and again at re-arm time
by the single pure function in `BambuTtl`:

```kotlin
/** grace = clamp(10% of remaining, 60, 180); TTL = remaining + grace. */
fun ttlMinutes(remainingMinutes: Int): Int {
  require(remainingMinutes > 0)
  val grace = ((remainingMinutes + 9) / 10).coerceIn(60, 180) // ceil(10%)
  return remainingMinutes + grace
}
```

Check against Revision 3's worked examples: 20 → 80 (1 h 20 m); 300 → 360
(6 h); 600 → 660 (11 h); 1200 → 1320 (22 h); 2400 → 2580 (43 h). ✔

`expires_at = created_at + ttlMinutes(remaining)`, stored in UTC.

**Remaining-time source chain** (first positive value wins):

1. Printer status `remaining_time` (minutes) for the watched/blocking job
   when the job is `printing` — this is `BambuPrinterStatus.remainingTimeMinutes`.
   Note its documented quirk: it can be `0`/null early in a print.
2. The queue row's slicer estimate: `ceil(print_time_seconds / 60)`
   (`BambuQueueItem.printTimeSeconds` — the archive/slicer estimate the
   requirements' fallback refers to).

   A `pending` own-job watch does not start until the queue ahead of it
   is done, so its remaining time is `BambuTtl.pendingFinishMinutes`: the
   job running on that printer (live remaining, else its estimate) + the
   estimates of pending rows ahead of it (by queue `position`, then id) +
   its own estimate. Rows without an estimate count as 0; a missing own
   estimate is still the 422 below.
3. Neither exists or both are ≤ 0 → **reject the arm** (422):
   *"We can't estimate how long this print will run yet, so we can't set
   a reminder for it. Try again in a moment, once printing is under way."*
   Justification: every alternative invents a number. A made-up default
   either expires mid-print (producing a spurious "check yourself" and
   consuming the member's one shot) or strands the watch for hours. The
   rejection is transient — MQTT remaining time and the queue estimate
   appear within moments for real prints — and the button remains on the
   page for a retry. A non-positive TTL is never armed, under any path.

There is no additional hard TTL cap: the grace cap (180 min) plus the
job's own remaining time bound the watch, and Revision 3's examples
already accept 43 h.

---

## 9. Lifecycle state machine and race safety

States: `ARMED → FIRED | EXPIRED | CANCELLED`; `ARMED → ARMED` (re-arm,
`rearm_count` 0→1, new `expires_at`). Terminal rows are tombstones
(§10.4). Four actors can touch one watch concurrently: the webhook
(fire), the sweeper (fire / re-arm / expire), the member (cancel), and a
duplicate click (arm — neutralised by §8.2's idempotent return).

**Claim discipline** — mirrors `NotificationsRepository.claim` (conditional
update, "only one worker sends"): every transition is a single conditional
UPDATE that succeeds only from the expected prior state, e.g.

```sql
UPDATE bambu_ephemeral_notifications
SET status='FIRED', terminal_at=:now, fired_notification_id=:notifId
WHERE id=:id AND status='ARMED'
```

Repository methods: `claimForFiring(id)`, `claimForExpiry(id)`,
`claimForRearm(id, newExpiresAt)` (additionally `AND rearm_count = 0`),
`claimForCancel(id, profileId)` — each returns whether it won (rows
updated > 0). Losing a claim is always a silent no-op + debug log; it is
the normal outcome of a race, never an error.

**Fire transaction — DEVIATION RESOLVED (post-review fix pass):** the
original text of this section claimed the claim and the notification
creation happen in one Exposed `suspendTransaction`, with nested calls
joining the ambient transaction. That is **false for the pinned Exposed
0.41.1**: verified against the dependency's bytecode
(`org.jetbrains.exposed.sql.transactions.experimental.SuspendedKt`),
`newSuspendedTransaction` passes a null current transaction into
`withTransactionScope`, so every nested `newSuspendedTransaction` starts
and commits its own transaction — only the `Transaction.suspendedTransaction`
extension (current transaction passed explicitly) reuses one. A single
genuine transaction would have required re-plumbing every repository and
`NotificationService` call onto that extension, so the implemented
guarantee is instead **compensation + reconciliation**, with the same
end-state invariant (no terminal tombstone without a notification):

1. Claim first (conditional update, as above). If the claim is lost,
   nothing is created.
2. If `NotificationService.create(...)` (or linking its id) fails after
   the claim committed, the service **reverts the claim to ARMED**
   (`revertTerminalClaim` / `revertRearm`, conditional on
   `fired_notification_id IS NULL`) and rethrows — the watch is retried
   by the webhook/sweeper instead of being consumed with nothing sent.
3. If the process *dies* between claim and create, the sweeper's
   `processDueWatches` pass first runs
   `EphemeralNotificationService.reconcileTerminalWatches()`: FIRED /
   EXPIRED tombstones with `fired_notification_id IS NULL` get their
   notice recreated and linked. Recreation is exactly-once because
   `NotificationService.create` is dedupe-key idempotent (a repeat key
   returns the existing row; the §14 per-watch key is the key used), and
   this is covered by a real-H2 integration test
   (`EphemeralNotificationStrandingTest`).

Because the fix removes the local suspend-capable transaction wrapper
rather than keeping it, no wrapper was lifted into `:service:core`
(the architect's conditional suggestion); `SuspendTransaction.kt` is
unchanged.

**Cancel:** `claimForCancel` scoped by owner profile id — a member can
never cancel another member's watch (wrong owner ⇒ 0 rows ⇒ respond as
already-terminal/404 without revealing which). Cancelling a terminal
watch returns 200 with its current status (idempotent DELETE).

**Expiry processing never trusts a pre-check:** the sweeper does its
BamBuddy check *first* (read-only), then attempts the specific claim for
the outcome it chose. If the claim fails (webhook fired or member
cancelled meanwhile), the chosen outcome is discarded. This ordering
means a finish webhook that lands during the expiry check always wins
over an expiry notice.

---

## 10. Expiry sweeper

### 10.1 Scheduling: events and deadlines, no polling

`BambuNotificationSweeper` (`@Singleton`, bambuddy module) has the passes
`processDueWatches()`, `releaseDueOfflineAlerts()`, `purgeTombstones()` and
`reconcile()`; each pass returns a `SweepResult` (log summary + failure
count). `BambuNotificationScheduler` drives them; the app launches it once
in `Routing.kt`, gated by `app.bambuddy.notifications.sweeper.enabled`
(default true). There is no fixed interval and no idle DB traffic:

- Loop: run the passes, then read the next deadline
  (`BambuScheduleRepository.nextDue()`: earliest ARMED `expires_at` /
  PENDING `release_at`, one transaction) and sleep until
  `min(that, next daily purge)` or a wake.
- `BambuWakeSignal.wake()` (conflated) is called after a watch is created
  (not on an idempotent re-click), after a re-arm or a printer-available
  retarget, and after a new offline episode is inserted. Producers hold the
  signal rather than the scheduler, so there is no dependency cycle with the
  sweeper.
- Feature fully off (§12): nothing is queried; the loop only waits for a
  wake (the first event after the flag is turned on).
- Startup: one probe read plus reconciliation. If the probe fails (e.g. the
  migration was not applied) one warning is logged and the loop waits for a
  wake; there is no retry loop.
- Failures: a pass with failed items (or a failing pass after startup) backs
  off 60 s, doubling to 15 min, and reconciles stranded tombstones. A
  deadline that stays in the past without any failure is treated the same
  after 5 back-to-back runs, so it can never spin.
- Reconciliation runs at startup and after any pass with failures; the purge
  runs once a day.
- Several instances (blue/green) may run at once; the atomic claims decide
  who handles each item. There is no cross-instance signalling, so an
  instance only learns of another instance's earlier deadline when its own
  sleep ends (the creating instance wakes itself).
- Batch limit 50 watches / 50 pending alerts per pass (oldest first), so a
  backlog drains over successive passes instead of one long transaction.

### 10.2 Expiry decision table (per due ARMED watch)

One check = `getPrinterStatus(printerId)` + `getQueue()` via
`IBambuddyApiClient` (fresh, uncached; the third and final category of
permitted BamBuddy read — receipt correlation, expiry check, offline
re-check; there are no other reads and no fleet loop).

| Check outcome | Action |
|---|---|
| Watched/blocking queue row now `completed` (webhook was lost) | Fire the **real** notice (finished template for OWN_JOB; available template for PRINTER_AVAILABLE), claim `FIRED`, dedupe as §9. TTL did its backstop job |
| Row now `failed` | Fire real action-required (OWN_JOB) / available (PRINTER_AVAILABLE), claim `FIRED` |
| Row still `printing`/`pending`, or printer effective state in `RUNNING/PREPARE/SLICING/PAUSE` with the watched filename as `currentPrint` | **Overdue path:** create the "taking longer than expected — please check" notice (`bambu-watch-overdue`), then, if `rearm_count == 0`, re-arm once: fresh remaining via §8.3 chain → new `expires_at`. If no fresh estimate can be computed, do not re-arm — fall through to expire-for-real below (the overdue notice just sent doubles as the member's prompt; the terminal notice is still sent so the watch never ends silently) |
| Re-armed watch (`rearm_count == 1`) expires and job still not terminal | **Expire for real:** create `bambu-watch-expired` ("we couldn't confirm your print finished — please check the printer / status page"), claim `EXPIRED`. No second re-arm, ever |
| Queue row gone and printer not printing the watched filename (job removed/replaced), or printer OFFLINE | Create `bambu-watch-expired`, claim `EXPIRED`, no re-arm |
| BamBuddy unreachable (exception / null status *and* queue failure) | Create `bambu-watch-expired`, claim `EXPIRED`, no re-arm (frozen decision 5) |

For PRINTER_AVAILABLE the "real notice" at expiry also fires when the
printer is simply free at check time (effective state IDLE, or FINISH
with no new job printing — the blocking job ended while webhooks were
lost): the member asked "tell me when it's free", and at the check, it
is. If a *different* job is now printing, the printer is not available:
overdue/re-arm path, recomputing TTL from the new blocking job (the
watch's stored `queue_id`/`filename` are updated to the new blocking job
on re-arm in that case, so a later webhook can still correlate).

Overdue and expired-final notices use dedupe keys
`bambu:watch:{watchId}:overdue` and `bambu:watch:{watchId}:expired`
(§14 note on dedupe shape) so a sweeper retry can never double-send them.

**Printer-available watches are never announced while work is queued.**
Before a printer-available watch fires (webhook path and the expiry
decision above), the queue is checked for a `pending` row on that printer.
If one exists the watch is retargeted instead (`claimForRetarget`: queue id /
filename / job name of the next pending row by position, new `expires_at`
from `BambuTtl`, stays ARMED, `rearm_count` untouched) and the scheduler is
woken; no notice is sent. A failed queue read skips the check and fires as
before. The "available" copy also says the last print may still need to be
cleared from the bed.

### 10.3 Offline-hold release

Same loop, `releaseDueOfflineAlerts()`: rows `PENDING` with
`release_at <= now`, oldest first. Per row: **first** claim the episode
with a conditional update to the transient `PROCESSING` status
(`claimProcessing`, `PENDING → PROCESSING`); only the claim winner
proceeds — this was amended in the post-review fix pass, because
claiming only at *resolution* time let an overlapping sweeper instance
fan out for an episode another instance had already resolved as
SUPPRESSED. The winner then does the one re-check:
`getPrinterStatus(printer_id)` → effective state, and resolves
`PROCESSING → SENT | SUPPRESSED`; a failed attempt returns the claim to
`PENDING` for the next pass. The email states the hold that episode used
(`release_at - offline_since`). (`status` is a string column, so
`PROCESSING` needed no DDL change; the migration and `create.sql`
comments list it.)

- Effective state ≠ OFFLINE (printer reachable again) → conditional
  `UPDATE … SET status='SUPPRESSED' WHERE id AND status='PENDING'`;
  log `offline suppressed (back online)`. No notification is ever created.
- Effective state = OFFLINE, **or** status null / call throws (BamBuddy
  cannot confirm recovery — `BambuddyStatusService` folds both to
  OFFLINE, and this design follows it) → fan out (§11.3), then
  conditional update to `SENT` with `notifications_created` recorded.
  If the update loses a race (duplicate sweeper during a deploy), the
  per-member dedupe keys make the fan-out itself harmless.

### 10.4 Tombstone purge

Terminal watches (`FIRED/EXPIRED/CANCELLED` with `terminal_at` older than
retention) and resolved pending-offline rows (`SENT/SUPPRESSED` with
`resolved_at` older than retention) are hard-deleted by the sweeper.
Retention = site config `bambu-notifications.tombstone-retention-days`
(IntType, default **7**) so "why didn't I get notified?" stays answerable
for a week without a redeploy. ARMED rows and PENDING rows are never
purged.

---

## 11. Committee printer-offline flow

The hold length is `bambu-notifications.offline-hold-minutes`; the
subscribe-button copy (`BambuCommitteeAlertsSubscriptionHandler.copy`, now
`suspend`) and the status-page hint read it, so no UI text hard-codes "5".

### 11.1 Receipt

`printer_offline` webhook → resolve printer → if a `PENDING` row already
exists for that printer, do nothing (same episode; BamBuddy may repeat
the event) → else insert a row with `offline_since = receipt time (UTC)`,
`release_at = offline_since + hold`. Hold = site config
`bambu-notifications.offline-hold-minutes` (IntType, default **5**;
Revision 4.1 — changing it later is a config edit, not a deploy).
No notification is created at receipt.

### 11.2 Subscription (standing, opt-in)

`BambuCommitteeAlertsSubscriptionHandler : TopicSubscriptionHandler`:

- `topic = BambuCommitteeAlertsTopic`, `featureFlag =
  ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED`,
  `argNames = emptySet()` (the button takes no arguments — one topic, all
  printers).
- `isSubscribed/subscribe/unsubscribe` delegate to
  `BambuCommitteeSubscriptionsRepository` (idempotent insert/delete,
  pattern: `CommitteeSubscriptionsRepository`).
- `copy(...)`: subscribe "Notify me", subscribed "Notifying",
  unsubscribe "Stop notifying"; hint "Get notified when a 3D printer
  goes offline"; toasts in the established style; a `subscribeConfirm`
  explaining alerts go to all subscribers when a printer stays offline
  ~5 minutes.
- Registered `@IntoMap @StringKey(TopicKeys.BAMBU_COMMITTEE_ALERTS)` in
  the app `BambuddyModule` (§2). The existing
  `GET/POST/DELETE /subscriptions/{topicKey}` routes and the UI
  `/backend-api` proxy (`subscriptions/*` is forwarded) then work
  unchanged; the UI renders the shared subscribe button (§13.3).
- Recipients at fan-out time = `getSubscribers()` (usernames), resolved
  fresh at release — a member who unsubscribes during the 5-minute hold
  gets nothing; one who subscribes during the hold *does* get it. Both
  are the least surprising readings of a standing subscription.

### 11.3 Fan-out

```kotlin
notificationService.createForMembers(
    memberUsernames = subscribers,
    topic = BambuCommitteeAlertsTopic,
    templateName = "bambu-printer-offline",
    payload = json(BambuPrinterOfflineData(printerName, offlineSinceText, statusPageUrl)),
    channel = Channel.EMAIL,
    dedupeKey = { username -> "bambu:offline:{printerId}:{pendingAlertId}:$username" },
    consentOverride = false, // §5
)
```

Properties: **one offline episode = one alert per subscriber** (dedupe
key + the single PENDING row); a multi-printer LAN incident inserts one
row per printer and, because each member's rows are created within the
same worker hold window, the existing worker batches them into one email
per member. Suppression/inactive/preference rules apply normally inside
`NotificationService` — a subscriber who disabled the topic gets a
`SKIPPED` row, which is the platform's audit answer, not an error.
Zero subscribers → fan-out is a no-op, row still resolves to `SENT` with
`notifications_created = 0`.

**Blind spot (accepted, Revision 3):** if BamBuddy itself is down, no
webhook arrives and nothing can fire. This design does not detect
BamBuddy outages.

---

## 12. Feature flags and configuration (frozen)

### 12.1 Site config (`ConfigRegistry.kt`, all added to `ALL`)

| Key | Type | Default | Gates |
|---|---|---|---|
| `bambu-notifications.enabled` → `BAMBU_NOTIFICATIONS_ENABLED` | `FeatureFlagType` | `FeatureFlagValue(enabled = false)`, category `"3D Fab"` | Per-user (via `FeatureFlagService.isEnabledForUsername` / UI `isFeatureEnabled`): watch buttons, watch endpoints, committee subscribe button/handler. Pilots via `enabledUsernames`/`enabledGroupNames` like `BAMBU_STATUS_ENABLED` |
| `bambu-notifications.offline-hold-minutes` → `BAMBU_NOTIFICATIONS_OFFLINE_HOLD_MINUTES` | `IntType` | `5` | §11.1 hold |
| `bambu-notifications.tombstone-retention-days` → `BAMBU_NOTIFICATIONS_TOMBSTONE_RETENTION_DAYS` | `IntType` | `7` | §10.4 purge |

The existing `bambu-status.enabled` flag is **not** reused for
notifications: the status page is already rolled out, and notifications
must pilot independently (a member can be trusted with a status page and
not yet with email-sending watches). The UI page requires
`bambu-status.enabled` to render at all, then additionally checks
`bambu-notifications.enabled` for every notification affordance.

**Receiver gating:** webhooks carry no member identity, so the per-user
flag cannot gate them. The receiver processes events when the feature is
in *any* rollout state — `ConfigOverrideService.get(BAMBU_NOTIFICATIONS_ENABLED)`
has `enabled == true` **or** a non-empty pilot list (pilot members' watches
must be fireable). Fully off → 200 + log, nothing else (the shipped default is a pilot, not fully off: see §12.2).
This is checked per event (cheap; site config is cached) so flipping the
flag stops processing without a restart. The sweeper applies the same
check per pass.

### 12.2 `application.conf` / env (service)

```hocon
app.bambuddy {
  webhook-secret = "dev-only-placeholder"
  webhook-secret = ${?BAMBUDDY_WEBHOOK_SECRET}
  notifications {
    sweeper.enabled = true          # ${?BAMBU_NOTIFICATIONS_SWEEPER_ENABLED}
  }
}
```

`.env.template` gains `BAMBUDDY_WEBHOOK_SECRET` (placeholder
`your-bambuddy-webhook-secret-here`, rejected like unset in every mode) and
`BAMBU_NOTIFICATIONS_SWEEPER_ENABLED` with comments. The old
`BAMBU_NOTIFICATIONS_SWEEPER_INTERVAL_MS` is gone (§10.1). No UI-side
secrets: the UI never sees the BamBuddy secret.

`bambu-notifications.enabled` ships as a pilot (not fully off): enabled for
`mandarlimaye` and the `IT Early Access` group, off for everyone else.
Receiver and scheduler therefore run in that state (any-rollout gating).

---

## 13. UI design (`:ui`, Kotlin 1.9.23 / Ktor 2.3.5 side)

### 13.1 What the status page needs from the service

- `BambuPrinterStatusHandler` already loads printers via
  `MemberServiceClient.getBambuPrinters`. It additionally loads, only
  when `bambu-notifications.enabled` is on for the viewer:
  (a) the viewer's ARMED watches via a new
  `MemberServiceClient.getBambuWatches(sessionId, username)`
  (fail-soft → empty list, buttons render un-armed), and
  (b) the committee subscription status via the existing
  `getSubscriptionStatus(TopicKeys.BAMBU_COMMITTEE_ALERTS, emptyMap(), …)`.
- `BambuStatusViewModel.buildPrinterViewModel` gains: `currentQueueId`,
  `nextQueueId` (passthrough), `isOwnCurrentJob`
  (`printedByUsername == viewerUsername`), `isOwnNextJob`
  (next job's owner — **requires** the service DTO to also expose
  `nextPrintedByUsername`; `BambuddyStatusService` already computes the
  pending row it takes `nextJobName` from, so this is one more field on
  `BambuPrinterDetailDTO`), `currentJobWatchId` / `nextJobWatchId` /
  `availableWatchId` (matched from the viewer's watch list by
  printer+queue), and `notificationsEnabled` (page-level model entry).

### 13.2 Buttons and endpoints

New dedicated handlers in `server/bambustatus/` (pattern:
`BambuCameraApiHandler` — `AuthenticatedHandler`, flag check first),
registered in `BambuStatusModule` + `RouteFactory`:

| UI route | Handler | Service call (new `MemberServiceClient` methods, `X-Username` from session) |
|---|---|---|
| `POST /bambu-api/watches` | `BambuWatchApiHandler` | `POST /bambu/watches` |
| `DELETE /bambu-api/watches/{id}` | same | `DELETE /bambu/watches/{id}` |
| (`GET` list is server-rendered into the page; no UI GET endpoint in v1) | — | `GET /bambu/watches` via client |

Dedicated handlers are used instead of the generic `/backend-api` proxy
for the same reason the camera proxy exists: the service endpoints act on
`X-Username`, and the handler derives it from the session, checks the
flag, and can translate service errors (403/404/409/422 + message) into
JSON the button JS shows verbatim — through the generic proxy every error
would collapse to a message-less 500 (documented behaviour for
`/subscriptions/*`). Request bodies are forwarded as parsed JSON maps
(pattern: `BackendApiHandler.receiveJsonBody`).

Template (`bambu-printer-status.html`) + a small page script
(`bambu-notifications.js`, loaded after `app.js`; template keeps the
required Bootstrap/`app.js`/`dms-page-loader.js` tail):

- **Own-job button** next to the current-job block (and the "Next up"
  line) when `isOwnCurrentJob`/`isOwnNextJob`: bell button labelled
  "Notify me when this finishes"; when a watch exists, filled state
  "Watching · Cancel" (click = DELETE). POST body:
  `{scope:"OWN_JOB", printerId, queueId}`.
- **Printer-available button** on each card whose state is a printing
  state and which has no own-job button for the viewer: "Notify me when
  free" / armed state as above. POST body:
  `{scope:"PRINTER_AVAILABLE", printerId}`.
- Errors from the service (409/422 messages from §8.2) are shown as a
  toast with the service's message — they are written to be member-facing.
- Buttons render only when `notificationsEnabled`; no button ever
  renders for another member's print.

### 13.3 Committee subscribe button

In the status page header card, when `notificationsEnabled`: render the
shared fragment
`th:replace="~{fragments/subscribe-button :: button(${model})}"` with
`subscriptionButtonModel(status, …)` for topic
`bambu.committee-alerts` and load `/static/js/dms-subscribe-button.js`
(existing machinery, unchanged). If status is null (flag off / call
failed) render nothing — the established behaviour.

---

## 14. Dedupe keys (with one documented deviation)

`notifications.dedupe_key` is **globally unique across all members**.
The brief's key shape `bambu:{printerId}:{jobId}:{eventType}` is therefore
used as the *prefix*, with the producing watch appended for watch-fired
notifications:

- Watch-fired: `bambu:{printerId}:{jobId}:{eventType}:{watchId}` —
  without the suffix, an own-job watch and another member's
  printer-available watch ending on the same job+event (a normal case,
  §7.3) would collide: the second member's create would return the first
  member's row and they would never be notified. The suffix preserves
  the key's replay-safety per watch (one watch ⇒ at most one delivered
  notification, even if fire is attempted twice) while keeping the
  specified shape greppable.
- Overdue: `bambu:watch:{watchId}:overdue` · Expired-final:
  `bambu:watch:{watchId}:expired`.
- Committee offline: `bambu:offline:{printerId}:{pendingAlertId}:{username}`
  (per-member by construction, via `createForMembers`' key lambda).

`eventType` is the BamBuddy event string (`print_complete`, …); for
expiry-check fires it is the *inferred* terminal event
(`print_complete`/`print_failed`) so a lost-webhook fire and a late
duplicate webhook for the same job share a key prefix and the watch
claim (§9) plus this key together make double-delivery impossible.

---

## 15. AuthN/Z, logging, audit

### 15.1 Authorization matrix

| Action | Who | Enforced where |
|---|---|---|
| Arm own-job watch | The job's owner only (`created_by_username == X-Username`) | Service `arm` (§8.2) |
| Arm available watch | Any member who passes the per-user flag check | Service `arm` |
| Cancel / list watches | Owner only (queries scoped by actor's profile id) | Service routes |
| Subscribe/unsubscribe committee alerts | Self only, via existing `/subscriptions/*` (`X-Username`) | `TopicSubscriptionService` + handler flag |
| Receive committee alerts | Subscribers at fan-out time, through normal preference/suppression/inactive rules | `NotificationService` |
| POST webhook | Holder of the BamBuddy secret only | §6.2 |
| See buttons | Session member with `bambu-status.enabled` (page) **and** `bambu-notifications.enabled` (buttons) | UI handlers |

Identity always comes from session/`X-Username`, never from a request
body — the create DTO has no identity field at all.

### 15.2 Logging (never the secret, never the full payload)

Rule: the `Authorization` header value and the configured secret are
never logged, at any level, by any component. The generic webhook route's
log-all-headers line is one more reason BamBuddy traffic must not pass
through it (§6.1). The raw payload is not logged (it can carry a base64
`image`); log lines carry only the fields below.

- Receiver: `bambu webhook event={event} printer={printerName}
  correlation={fired|no-watch|no-printer|filename-only|unverified|state-mismatch}
  watchesFired={n}`; auth failures at warn with source IP only;
  ignored events at info; malformed JSON at warn with body length only.
- Watch lifecycle: create (`watch={id} member={username} scope printer
  queue expiresAt rearm=0`), fire, overdue, re-arm, expire, cancel —
  one info line each, with the produced notification id when one exists.
- Offline: hold inserted (`printer episode={pendingId}`), suppressed
  (back online), released (`subscribers={n} created={m}`).
- Sweeper passes log a summary line only when they did work
  (pattern: `NotificationWorker`).

The audit trail for "why (not) notified" is durable, not just logs: the
watch tombstone (status, `terminal_reason`, `fired_notification_id`), the
pending-offline row, and the `Notification` row itself (incl. `SKIPPED` /
`SUPPRESSED` rows with reasons) answer it for 7 days / permanently
respectively.

---

## 16. Error-handling summary

| Surface | Failure | Response |
|---|---|---|
| Watch arm | printer/queue missing | 404, member-facing message |
| | not your print | 403 |
| | print already finished / printer not busy (available) | 409 + state-specific message |
| | no time estimate | 422 + retry-in-a-moment message (§8.3) |
| | flag off for member | 403 |
| | duplicate click | 200 + existing watch (not an error) |
| Watch cancel | not owner / unknown id | 404 (no existence leak); terminal watch → 200 + status |
| Webhook | bad auth / malformed JSON / internal | 401 / 400 / 500 (§6.4); all business outcomes 200 |
| Sweeper | BamBuddy down during a check | That watch follows the unreachable row of §10.2; the pass continues with the next watch |
| Fan-out | one member's create fails | Counted in `FanOutResult.failed`, logged by `NotificationService`; others unaffected |

---

## 17. Rollout and verification checklist

1. Apply `service/docs/sql/bambu-notifications-migration.sql` (and the
   change is also in `create.sql` for fresh installs).
2. Set `BAMBUDDY_WEBHOOK_SECRET` in the service environment (long random
   value); deploy service, then UI.
3. **Verify against the live DMS BamBuddy instance before enabling**
   (upstream may differ): generic payload field names for filament/duration,
   the queue status string for a stopped job, and that the DMS install's
   provider supports all four toggles.
4. Configure the BamBuddy provider per `bambuddy-setup.md`.
5. Pilot: set `bambu-notifications.enabled` with
   `enabledUsernames = [a tester]`; arm a watch on a short print; confirm
   finish email, an expiry-path email (arm, then block the webhook by
   pausing the provider), and an offline alert (unplug a printer > 5 min).
6. Open the flag (`enabled = true`) when the pilot is clean. Kill switch:
   set the flag fully off (stops buttons, endpoints, receiver processing
   and sweeper passes; ARMED watches simply wait) or unset
   `BAMBUDDY_WEBHOOK_SECRET` (receiver 401s; watches fall back to the
   expiry path).

---

## 18. Test strategy

Frameworks/patterns: JUnit 4 + `kotlin.test` + Mockito-Kotlin +
`runBlocking`, as in `BambuddyStatusServiceTest` and the notifications
tests (fakes/mocks for `IBambuddyApiClient`, repositories and
`NotificationService`; repository tests follow
`NotificationsRepositoryTest`'s in-memory style where the existing tests
do so). No test needs a live BamBuddy.

| Suite (Gradle task) | Coverage |
|---|---|
| `BambuTtlTest` (`:service:bambuddy:test`) | All Revision 3 worked examples; boundaries: remaining 1, 599/600/601 (grace floor edge), 1799/1800/1801 (cap edge), very long (10 000); `require` rejects 0/negative |
| `EphemeralNotificationServiceTest` (`:service:bambuddy:test`) | Arm validation matrix (§8.2) incl. owner mismatch 403, finished job 409, available-on-IDLE 409, idempotent re-click returns same watch, TTL fallback (status remaining → queue estimate → 422), pending-job arm uses estimate |
| `BambuCorrelationServiceTest` (`:service:bambuddy:test`) | Exact queue-id match fires; same-filename reprint (different queue id) does **not** fire; lingering old completed row ignored; filename-only fallback fires on exactly-one, refuses on two; unreachable → unverified fire; state-mismatch → no fire |
| `BambuddyWebhookHandlerTest` (`:service:bambuddy:test`) | Auth: exact secret, `Bearer ` form, wrong/missing → 401, unconfigured secret → 401; malformed JSON → 400; unknown event → 200 no-op; missing printer on print event → 200; offline inserts one PENDING row, duplicate offline event doesn't create a second |
| `BambuNotificationSweeperTest` (`:service:bambuddy:test`) | Expiry table (§10.2) row by row: lost-webhook fire, overdue + re-arm once (new TTL from fresh remaining), re-armed expiry → final notice + EXPIRED, unreachable → expired notice, no second re-arm; claim-loss (watch fired mid-check) → no notice; purge deletes only old tombstones |
| Offline release (`BambuNotificationSweeperTest`) + repository claims (`BambuRepositoriesTest`) (`:service:bambuddy:test`) | Release while still offline → fan-out with per-member dedupe keys; back online → SUPPRESSED, zero creates; re-check throws → fan-out (folds to OFFLINE); zero subscribers → SENT with 0; PROCESSING claim resolves exactly once; a second episode inserts after the first resolves. (There is no separate `PendingOfflineAlertsTest` suite.) |
| `BambuCommitteeAlertsSubscriptionHandlerTest` (`:service:bambuddy:test`) | Subscribe/unsubscribe idempotent; status reflects repository; copy fields present |
| Template tests (`:service:notifications:test`) | One per new template (pattern: `CommitteeClassTemplateTest`): subject/body contain printer + job name, HTML escapes a hostile job name, `TemplateRegistryTest` picks up all five topics |
| `NotificationServiceTest` additions (`:service:notifications:test`) | `consentOverride = true`: explicit `enabled=false` preference still creates QUEUED/IN_DIGEST per aggregation; suppression still SUPPRESSED; inactive still SKIPPED; default (false) behaviour unchanged |
| UI tests (`:ui:test`) | `BambuStatusViewModelTest` additions: own/next/available watch state mapping, buttons hidden when flag off or job not owned; handler flag gate (pattern: existing bambustatus tests) |

**Toolchain caveat:** this VM has no JDK. The implementer will attempt a
Temurin 21 download (repo requires JDK 21); tests are designed to run via
`./gradlew :service:bambuddy:test :service:notifications:test :ui:test`
(and `./gradlew test` for the full suite) once a JDK exists. If no JDK can
be obtained, the implementation phase must say so plainly rather than
claiming tests pass — the design does not depend on any test-only
behaviour.

---

## 19. Frozen contracts — index for the follow-on build phase

Everything a builder needs without re-deciding:

- **Tables:** §3 DDL (`bambu_ephemeral_notifications`,
  `bambu_pending_offline_alerts`, `bambu_committee_subscriptions`) +
  migration file path.
- **Routes:** `POST /webhook/bambuddy` (§6, codes in §6.4);
  `POST/GET /bambu/watches`, `DELETE /bambu/watches/{id}` (§8.1 DTOs);
  UI `POST /bambu-api/watches`, `DELETE /bambu-api/watches/{id}` (§13.2);
  committee subscription via existing `/subscriptions/bambu.committee-alerts`
  with no args.
- **Topics/templates/payloads:** §4 table (keys in `TopicKeys`).
- **Dedupe keys:** §14. **TTL:** §8.3 function + fallback chain.
- **Config:** §12.1 site keys, §12.2 `application.conf`/env keys.
- **Consent semantics:** §5. **State machine + claims:** §9.
  **Expiry table:** §10.2. **Offline flow:** §11.
- **BamBuddy provider settings:** `bambuddy-setup.md`.

## 20. Out of scope for v1 (explicit non-goals)

No polling anywhere; no committee FAILED/stall/escalation alerts; no
printer-back-online committee notice; no BamBuddy-outage detection; no
quiet hours; no non-email channels; no general own-job opt-in or
auto-arming; no per-member watch cap; no notification side-effects on
prints (never clear/requeue/power anything); no finish-photo in emails
(`finish_photo_url` accepted and ignored); no watch list page beyond the
status-page button state and `GET /bambu/watches`.
