package org.dallasmakerspace.webhook.handlers

import javax.inject.Inject
import kotlinx.serialization.json.Json
import org.dallasmakerspace.webhook.WebhookHandler
import org.dallasmakerspace.webhook.WebhookResult
import org.dallasmakerspace.webhook.models.MoodleWebhookEnvelope
import org.slf4j.LoggerFactory

/**
 * "Something changed in Moodle's course catalog." The `local_addad` Moodle plugin sends this
 * (through the UI's signed public relay) when a course is created, edited or its category changes.
 * The event only triggers a pass; [MoodleCourseAnnouncer] works out which courses are new, so a
 * duplicate or missed signal costs nothing. Any failure returns a non-success result, which Moodle
 * answers by retrying with backoff.
 */
class MoodleCourseWebhook @Inject constructor(private val announcer: MoodleCourseAnnouncer) :
    WebhookHandler() {

  private val logger = LoggerFactory.getLogger(MoodleCourseWebhook::class.java)
  private val json = Json { ignoreUnknownKeys = true }

  override fun getName() = "moodle-course"

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleWebhook(body: String): WebhookResult {
    val event =
        runCatching { json.decodeFromString<MoodleWebhookEnvelope>(body).event }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "unknown"
    return try {
      val result = announcer.announce()
      logger.info("Moodle webhook ($event): ${result.message}")
      WebhookResult(result.success, result.message)
    } catch (e: Exception) {
      logger.warn("Moodle webhook ($event) failed", e)
      WebhookResult(false, "Moodle course announcement failed: ${e.javaClass.simpleName}")
    }
  }
}
