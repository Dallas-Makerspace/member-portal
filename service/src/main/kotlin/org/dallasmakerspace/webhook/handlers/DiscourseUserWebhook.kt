package org.dallasmakerspace.webhook.handlers

import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import org.dallasmakerspace.discourse.DiscourseAutoLinkService
import org.dallasmakerspace.webhook.WebhookHandler
import org.dallasmakerspace.webhook.WebhookResult
import org.dallasmakerspace.webhook.models.DiscoursePostWebhookEnvelope
import org.dallasmakerspace.webhook.models.DiscourseUserEventBody
import org.slf4j.LoggerFactory

/**
 * Webhook handler for Discourse user events: links a Talk account to a member by email once the
 * account has confirmed it (see [DiscourseAutoLinkService.linkFromTalkEvent]).
 *
 * Discourse disables webhooks that keep failing, so only a malformed payload is an error; anything
 * else, including a Talk or database outage, is left for the nightly job. Parse errors are never
 * echoed: the JSON excerpts they carry include the member's email.
 */
class DiscourseUserWebhook
@Inject
constructor(private val autoLinkService: DiscourseAutoLinkService) : WebhookHandler() {

  private val logger = LoggerFactory.getLogger(DiscourseUserWebhook::class.java)
  private val json = Json { ignoreUnknownKeys = true }

  override fun getName() = "discourse-user"

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleWebhook(body: String): WebhookResult {
    val envelope =
        try {
          json.decodeFromString<DiscoursePostWebhookEnvelope>(body)
        } catch (e: Exception) {
          logger.error("Invalid discourse-user webhook envelope: ${e.javaClass.simpleName}")
          return WebhookResult(false, "Invalid discourse-user webhook payload")
        }
    if (envelope.event !in HANDLED_EVENTS) {
      return WebhookResult(true, "Ignoring event ${envelope.event}")
    }
    val user =
        try {
          json.decodeFromString<DiscourseUserEventBody>(envelope.payload).user
        } catch (e: Exception) {
          logger.error("Invalid Discourse user payload: ${e.javaClass.simpleName}")
          return WebhookResult(false, "Invalid Discourse user payload")
        }
    if (user.staged) return WebhookResult(true, "Ignoring staged user ${user.id}")
    val email = user.email
    if (email.isNullOrBlank()) {
      return WebhookResult(true, "No email for user ${user.id}; the nightly job will retry")
    }

    return try {
      WebhookResult(true, "User ${user.id}: ${autoLinkService.linkFromTalkEvent(user.id, user.username, email)}")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.warn("Auto-link deferred for user ${user.id}: ${e.javaClass.simpleName}")
      WebhookResult(true, "User ${user.id}: deferred to the nightly job (${e.javaClass.simpleName})")
    }
  }

  private companion object {
    val HANDLED_EVENTS = setOf("user_created", "user_confirmed_email")
  }
}
