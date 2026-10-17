package org.dallasmakerspace.webhook.handlers

import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.cron.EVENT_CHECKIN
import org.dallasmakerspace.cron.EVENT_NEW_WAIVER
import org.dallasmakerspace.cron.SmartWaiverProcessQueueJob
import org.dallasmakerspace.smartwaiver.SmartWaiverRepository
import org.dallasmakerspace.webhook.WebhookHandler
import org.dallasmakerspace.webhook.WebhookResult
import org.dallasmakerspace.webhook.models.SmartWaiverWebhookRequest
import org.slf4j.LoggerFactory

// The queue key for "sync recent check-ins"; the payload's own id isn't needed for that.
private const val CHECKIN_QUEUE_KEY = "checkin-sync"

class SmartWaiverWebhook
@Inject
constructor(
    private val smartWaiverRepository: SmartWaiverRepository,
    private val processQueueJob: SmartWaiverProcessQueueJob,
    private val configOverrideService: ConfigOverrideService,
) : WebhookHandler() {

  private val logger = LoggerFactory.getLogger(SmartWaiverWebhook::class.java)
  private val json = Json { ignoreUnknownKeys = true }

  override fun getName() = "smartwaiver"

  @Suppress("TooGenericExceptionCaught")
  override suspend fun handleWebhook(body: String): WebhookResult {
    return try {
      val request = json.decodeFromString<SmartWaiverWebhookRequest>(body)

      val (queueKey, event) =
          when {
            request.event == EVENT_NEW_WAIVER -> request.uniqueId to EVENT_NEW_WAIVER
            request.event.contains("checkin", ignoreCase = true) && mirrorEnabled() ->
                CHECKIN_QUEUE_KEY to EVENT_CHECKIN
            else -> return WebhookResult(true, "Ignored event: ${request.event}")
          }

      val queued = smartWaiverRepository.insertWebhookQueueEntry(queueKey, event)
      if (!queued) logger.warn("Duplicate webhook delivery ignored: $queueKey ($event)")
      CoroutineScope(Dispatchers.IO).launch { processQueueJob.run() }
      WebhookResult(true, "Queued $event: $queueKey")
    } catch (e: Exception) {
      logger.error("Error queuing SmartWaiver webhook", e)
      WebhookResult(false, "Error queuing webhook: ${e.message}")
    }
  }

  private suspend fun mirrorEnabled() =
      configOverrideService.get(ConfigRegistry.SMARTWAIVER_MIRROR_ENABLED)
}
