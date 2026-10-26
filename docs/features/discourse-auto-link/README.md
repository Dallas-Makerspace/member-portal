# Talk auto-link (automatic green dot)

Links a member's Talk (Discourse) account to their profile automatically when the account's email matches the
member's MakerManager email, and adds it to the "DMS Members V2" Talk group (the green dot). Status and open
steps: [`PENDING.md`](PENDING.md).

## Triggers

- **Webhook (immediate).** Talk's user events `user_created` and `user_confirmed_email` reach the UI's
  `/webhook/discourse`, which forwards `X-Discourse-Event-Type: user` deliveries to the service handler
  `discourse-user` (`DiscourseUserWebhook`). Everything else still goes to `discourse-post`.
- **Nightly cron (backlog).** `GET /cron/discourse-auto-link` (`EXECUTE_CRON`, called by Jenkins) links up to
  `discourse.auto-link.nightly-cap` members per run, longest-standing member first. It is a **dry run unless
  `dryRun=false`** is passed exactly. A dry run is allowed while the kill switch is off (read-only preview); a
  real run is refused.

## Matching rules

- Compare the Talk account's primary email with `dms-makermanager.users.email` of **`ad_active = 1`** users, trimmed
  and lowercased. Nothing else (no AD `mail`, no Talk secondary emails).
- An email shared by more than one active user is skipped. Talk emails are unique, so a Talk-side duplicate can't
  happen; the check stays as a guard.
- The Talk account must be real (`id > 0`), `active`, not staged, suspended or silenced. Anything unclear counts
  as not eligible. The webhook payload has no `active`, so the handler re-checks via
  `GET /admin/users/list.json?email=` before linking; an unconfirmed `user_created` is skipped there.
- Never overwrites a member who already has a Talk link, and never takes a Talk account held by **any** other
  profile, including a disabled member (manual SSO linking still reassigns in that case).
- Skips members disabled in AD.
- No opt-out: a member who unlinks can be linked again by a later run if the emails still match. The Unlink
  confirm text says so.
- No notification is sent to the member.

## Safety

- `MemberService.autoLinkDiscourse` claims the link with a conditional update (`discourse_username IS NULL`; the
  table's unique key backs it up), then adds the Talk group and writes a `LINK_DISCOURSE` activity-log row with
  attributes `{"auto":true,"trigger":"cron|webhook"}`. If the group add fails the claim is released. It touches
  only the Talk columns, never Discord.
- **No emails anywhere**: the admin Talk client has no request logging, errors carry no URL/body/cause, DTOs
  redact the email in `toString`, webhook responses and the cron report hold usernames and counts only.
- The webhook handler returns success for everything except a malformed payload (Talk disables failing
  webhooks); a Talk/DB/AD outage is left to the nightly run.
- The UI relay now refuses the placeholder `ENV_DISCOURSE_WEBHOOK_SECRET` outside development.

## Config (site config, category "Talk")

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `discourse.auto-link.enabled` | Boolean | **on** | Kill switch for both triggers. Read fresh (no cache) and fails closed: a read error counts as off. |
| `discourse.auto-link.nightly-cap` | Int | 20 | Max links per nightly run; 0 pauses; clamped to 200. |

Since the switch defaults on, nothing changes until the Talk webhook is created or Jenkins calls the cron with
`dryRun=false`. Link straight to the keys with `/admin/config?filter=discourse.auto-link`.

## Nightly algorithm

1. One bulk MakerManager read (active users with email, oldest first) and the profiles that already have a link.
2. Drop shared emails and members already linked; if nothing is left, make no Talk calls.
3. Page through Talk once (`/admin/users/list.json?show_emails=true&order=username&asc=true&page=N`, 100 per page,
   ordered by username because `order=created` pages unstably - verified 2026-10-26: 7,762 rows but 5,941 distinct
   accounts - while username gives every account exactly once; repeated ids are also dropped,
   paced 1.1 s apart, 429 waits honoured, 3 attempts per page), keeping only accounts whose email is a candidate.
   If any page fails or the scan passes 400 pages, the run ends **INCOMPLETE with no changes**.
4. Match, check AD (batches of 100), take the first `cap` in oldest-first order.
5. Dry run lists them; a real run links one at a time, paced, and reports outcomes.

Each page read with `show_emails=true` adds one "check email" row to Talk's staff action log.

## Webhook setup (Talk admin)

Create a **new** webhook at `talk.dallasmakerspace.org/admin/api/web_hooks` (the Show & Tell one has a category
filter, and user events have no category so they would be dropped):

- **Payload URL:** `https://members.dallasmakerspace.org/webhook/discourse`
- **Content type:** `application/json`
- **Secret:** the same value as `ENV_DISCOURSE_WEBHOOK_SECRET`
- **Events:** individual events, then under User Event tick **User created** and **User confirmed email** only
- No category, tag or group filters. **Check TLS certificate:** on; **Active:** on

Use "Ping" and the recent-deliveries view to confirm 200s. The Talk API key (`app.discourse.apiKey`) must be a
Global key, or a granular one with `users:list`.
