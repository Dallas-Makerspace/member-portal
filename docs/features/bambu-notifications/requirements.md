# Bambu Status Page — Notifications: High-Level Requirements

**Panel synthesis — 2026-10-22**
Repo: `Dallas-Makerspace/member-portal` (master, checked live)
Panel seats: Member Experience · 3DFab Committee/Ops · Backend/Bambuddy · Notifications Platform · Reliability/Anti-Spam & Privacy (red team)

## Panel verdict in one line

Build this **on the existing notifications platform, email-only for v1, immediate for finished/failed** — the hard new work is not sending, it's detecting state transitions reliably without spamming anyone.

---

## Revision — 2026-10-22 (Mandar's direction, supersedes §3 polling)

1. **No background polling. BamBuddy webhooks are the trigger source** (researched against upstream `maziggy/bambuddy` main, 2026-10-22 — see new §9).
2. **New concept: `EphemeralNotification`** — a one-shot watch that deletes itself after it fires once, or after a TTL if it never fires (see new §10). This replaces permanent "watch this printer" subscriptions for member-facing watches.

## 9. BamBuddy webhooks — what upstream actually supports

Researched in upstream code (`backend/app/models/notification.py`, `schemas/notification.py`, `services/notification_service.py::_send_webhook`, ~3,100-star repo, pushed 2026-10-22), **not** just the features page. Verify against the live DMS instance before building — upstream main may be ahead of the DMS install, and portal code has been burned by this before (models were verified against a live instance 2026-09-17).

- **It is a notification provider type, not a bespoke webhook API.** In BamBuddy you create one provider: `provider_type = webhook`, config = `{ webhook_url, auth_header?, payload_format: generic|slack, field_title?, field_message? }`, an optional `printer_id` filter (null = all printers), and per-event toggles.
- **Use `generic` format.** Payload is a flat JSON POST: `{ <title>, <message>, timestamp, source: "Bambuddy", event: <event_type>, ...variables, image?: base64 }`. `slack` format is just `{text: "*title*\nmessage"}` — human chat only, not machine-parseable.
- **Events available as toggles** (defaults in brackets): `print_start` [off], `print_complete` [on], `print_failed` [on], `print_stopped` [on], `print_progress` at 25/50/75% [off], first-layer complete [off], bed cooled [off], plate not empty [on], plate clear required [off], `printer_offline` [off], `printer_error` [off], AI failure detection [off], filament low [off], maintenance due [off], and queue events: `queue_job_added` [off], `queue_job_assigned` [off], `queue_job_started` [off], `queue_job_waiting` [on], `queue_job_skipped` [on], `queue_job_failed` [on], `queue_completed` [off].
- **Variables are thin and vary by event.** Print start: `printer, filename, estimated_time, eta`. Print complete/failed/stopped: `printer, filename, duration, filament_grams, reason, filament_details, progress, finish_photo_url`. **There is no job/queue ID and no owner username in the payload.** Title/message are BamBuddy-template human text — never parse them.
  - *Consequence:* on receipt, the portal must correlate `printer + filename + time` to a queue item via **one** `GET /queue/` call (an event-driven lookup, not background polling) to resolve the owner — or accept ownerless delivery to watchers/committee only.
- **Auth: no HMAC/signature.** Only an optional static `auth_header`, sent as `Authorization: <value>` (or `Bearer <value>` if it's a bare token). The portal's existing webhook routes are guarded by an `X-Webhook-Token` header BamBuddy cannot send — so this needs a **new receiver route** that accepts the `Authorization` bearer, is otherwise unauthenticated-by-session, and returns 200 fast.
- **Delivery: fire-and-forget, single POST, no retries.** Success = HTTP 200/201/202/204; failures are only logged in BamBuddy (`NotificationLog`, provider `last_error`). If the portal is down/deploying, the event is **lost**. Accepted trade-off of the no-polling direction — it is the main argument for TTLs on ephemeral watches (§10), and the open decision in §11 on whether committee FAILED gets any backstop.
- **Gaps vs. the panel's event table:** there is **no "print paused" event** and no duration semantics — `printer_offline` fires when BamBuddy sees the disconnect, not "offline > 15 min", and prolonged-PAUSE / prolonged-OFFLINE / queue-stall are *duration* conditions a stateless webhook cannot express. In a webhook-only design those either drop from v1, or the portal evaluates them lazily when a related event / TTL sweep touches that printer.
- Set BamBuddy-side quiet hours / digest **off** for this provider — the portal owns member preferences, batching, and caps. Set `attach_photo = false` — payloads stay lean and the portal already proxies camera snapshots itself.
- Note the naming collision: BamBuddy also has *inbound* `/webhook/*` routes (queue/add, printer start/stop/status) for other systems calling **into** BamBuddy. Unrelated to this design.

## 10. EphemeralNotification — assessment: yes, with one correction

**The concept is right; the layering matters.** What members actually want here is rarely a permanent subscription — it's *"tell me when this printer is free / my print is done,"* once. A permanent follow would keep emailing them about strangers' prints forever. One-shot + TTL is the correct primitive, and TTL is also the safety net for lost webhooks (§9): a watch whose event never arrives must not live forever.

**Correction: it is not a kind of delivered Notification — it is a watch/trigger that *produces* one.**

The existing `Notification` is the outbox/audit row (inbox, dedupe, suppression history). If that self-deleted, you'd lose the audit trail and the member's inbox. So:

- New first-class entity, e.g. `EphemeralNotification { id, member, scope (printer | job | queue), printer_id, job/queue correlation, trigger event(s), created_at, expires_at, status: ARMED | FIRED | EXPIRED | CANCELLED, fired_notification_id? }`.
- On trigger: **atomically consume** the watch (same conditional-claim discipline as the notification worker) and create a normal `Notification` via `NotificationService` with dedupe key `bambu:{printerId}:{jobId}:{eventType}`. The delivery persists in the inbox; the watch does not.
- On TTL expiry: ~~consume silently~~ **superseded by Revision 2 above** — expiry notifies the member to check themselves, with TTL = remaining time + 10% (guardrails and an expiry-time status check proposed there). A periodic sweeper (piggyback an existing worker cadence) handles expiry and purges.
- Prefer a status tombstone purged after a short retention (e.g. 7 days) over instant hard-delete, so "why didn't I get notified?" is answerable; the member-facing list shows only ARMED watches either way.
- **Creation paths:** explicit only — "Notify me when this printer is free / about this job" on the status page. ~~Auto-arm on print_start~~ was **removed in Revision 5**: own-job watches are created solely by the member clicking the button next to that specific print, every time.
- **TTL defaults (to confirm):** own-job watch = job-bound, expires when the job ends any other way, hard cap ~48h; printer-available watch = member-chosen window, default ~4h, cap 24h. Cap armed watches per member.
- **Auto-cancel** when superseded: job removed from queue, new job replaces the watched state, printer goes into maintenance/mute, member cancels manually.
- Committee alerts are **not** ephemeral — they stay standing topic notifications (§2). Ephemeral is the member/watch layer only.

## Revision 2 — 2026-10-22 (Mandar's decisions)

1. **No committee alerts in v1.** The `bambu.committee-alerts` topic, on-duty roster, escalation, and committee OFFLINE/stall alerts in §§1–2 are **out of scope**. V1 is owner alerts + personal ephemeral watches only. Accepted consequence: a FAILED or ownerless (slicer-started) print may sit until its owner or a passer-by notices it on the status page.
2. **TTL expiry is NOT silent.** When an ephemeral watch expires, notify the member to check the status page / printer themselves (wording in §10 amendment below). This also turns a lost BamBuddy webhook from a silent failure into a visible one. The expiry notice is terminal — it creates no new watch and does not repeat.
3. **TTL = the job's current remaining time + 10%.** Taken at watch creation / auto-arm time, from the printer's reported remaining time for that job. For a printer-available watch, it's the *current* job's remaining time on that printer.

**Assistant pushback on (3), pending Mandar's call (not yet decided):**

- **10% alone is too tight at the extremes.** A 20-min print gets 2 min of grace — less than sweeper cadence + delivery lag — and a 20-h print gets 2 h, which is a long time to wait for a "check yourself" notice if the finish webhook was lost. Proposed guardrail: `grace = clamp(10% of remaining, minimum 15 min, maximum 60 min)`, i.e. `TTL = remaining + grace`.
- **`remaining_time` can be 0/null at print start** (BamBuddy's own code notes MQTT remaining time may be 0 then). TTL must fall back to the archive/slicer estimate (`estimated_time` in the print_start payload); a 0 TTL must never arm-and-instantly-expire.
- **Wall-clock TTL vs. pauses:** remaining time freezes during a PAUSE, the TTL clock doesn't. A legitimate hour-long filament pause would expire (and consume) the watch mid-print, and the real finish would then notify nobody. Mitigation in the expiry-check proposal below.
- **Proposed expiry behaviour (replaces pure silent-or-notify expiry):** when a watch hits its TTL, the sweeper makes **one** status/queue lookup for that printer/job (a watch-scoped check at expiry, not fleet polling):
  - Job actually FINISH/FAILED in BamBuddy (webhook was lost) → fire the *real* notification, consume the watch. TTL becomes the backstop that makes fire-and-forget acceptable.
  - Job still RUNNING/PAUSE → send Mandar's "it's taking longer than expected — please check" notice, and re-arm **once** with the fresh remaining time + grace (cap total re-arms, e.g. 2, after which it expires for real with a final check-yourself notice).
  - Job/printer unreachable or job gone → send the check-yourself notice and expire.

## Revision 3 — 2026-10-22 (Mandar's decisions, supersede Revision 2 where they conflict)

1. **Committee alerts are back — for printer OFFLINE only.** (Supersedes Revision 2 item 1.)
   - Scope is narrow: a standing `bambu.committee-alerts` topic covering **printer offline** events for **committee maintainers**. No committee FAILED, stall, or escalation alerts in v1.
   - Source: BamBuddy's `on_printer_offline` provider toggle [off by default — must be enabled for the portal webhook provider]. It fires when BamBuddy sees the disconnect, with no duration threshold, so it is near-real-time.
   - It is a **standing** subscription (maintainers opt in via topic preferences), **not** an EphemeralNotification — a maintainer wants every offline, not one shot.
   - Blind spot to accept: if **BamBuddy itself** is down, no webhook arrives at all, so this cannot report a BamBuddy outage — only individual printers BamBuddy can't reach.
   - Still to define: who counts as a "committee maintainer" recipient (opt-in topic subscribers vs. all 3DFab committee members), and flap handling — BamBuddy has no "printer back online" webhook event, so a portal-side proposal is to let the existing 5-minute notification hold do double duty: re-check that one printer when the held offline alert is released, and suppress it if the printer is already back. Batching is partly free: the worker already claims all a member's queued rows into one email, so a multi-printer LAN incident arriving together lands as one email per maintainer, not N.
2. **TTL formula settled: `TTL = remaining + clamp(10% of remaining, min 1 hour, max 3 hours)`.** (Supersedes Revision 2 item 3 and the assistant's proposed 15-min/60-min clamp.)
   - Worked examples: 20-min job → 20m + 1h = **1h20m**; 5-h job → 5h + 1h (10% = 30m, clamped up) = **6h**; 10-h job → 10h + 1h = **11h**; 20-h job → 20h + 2h = **22h**; 40-h job → 40h + 3h (capped) = **43h**.
   - Accepted trade-off: generous grace means far fewer false expiries (and most legitimate pauses fit inside it), at the cost of a lost finish/failed webhook only surfacing at expiry — at least 1 hour after the expected end.
   - The other Revision 2 guardrails still stand and are unaffected: fall back to the slicer/archive estimate when `remaining_time` is 0/null at print start, and the expiry-time status-check proposal (below, §11 item 2) remains **pending**, not decided by this formula.

## Revision 4 — 2026-10-22 (Mandar's decisions, resolving most open items)

1. **Offline committee alerts use the 5-minute hold — approved** (the Revision 3 proposal: the held alert is re-checked against that printer when the hold releases, and suppressed if the printer is already back). The 5-minute window is the v1 value; it can be changed later. This is the same hold the notifications worker already applies — no new mechanism.
2. **Committee-alert audience = anyone who subscribes to the topic.** "Maintainers" was context for who'd care, not a restricted role or roster. Ordinary opt-in topic subscription; no on-duty model. (Resolves §11 item 5 and §8 item 2.)
3. **Own-job alerts are opt-in** — not on-by-default. (Resolves §8 item 1.) Consequence for §10: auto-arming an owner's finish/fail watch on print_start may only happen for members who have opted in; creating a watch remains itself an opt-in act. No owner who hasn't opted in gets email.
4. **No quiet hours in v1** — neither BamBuddy-side nor portal-side for these topics. (With item 1, this settles §8 item 3: the only timing rule is the 5-minute offline window.)
5. **Job name IS included in notification emails.** Rationale (Mandar's): job names are already visible to all logged-in members on the status page, so they carry no private information. This overrides the red team's authenticated-inbox-only position and the watchers-get-no-job-name split in §6 — job name may appear in owner, watcher, and committee emails alike.
6. **FAILED alerts are NOT exempt from digest/cap rules.** (Resolves §8 item 6.) Action-required notifications follow the platform's normal rules exactly: 5-minute hold/batching, the member's per-topic aggregation preference (including digest, if that's what they chose), and the 15/day immediate cap with overflow to digest. No special-casing for failed prints in v1.

**Still open after Revision 4:** the §8 item 4 FINISH-reminder question (subsumed in practice by the ephemeral finish watch + non-silent TTL expiry, but never explicitly decided), the expiry-time one-shot status check + re-arm proposal (§11 item 2), the per-member armed-watch cap (§11 item 3), and hard-delete vs tombstone (§11 item 4).

## Revision 5 — 2026-10-22 (Mandar's decisions)

1. **Own-job opt-in is per-print, by click, every time.** (Supersedes Revision 4 item 3's interpretation.) There is **no general/standing opt-in** for your own jobs and **no auto-arming** on print_start. The member clicks the notify button next to *their specific print* on the status page; that click creates that print's EphemeralNotification, and only that print's. A member who queues a print and never clicks gets no alerts for it — that is intended. (The standing topic subscription in Revision 4 item 2 is a different thing — it's for committee offline alerts, not own jobs.)
   - UX consequence: the button must be present and obvious next to the member's own active/queued print on the status page, since the click has to happen while that print exists.
2. **No per-member cap on armed ephemeral watches.** (Resolves §11 item 3.) Watch creation is a manual per-print click, which is its own rate limit, and TTLs bound every watch's lifetime anyway.

**Explained at Mandar's request, decisions pending:** the expiry-time one-shot check + re-arm (§11 item 2) and tombstone-vs-hard-delete (§11 item 4). Assistant recommendations: adopt the expiry check with capped re-arms; tombstone with a short purge rather than instant hard-delete. See chat 2026-10-22 for the full explanations.

## Revision 6 — 2026-10-22 (Mandar's decisions — requirements now decision-complete for v1)

1. **Expiry-time one-shot check adopted, with exactly ONE re-arm.** (Resolves §11 item 2.) When a watch's TTL expires, the sweeper makes one BamBuddy status/queue lookup for that printer/job:
   - Job already FINISH/FAILED (webhook lost) → send the real finished/failed notification, consume the watch.
   - Still RUNNING/PAUSE → send the "taking longer than expected — please check" notice and re-arm **once**, with a fresh TTL computed from the new remaining time (same formula, Revision 3). When that re-armed TTL expires, the sweeper checks one final time: fire the real notification if the job ended, otherwise expire for real with the check-yourself notice. No second re-arm.
   - Unreachable / job gone → check-yourself notice, expire, no re-arm.
2. **Tombstone with purge** for fired/expired/cancelled watches. (Resolves §11 item 4.) The row flips to its terminal status (`FIRED` / `EXPIRED` / `CANCELLED`) with a timestamp and the ID of the notification it produced; it never shows in the member's active-watch list; the sweeper purges tombstones after the retention window (7 days, as proposed — configurable, not load-bearing).

With Revision 6, every item in §11 and §8 is decided or explicitly subsumed (FINISH reminder → the ephemeral finish watch + non-silent expiry). Next step when Mandar says go: panel-driven design + build.

## 11. New open decisions from this revision

1. ~~Lost-webhook tolerance for committee FAILED~~ — **moot** (no committee FAILED in v1, Revision 3). For committee OFFLINE, the equivalent question is the flap/re-check proposal in Revision 3 item 1.
2. Prolonged PAUSE / stall: drop from v1, or evaluate lazily on related events + the ephemeral sweeper? (The expiry-time one-shot check + re-arm proposal from Revision 2 is the concrete form of this — still pending Mandar's call.)
3. ~~TTL defaults~~ — **settled** (Revision 3 item 2). Per-member armed-watch cap (§10) still open.
4. Instant hard-delete vs. status tombstone + purge for fired/expired watches?
5. Committee OFFLINE audience: who exactly is a "committee maintainer" recipient, and is it opt-in?

---

## 1. Who gets what

| Event | Job owner | Printer watchers (opt-in) | 3DFab committee / on-duty |
|---|---|---|---|
| My job started / I'm next in queue | Yes | No | No |
| Print **FINISH** | Yes — prompt to collect / clear bed | Printer-available interest only (see §5) | No incident; digest/summary at most |
| Print **FAILED** | Yes | No (state only, if subscribed) | **Yes — immediate** |
| **PAUSE** prolonged (>10–15 min, sustained) | Yes | No | Yes, if it stays stuck |
| Printer **OFFLINE** prolonged | Only if it was their print | No | **Yes — immediate, committee only** |
| Queue stalled (jobs waiting, nothing printing) | Queued members: delay notice | No | Yes |
| Recovery (back to IDLE/RUNNING) | If they got the incident alert | No | Yes — one closing notice |

Owner = Bambuddy queue `created_by_username` / `printedByUsername`. No subscription needed for your own job.

**Ownerless jobs:** slicer-started jobs have no username. Never guess an owner. Fallback is committee/watchers (state only), and the UX must not promise owner alerts for these jobs.

## 2. Reuse the existing notifications platform — no parallel system

- Emit only via `NotificationService.create()` (owner) / `createForMembers()` (watchers, committee). No new sender, queue, or email path.
- **Several topics, not one** — preferences are per topic × channel, so one topic would force one urgency for everything. Panel proposal:
  - `bambu.print.finished`
  - `bambu.print.action-required` (failed / prolonged pause)
  - `bambu.committee-alerts`
- Each topic = `NotificationTopic` object + `NotificationTemplate` + Dagger `@IntoSet` registration (the documented "Adding a topic" recipe).
- Dedupe key, deterministic and replay-safe: `bambu:{printerId}:{jobId}:{eventType}`.
- **Channels: v1 is email only.** `EmailNotifier` is real; SMS / Discord / Discourse in `Channel.kt` are `MockNotifier` — not live. Don't show them as selectable until a real notifier + verification + failure handling exists.
- Aggregation: finished and action-required are `IMMEDIATE` by default (a daily 8 AM digest defeats "come clear the bed"). The existing 5-minute hold/batching stays. Watch the 15/day immediate cap: overflow-to-digest is fine for finished, risky for failed — monitor cap hits.

## 3. The new capability: transition detection

> **Superseded by the 2026-10-22 revision above:** Mandar directed webhook triggers instead of background polling, plus EphemeralNotification (§§9–10). The persistence/idempotency requirements below still apply to the webhook receiver; the poller items do not.

Today `BambuddyStatusService` is on-demand only (10s cache, in-memory snapshot, no history). Notifications require:

- A background poller (cadence ≥ the 10s cache, e.g. 15–30s, staggered per printer — status is per-printer calls, no bulk endpoint).
- Persisted last state per printer: `printer_id`, effective state, current job/queue ID, state-since, last-successful-poll time. In-memory alone loses transitions on restart.
- Transitions defined on *effective* state (disconnected → OFFLINE), using the existing state sets.
- **Notify on:** printing → FINISH, printing → FAILED, prolonged PAUSE, prolonged OFFLINE mid-print, queue pending → started/next.
- **Explicitly ignore:** repeated FINISH, PREPARE↔SLICING↔RUNNING within one job, progress/remaining-time changes alone, brief PAUSE/OFFLINE flaps.
- Capture at detection time (it disappears when the next job starts): printer, queue ID, job name, owner username, before/after state, progress, layers, `remaining_time` (**minutes**, not seconds), detection time, `completed_at`.
- Identity matching must use printer + queue ID, not job name alone — reprints/duplicates make name-only matching ambiguous.

## 4. Anti-spam — the red team's BLOCKING requirements

No launch without all three, proven under simulated flapping, outage/recovery, and restart replay:

1. **Durable, job-scoped idempotency** — one notification per job + terminal state, surviving restarts/deploys. First snapshot after restart is a baseline, not a catch-up burst.
2. **Send-time revalidation + freshness** — no transition inferred from a stale fail-soft snapshot; if a held notification's job is superseded by release time, cancel or demote it to a labelled digest entry. Never deliver "your print finished" as a fresh alert hours late.
3. **Outage circuit-breaker** — all printers OFFLINE at once = one Bambuddy/infrastructure incident per recipient, not N printer alerts. Suppress offline/online pairs during an outage.

Plus: flap suppression (sustained state across multiple polls before first alert), one incident = one thread per printer, a single reminder/escalation only after a second, longer threshold, and opt-out checked at send time (per-printer mute/snooze must be real).

## 5. Hard domain traps (all seats agreed)

- **FINISH ≠ bed occupied, and ≠ bed cleared.** It persists indefinitely until the next print starts; `awaiting_plate_clear` was checked live and is unreliable. So: never auto-alert the committee on lingering FINISH, never mark a printer blocked/unavailable from it, never auto-cancel/requeue anything from a notification. Wording must be "your print finished — please collect it," not "the bed is still occupied."
- **OFFLINE ≠ printer broken.** It often means Bambuddy lost LAN reachability. Threshold + all-printers correlation before alerting anyone.
- **No automation side-effects:** notifications never power-cycle, clear, requeue, or delete jobs.

## 6. UX & message content

- One-click Subscribe on each printer card ("Notify me about this printer") and on your own active/queued job, reusing the `TopicSubscriptionHandler` / `/subscriptions/{topicKey}` framework — but with `printerId` as the arg, not a username (a new handler, not FollowMember reused as-is). Clear subscribed state, easy unsubscribe, no settings hunt.
- Message: printer name/model, event, timestamp, what to do next, link back to status page/camera. Owner messages may include job name + ETA/progress.
- **Privacy split (panel disagreement):** watchers get printer + state only — no job name, no owner name. The red team would also keep job names out of email previews entirely (owner sees it in the authenticated inbox); Member Experience would include it in the owner's own email. **Decided in Revision 4: job name is included in emails — it's already visible to all members on the status page, so it isn't private.** (The owner-name restriction for watchers was not revisited.)

## 7. Admin / config

- Per-printer toggles for FAILED / OFFLINE / stall alerts, plus a maintenance/mute mode with expiry, visible on the status page (a printer under repair stays silent).
- Thresholds (offline duration, stall duration, escalation delay, quiet hours) configurable by chair/admin — no code deploy to change.
- Audience is configurable: who counts as "on-duty / committee" vs. general 3DFab subscribers.
- Observability: every send/skip shows in the member inbox with reason; metrics per topic/printer for created, sent, suppressed, dedupe hits, cap overflows, retries.

## 8. Open decisions for Mandar / 3DFab committee

> Most of these were decided in Revision 4 above: item 1 → opt-in; item 2 → moot (any subscriber); item 3 → no quiet hours, 5-minute offline window; item 5 → job name included in emails; item 6 → NOT exempt from digest/cap/hold rules. Item 4 remains open.

1. **Own-job alerts: opt-in or on-by-default with opt-out?**
2. **First responder model:** rotating on-duty roster, or broadcast to all committee — and is there an acknowledgement/"I'm handling it" claim?
3. **Thresholds & quiet hours:** how long OFFLINE / stalled before alerting, and do FAILED alerts bypass quiet hours?
4. **FINISH reminder:** is a single owner reminder wanted at all, after how long — or left manual?
5. **Job name in owner's email:** include it, or authenticated-inbox-only per the red team?
6. **FAILED vs. the daily cap / 5-min hold:** are failed-print alerts exempt, so they can never be batched away or demoted to digest?

---
*Grounding: Bambuddy module (`BambuddyModels`, `BambuddyStatusService`, `BambuddyRoutes`), Bambu status UI (`BambuPrinterStatusHandler`, `BambuStatusViewModel`, `bambu-printer-status.html`), and the existing notifications system (`service/modules/notifications`, `docs/features/notifications/README.md`) — all read from master on 2026-10-22.*
