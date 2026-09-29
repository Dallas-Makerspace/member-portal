# Notifications & follows

Member notifications are an outbox: producers insert rows into `notifications`, and two background
loops in the service deliver them.

## Pipeline

1. **Create:** `NotificationService.create(...)` for a single member, or `createForMembers(...)`
   for a fan-out. A fan-out renders the template once and checks membership with one AD lookup.
   This step resolves suppression, active membership, and preferences, and then stores the row as:
   - `QUEUED`: immediate delivery, held for 5 minutes (`worker.hold-seconds`) so a burst goes
     out as one email.
   - `IN_DIGEST`: `next_attempt_at` is set to the next digest window (`DigestSchedule`: daily at
     8 AM Central, weekly on Fridays at 8 AM).
   - `SKIPPED` / `SUPPRESSED`: stored but not sent, so they still show in the member's inbox.
2. **`NotificationWorker`** (every 5s) handles immediate delivery. Once a member's earliest held
   row is due, it atomically claims *all* of that member's queued rows and sends them as one email
   per channel. A single row is sent as rendered; several get a topic-specific subject (e.g.
   "3 new events from members you follow"). Failures are retried with backoff. Each pass first
   requeues rows stuck in `SENDING` for more than 15 minutes (a crash mid-send).
   - **Daily cap** (`notifications.per-member-immediate-daily-cap`, default 15) is a safety valve.
     It counts emails, so a combined email counts once. The email that reaches the cap tells the
     member and links to preferences; anything after that goes to the next daily digest.
3. **`DigestProcessor`** (every 60s) handles digests. It picks *members* whose digest window has
   arrived, claims all of that member's due rows, and sends **one** message per channel. A
   transient failure keeps the rows `IN_DIGEST` with backoff, so the retry is still a single
   digest and never individual emails.

### No duplicates

- **Same event emitted twice:** `dedupe_key` is unique. The calendar webhook uses
  `event-announcement-<eventId>-<username>`.
- **Two workers running at once** (for example, during a deploy): `claim()` is a conditional
  update, so only one worker sends a given row.
- **A member's digest split across batches:** digests are selected by member, not by row batch.
- **Identical content in one digest:** `DigestBodyBuilder` shows it once.

## Producers

Both feed the single `FollowingTopic` ("Updates from people you follow", stored key
`EVENT_ANNOUNCEMENT`), so members have one preference toggle for everything they follow.

- **Events:** calendar calls `POST /webhook/calendar-event` (internal) when an event is published.
- **Projects:** Discourse calls the UI's public `POST /webhook/discourse`, which verifies the
  `X-Discourse-Event-Signature` HMAC and forwards `{event, payload}` to the service's
  `discourse-post` handler (`DiscoursePostWebhook`). It notifies followers when a member posts an
  image in a monthly Show & Tell topic - exactly the posts the profile's Featured Projects list
  shows, since both use `ShowAndTellRules`. `post_created` and `post_edited` are both handled
  (photos are often added in an edit); an edit only counts for posts under 7 days old, and the
  per-post/per-follower dedupe key (`project-post-<postId>-<username>`) makes repeats harmless.
  It also drops the poster's cached Featured Projects so their profile updates promptly.

### Discourse webhook setup

A Discourse admin creates this at `talk.dallasmakerspace.org/admin/api/web_hooks`:

- **Payload URL:** `https://members.dallasmakerspace.org/webhook/discourse`
- **Content type:** `application/json`
- **Secret:** the same value as the UI's `ENV_DISCOURSE_WEBHOOK_SECRET`
- **Events:** Post Event (created, edited)
- **Category filter:** Show & Tell
- **Check TLS certificate:** on; **Active:** on

Use the webhook's "Ping" and recent-deliveries view to confirm 200 responses. Discourse disables a
webhook that keeps failing, so the handler returns 200 for everything it deliberately ignores.

## Adding a topic

1. Add an `object MyTopic : NotificationTopic` with a stable `key`. Set
   `visibleInPreferences = true` once something actually produces it.
2. Add a `NotificationTemplate<MyData>` that references the topic.
3. Register the template in your feature's Dagger module:
   `@Provides @IntoSet fun ...(): NotificationTemplate<*>`. The set of known topics is derived from
   the registered templates.
4. Emit notifications through `NotificationService`. Always pass a dedupe key for anything that
   can be retried.

## Adding a channel

Implement `Notifier`, which resolves its own recipient from the username and sets
`supportsDigest` if the channel can batch. Contribute it with
`@Provides @Singleton @IntoMap @ChannelKey(Channel.X)` in `NotificationsModule`. A channel without
a notifier is rejected at create time and hidden from preferences, and digest preferences fall back
to immediate delivery on channels that can't batch.

## Security

- The service's `/notifications/*` routes are authorized by the UI's API credentials, not by the
  end user. The UI's generic `/backend-api` proxy therefore refuses `notifications/*` and
  `webhook/*` (`BackendApiHandler`). Member access goes through `NotificationsInboxHandler`
  (self or admin only).
- `POST /webhook/notifications` requires `NOTIFICATIONS_WEBHOOK_TOKEN`. The dev default is rejected
  outside development.

## UI

- Follower and following lists use `fragments/member-grid.html`, the same grid as group member
  lists.
- The profile Follows card updates in place when you follow or unfollow.
- The notifications page shows member-friendly status labels and reasons. Raw provider errors are
  shown to admins only.
- Members who got 10 or more immediate emails in the last 7 days see a one-click "Switch to daily
  digest" suggestion on the preferences card.
