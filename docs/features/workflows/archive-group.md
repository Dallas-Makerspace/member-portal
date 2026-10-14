# Archive group: design and decisions

Status: design agreed with the product owner on 2026-10-13; not built yet. Builds on
[`README.md`](README.md) (engine) and [`PENDING.md`](PENDING.md) (state). The read-only `group.export-members` workflow
already exists but has **no entry point** (hidden on purpose); this feature adds the only user-facing entry point for
now.

## What it does

`group.archive` exports a group's members to the object store, marks the group DISABLED in the portal DB, deletes the
AD group, and marks it ARCHIVED with who/when/why. Archived groups are visible in a report and from the committee page.

## Decisions (all from the product owner)

| Topic | Decision |
| --- | --- |
| Entry point | Group page, an Archive button |
| Who may archive | The group's listed administrators (`managedBy`), plus implicitly Domain Admins and Infrastructure members (they are not listed on the page but must have the option) |
| Which groups | **Main group OU only** (`OU=Groups` and its sub-OUs). Groups in the admin OU cannot be archived through the portal |
| References | **Block until cleared.** Preflight looks for the group in calendar prerequisites, Moodle, site-config feature flags, remote-access settings and hard-coded role/chair groups; any hit blocks the start and is listed (counts and locations, no member data) |
| Reason | Required input to the workflow, short free text, 10 to 200 characters. The dialog warns that all members can read it |
| Confirmation | The admin must type the exact group name before Archive is enabled |
| Report | New report "Archived Groups" under IT Infrastructure. Columns: group, archived (date), archived by, reason. Visible to **anyone who can see the committee page**. A Download column is shown **to infra members only** |
| Committee page | The Committee Groups card shows an "Archived Groups" button when any archived group matches the committee's group prefixes. It links to the report with the committee as a filter (same pattern as other reports) |
| Snapshot retention | Archive snapshots live under their own prefix (`group-archives/`), exempt from any export expiry rule |
| Download | Infra only, via the generic audited presigned artifact download (see below) |

## Flow

1. Group admin (or Domain Admins / Infrastructure member) opens the group page, clicks Archive, enters a reason, sees the
   preflight plan (steps, warnings, references that block), types the group name, confirms.
2. Steps, in order:
   1. `ExportGroupMembers` (existing public step, `requireComplete = true`; also blocks if the group has nested groups).
      The document additionally records what rollback needs: the group DN, description, managedBy, and the parent
      groups it is a member of.
   2. `MarkGroupDisabled` (DB status DISABLED; rollback sets ACTIVE).
   3. `DeleteAdGroup` (validate blocks if membership changed since the snapshot; BEST_EFFORT: rollback recreates the
      group and re-adds members from the snapshot, with a new objectGUID, and updates the row).
   4. `FinalizeGroupArchive` (status ARCHIVED, `archived_at`, `archived_by`, `archive_reason`, run id and snapshot key;
      private step).
3. Failure policy: PAUSE (operator decides), unlike the export which auto-rolls back.

## New pieces

- **Per-group authorization.** The engine's `requiredPermission` is not enough (it is global). Add a workflow-level
  authorization mode "group administrator" that the UI evaluates on preflight, start and the SSE relays: allowed if the
  user is a listed admin of that group (existing `isGroupAdmin`), a Domain Admins member, or an Infrastructure member,
  **and** the group is in the main OU. The service also re-checks the OU rule in `validate()`.
- **AD write methods:** `deleteGroup`, `createGroup` (+ mocks). The service's AD account may delete and rename groups.
- **Reference checker:** read-only `GroupReferenceChecker` with one finder per system; what cannot be detected (for
  example access AD grants to other systems) is said plainly in the dialog.
- **groups table:** archive metadata columns, and **stage 4** of the objectGUID migration (name/DN uniqueness only among
  non-ARCHIVED rows, via generated columns) so a new group can reuse an archived group's name. Matching must ignore
  ARCHIVED rows. Stage 4 needs your go-ahead before it is applied to production.
- **Artifacts (generic):** a step can declare files it produced (name, label, content type, storage key). The UI is told
  only names and labels. One generic route, "download artifact N of step M of run R", checks permission, writes an audit
  event to the run log, and returns a presigned URL (about 5 minutes) generated on click; nothing is stored or embedded
  in pages. For archives the permission is infra only.
- **Service endpoint** `GET /groups/archived?prefix=...` returning archived groups for the report.
- **UI:** group page Archive button (replacing the export button, which is removed); committee page button; report page
  with the committee filter and the infra-only Download column; ReportGraph entry.

## Out of scope for now

- Restoring an archived group from the UI (rollback during a run is best-effort only).
- Archiving admin-OU groups, nested-group expansion, scheduled/automatic archiving.
- Making the export workflow reachable from the UI.
