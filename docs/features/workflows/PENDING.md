# Workflows and groups objectGUID: status and pending work

Last updated: 2026-10-13 (after the DN backfill). Read this first when resuming; the design is in [`README.md`](README.md).
Operating notes for the person/agent doing the work are at the bottom.

## 1. What is done

All of this is on `master` and deployed (CI + Service CD + UI CD green), except where noted.

| Commit | What |
| --- | --- |
| `945bfa0` | Design doc and run state diagram |
| `8f4d0ca` | Workflow engine (`:service:workflows`), MariaDB store (`:service:workflows-store`), contracts (`:service:workflows-api`), wire DTOs in common-models |
| `d2962ce` | Service API: `/workflows/**` routes, three SSE streams, `RUN_WORKFLOWS` permission, `workflows.enabled` flag, Dagger wiring, 409 lock enforcement on group edits |
| `127ca4c` | UI: `/api/workflows/**` relay, run page, admin page, reusable launch button, About > Admin menu (Site Config moved there) |
| `0ca4ca1` | groups objectGUID stages 1-3 (schema, dual-write, backfill job) and the AD `objectGUID` decoding fix |
| `437dca8` | `getAllGroups()` reads both group OUs; profile shows only groups in those OUs (`DirectoryOus` in common-models) |
| `5a42486` | Backfill job also replaces stale/placeholder DNs; duplicate group names resolve to the first OU in `DirectoryOus.GROUP_OUS`; duplicate warning logs once per name |

Key behaviours to remember:

- **Executor never polls.** It wakes on in-process events (`WorkflowWakeSignal`) and sleeps until
  `WorkflowStore.millisUntilNextWork()`. At startup it makes one read; if that fails it logs once and idles.
- **Feature flag `workflows.enabled` is OFF** (config key, FeatureFlagType). UI routes, pages and the menu are hidden
  until it is turned on for a user. Nothing registers a real workflow yet, so there is nothing to run.
- **Failure policy** is PAUSE (operator retries, skips, rolls back, or abandons).
- **S3 snapshots are never deleted on rollback** (decided).

## 2. Production database state (shared `member_profile` DB; local runs hit the same DB)

- Tables `workflow_run`, `workflow_step_run`, `workflow_run_subject`, `workflow_event` exist (applied 2026-10-11).
  They are empty.
- `groups` has `object_guid` (unique), `status` (default ACTIVE), `archived_at`. 166 rows, 165 with a GUID, all ACTIVE.
  The one NULL row is id 54, a built-in group outside both group OUs; it stays NULL on purpose.
- 12 stale groups (not in AD) and their 102 `group_history` rows were deleted on 2026-10-12.
- **Backup tables still in the DB** (drop when you are sure): `groups_backup_20261012` (166 rows, pre-GUID-backfill),
  `groups_backup_20261013` (166 rows, pre-DN-backfill), `groups_dropped_20261012` (12 rows),
  `group_history_dropped_20261012` (102 rows).
- DNs were backfilled on 2026-10-13: 156 rows got their real AD DN (0 conflicts, 0 failures); 7 rows now live in the
  admin group OU; all 166 DNs are distinct. Dual-write keeps healing DNs on events.
- Duplicate-name rule: if a group name exists in both group OUs, the first OU in `DirectoryOus.GROUP_OUS` wins
  everywhere (`getAllGroups`, `getGroup`, `getMultipleGroups`). The twin in the other OU is not tracked in `groups`.

## 3. Pending work

### 3.1 Immediate / small

- [x] ~~Decide the duplicate group name~~ Done: first OU in `DirectoryOus.GROUP_OUS` wins (see section 2). Revisit only
  if the admin-OU twin ever needs its own row.
- [x] ~~Backfill real DNs~~ Done 2026-10-13 (156 updated).
- [ ] **Stop the dev apps started from IntelliJ** (`service [run]`, `ui [run]`). They run old code and lock jars, so
  Gradle in the repo fails with "Unable to delete file ... common-models-0.0.1.jar". Until then verify in a scratch copy
  (see section 5).
- [ ] **Turn the flag on for yourself** to see the new UI (Site Config > `workflows.enabled`, FeatureFlag with
  `enabledUsernames`). The About > Admin > Site Config entry is visible to everyone regardless.
- [ ] **Look at the UI in a browser.** Never seen rendered for real: About > Admin submenu, the run page timeline, the
  admin page (lease age ticks locally), the launch button dialog. Only template tests and a canned-data harness covered it.

### 3.2 Next feature steps (planned order)

1. [x] **First real workflow, read-only: `group.export-members`** (commit `44d6255`, deployed; launch button on the group
   page behind the flag). An independent review found issues (truncated export looked complete; a failed run held the
   group lock; preflight skipped validation when S3 was down; display names could be the text "null"; weak tests).
   All fixed in the follow-up commit: the document carries `complete`/`sourceMemberCount`/`unresolvedMemberCount`/
   `nestedGroups` and the step has a `requireComplete` arg (archive must set it and also check `nestedGroupCount`,
   since nested groups are listed but not expanded); AUTO_ROLLBACK releases the lock on failure; no S3 call in
   preflight; EXECUTE-mode validate is cheap; idempotency check is head-based; `GroupNotFoundException` replaces text
   matching. Known limits: `complete` relies on a name heuristic to tell nested groups from users (errs toward
   incomplete); the mock directory always looks complete; the literal text "null" for absent AD attributes is
   handled in the export only (other `getMembersBy*` callers still stringify nulls).
   - [ ] **Audited presigned download of the export** (service route + link on the run page, shown only on a SUCCEEDED
     run to users who may run the workflow; clicking asks the UI, which checks permission and gets a ~5 minute
     presigned URL from the service; every click is written to the workflow event log; the page holds no URL).
     Decided yes. Still to confirm: who may download (default: anyone who can run it), link lifetime (default 5 min),
     whether archive snapshots are downloadable (default: infra admins only).
   - **S3 bucket checked 2026-10-13** (read-only, AWS CLI): the object-store bucket is shared with profile photos; it is
     private (all public-access blocks on, no bucket policy, ACLs disabled), encrypted at rest (AES256), versioned,
     has no access logging, and was empty. Its only lifecycle rules expire non-current versions after 30 days and abort
     incomplete uploads after 1 day, so **current objects never expire**.
   - [ ] **Retention rule for exports: you chose to set it yourself.** Recommended: a lifecycle rule scoped to the
     prefix `workflow-exports/group-exports/` only (profile photos untouched; archive snapshots must go under a different
     prefix so they are exempt): expire current objects after 90 days and non-current versions after 7 days.
     Note versioning means a deleted export lingers as a hidden version for the non-current window.
   - Decided: any user with MANAGE_GROUPS may export any group in the two group OUs (no admin-OU exclusion, no
     per-group-admin path).
   - Open: no LDAP response timeout is configured, so a hung AD read is bounded only by the library default (~300 s);
     the executor lease must stay longer than any AD read or be given an explicit timeout.
2. [ ] **Keycloak investigation** (must precede any rename/archive pilot). Check how the realm's LDAP group mapper
   identifies groups (by name or objectGUID), whether it drops groups on sync, and whether role mappings or token claims
   are attached to Keycloak groups. A "trigger Keycloak group sync" step may be needed at the end of both workflows.
3. [ ] **AD write methods** in `IActiveDirectoryService` (+ mocks): `deleteGroup`, `renameGroup`, `createGroup` (the last
   is for archive rollback). The service's AD account has the rights (confirmed).
4. [ ] **`group.archive` workflow:** export members, upload to S3, mark group DISABLED, delete AD group, finalize
   (ARCHIVED). `DeleteAdGroup.validate()` must block if membership changed since the snapshot. Rollback recreates the
   group (new objectGUID, so update the row's GUID) and is BEST_EFFORT. Pilot for a few users via the flag.
5. [ ] **Stage 4 of the groups migration** (do with archive): see 3.3.
6. [ ] **`group.rename` workflow:** "find references" checks per system (calendar prerequisite group column, Moodle
   course-to-group mapping and chair-group config, feature-flag `enabledGroupNames`, possibly Guacamole). Calendar and
   Moodle need write paths (direct DB write vs admin API: still undecided). AD rename changes the DN; the row keeps its
   GUID. Hardcoded group names (UI role mapping, committee chair groups, a voting observer, `REMOTE_ACCESS_*_AD_GROUP`)
   are out of scope for v1 and the preflight must say so.
7. [ ] Remaining use cases (offboarding, onboarding, badge replacement, group merge, data export/erasure), approval
   gates, notifications on completion, dashboard polish.

### 3.3 groups objectGUID stage 4 (not started)

Stage 4 is the only unfinished stage. Lookup is already GUID-first, so what remains is:

- [ ] SQL: replace `groups_name_UNQ` / `groups_dn_UNQ` with unique indexes on generated columns
  `active_name = IF(status='ARCHIVED', NULL, name)` and `active_dn` likewise, so a new group can reuse an archived
  group's name. Hand-written script in `service/docs/sql/`, tested on a throwaway MariaDB first.
- [ ] Code: `getOrCreateGroup`/`resolveGroup` must ignore ARCHIVED rows when matching by name or DN.
- [ ] Prerequisites: backfill report still clean (only the known NULL row, 0 ambiguous, DN_CONFLICT 0), a longer soak
  of the dual-write. (Duplicate-name decision and real DNs are done.) Nothing needs stage 4 until the archive
  workflow sets ARCHIVED.
- Known gap until then: a late AD event carrying an old group name can still create a duplicate name-only row
  (the event has no GUID). `GroupHistoryService.kt` still creates rows by name but nothing calls it.

### 3.4 Clean-ups and open design questions

- [ ] Drop the four backup tables (section 2) when no longer needed.
- [ ] Open design questions from the review, not yet addressed: cancel semantics (RUNNING to CANCELLED leaves partial
  effects), cancel from PENDING/WAITING_RETRY, refusing AUTO_ROLLBACK for workflows with BEST_EFFORT/IRREVERSIBLE steps
  (the registry already rejects it at registration), revert of a finished run as a new run that retakes the locks,
  a retry-wait state for ROLLING_BACK.
- [ ] Retention for finished runs/events, and for S3 snapshots (they contain member lists, so personal data).
- [ ] Should cron/webhook-triggered runs (actor = system) be in v1? Should archive capture group attributes beyond members?
- [ ] Optional lightweight AD call `getGroupGuid(name)` so the webhook path stops loading all members via `getGroup`.
- [ ] Style debt: the agents did not run ktfmt/detekt. `:ui:detekt` and `:service:detekt` report violations (many
  pre-existing, a few new in the workflow UI files for complexity/parameter count). Do not run `ktfmtFormat`
  (it rewrites about 150 unrelated files); hand-format.
- [ ] Engine test gap: the single startup warning log line is asserted by call count only (logger factory is final).

## 4. Known unverified things

- The store has been run on **real MariaDB only for the DDL and the key SQL expressions** (throwaway container). The
  Kotlin store and executor were tested on H2 in MariaDB mode.
- The AD `objectGUID` byte order is unit-tested but not compared against an AD tool. The values look well-formed and
  the dual-write and backfill agree with each other, but nobody has diffed one against ADUC/PowerShell.
- No workflow has ever run end to end (none exists). Executor, store, SSE and UI have only been exercised by tests.
- Cloudflare in front of the site: SSE streaming and the 15 s heartbeat have not been verified through it.

## 5. Operating notes (so a fresh session does not repeat mistakes)

- **Run the project through the IntelliJ MCP run configurations**, not a shell, when the goal is to run the app. Those
  configs use the real shared database; there is no mock mode via IntelliJ.
- **Production data is read-only** unless the user explicitly authorizes a write. The prod DB is the same one local
  runs use. Writes so far were explicitly authorized: the two migrations, the stale-row delete, the backfill.
- **Verify in a scratch copy while the dev apps run:** copy the repo without `.git`, `build`, `.gradle`, `.idea`,
  `node_modules` and any `.env*` file into a new directory, then run `./gradlew test` there. Never copy or read `.env`.
- **Prod SQL / cron access** is over ssh to the docker host (see the local memory notes): extract credentials on the
  host with anchored greps into a temp file, never echo them, pass SQL with `-e "source /q/q.sql"`. Test any DDL on a
  throwaway MariaDB container on the same host first. API clients are `API_CLIENT_<n>_NAME/KEY/ROLE` in the service env
  file; the values may have trailing spaces (trim). Cron endpoints need a client whose role has `EXECUTE_CRON`.
- **Backfill endpoint:** `GET /cron/group-guid-backfill` is a dry run unless `dryRun=false` is passed exactly. A real
  run refuses on any ambiguity and first creates `groups_backup_<date>`.
- **Conventions:** trunk-based on `master` (a push deploys); no real DMS data in code, tests, comments or commit
  messages; terse comments; the workflow design says no polling anywhere (SSE and event-driven wakeups only).
- **Commit style:** `[Area] Subject`, with the co-author and session trailer lines the harness requests.
