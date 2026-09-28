# Profile Photo Upload - Product Requirements Document

## Overview

Let members set their own profile photo from the profile page, either by taking a selfie with their device camera or
by uploading an existing picture. Inspired by Facebook's profile page, a small camera button sits on the bottom-right
edge of the circular avatar; clicking it opens a menu of photo actions.

Before upload, an on-device face-detection model checks that the image is a usable headshot and gives actionable
coaching when it isn't (e.g. *"Face a light source like a window and try again"*). Validated photos are stored in S3
through a new **generic object-storage component** in the service, designed to be reused by future features (kiosk
waiver photos, report exports, event images, etc.).

## Goals

- Members can set a recognizable photo of themselves in under a minute, on desktop or phone
- Photos are consistently usable headshots (one face, lit, in focus, reasonably centered) so members can recognize each
  other in the space, in facepiles, and on staff-facing screens
- Validation uses a proven, off-the-shelf model — no custom training
- Introduce a reusable, testable, mockable S3 read/write abstraction rather than a one-off profile-photo uploader
- Ship dark behind a feature flag and pilot before full rollout

## Non-Goals

- Face **recognition** / identity matching (e.g. comparing the photo against the waiver kiosk photo). Detection only.
- Pushing the photo to Discourse, Discord, or AD `thumbnailPhoto` (see [Future Work](#future-work))
- General-purpose image editing (filters, stickers, text)
- Human moderation queue (admins can remove a photo after the fact; see [Moderation](#moderation))

## User Stories

### As a Member viewing my own profile

- I want to see a camera button on my avatar so I know I can change my photo
- I want to take a selfie right in the browser without leaving the page
- I want to upload a photo I already have on my phone/computer
- I want to crop/zoom so my face fills the circle
- I want clear, friendly guidance when my photo isn't good enough, and to retry immediately
- I want to remove my uploaded photo and go back to my Discourse avatar / default placeholder

### As a Member viewing someone else's profile

- I want to see their photo (no camera button, no edit affordance)

### As an Infrastructure Team Member

- I want to remove an inappropriate photo from any member's profile
- I want the photo storage to be private, encrypted, and least-privilege
- I want to reuse the same storage component for the next feature that needs S3

## Current State

**Implemented** (Steps 1–6 and 8 of the build; see [Implementation Phases](#implementation-phases)):

- `profile.html` renders `<img class="profile-avatar rounded-circle" id="profileAvatarImg">` (100×100, white border,
  drop shadow) from `avatar_url`, falling back to `static/images/profile-pic-not-set.svg`.
- `DMSMember.displayAvatarUrl()` (in `common-models`, shared by `service` and `ui`) is the single precedence rule:
  **uploaded photo → Discourse avatar**. Every avatar read site (`ProfileHandler`, its facepile, the dataviz reports,
  `FeaturedProjectsService`, and the five UI handlers that render avatars in committees/groups/search/suggested-events)
  goes through it. The old `avatar_url` DB column and `DMSMember` field this doc originally described as a third,
  higher-priority source turned out to be dead in production (0/5601 rows populated) and were dropped entirely.
- Discourse-avatar-URL normalization (absolute/protocol-relative/relative path handling, `{size}` substitution) is
  centralized as `normalizeAvatarUrl()`, also in `common-models` — previously reimplemented, inconsistently, in about
  a dozen places.
- The service depends on AWS SDK v2 for both `bedrockruntime` and now `s3`, authenticating with
  `DefaultCredentialsProvider` using `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` from the environment (same vars,
  shared across both integrations).
- The camera button, crop flow, client-side validation (including MediaPipe face detection), and the upload/remove
  backend are all built — see the sections below, which describe the shipped behavior, not just the original plan.

**Not yet done** (Step 7): the Playwright e2e test, and the operational rollout itself (nothing here is live -
`profile-photo.enabled` defaults off, and the DB migration/S3 bucket haven't been applied in any real environment).

## User Experience

### 1. Camera button

- A 32px circular button overlapping the bottom-right of the avatar (≈ 4 o'clock position), light-gray background,
  dark camera icon, subtle shadow — matching the reference screenshot.
- Rendered **only when `is_self` is true** and the feature flag is enabled for the viewer.
- Accessible: real `<button>` with `aria-label="Update profile photo"`, keyboard focusable, visible focus ring,
  minimum 44×44px tap target (invisible padding is fine).
- Hover/focus on desktop darkens the background slightly.

### 2. Action menu

Clicking the button opens a Bootstrap dropdown anchored to the button:

| Item | Shown when | Action |
| --- | --- | --- |
| 📷 Take a selfie | Browser exposes `navigator.mediaDevices.getUserMedia` | Opens the camera modal |
| ⬆️ Upload a picture | Always | Opens the native file picker |
| 🗑️ Remove photo | Member currently has an uploaded photo | Confirm, then delete |

On mobile, "Upload a picture" uses `<input type="file" accept="image/jpeg,image/png">` (no `capture` attribute, so the
OS offers both library and camera).

### 3. Take a selfie (camera modal)

- Modal with a live, **mirrored** preview from the front camera (`getUserMedia({ video: { facingMode: "user",
  width: { ideal: 1280 }, height: { ideal: 720 } } })`).
- A circular overlay/guide shows where the face should go.
- **Live guidance:** while the preview is running, face detection runs on video frames (throttled to ~5 fps) and shows
  a single status line under the preview, e.g. "Move closer", "Center your face", "Looking good!". The shutter button
  is always enabled — live guidance is advisory; the capture is validated the same way as an upload.
- "Capture" freezes the frame (un-mirrored in the saved image) → goes to the crop step.
- Camera stream is stopped (`track.stop()`) whenever the modal closes, on any path.
- Permission denied / no camera / insecure context: show an inline message ("We couldn't access your camera. Check
  your browser's camera permission, or upload a picture instead.") with an "Upload instead" button.

### 4. Crop & zoom

- Both paths (selfie and upload) land on a crop step with a fixed **1:1** crop area displayed as a circle, drag to pan,
  pinch/scroll/slider to zoom. Use **Cropper.js** (loaded from jsDelivr like Bootstrap).
- The crop is pre-positioned around the detected face box when one was found (face box + ~60% padding, clamped to
  image bounds), so most members can just click "Next".

### 5. Validation & feedback

- Runs on the cropped image when the member clicks "Next". Target: < 500 ms on a mid-range phone after the model is
  loaded.
- **Pass:** show the circular preview, "Save photo" and "Retake / Choose another".
- **Fail:** show the preview with the **single most important** suggestion (see rule table below) and "Try again"
  (returns to camera or file picker, whichever the member used). A secondary "Use anyway" link is shown **only** for
  soft failures (lighting, blur, off-center); hard failures (no face, multiple faces, face too small) cannot be
  overridden.
- While saving: spinner on the button, modal can't be dismissed, then the avatar on the page updates in place without
  a full reload. Error toast on failure with a retry.

### 6. Remove photo

- Confirmation dialog ("Remove your profile photo? Your Discourse avatar will be shown instead."), then `DELETE`,
  then the avatar updates in place.

## Face Validation

### Library choice

Use **MediaPipe Tasks Vision – Face Detector** (`@mediapipe/tasks-vision`, Apache-2.0) in the browser:

- Ready-made, maintained by Google, runs fully on-device via WASM/WebGL — no image leaves the device for validation
- Model: **BlazeFace short-range** (`blaze_face_short_range.tflite`, ~230 KB), designed for front-camera selfies
- Returns per-face bounding box, confidence score, and 6 keypoints (both eyes, nose tip, mouth, both ear tragions),
  which is enough for count, size, centering, and rough yaw checks
- Works in `IMAGE` mode (uploads) and `VIDEO` mode (live preview)

Loading:

- Lazy-load the library and model **only when the member opens the photo flow**, never on page load (the profile page
  is heavily used and must not regress).
- Load the JS bundle from jsDelivr pinned to an exact version with SRI where possible; **self-host the `.tflite` model
  and WASM files** under `/static/vendor/mediapipe/` so the flow doesn't depend on a Google CDN at runtime.
- If the library fails to load (old browser, blocked CDN), fall back to "validation unavailable" mode: skip ML checks,
  still run the non-ML checks below, and still allow saving. Log the failure client-side to the existing app logging
  endpoint if one exists; otherwise `console.warn`.

Alternatives considered:

| Option | Why not (for v1) |
| --- | --- |
| face-api.js | Unmaintained since 2020, larger models |
| TensorFlow.js BlazeFace model package | Superseded by MediaPipe Tasks; same model, older API |
| MediaPipe Face Landmarker (478 points) | ~3 MB model; overkill for "is this a usable headshot" |
| AWS Rekognition `DetectFaces` server-side | Excellent signals (Brightness, Sharpness, Pose, EyesOpen, Sunglasses, FaceOccluded) and tamper-proof, but adds a paid per-image network round trip and sends every attempt off-device. Kept as an optional **server-side** gate — see [Future Work](#future-work). |

### Checks and suggestions

Checks run in this order; the **first failing** check determines the message shown. Thresholds are initial values
and must live in one JS config object so they can be tuned without touching logic.

| # | Check | How | Threshold (initial) | Severity | Suggestion shown |
| --- | --- | --- | --- | --- | --- |
| 1 | Decodable image | `createImageBitmap` succeeds | — | Hard | "We couldn't read that file. Try a JPEG or PNG photo." |
| 2 | Minimum resolution | Source crop ≥ N px square | 256 px | Hard | "This photo is too small. Try a larger or higher-quality picture." |
| 3 | Face present | ≥ 1 detection with score ≥ T | T = 0.6 | Hard | "We couldn't find a face. Make sure your face is visible and try again." |
| 4 | Single face | Exactly 1 detection ≥ T | — | Hard | "We found more than one face. Profile photos should show just you." |
| 5 | Face size | Face box width ÷ crop width | ≥ 0.30 | Hard (< 0.20), Soft (0.20–0.30) | "Move closer to the camera so your face fills more of the circle." |
| 6 | Face not too close | Face box fully inside crop, width ratio ≤ 0.85 | ≤ 0.85 | Soft | "You're a little too close — move back slightly." |
| 7 | Centered | Face box center offset from crop center | ≤ 15% of width on each axis | Soft | "Center your face in the circle." |
| 8 | Facing the camera | Yaw proxy: nose-tip horizontal position between the two eye keypoints | nose within middle 60% of inter-eye span | Soft | "Look straight at the camera." |
| 9 | Too dark | Mean luma (Rec. 601) of face box region | ≥ 70 / 255 | Soft | "Your photo is too dark. Face a light source like a window and try again." |
| 10 | Too bright | Mean luma of face box / % clipped (≥ 250) pixels | ≤ 215 and < 25% clipped | Soft | "Your photo is overexposed. Move away from direct light or a bright window behind the camera." |
| 11 | Backlit | Face-box mean luma ÷ full-image mean luma | ≥ 0.75 | Soft | "The light is behind you. Turn to face the light source and try again." |
| 12 | Uneven lighting | Luma difference between left and right halves of face box | ≤ 60 | Soft | "One side of your face is in shadow. Face the light more directly." |
| 13 | Blurry | Variance of Laplacian on grayscale face box (downscaled to 128 px) | ≥ 60 | Soft | "Your photo looks blurry. Hold still (or wipe your camera lens) and try again." |

Notes:

- Checks 9–13 are plain canvas pixel math, not ML — this is intentional; don't pull in a second model for them.
- All messages live in one map keyed by check id so copy can be edited in one place.
- Record which check failed (id only, no image) in a lightweight client metric if/when the UI gets client-side
  analytics; until then, the server logs an `override=true` flag when a soft failure was saved via "Use anyway" so
  thresholds can be tuned from Kibana.

### Trust model

Client-side validation is a **UX aid, not a security control** — a determined member can bypass it. That's
acceptable because the only victim is their own profile photo and admins can remove bad photos. The server enforces
the non-ML guarantees (type, size, dimensions, re-encode, EXIF strip) described below.

## Image Processing

Client (before upload):

- Export the crop to a **512×512 JPEG, quality 0.85** via canvas (`canvas.toBlob`) — typically 40–120 KB.
- Canvas export naturally drops EXIF (including GPS) and applies EXIF orientation already honored by
  `createImageBitmap(file, { imageOrientation: "from-image" })`.

Server (authoritative), in `ProfilePhotoService`:

- The UI reads the browser's `multipart/form-data` `photo` part (bounded read, rejecting > **2 MB**) and forwards the
  raw bytes to the service as a `PUT` body; the service itself does no multipart parsing.
- Sniff magic bytes; accept **JPEG/PNG only** regardless of the declared `Content-Type` (`415` otherwise) — the client
  always exports JPEG, so there was no need for a WebP decoder.
- Decode with ImageIO, reject if either dimension < 256 or > 4096, or if not ~square (aspect within 0.95–1.05).
- **Re-encode** to 512×512 JPEG (q=0.85). This strips all metadata (GPS, device info) and neutralizes polyglot/malformed
  files. Only the re-encoded bytes are stored.
- Decoding/encoding is blocking — run on `Dispatchers.IO`.
- In-memory rate limit: 10 saves per member per 24h, tracked with an atomic `ConcurrentHashMap.compute` (a
  `computeIfAbsent`-then-`add` version had a race under concurrent uploads from the same user).

## Storage & Serving

### Generic object-storage component (service)

New package `org.dallasmakerspace.storage`:

```kotlin
interface IObjectStore {
  suspend fun put(key: ObjectKey, bytes: ByteArray, contentType: String, metadata: Map<String, String> = emptyMap()): StoredObject
  suspend fun get(key: ObjectKey): ObjectContent?          // null when missing
  suspend fun head(key: ObjectKey): StoredObject?
  suspend fun delete(key: ObjectKey)
  suspend fun list(prefix: ObjectKey, limit: Int = 1000): List<StoredObject>
  fun presignedGetUrl(key: ObjectKey, ttl: Duration): URL
  fun presignedPutUrl(key: ObjectKey, contentType: String, ttl: Duration): URL
}
```

Requirements:

- **Namespacing:** each consumer gets an `ObjectStore` scoped to its own prefix via a small factory, e.g.
  `objectStoreFactory.forNamespace(StorageNamespace.PROFILE_PHOTOS)` → keys are written under `profile-photos/…`.
  A namespace cannot read/write outside its prefix (enforced in the scoped wrapper; keys containing `..`, leading `/`,
  or control characters are rejected).
- `ObjectKey` is a value class that validates the key; consumers never build raw S3 keys by string concatenation.
- **Implementations:**
  - `S3ObjectStore` — AWS SDK v2 `S3AsyncClient` (CRT or Netty client) + `S3Presigner`, awaited with
    `kotlinx-coroutines-jdk8` `.await()` so no blocking on Ktor threads. Region and bucket from config.
  - `InMemoryObjectStore` — used when `useMockServices()` is true (tests and `runAll` default), so no AWS access is
    needed locally. Presigned URLs return a local service URL that serves from memory.
- **DI:** provided from a new `StorageModule`; the `@Provides` methods for the S3 client, presigner, and store **must**
  be `@Singleton` (see the connection-pool incident with unscoped `@Provides`).
- **Errors:** wrap SDK exceptions in `ObjectStoreException` (with `isNotFound` / `isRetryable`), never leak SDK types
  through the interface.
- **Observability:** log op, namespace, key, size, latency, outcome at `INFO`/`WARN`; never log object bytes or
  presigned URLs (they are bearer credentials).
- **Config** (`application.conf`, overridable by env):
  - `app.storage.s3.bucket` ← `S3_BUCKET`
  - `app.storage.s3.region` ← `S3_REGION` (default `us-east-1`)
  - Credentials via `DefaultCredentialsProvider` (same as Bedrock). Add the vars to `service/.env.template` with
    placeholder values.
- **Tests:** contract test suite written once against `IObjectStore` and run against `InMemoryObjectStore` and
  (optionally, tagged/opt-in) against S3/MinIO via Testcontainers.

### AWS resources

- **New dedicated bucket** for member-portal assets (placeholder name: `example-member-portal-assets`) rather than
  reusing an existing backup/report bucket, so lifecycle, access, and cost are isolated.
  - S3 **Block Public Access: all four settings on**
  - Default encryption **SSE-S3**
  - Object ownership: **bucket owner enforced** (ACLs disabled)
  - Versioning: **on**, with a lifecycle rule expiring noncurrent versions after 30 days (lets an admin recover a
    wrongly removed photo, bounds cost)
  - Lifecycle rule aborting incomplete multipart uploads after 1 day
  - No CORS config needed (browser never talks to S3 directly for uploads in v1)
- **IAM:** a new customer-managed policy attached to the IAM identity the service already uses for Bedrock, scoped to
  the bucket:
  - `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` on `arn:aws:s3:::<bucket>/*`
  - `s3:ListBucket` on `arn:aws:s3:::<bucket>` (needed for `list` and correct 404-vs-403 behavior)
  - Future namespaces can be tightened to `<bucket>/<namespace>/*` if a separate identity is ever introduced.
- Provision via the AWS CLI with the exact commands captured in `ops/` (or Terraform if the team adopts it) so the setup
  is reproducible; do not click-ops without recording it.

### Profile-photo data model

- Keys are **immutable and unique per upload**: `profile-photos/{username}/{uuid}.jpg`. A new upload never overwrites
  the previous object, so CDN/browser caches never serve a stale image under a changed URL.
- Store the **key**, not a URL, in the DB: `ProfileTable`/`ProfileDAO` have `profile_photo_key varchar(512) null` and
  `profile_photo_updated_at datetime null` (UTC). ALTERs are in `service/docs/sql/profile-photo-migration.sql`
  (not yet applied to prod).
- There is no `avatar_url` column anymore — it was dropped as a separate cleanup step once it was confirmed unused (0
  of 5601 prod rows populated) and every read site had moved to `DMSMember.displayAvatarUrl()`. See
  [Current State](#current-state).
- On successful save: write new object → update DB row → best-effort delete of the previous object (a failed delete is
  logged, not surfaced; versioning/lifecycle cleans up). If the DB write fails after the object upload succeeds, the
  newly-uploaded (now-orphaned) object is deleted too.
- On remove: clear the DB columns, then delete the object.

### Serving

- The bucket stays private, and the browser **never talks to S3 directly** — photos are streamed byte-for-byte through
  the app, the same shape as the existing `BambuCameraProxyClient` binary proxy. No presigned URLs are used for
  serving (`presignedGetUrl` on `IObjectStore` exists for future use, e.g. `Future Work`'s direct-to-S3 uploads, but
  isn't called anywhere in this feature).
- Stable UI URL: `GET /avatar/@{username}` (`AvatarHandler`, session-authenticated). It calls
  `ProfilePhotoClient.streamOrRedirect`, which requests `GET /members/{username}/profile-photo` from the service and
  streams the response bytes straight through with the service's `Content-Type`/`Cache-Control` headers.
- The service's `GET /members/{username}/profile-photo` returns the raw JPEG bytes with `200`, or `404` if the member
  has no uploaded photo. It is not gated by the feature flag.
- On `404` (or any error), `AvatarHandler` falls back to a redirect to the member's Discourse avatar
  (`normalizeAvatarUrl(member.discourseAvatarUrl)`) or the placeholder SVG, so every consumer (profile, facepile,
  future pages) can point at one `/avatar/@{username}` URL regardless of which source the photo actually comes from.
  The member lookup for that fallback only happens when there's no photo to stream.
- The service responds with `Cache-Control: private, max-age=31536000, immutable`, so every `profilePhotoUrl` it
  hands out carries a `?v=` derived from the storage key (`profilePhotoUrl(username, key)` in `DMSMemberMapping.kt`).
  The key is a new UUID per upload, so the URL changes on every upload - even two within the same second.

### Avatar precedence

Everywhere an avatar is shown: **uploaded photo → Discourse avatar → `profile-pic-not-set.svg`**, via the shared
`DMSMember.displayAvatarUrl()` extension in `common-models` (`profilePhotoUrl ?: discourseAvatarUrl`) piped through
`normalizeAvatarUrl()`. This is wired into `ProfileHandler` (header + facepile), the service dataviz reports,
`FeaturedProjectsService`, and the UI's committee/group/search/suggested-events handlers — see
[Current State](#current-state).

## API

Service (`X-Api-Key` / `X-Api-Client` auth, like other routes):

| Method | Path | Body / Response |
| --- | --- | --- |
| `PUT` | `/members/{username}/profile-photo` | Raw `image/jpeg`/`image/png` body (no multipart); `X-Validation-Override: true` header when the member used "Use anyway" → `200` `ApiResponse` with `photoUrl`, or `413`/`415`/`429` on rejection |
| `DELETE` | `/members/{username}/profile-photo` | `200` `ApiResponse`; allowed for the actor themself or an Infrastructure-group actor |
| `GET` | `/members/{username}/profile-photo` | Raw photo bytes, `200`, or `404` if none — **not** a redirect |

UI (session auth). The UI has no general CSRF protection today; these routes must at minimum require a same-origin
`Origin`/`Referer` header and rely on the session cookie's `SameSite=Lax`:

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/profile/@{username}/photo` | Only when session user == `{username}`; multipart `photo` part (+ optional `override=true` form field), proxies to service `PUT` |
| `DELETE` | `/profile/@{username}/photo` | Self, or Infrastructure admin (moderation) |
| `GET` | `/avatar/@{username}` | Stable image URL described above |

Both `POST`/`DELETE` in `ProfilePhotoHandler` reject the request with `403` unless the `Origin` (or `Referer`) header's
host matches the `Host` header — the app's only CSRF defense, since there's no framework-level protection. Authorization
is enforced in the UI handler **and** re-checked in the service using the `X-Username` header the UI already sends.

Rate limiting: max **10 uploads per member per 24h** (in-memory counter per service instance is fine for v1); `429`
with "You've changed your photo a lot today — try again tomorrow."

## Moderation

- Infrastructure admins see a "Remove photo" action in the camera menu when viewing any member's profile (the camera
  button is shown to them on every profile, with only the remove action).
- Removals by an admin are logged with actor, subject, and timestamp; the member sees their Discourse avatar/placeholder
  afterward. (Notification to the member is out of scope for v1.)

## Feature Flag & Rollout

```kotlin
val PROFILE_PHOTO_ENABLED =
    ConfigKey(
        key = "profile-photo.enabled",
        description = "Let members upload/take a profile photo from their profile page",
        category = "Profile Photo",
        defaultValue = FeatureFlagValue(enabled = false),
        type = ConfigValueType.FeatureFlagType,
    )
```

- Checked via `MemberServiceClient.isFeatureEnabled(...)` for the **viewer**: controls the camera button and the upload
  endpoints. Serving already-uploaded photos (`/avatar/…` and precedence) is **not** gated, so turning the flag off
  hides the editing UI without making existing photos disappear.
- Rollout: ship dark → Infrastructure group pilot → a handful of volunteer members → review override rate and failed
  check distribution in Kibana, tune thresholds → `enabled = true`.

## Privacy & Compliance

- Face detection runs entirely in the browser; no face geometry, embeddings, or detection results are sent to or
  stored on the server. We store only the final photo. This keeps the feature on the "photograph" side of biometric
  privacy laws (e.g. Texas CUBI) — **no face recognition, no stored face templates.** Any future face-matching
  feature needs its own consent/privacy review.
- EXIF/GPS stripped client-side and again server-side via re-encode.
- Camera is only activated after an explicit "Take a selfie" click, and released when the modal closes.
- Photos are visible only to authenticated portal users.
- When a member account is purged per existing retention processes, their photo objects must be deleted too (hook into
  whatever account-cleanup path exists at implementation time; verify one exists).

## Non-Functional Requirements

- **Performance:** profile page load unchanged when the flow isn't opened (all new JS lazy-loaded). Model + WASM load
  < 2 s on broadband, cached thereafter. Save round trip < 2 s p95.
- **Browser support:** current Chrome/Edge/Firefox/Safari, iOS Safari 16+, Android Chrome. Camera requires HTTPS
  (production already is; `localhost` works for dev).
- **Accessibility:** modal focus trap, all controls keyboard operable, validation messages in an `aria-live="polite"`
  region, sufficient contrast on the camera button over any photo.
- **Templates:** the camera button, dropdown, and modal live in `fragments/profile-photo.html`, included from
  `profile.html` rather than growing it further. Logic lives in `static/js/profile-photo.js` (orchestration) and
  `static/js/photo-checks.js` (pure, unit-tested validation functions), following the required script includes
  (Bootstrap bundle, `app.js`, `dms-page-loader.js`).

## Acceptance Criteria

1. With the flag off for me, my profile page looks and behaves exactly as today.
2. With the flag on, a camera button appears on my own avatar only; not on other members' profiles (unless I'm an
   Infrastructure admin, in which case only "Remove photo" is offered there).
3. "Take a selfie" shows a mirrored live preview with live guidance; denying camera permission shows the fallback
   message with an "Upload instead" option.
4. "Upload a picture" accepts JPEG/PNG and rejects other types with a friendly message.
5. A dark selfie shows "Face a light source like a window and try again"; a photo with two people is rejected with no
   override; a slightly off-center photo can be saved via "Use anyway".
6. After saving, the new photo appears immediately on my profile and in facepiles on other pages after refresh; the
   stored object is 512×512 JPEG with no EXIF.
7. "Remove photo" reverts to my Discourse avatar (or the placeholder).
8. The S3 bucket is not publicly readable; an unsigned direct object URL returns 403 (the app itself never generates
   presigned URLs for this feature — it always streams through the service).
9. `runAll` with mock services works end-to-end without AWS credentials (in-memory store).
10. `IObjectStore` has contract tests; profile-photo routes have tests for authz (self vs other vs admin), size/type
    rejection, and replace/delete of prior objects. Detekt and existing tests pass.

## Implementation Phases

Built as 8 small commits on `master`, each independently verified (compile, tests, detekt/ktfmt) before moving on:

1. **Generic object store** (`org.dallasmakerspace.storage`): `IObjectStore`, `S3ObjectStore`, `InMemoryObjectStore`,
   `ObjectKey`, `NamespacedObjectStore`/`StorageNamespace`, `StorageModule` DI, contract tests. No user-visible change.
2. **Profile photo backend:** `profile_photo_key`/`profile_photo_updated_at` columns, `ProfilePhotoService`
   (validate/re-encode/rate-limit), `ProfilePhotoRoutes` (`PUT`/`DELETE`/`GET`), `PROFILE_PHOTO_ENABLED` flag.
3. **Centralize avatar normalization:** moved `normalizeAvatarUrl()` into `common-models`, deleted ~a dozen duplicate
   (one buggy) reimplementations across dataviz reports and `FeaturedProjectsService`. Not originally planned as its
   own phase — pulled out once the duplication was found while starting phase 4, so phase 4 had one function to call.
4. **Avatar precedence everywhere:** `DMSMember.displayAvatarUrl()`, `AvatarHandler` + `ProfilePhotoClient`
   (streaming), and switching every remaining read site (profile, committees, groups, search, suggested-events) onto
   it.
5. **UI upload flow:** `ProfilePhotoHandler`, `profile-photo.html` fragment (button/menu/modal), `profile-photo.js`
   (camera capture, Cropper.js crop, save/remove), `photo-checks.js` (non-ML checks: resolution, lighting, blur).
6. **MediaPipe face validation:** lazy-loaded `@mediapipe/tasks-vision` + self-hosted BlazeFace model, face-geometry
   checks (size/centering/yaw), live selfie guidance, crop pre-positioning on the detected face. Combined into one
   commit with phase 5 (both were built and reviewed together).
7. **Not yet done:** Playwright e2e test; this doc update.
8. **Drop `avatar_url`:** removed the dead DB column, `DMSMember` field, and repository copy logic, once phase 4 had
   moved every reader off it and prod was confirmed to have it NULL everywhere. Kept as a separate, low-priority
   cleanup commit rather than folded into phase 4.

Rollout (user-side, not yet done): run `service/docs/sql/profile-photo-migration.sql` against prod → provision the S3
bucket/IAM policy → set `OBJECT_STORE_BUCKET`/`OBJECT_STORE_REGION` on docker-h0 → deploy → pilot via
`enabledGroupNames: ["Infrastructure"]` → tune thresholds from override-rate data in Kibana → `enabled = true`.

## Future Work

- Server-side AWS Rekognition `DetectFaces` as an authoritative gate (and to catch sunglasses/eyes closed/occlusion),
  behind its own flag, if client-side bypass or quality becomes a problem.
- Sync the uploaded photo to Discourse (`/users/{username}/preferences/avatar/pick`) and/or AD `thumbnailPhoto`.
- Multiple renditions (64/128/256/512) generated at upload time for facepiles.
- Reuse `IObjectStore` for kiosk waiver photos, generated report exports, and event images.
- Direct-to-S3 uploads via `presignedPutUrl` for large files (would need bucket CORS).

## Open Questions

1. Should the uploaded photo override Discourse everywhere immediately, or should members opt in to "use this photo
   on the forum too" once Discourse sync exists?
2. ~~Is Infrastructure the right group for moderation, or should it be a dedicated moderator AD group?~~ **Resolved:**
   Infrastructure (`Permission.MANAGE_MEMBERS`) — implemented as-is, no dedicated group.
3. Should front-desk/staff tools show this photo for member identification? If so, do we require a photo refresh
   cadence or block overrides of soft failures?
4. Retention: keep noncurrent versions 30 days — acceptable, or should removed photos be purged immediately?

## Flow Diagram

See [`upload-flow.mmd`](upload-flow.mmd).
