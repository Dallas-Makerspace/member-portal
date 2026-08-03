package org.dallasmakerspace.webhook.handlers

import javax.inject.Inject
import kotlinx.serialization.json.Json
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.CreateNotificationRequest
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.NotificationTopic
import org.dallasmakerspace.webhook.WebhookHandler
import org.dallasmakerspace.webhook.WebhookResult
import org.dallasmakerspace.webhook.models.NotificationWebhookRequest
import org.slf4j.LoggerFactory

/**
 * Webhook handler for emitting notifications to members. The route guards this handler with a
 * shared secret (see `X-Webhook-Token` header check in Routing).
 */
class NotificationWebhook
@Inject
constructor(private val notificationService: NotificationService) : WebhookHandler() {

  private val logger = LoggerFactory.getLogger(NotificationWebhook::class.java)
  private val json = Json { ignoreUnknownKeys = true }

  override fun getName() = "notifications"

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleWebhook(body: String): WebhookResult {
    return try {
      val request = json.decodeFromString<NotificationWebhookRequest>(body)
      val notification =
          notificationService.create(
              CreateNotificationRequest(
                  memberUsername = request.memberUsername,
                  topic = NotificationTopic.valueOf(request.topic),
                  templateName = request.templateName,
                  payload = request.payload,
                  channel = Channel.valueOf(request.channel),
                  dedupeKey = request.dedupeKey,
              ))
      WebhookResult(true, "Notification ${notification.id} created (status=${notification.status})")
    } catch (e: IllegalArgumentException) {
      logger.error("Invalid notifications webhook payload: ${e.message}")
      WebhookResult(false, "Invalid notifications webhook payload: ${e.message}")
    } catch (e: Exception) {
      logger.error("Error processing notifications webhook", e)
      WebhookResult(false, "Error processing webhook: ${e.message}")
    }
  }
}
