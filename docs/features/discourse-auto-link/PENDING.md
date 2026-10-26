# Talk auto-link: status and pending work

Last updated: 2026-10-25. Design and runbook: [`README.md`](README.md).

## Done (committed locally on `master`, not pushed)

- Site configs `discourse.auto-link.enabled` (kill switch, on by default) and `discourse.auto-link.nightly-cap` (20).
- MakerManager email queries; Talk admin lookups (`findUsersByEmail`, `listUsersPage`); race-safe
  `claimDiscourseUsernameIfUnlinked` / `releaseDiscourseUsername`; `MemberService.autoLinkDiscourse`.
- Pure planner + `DiscourseAutoLinkService` (webhook and nightly paths); `discourse-user` webhook handler;
  `GET /cron/discourse-auto-link`.
- UI relay routes `X-Discourse-Event-Type: user` to `discourse-user`, refuses the placeholder webhook secret, and
  the Unlink confirm text mentions automatic re-linking.

## Needs a person (in this order)

1. Confirm prod `ENV_DISCOURSE_WEBHOOK_SECRET` is **not** `change-me-in-production` before the UI deploys (the new
   guard would otherwise 403 the existing Show & Tell webhook).
2. Confirm the Talk API key can list users: `GET /admin/users/list.json?email=<your own email>` returns 200.
3. After the service and UI are deployed, create the new Talk user-events webhook (README, "Webhook setup") and Ping it.
4. Run the cron once as a dry run and review the list: `GET /cron/discourse-auto-link` (no `dryRun`). Check the
   counts and the `would link:` lines look sane.
5. Add the Jenkins nightly job: `GET <service>/cron/discourse-auto-link?dryRun=false` with an `EXECUTE_CRON` API
   client, off-peak, `curl --max-time 900` (a run takes roughly 2-4 minutes).
6. Tell the Talk admins to expect about 100-200 "check email" staff-log rows a night.

Because the kill switch defaults on, steps 3 and 5 are what actually start linking. To stop at any time, turn
`discourse.auto-link.enabled` off (real runs are refused and the webhook does nothing) or set the cap to 0.

## Follow-ups

- Once Talk is upgraded past mid-2026, `/admin/users/list.json` accepts a multi-email `filter=`, so the cron could
  look up 100 candidate emails per call instead of reading every page.
- The avatar of a newly linked member is filled in by the existing avatar refresh, not at link time.
- Manual link switching from Talk account A to B never removes A from the group (existing behaviour, untouched).
