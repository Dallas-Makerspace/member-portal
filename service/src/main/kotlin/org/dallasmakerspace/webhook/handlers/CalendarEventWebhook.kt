package org.dallasmakerspace.webhook.handlers

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
import org.dallasmakerspace.notifications.NotificationTopic
import org.dallasmakerspace.notifications.ProfileSubscriptionService
import org.dallasmakerspace.webhook.WebhookHandler
import org.dallasmakerspace.webhook.WebhookResult
import org.dallasmakerspace.webhook.models.CalendarEventWebhookRequest
import org.slf4j.LoggerFactory

private val CENTRAL_ZONE = ZoneId.of("America/Chicago")
private val EVENT_START_FORMATTER = DateTimeFormatter.ofPattern("EEE MMM d h:mm a")

/**
 * Webhook handler for "an organizer posted a new calendar event." Fans out an EVENT_ANNOUNCEMENT
 * notification to every member following the organizer - the piece that connects the follow feature
 * to real notifications. This service is only reachable inside the network, so the real auth
 * boundary for the calendar system (an external caller) is the UI's public-facing proxy handler,
 * not this endpoint directly - see CalendarEventWebhookHandler in the ui module.
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

  override fun getName() = "calendar-event"

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleWebhook(body: String): WebhookResult {
    val request =
        try {
          json.decodeFromString<CalendarEventWebhookRequest>(body)
        } catch (e: Exception) {
          logger.error("Invalid calendar-event webhook payload", e)
          return WebhookResult(false, "Invalid calendar-event webhook payload: ${e.message}")
        }

    val followers = profileSubscriptionService.getFollowers(request.organizerUsername)
    if (followers.isEmpty()) {
      return WebhookResult(true, "No followers for ${request.organizerUsername}, nothing to send")
    }

    val payload =
        json.encodeToString(
            EventAnnouncementPayload.serializer(),
            EventAnnouncementPayload(
                eventName = request.eventName,
                organizerName = resolveOrganizerName(request.organizerUsername),
                organizerUsername = request.organizerUsername,
                eventStart = formatEventStart(request.eventStart),
                eventUrl = request.eventUrl))

    var sent = 0
    for (follower in followers) {
      try {
        notificationService.create(
            CreateNotificationRequest(
                memberUsername = follower,
                topic = NotificationTopic.EVENT_ANNOUNCEMENT,
                templateName = "event-announcement",
                payload = payload,
                channel = Channel.EMAIL,
                dedupeKey = "event-announcement-${request.eventId}-$follower"))
        sent++
      } catch (e: Exception) {
        logger.error("Failed to create event-announcement notification for $follower", e)
      }
    }
    return WebhookResult(
        true, "Notified $sent of ${followers.size} follower(s) of ${request.organizerUsername}")
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

  @Suppress("TooGenericExceptionCaught")
  private fun formatEventStart(raw: String): String =
      try {
        OffsetDateTime.parse(raw).atZoneSameInstant(CENTRAL_ZONE).format(EVENT_START_FORMATTER)
      } catch (e: Exception) {
        logger.warn("Failed to parse eventStart '$raw', using it verbatim", e)
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
