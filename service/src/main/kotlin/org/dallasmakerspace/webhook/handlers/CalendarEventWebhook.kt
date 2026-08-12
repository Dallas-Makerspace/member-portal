package org.dallasmakerspace.webhook.handlers

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.CreateNotificationRequest
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.ProfileSubscriptionService
import org.dallasmakerspace.notifications.topics.EventAnnouncementTopic
import org.dallasmakerspace.webhook.WebhookHandler
import org.dallasmakerspace.webhook.WebhookResult
import org.dallasmakerspace.webhook.models.CalendarEventWebhookEnvelope
import org.slf4j.LoggerFactory

private val CENTRAL_ZONE = ZoneId.of("America/Chicago")
private val UTC_ZONE = ZoneId.of("UTC")
private val EVENT_START_FORMATTER = DateTimeFormatter.ofPattern("EEE MMM d h:mm a")

/**
 * Webhook handler for "an organizer posted a new calendar event." Fans out an EVENT_ANNOUNCEMENT
 * notification to every member following the organizer - the piece that connects the follow feature
 * to real notifications. The calendar system is internal-only, so it calls this endpoint directly
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
    val followers = profileSubscriptionService.getFollowers(event.organizerUsername)
    if (followers.isEmpty()) {
      return WebhookResult(true, "No followers for ${event.organizerUsername}, nothing to send")
    }

    val payload =
        json.encodeToString(
            EventAnnouncementPayload.serializer(),
            EventAnnouncementPayload(
                eventName = event.name,
                organizerName = resolveOrganizerName(event.organizerUsername),
                organizerUsername = event.organizerUsername,
                eventStart = formatEventStart(event.eventStart, event.eventStartTimezone),
                eventUrl = event.url))

    var sent = 0
    for (follower in followers) {
      try {
        notificationService.create(
            CreateNotificationRequest(
                memberUsername = follower,
                topic = EventAnnouncementTopic,
                templateName = "event-announcement",
                payload = payload,
                channel = Channel.EMAIL,
                dedupeKey = "event-announcement-${event.id}-$follower"))
        sent++
      } catch (e: Exception) {
        logger.error("Failed to create event-announcement notification for $follower", e)
      }
    }
    return WebhookResult(
        true, "Notified $sent of ${followers.size} follower(s) of ${event.organizerUsername}")
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

@Serializable
private data class EventAnnouncementPayload(
    val eventName: String,
    val organizerName: String,
    val organizerUsername: String,
    val eventStart: String,
    val eventUrl: String,
)
