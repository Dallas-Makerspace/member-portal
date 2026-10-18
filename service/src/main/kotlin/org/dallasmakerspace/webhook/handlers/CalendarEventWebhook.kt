package org.dallasmakerspace.webhook.handlers

import java.net.URI
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.calendar.CalendarService
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.ProfileSubscriptionService
import org.dallasmakerspace.notifications.templates.EventAnnouncementData
import org.dallasmakerspace.notifications.templates.plainExcerpt
import org.dallasmakerspace.notifications.topics.FollowingTopic
import org.dallasmakerspace.webhook.WebhookHandler
import org.dallasmakerspace.webhook.WebhookResult
import org.dallasmakerspace.webhook.models.CalendarEventWebhookData
import org.dallasmakerspace.webhook.models.CalendarEventWebhookEnvelope
import org.slf4j.LoggerFactory

private val CENTRAL_ZONE = ZoneId.of("America/Chicago")
private val UTC_ZONE = ZoneId.of("UTC")
private val EVENT_START_FORMATTER = DateTimeFormatter.ofPattern("EEE MMM d h:mm a")

/**
 * Webhook handler for "an organizer posted a new calendar event." Fans out an EVENT_ANNOUNCEMENT
 * notification to every member following the organizer - the piece that connects the follow feature
 * to real notifications. It also tells followers of the committee that owns the class
 * ([CommitteeClassAnnouncer]). The calendar system is internal-only, so it calls this endpoint directly
 * (no UI proxy needed, unlike genuinely external callers such as SparkPost) - same as the existing
 * groups-audit webhook.
 *
 * Calendar's WebhookComponent fires this same endpoint for the full event lifecycle (published,
 * updated, deleted, ...) wrapped in a `{event_type, occurred_at, data}` envelope; only
 * [EVENT_TYPE_PUBLISHED] triggers a notification here, everything else is a no-op success so
 * calendar doesn't see it as a failure.
 */
class CalendarEventWebhook
@Inject
constructor(
    private val profileSubscriptionService: ProfileSubscriptionService,
    private val notificationService: NotificationService,
    private val activeDirectoryService: IActiveDirectoryService,
    private val committeeClassAnnouncer: CommitteeClassAnnouncer,
    private val calendarService: CalendarService,
) : WebhookHandler() {

  private val logger = LoggerFactory.getLogger(CalendarEventWebhook::class.java)
  private val json = Json { ignoreUnknownKeys = true }

  companion object {
    private const val EVENT_TYPE_PUBLISHED = "event.published"
  }

  override fun getName() = "calendar-event"

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleWebhook(body: String): WebhookResult {
    val envelope =
        try {
          json.decodeFromString<CalendarEventWebhookEnvelope>(body)
        } catch (e: Exception) {
          logger.error("Invalid calendar-event webhook payload", e)
          return WebhookResult(false, "Invalid calendar-event webhook payload: ${e.message}")
        }

    if (envelope.eventType != EVENT_TYPE_PUBLISHED) {
      return WebhookResult(true, "Ignoring event_type=${envelope.eventType}")
    }

    val event = envelope.data
    val eventStart = formatEventStart(event.eventStart, event.eventStartTimezone)
    val followers = profileSubscriptionService.getFollowers(event.organizerUsername)
    return coroutineScope {
      // Looked up at most once, and only if someone is actually going to be emailed.
      val lookup = async(start = CoroutineStart.LAZY) { lookUpPreview(event) }
      val preview: EventPreviewProvider = { lookup.await() }
      val organizerSummary = notifyOrganizerFollowers(event, followers, eventStart, preview)
      val committeeSummary =
          announceToCommitteeFollowers(event, eventStart, followers.toSet(), preview)
      WebhookResult(true, "$organizerSummary; $committeeSummary")
    }
  }

  private suspend fun notifyOrganizerFollowers(
      event: CalendarEventWebhookData,
      followers: List<String>,
      eventStart: String,
      preview: EventPreviewProvider,
  ): String {
    if (followers.isEmpty()) return "No followers for ${event.organizerUsername}, nothing to send"

    val eventPreview = preview()
    val payload =
        json.encodeToString(
            EventAnnouncementData.serializer(),
            EventAnnouncementData(
                eventName = event.name,
                organizerName = resolveOrganizerName(event.organizerUsername),
                organizerUsername = event.organizerUsername,
                eventStart = eventStart,
                eventUrl = event.url,
                imageUrl = eventPreview.imageUrl,
                excerpt = eventPreview.excerpt))

    val result =
        notificationService.createForMembers(
            memberUsernames = followers,
            topic = FollowingTopic,
            templateName = "event-announcement",
            payload = payload,
            channel = Channel.EMAIL,
            dedupeKey = { follower -> "event-announcement-${event.id}-$follower" })
    return "Notified ${result.created} of ${followers.size} follower(s) of ${event.organizerUsername}" +
        " (duplicates=${result.duplicates}, failed=${result.failed})"
  }

  /**
   * Committee followers are a bonus on top of the organizer fan-out: a failure here (for example
   * the committee_subscriptions table not being migrated yet) is logged and never fails the
   * webhook, which calendar would report as a delivery error.
   */
  @Suppress("TooGenericExceptionCaught")
  private suspend fun announceToCommitteeFollowers(
      event: CalendarEventWebhookData,
      eventStart: String,
      alreadyNotified: Set<String>,
      preview: EventPreviewProvider,
  ): String =
      try {
        committeeClassAnnouncer.announce(event, eventStart, alreadyNotified, preview)
      } catch (e: Exception) {
        logger.warn("Committee follower announcement failed for event ${event.id}", e)
        "Committee followers: failed (${e.javaClass.simpleName})"
      }

  /** Each part is blank when the event has none or the lookup fails; the emails just omit it. */
  @Suppress("TooGenericExceptionCaught")
  private suspend fun lookUpPreview(event: CalendarEventWebhookData): EventPreview =
      try {
        val data = calendarService.getEventPreviewData(event.id)
        EventPreview(
            imageUrl = data?.imagePath?.let { absoluteImageUrl(event.url, it) }.orEmpty(),
            excerpt = data?.description?.let { plainExcerpt(it) }.orEmpty())
      } catch (e: Exception) {
        logger.warn("Preview lookup failed for event ${event.id}, sending without it", e)
        EventPreview()
      }

  /**
   * Resolves the organizer's full display name from AD. LDAP is blocking, so this is scoped to
   * [Dispatchers.IO] like the other AD lookups reachable from shared request-handling routes.
   */
  @Suppress("TooGenericExceptionCaught")
  private suspend fun resolveOrganizerName(username: String): String =
      withContext(Dispatchers.IO) {
        try {
          activeDirectoryService.getMembersByUsernameList(listOf(username))[username]?.displayName
              ?: username
        } catch (e: Exception) {
          logger.warn("Failed to resolve display name for organizer $username, using username", e)
          username
        }
      }

  /**
   * Parses [raw] as an offset/instant timestamp if it has one; otherwise treats it as a local
   * timestamp in [timezoneId] (falling back to UTC if that's missing or unrecognized).
   */
  @Suppress("TooGenericExceptionCaught")
  private fun formatEventStart(raw: String, timezoneId: String?): String =
      try {
        val eventStart =
            runCatching { OffsetDateTime.parse(raw) }
                .getOrElse {
                  val zone =
                      timezoneId?.let { id -> runCatching { ZoneId.of(id) }.getOrNull() }
                          ?: UTC_ZONE
                  LocalDateTime.parse(raw).atZone(zone).toOffsetDateTime()
                }
        eventStart.atZoneSameInstant(CENTRAL_ZONE).format(EVENT_START_FORMATTER)
      } catch (e: Exception) {
        logger.warn("Failed to parse eventStart '$raw' (timezone '$timezoneId'), using it verbatim", e)
        raw
      }
}

/**
 * Joins the calendar site's origin (taken from the event page URL, so nothing is hard-coded) with
 * a web path such as `/files/1/my flyer.jpg`, percent-encoding the path. Blank if either is unusable.
 */
internal fun absoluteImageUrl(eventUrl: String, path: String): String =
    try {
      val origin = URI(eventUrl)
      if (origin.scheme.isNullOrBlank() || origin.rawAuthority.isNullOrBlank()) ""
      else URI(origin.scheme, origin.rawAuthority, path, null, null).toASCIIString()
    } catch (e: java.net.URISyntaxException) {
      ""
    }

/** The optional extras shown next to an event in emails: an absolute image URL and a short teaser. */
data class EventPreview(val imageUrl: String = "", val excerpt: String = "")

/** Fetches the preview on first call; shared by both fan-outs so it is looked up at most once. */
typealias EventPreviewProvider = suspend () -> EventPreview
