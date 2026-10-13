# Workflows - Design

## Overview

A generic, in-process framework for multi-step operations that touch several systems (member portal DB, Active
Directory, Moodle, calendar, S3, ...). A **workflow** is a code-defined, ordered list of **steps**. Runs are persisted
in MariaDB after every step transition, so they survive restarts, can be retried, paused, rolled back, and displayed in
the UI as a timeline.

Decisions already made:

- **Engine:** in-process in the service, state in the member-portal MariaDB. No external engine.
- **On failure** (retries exhausted): the run **pauses** and waits for an operator (Retry step / Roll back / Abandon).
  A workflow may opt in to auto-rollback.
- **Launch flow:** button -> **preflight plan** (check + validate every step, no side effects) -> operator confirms ->
  run starts. Approval gates are a later addition.
- **Integration writes** (calendar, Moodle): each integration gets a client interface plus a mock; the write mechanism
  (direct DB write vs. admin API) is decided when that step is built.
- **Rollout:** ships to `master` behind a `workflows.enabled` `FeatureFlagType` config (per CLAUDE.md) that
  **defaults to disabled** (`FeatureFlagValue(enabled = false)`). It is piloted for specific usernames/groups
  (`enabledUsernames`/`enabledGroupNames`) before a full rollout. Individual workflows still need their own permission
  to launch.
- **Live updates:** server-sent events (SSE) in v1. No polling anywhere in the UI.
- **Deploys:** a brief overlap where the old and new service containers both run is acceptable (blue/green). The
  engine must still be safe under it (fencing, see Durability). An admin-only page under **About > Admin** (a new
  menu) shows workflows in progress so admins can check before restarting or deploying.
- **Groups are keyed by AD `objectGUID`**, not by name or DN (see "Required changes outside the engine").
- **Hardcoded group names** (UI role mapping, committee chair groups, remote-access config keys, Moodle chair-group
  config, ...) are **not** protected in v1: renaming or archiving such a group is the operator's responsibility. A
  note in the preflight dialog says so; removing the hardcoding is future work.
- **Edge:** `members.` is fronted by Cloudflare (then Traefik / OpenResty); see "Live updates (SSE)".

## Research summary: what existing engines teach us

| Source | Takeaway applied here |
| --- | --- |
| Saga pattern | Rollback is a *semantic undo* (compensation), not a transaction rollback. Compensations must be idempotent and retryable, and some actions are irreversible. So every step declares `reversibility`. |
| Temporal / Durable execution | Persist progress, resume after crash. We skip deterministic replay but keep the rule: **steps must be idempotent**, and the executor *always calls `check()` before `run()`* so re-execution after a crash is safe. |
| AWS Step Functions | Per-state `Retry` (max attempts, backoff, which errors) and `Catch`. We model retry policy the same way and classify errors as transient vs. permanent. |
| Camunda / BPMN | Human-in-the-loop tasks, timers, incidents ("needs attention" inbox). Informs the paused state, operator actions, and later approval/wait steps. |
| Airflow / Argo / GitHub Actions | The run timeline UI, per-step logs, and "re-run from failed step". Informs the timeline and run-detail UI. |
| Terraform plan/apply | Preflight plan before changes: shows what will change and what is a no-op. Maps directly to `check()` + `validate()`. |
| Job queues (Sidekiq, JobRunr) | Lease + heartbeat for claiming runs, so a crashed worker's run is picked up. Unique-job keys to prevent duplicate runs. |

Cross-cutting lessons: idempotency keys, single active run per subject, definition versioning for in-flight runs,
step timeouts, audit trail, cooperative cancellation, and never storing secrets or bulk PII in run state.

## Core concepts

- **WorkflowDefinition** - id, version, typed input, ordered steps, retry policy, failure policy, required
  permission, subject mapping.
- **Step** - one unit of work with `check`, `validate`, `run`, `rollback`. *Public* steps are reusable singletons
  in a `StepRegistry`; *private* steps are declared inside one workflow (Kotlin `private`) and cannot be referenced
  elsewhere.
- **WorkflowRun** - one execution: explicit input (JSON), implicit context, status, current step.
- **StepRun** - per-step state within a run: status, attempts, output, error, timestamps.
- **Subject** - the entity a run is about (`group:<name>`, `member:<username>`). Drives the launch button status, the
  per-entity history panel, and conflict locking.

### Inputs

- **Explicit input:** a typed Kotlin data class per workflow, serialized to JSON on the run (e.g.
  `ArchiveGroupInput(groupName)`, `RenameGroupInput(groupName, newName)`).
- **Implicit context** (injected by the engine, never supplied by the caller): `runId`, `actor` (member id/username),
  `triggeredBy` (UI / API / cron / webhook), `startedAt`, `clock`, `attempt`, and `mode` (PREFLIGHT or EXECUTE).

### Step contract

```kotlin
interface Step<A, O> {
  val id: String                       // stable, e.g. "ad.delete-group"
  val reversibility: Reversibility     // REVERSIBLE | BEST_EFFORT | IRREVERSIBLE
  val skippable: Boolean get() = false // operator may skip when paused here
  val retry: RetryPolicy? get() = null // overrides the workflow default

  /**
   * Read-only. Would run() do anything? Used for preflight and before every run().
   * NoOp carries the output the step would have produced (so rollback and later steps still have it).
   */
  suspend fun check(ctx: StepContext, args: A): StepCheck<O>    // WillRun(summary) | NoOp(reason, output?) | Unknown(reason)

  /** Read-only. Is it eligible to run now, or likely to fail? */
  suspend fun validate(ctx: StepContext, args: A): Validation   // Ok | Warnings(list) | Blocked(list)

  /**
   * The action. MUST be idempotent on its own, not just guarded by check(): treat "already exists" / "no such
   * object" as success. Output must be derivable from the run (e.g. an S3 key built from runId) so it can be
   * recomputed if the process dies before it is saved. Returns serializable output for later steps and rollback.
   */
  suspend fun run(ctx: StepContext, args: A): O

  /** Undo run(). Receives this step's own persisted output (e.g. the S3 key, the old name). Idempotent. */
  suspend fun rollback(ctx: StepContext, args: A, output: O)
}
```

Public steps take typed args `A`; a workflow binds them from its context:
`step(MarkGroupDisabled) { MarkGroupDisabled.Args(groupName = input.groupName) }`. A workflow can read a previous
step's output through a typed key (`ctx.output(ExportMembers.RESULT)`).

Why `check()` is mandatory: it lets retries and crash-resume skip work that already landed and powers the preflight
plan. It is an optimization and a preview, **not** the idempotency guarantee: the AD client fails over across domain
controllers, so a read right after a write can be stale and `check()` can wrongly say WillRun. `run()` must therefore
be idempotent by itself.

Error classification: steps throw `TransientStepException` (retry per policy) or `PermanentStepException` (no retry,
go straight to paused). Unclassified exceptions are treated as transient.

### Workflow definition

```kotlin
object ArchiveGroupWorkflow : Workflow<ArchiveGroupInput>(
  id = "group.archive", version = 1,
  permission = Permission.MANAGE_GROUPS,
  onFailure = FailurePolicy.PAUSE,
  retry = RetryPolicy(maxAttempts = 3, backoff = Exponential(initial = 5.seconds, max = 2.minutes)),
) {
  override fun subjects(input) = listOf(Subject("group", input.groupName))
  override fun steps() = listOf(
    step(ExportGroupMembers) { ... },     // public (read-only)
    step(UploadArchiveToS3) { ... },      // public
    step(MarkGroupDisabled) { ... },      // public
    step(DeleteAdGroup) { ... },          // public, rollback recreates group from the S3 snapshot
    step(FinalizeGroupArchive) { ... },   // private
  )
}
```

Wiring: workflows and public steps are Dagger multibindings (`@IntoSet`), so a new workflow is one class plus one
binding line.

## Execution semantics

1. **Preflight (no side effects):** for each step in order, `check()` then `validate()`. Result is a plan: per step
   WILL_RUN / NO_OP / BLOCKED / WARNING with reasons. Blocked => run cannot start. Later steps may depend on earlier
   outputs that don't exist yet in preflight, so `check` and `validate` must degrade gracefully (return
   `Unknown(reason)`) rather than fail.
2. **Start:** insert the run and acquire subject locks in one transaction (see Concurrency).
3. **Per step:**
   1. Commit a **STARTED** record (`status=RUNNING`, attempt number, fence token) *before* anything else, so a crash
      leaves evidence that the step may have partly or fully run.
   2. `check()`. NoOp with no prior attempt => SKIPPED. **NoOp on a step that has a prior STARTED attempt =>
      SUCCEEDED** (our own earlier attempt landed the effect), using the output `check()` returns or recomputes.
      WillRun => `run()`.
   3. Persist output and status in one transaction (guarded by the fence, see Durability).
   A step with a STARTED record and no finished record is treated as *possibly applied*: it is included in rollback
   (see 6) and never silently marked SKIPPED.
4. **Retry:** on a classified-transient failure, record the attempt, compute `next_attempt_at` from the policy, set the
   run to WAITING_RETRY. A poller picks it up when due. Per-attempt timeout is part of the policy.
5. **Retries exhausted / permanent error:** run -> PAUSED (policy PAUSE) with the failing step highlighted.
   Operator actions: **Retry step**, **Skip** (only `skippable` steps), **Roll back**, **Abandon** (stop, leave state
   as is, recorded in the audit trail).
6. **Rollback:** the failed step (it may be partly applied) and then completed steps in reverse order, calling
   `rollback(output)` for each step that ran (SUCCEEDED, or STARTED without a finish). Rollback of a step with no
   saved output uses the recomputable output (see the step contract). Steps that were truly SKIPPED (never
   attempted) are not rolled back. `IRREVERSIBLE` steps are skipped and flagged in the timeline. Rollback has its own retry; if it fails the run
   goes to ROLLBACK_FAILED and needs an operator.
7. **User-requested revert of a finished run:** same rollback path, allowed only if every step that ran is reversible
   or best-effort (preflight shows the "revert plan" first).
8. **Cancel:** cooperative. Takes effect between steps (or at `ctx.checkCancelled()` inside long steps).

State machine: see [`run-state.mmd`](run-state.mmd).

### Durability and crash recovery

- **The executor never polls.** It is a coroutine started with the service that drains everything claimable, then
  makes one cheap read (`WorkflowStore.millisUntilNextWork()`, DB clock) and sleeps until that deadline (the earliest
  `next_attempt_at` of a WAITING_RETRY run, or another instance's `lease_until`) or until an in-process wake signal
  (`WorkflowWakeSignal`), whichever comes first. Wake signals fire when a run is started, an operator action is
  taken, the executor starts, and on shutdown. Idle cost is zero DB traffic. There is deliberately no safety-net
  sweep. If the first read at startup fails (e.g. tables not migrated yet) it logs one warning and idles until the
  next wake signal. Heartbeats exist only while a step is actually running, at a fraction of the lease length.
  Consequence: a run orphaned by a crashed peer is picked up at its lease expiry if this instance already knew about
  it, otherwise at the next event or restart.
- It claims **only runnable runs** with a lease:
  `UPDATE workflow_run SET locked_by=?, lease_gen=lease_gen+1, lease_until=NOW()+INTERVAL ? SECOND
  WHERE id=? AND status IN ('PENDING','RUNNING','WAITING_RETRY','ROLLING_BACK')
  AND (next_attempt_at IS NULL OR next_attempt_at <= NOW()) AND (lease_until IS NULL OR lease_until < NOW())`
  (PAUSED, ROLLBACK_FAILED and terminal runs are never claimed). All lease timing uses the **database clock**
  (`NOW()`), never the JVM's, so instances with skewed clocks agree.
- **Fencing:** the claim returns `lease_gen`. Every later write for the run (`workflow_step_run`, status changes,
  heartbeat) includes `WHERE id=? AND locked_by=? AND lease_gen=?`; zero rows updated means the lease was lost and
  the executor aborts that run immediately without writing anything further. Two overlapping instances (the
  accepted blue/green deploy overlap, or a long GC pause) therefore cannot both advance a run.
- Heartbeat extends the lease while a step runs; if a heartbeat fails, the executor stops the step's coroutine. This
  only helps for suspend calls: `IActiveDirectoryService` is blocking, so a coroutine timeout cannot interrupt an
  LDAP call. Configure **LDAP connect/response timeouts below the lease duration** (and keep leases at least a few
  times the heartbeat interval) so a hung call fails instead of outliving its lease.
- **Graceful shutdown (SIGTERM; deploys give ~30 s):** stop claiming new work, let the current step finish if it can
  within the grace period, release the lease (`lease_until = NOW()`) between steps, close SSE streams so clients
  reconnect to the live instance. A step that doesn't finish keeps its lease until expiry and is then resumed by
  whichever instance claims it; that is why `run()` must be idempotent.
- After a crash, an expired lease makes the run claimable again; execution resumes at the current step using the
  STARTED record from Execution semantics step 3, so a half-finished step is retried safely.
- **Ephemeral step output** (the "export members as an in-memory list" case): the member list is bulk PII and is not
  persisted in run state. A step may declare `output = EPHEMERAL`; the output lives only in memory, and if the run is
  resumed in a new process the executor first re-runs preceding read-only ephemeral steps to rebuild it.
  Alternative: merge export and upload into a single step whose persisted output is only the S3 key, count, and
  checksum. Recommended default: merge, and keep `EPHEMERAL` available for other cases.
- **Definition versioning:** the run stores `workflow_version` and the ordered step-id list at start. A deploy that
  changes the definition does not alter in-flight runs; if a stored step id no longer exists, the run pauses with a
  clear error.

### Concurrency

- Each run declares subjects; `workflow_run_subject` has a unique key on `(subject_type, subject_key)` for *active*
  runs (MariaDB has no partial indexes, so the row is deleted, or an `active` column nulled, when the run reaches a
  terminal state). One active run per subject, which also makes archive and rename of the same group mutually
  exclusive.
- **Normalize subject keys** (lowercase, trimmed) because the DB collation is case-insensitive; otherwise a case-only
  rename would lock against itself. A group rename locks both the old and the new normalized name.
- Idempotency key on start (`workflow_id + subject + client token`). A repeat request with the same token **returns
  the existing run** (200), not a 409; a different token on a locked subject returns 409 with a link to the active
  run.
- **Locks are enforced outside the UI too.** Any service endpoint or job that mutates a subject (group member
  add/remove, group settings, rename) checks the subject lock first and returns 409 while a run holds it. The
  AD-originated group-history webhook cannot be refused (it records what already happened in AD), so it keeps
  working but is visible in the run timeline.
- **Snapshot staleness (archive):** a change made directly in AD can still land after the export. The
  `DeleteAdGroup` step's `validate()` re-reads current membership and returns Blocked if it differs from the
  snapshot, so the operator re-runs the export/upload (or accepts) instead of deleting a group with members the
  archive doesn't contain.

### Permissions

- End-user permissions are computed in the **UI** module; the service authenticates the UI as an API client, not as
  the end user. So each workflow's required `Permission` is enforced in the UI layer, on every workflow route
  including both SSE streams, before the call is relayed.
- The UI passes the acting username to the service (header), which stores it as `actor` on the run and in
  `workflow_event`. The UI's API client gets a new service-side `RUN_WORKFLOWS` permission so only it can call the
  workflow routes.
- `GET /workflows/definitions` returns each workflow's required permission so the UI can decide what to show.

## Persistence

All timestamps UTC (per project convention); convert to Chicago time at the edge.

| Table | Purpose |
| --- | --- |
| `workflow_run` | id, workflow_id, workflow_version, status, input_json, actor_id, triggered_by, current_step_index, step_ids_json, locked_by (instance id), lease_gen, lease_until, next_attempt_at, idempotency_token, created_at, started_at, finished_at, error_summary |
| `workflow_step_run` | run_id, step_index, step_id, status (incl. RUNNING = STARTED record), attempts, output_json, error_json, started_at, finished_at, rollback_status, rollback_error |
| `workflow_run_subject` | run_id, subject_type, subject_key, active_key (unique while active) |
| `workflow_event` | append-only: run_id, step_index?, ts, level, message, actor_id?. Feeds the run log and audit trail (operator actions included) |

Rules: no secrets in `input_json` / `output_json`; large artifacts go to S3 and only the key is stored. Add a new
`StorageNamespace.WORKFLOW_EXPORTS` (prefix `workflow-exports`) for export and archive snapshots.

## Required changes outside the engine (found in the code survey)

- **What the `groups` table actually is** (verified in code): a lazy mirror, not the source of truth. Rows are created
  *by name* the first time something needs one (`GroupHistoryRepository.getOrCreateGroup`, called from the
  group-history webhook), with a **placeholder DN** (`CN=<name>,OU=Groups,DC=...`) when no DN is supplied, so
  existing `dn` values may not be real. Group lists and pages come from AD, not this table. The AD webhook event
  carries only the group `Name` and domain, no GUID or DN. Tables with FKs to `groups.id`: `group_history`,
  `group_settings`, `short_links_namespaces.owner_group_id`. Because of those FKs, groups are **soft archived**,
  never row-deleted.
- **Key groups by AD `objectGUID`.** A name or DN can change (rename) or be reused (delete then recreate); the GUID
  is stable for the life of an AD object. Keep `groups.id` as the surrogate key so no FK changes.
  - Columns: `object_guid` (canonical lowercase hyphenated string, to be confirmed against what
    `ADGroup.objectGuid` returns), `status` (`ACTIVE | DISABLED | ARCHIVED`, default `ACTIVE`), `archived_at`.
  - Replace `groups_name_UNQ` / `groups_dn_UNQ` with unique indexes on generated columns
    `active_name = IF(status='ARCHIVED', NULL, name)` and `active_dn` likewise (NULLs don't collide), so a new
    group can reuse an archived group's name. `object_guid` gets a plain unique index (many NULLs allowed).
  - **Migration, in separate deploys, each independently reversible:**
    1. **Additive schema only:** add nullable `object_guid`, `status`, `archived_at` and the unique index. No code
       change; hand-written SQL, and `create.sql` updated in the same commit (there is no migration tool).
    2. **Backfill, dry-run first.** One-off admin job (not a workflow): snapshot the table first
       (`CREATE TABLE groups_backup_<date> AS SELECT * FROM groups`), read all AD groups with their GUIDs, then
       match rows by DN then by case-insensitive name. Dry-run output lists: matched, row with no AD group
       (historical or already deleted: leave `object_guid` NULL), AD group with no row (ignored), and
       **ambiguous or duplicate matches (never auto-resolved; stop and review by hand)**. The real run is
       idempotent, processes in small batches, never modifies `id`, and refuses to run if the dry-run report has
       ambiguities.
    3. **Dual-read/write:** `getOrCreateGroup` resolves the name to a GUID through AD (cached), looks up by GUID
       first and falls back to name. A row found by name with a NULL GUID gets the GUID filled in; a row found by
       GUID whose name or DN differs gets updated (heals renames made outside a workflow). If AD no longer has the
       group (e.g. webhook for a just-deleted group), fall back to name and leave the GUID NULL.
    4. **Enforce:** after the backfill report is clean and the dual-write has run in production for a while, make
       GUID the primary lookup, swap the unique indexes to the generated-column versions, and stop relying on name.
  - Consequences for the workflows: **rename** keeps the same GUID and updates `name` and `dn` on the same row, so
    webhook events arriving mid-rename resolve to the same row instead of creating a duplicate. **Archive** keeps
    the row and its GUID for audit; a later AD group with the same name has a new GUID and gets a new row.
    **Archive rollback** recreates the group in AD with a **new GUID**, so `DeleteAdGroup`'s rollback must update the
    row's GUID (one more reason it is BEST_EFFORT).
- Group state must be honoured by consumers of the `groups` table (group settings, short-link namespace ownership);
  since lists and pages come from AD, the AD delete is what actually removes a group from view.
- `IActiveDirectoryService` has no group create/delete/rename (it does have add/remove user and
  `updateBadgeNumber`). Needs `deleteGroup`, `renameGroup`, `createGroup` (the last for archive rollback), each with a
  mock. The service's AD account has the rights to delete and rename groups (confirmed).
- Rename changes the AD **DN**. Group names are also referenced by: calendar prerequisite `ad_group`, Moodle
  `local/addad` course-to-group mapping and "Chair groups" config, feature-flag `enabledGroupNames` in site config,
  and possibly Guacamole connection mappings. The rename workflow needs one "find references" check per system, and
  every reference step's `check()` is NoOp when the group isn't referenced there.
- **Hardcoded group names are out of scope for v1.** Known places: UI role mapping, committee chair groups, a voting
  registration observer, `REMOTE_ACCESS_*_AD_GROUP` config keys. The preflight dialog for rename/archive shows a
  standing note: "Some groups are referenced by name in code or config and are not updated automatically." Later:
  remove the hardcoding.
- **Keycloak (to investigate before piloting rename/archive):** nothing in the repos says how Keycloak tracks groups.
  Check in the admin console (User federation > LDAP provider > Mappers): is there a `group-ldap-mapper`, what are
  its groups DN and group-name attribute, does it drop non-existing groups on sync, and are any role mappings or
  token claims attached to Keycloak groups. If Keycloak identifies groups by name, a rename or a recreated group
  appears as a new Keycloak group and loses its role mappings; a "trigger Keycloak LDAP group sync" step may then be
  needed at the end of both workflows.
- Moodle client is read-only today and calendar is read through raw SQL: both need write paths (decision deferred).

## First two workflows

**`group.archive`** (subject `group:<name>`)

1. `ExportGroupMembers` (public, read-only, no rollback needed)
2. `UploadArchiveToS3` (public; S3 key derived from the run id so it is recomputable; rollback keeps the object by
   default, see open questions)
3. `MarkGroupDisabled` (public; rollback sets ACTIVE)
4. `DeleteAdGroup` (public; `validate()` blocks if current membership differs from the snapshot; **reversibility
   BEST_EFFORT**: rollback recreates the group with a new objectGUID, updates the row's GUID, and re-adds members
   from the S3 snapshot; SIDs/other attributes are not restored, so preflight must warn about this)
5. `FinalizeGroupArchive` (private; sets ARCHIVED, writes an audit event)

**`group.rename`** (subject `group:<old>` and `group:<new>`, so nobody can create/rename into the target name
concurrently)

1. `CheckNewNameAvailable` (private, validate-only)
2. `RenameGroupInCalendar`, 3. `RenameGroupInMoodle`, 4. further reference systems as discovered (all public: reused
   by the merge workflow). Each rollback restores the old name.
5. `RenameGroupInPortalDb`, 6. `RenameGroupInAd` (last, because AD is the hardest to undo and the system of record).
   Rollback renames back.

Failure between systems leaves names inconsistent for a while; that is why failures PAUSE and the timeline shows
exactly which systems already have the new name.

## Five additional use cases for the member portal

1. **Member offboarding / lapsed membership:** disable AD account, remove from all groups, revoke door badge, remove
   Guacamole access, unlink Discourse/Discord, suspend Moodle account, send notification. Reuses the group-removal
   and badge steps; rollback = re-enable on reinstatement.
2. **New member onboarding / reactivation:** verify WHMCS status, create or enable AD account, add default groups,
   issue badge, send Discourse invite, send welcome notification. The mirror image of 1 and shares most of its steps
   in reverse.
3. **Badge replacement (lost or stolen):** validate new badge number is unused, deactivate old badge at the door
   controller, `updateBadgeNumber` in AD, sync to the door controller, log, notify member. Needs strict ordering and
   clean rollback so a member is never locked out.
4. **Group merge:** move all members of group A into B, repoint calendar prerequisites and Moodle mappings, then run
   the archive steps on A. Composes the public steps of archive and rename; the best test of "public steps are
   reusable".
5. **Member data export / erasure request:** export profile, settings, notification prefs, and S3 profile photos into
   one archive in S3 with a presigned link; for erasure, anonymize the profile, delete photos, unlink external
   accounts, remove AD/Discourse data. Needs approval gates and a clear audit trail.

Also considered (later candidates): remote-access machine provisioning/decommission (Guacamole + WinRM), short-link
namespace transfer or retirement, bulk course-completion-to-group reconciliation, scheduled access reviews (review
group members, ask owner to confirm, remove stale), door-controller reconfiguration, and any current cron job that
needs retry visibility.

## API (service)

All under an authenticated route group that requires the service-side `RUN_WORKFLOWS` permission (granted to the UI's
API client). Each workflow's own `Permission` (e.g. `MANAGE_GROUPS`) is enforced by the UI before relaying; see
Permissions.

| Endpoint | Purpose |
| --- | --- |
| `GET /workflows/definitions` | Catalog: workflows, steps, which public steps are reused where |
| `POST /workflows/{id}/preflight` | Body: input. Returns the plan (per step WILL_RUN / NO_OP / BLOCKED / WARNING) |
| `POST /workflows/{id}/runs` | Start a run (with idempotency token). 409 if the subject is locked |
| `GET /workflows/runs?workflow=&subject=&status=&actor=` | List/filter; powers button status, history, and inbox |
| `GET /workflows/runs/{runId}` | Run detail: step timeline, events |
| `POST /workflows/runs/{runId}/{retry\|skip\|rollback\|abandon\|cancel}` | Operator actions (audited) |
| `GET /workflows/runs/stream?workflow=&subject=` (SSE) | Pushes run status changes for a subject or workflow; powers the launch button and dashboard |
| `GET /workflows/runs/{runId}/stream` (SSE) | Pushes step transitions and log events for one run; powers the timeline |
| `GET /workflows/admin/in-progress` | Admin page data: runs in RUNNING / WAITING_RETRY / ROLLING_BACK / PAUSED with instance id, lease age, current step; plus a "safe to deploy" flag. Also available as an SSE stream |

### Live updates (SSE)

- **SSE, not WebSockets:** updates are one-way (server to browser), operator actions are ordinary POSTs, and SSE
  auto-reconnects and passes through the reverse proxy as plain HTTP. Revisit WebSockets only if the UI ever needs
  client-to-server streaming.
- **Event source:** every status change and `workflow_event` row is published to an in-process event bus right after
  its transaction commits; stream handlers subscribe by run id or subject. Events carry a monotonically increasing id
  (the `workflow_event` id), and clients reconnect with `Last-Event-ID`, so the server replays anything missed from the
  table. A new stream always starts with a snapshot of current state, so the UI never needs a separate fetch to be
  correct.
- **Multiple instances:** the in-process bus only sees runs executed locally. While the service runs as a single
  instance this is fine. Before scaling out, add DB-backed fan-out (each instance tails `workflow_event` by id) behind
  the same bus interface.
- **UI hop:** the browser talks to the UI module (session auth), which relays the service's stream. The relay must
  stream without buffering, send a heartbeat comment every ~15 s, and end the stream when the session expires. The
  relay uses a **dedicated HTTP client with no request timeout**: the existing shared UI client has a 120 s timeout
  and a 100-requests-per-host cap, so long-lived streams would be cut or would starve ordinary API calls. The UI
  already has a streaming precedent (the Ask AI relay) to model it on. Both modules are on Ktor 3.6, so the SSE
  plugin is available on both sides; UI compression already skips `text/event-stream`.
- **Edge (Cloudflare, then Traefik, then OpenResty):** respond with `Content-Type: text/event-stream`,
  `Cache-Control: no-cache` and `X-Accel-Buffering: no`. Cloudflare closes proxied connections that send no bytes for
  about 100 seconds on standard plans (HTTP 524), so the 15 s heartbeat is mandatory, not optional. No OpenResty
  config change is expected, but verify end to end through Cloudflare after the first deploy that events arrive
  immediately rather than in batches, and that a hidden tab reconnects cleanly.
- **Connection hygiene:** one multiplexed stream per page (subject-scoped), closed when the tab is hidden for a while
  and reopened with `Last-Event-ID` on return.

## UI treatments

1. **Launch button with live status** (group page etc.): queries active runs for `(workflow, subject)`. States: idle,
   running (shows "step 3 of 5: Marking disabled"), waiting retry, paused/needs attention (red), recently succeeded
   (with link). Clicking while a run is active opens that run instead of starting another. Updates arrive over the
   subject-scoped SSE stream.
2. **Run timeline:** vertical stepper; each step shows pending / running / succeeded / no-op (skipped) / failed /
   rolled back, plus attempts, duration, and the error on failure. Expandable per-step log. Operator action buttons
   appear at the failing step.
3. **Preflight / confirm dialog:** the plan before starting, with warnings (e.g. "AD group delete can't be fully
   undone") and blocking reasons; confirm button disabled if blocked. For schema-driven inputs (rename needs a new
   name), a generated input form precedes it.
4. **Runs dashboard:** all runs with filters (workflow, status, subject, actor, date); "Needs attention" tab for
   paused/rollback-failed runs.
5. **Per-entity history panel:** past runs for the group/member on its page, with link to artifacts (e.g. presigned
   S3 archive link).
6. **Subject banner and guards:** "This group is being renamed" banner on the entity page, with conflicting edit
   actions disabled while a run holds the subject lock.
7. **Notifications:** use the existing notifications module to tell the actor when a run finishes, pauses, or needs
   attention; badge on the **About > Admin** menu item with the count of runs needing attention.
8. **Revert / re-run affordances:** "Revert" on finished runs (shows revert plan), "Re-run" on abandoned runs.
9. **Workflow catalog (read-only):** documents each workflow, its steps, reversibility, and where public steps are
   used.
10. **Live updates:** SSE from v1 (see above), shown with a small connected/reconnecting indicator. New templates must
    include `dms-page-loader.js`. The service-worker cache name is stamped at build time, so no manual bump.
11. **Admin page: workflows in progress (About > Admin).** Admin-only page that answers "is it safe to restart or
    deploy right now?" - see below.

### Admin page and the About menu

- The header (`fragments/header.html`) has top-level menus Organization and About. The About menu gets a nested
  **Admin** submenu containing **Site Config** (moved here from About) and **Workflows in progress** (shown only to
  admins when the workflows flag is on). GitHub stays in About.
- Page content: a table of runs in RUNNING, WAITING_RETRY, ROLLING_BACK and PAUSED/ROLLBACK_FAILED with workflow,
  subject, actor, current step, started time, attempt count, which service instance holds the lease and how stale its
  heartbeat is. A banner at the top shows **"Safe to deploy"** when nothing is RUNNING or ROLLING_BACK (WAITING_RETRY
  and PAUSED runs survive a restart, and the banner lists them for context) or **"N workflows in progress"**.
- Updates over SSE like the rest. Later: admin actions on this page (abandon, force-release a stale lease).
- The same view is useful in the deploy workflow: the service can expose the "safe to deploy" flag so CD could
  check it, but v1 only shows it to humans.

## Delivery plan (trunk-based; behind `workflows.enabled`, default off)

1. **Engine core:** definitions, step contract, executor, retry/timeout, in-memory run store, unit tests with fake steps
   (success, retry, pause, rollback, crash-resume).
2. **MariaDB persistence:** tables, repository, STARTED records, lease/fencing/heartbeat, subject locks (including
   enforcement in service endpoints), graceful shutdown, recovery and overlap tests (two executors, crash between
   `run()` and the output write).
3. **API + SSE + UI:** preflight, start, run list/detail, event bus and SSE endpoints (service and UI relay with its
   own HTTP client, Cloudflare verification), UI-side permission checks, launch button, timeline, and the
   **About > Admin** in-progress page.
4. **Groups table migration (no workflow yet):** steps 1-4 of the objectGUID migration above, each its own deploy,
   with the backup table and dry-run report reviewed by a human before the real backfill.
5. **Keycloak investigation** (groups, mappers, role mappings) - must be done before any rename/archive pilot.
6. **`group.archive`:** AD delete/create/rename methods with mocks, S3 namespace, piloted for a few users.
7. **`group.rename`:** reference-finding checks, calendar/Moodle writers, AD rename. Last, since it touches the most
   systems.
8. Remaining use cases, approval gates, notifications, dashboard polish.

Possible earlier proof of the engine before touching AD: a low-risk read-only workflow (e.g. export a group's
members to S3) as the first real run.

## Open questions

- Decided: rollback of `UploadArchiveToS3` never deletes the S3 snapshot (it may be the only copy of the member
  list once the AD group is gone). Still open: when old snapshots are purged.
- Merge export + upload into one step (recommended) or support `EPHEMERAL` outputs from v1? (Reviewer suggests
  dropping `EPHEMERAL`, since re-running the export after the delete step returns different data.)
- Retention for completed runs and events (indefinite vs. N months), and retention/access rules for the S3
  snapshots, which contain member lists?
- Should scheduled/cron-triggered runs be in scope for v1 (actor = system)?
- Should archive also capture group attributes beyond members (managed-by, description, nested groups)?
- Not yet addressed from the review: cancel semantics (RUNNING -> CANCELLED leaves partial effects), cancel from
  PENDING/WAITING_RETRY, refusing auto-rollback for workflows with BEST_EFFORT/IRREVERSIBLE steps, revert of a
  finished run as a new run that retakes the locks, and a retry-wait state for ROLLING_BACK.
- Scope trim suggested by the review for v1: drop `EPHEMERAL` outputs, revert of finished runs, auto-rollback, the
  catalog page, and multi-instance SSE fan-out.
