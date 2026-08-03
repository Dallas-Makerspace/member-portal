package org.dallasmakerspace.webhook.handlers

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.webhook.WebhookHandler
import org.dallasmakerspace.webhook.WebhookResult
import org.slf4j.LoggerFactory

private val UTC_ZONE = ZoneId.of("UTC")

/**
 * Webhook handler for SparkPost event batches (delivery/bounce/open/click), registered against
 * `POST /webhook/sparkpost`. See `service/docs/sparkpost-contract.md` (M4) for the payload
 * contract. Public like the other legacy webhooks - SparkPost live-validates the target URL by
 * POSTing to it at webhook-registration time and expects an unauthenticated 2xx.
 *
 * Correlates events back to our rows via `transmission_id`, which we store as
 * `notifications.provider_id` when a send is accepted. Events for an unknown transmission id are
 * logged and ignored (e.g. sends from before this handler existed).
 */
class SparkPostWebhook @Inject constructor(private val notificationService: NotificationService) :
    WebhookHandler() {

  private val logger = LoggerFactory.getLogger(SparkPostWebhook::class.java)
  private val json = Json { ignoreUnknownKeys = true }

  override fun getName() = "sparkpost"

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleWebhook(body: String): WebhookResult {
    val envelopes =
        try {
          json.decodeFromString(ListSerializer(SparkPostEventEnvelope.serializer()), body)
        } catch (e: Exception) {
          logger.error("Invalid SparkPost webhook payload", e)
          return WebhookResult(false, "Invalid SparkPost webhook payload: ${e.message}")
        }

    var processed = 0
    for (envelope in envelopes) {
      val event = envelope.msys.values.firstOrNull() ?: continue
      val transmissionId = event.transmissionId ?: continue
      handleEvent(event, transmissionId)
      processed++
    }
    return WebhookResult(true, "Processed $processed of ${envelopes.size} SparkPost events")
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun handleEvent(event: SparkPostEvent, transmissionId: String) {
    try {
      when (event.type) {
        "delivery" ->
            notificationService.recordDelivery(transmissionId, parseTimestamp(event.timestamp))
        "bounce" ->
            notificationService.recordBounce(
                transmissionId, event.rcptTo, event.reason ?: "Unknown reason")
        "open",
        "initial_open",
        "click" -> notificationService.recordEngagement(transmissionId, event.type)
        else -> logger.info("Ignoring unhandled SparkPost event type '${event.type}'")
      }
    } catch (e: Exception) {
      logger.error("Error processing SparkPost event type=${event.type} id=$transmissionId", e)
    }
  }

  private fun parseTimestamp(raw: String?): LocalDateTime =
      raw?.toLongOrNull()?.let { LocalDateTime.ofInstant(Instant.ofEpochSecond(it), UTC_ZONE) }
          ?: LocalDateTime.now(UTC_ZONE)
}

/** One SparkPost webhook batch entry: `{"msys": {"<event-kind>": {...}}}`. */
@Serializable private data class SparkPostEventEnvelope(val msys: Map<String, SparkPostEvent>)

/** Fields shared across SparkPost message/track event kinds (delivery, bounce, open, click). */
@Serializable
private data class SparkPostEvent(
    val type: String? = null,
    @SerialName("transmission_id") val transmissionId: String? = null,
    @SerialName("rcpt_to") val rcptTo: String? = null,
    val reason: String? = null,
    val timestamp: String? = null,
)
