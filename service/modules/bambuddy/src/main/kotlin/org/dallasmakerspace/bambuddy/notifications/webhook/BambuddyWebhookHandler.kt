package org.dallasmakerspace.bambuddy.notifications.webhook

import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import org.dallasmakerspace.bambuddy.notifications.BambuCorrelationService
import org.dallasmakerspace.bambuddy.notifications.BambuWakeSignal
import org.dallasmakerspace.bambuddy.notifications.CorrelationMode
import org.dallasmakerspace.bambuddy.notifications.EphemeralNotificationService
import org.dallasmakerspace.bambuddy.notifications.PendingOfflineAlert
import org.dallasmakerspace.bambuddy.notifications.PendingOfflineStatus
import org.dallasmakerspace.bambuddy.notifications.configuredBambuddyWebhookSecret
import org.dallasmakerspace.bambuddy.notifications.db.PendingOfflineAlertsRepository
import org.dallasmakerspace.bambuddy.notifications.inAnyRollout
import org.dallasmakerspace.bambuddy.notifications.sanitizeForLog
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory

private val PRINT_EVENTS = setOf("print_complete", "print_failed", "print_stopped")

/**
 * Receiver logic for `POST /webhook/bambuddy` (design §6). Deliberately NOT a
 * `webhook.WebhookHandler`: that interface receives only the body, so it cannot see the
 * `Authorization` header BamBuddy sends, and the generic route logs every header (which would
 * write the shared secret to the log). The route in the app module is a thin shell over [handle].
 *
 * Response codes: handled/ignored/no-match → 200; malformed JSON → 400; bad/missing auth → 401;
 * unexpected internal failure → 500 (surfaces in BamBuddy's provider `last_error`). The secret and
 * the raw payload are never logged.
 */
@Singleton
@Suppress("LongParameterList")
class BambuddyWebhookHandler
@Inject
constructor(
    private val correlationService: BambuCorrelationService,
    private val watchService: EphemeralNotificationService,
    private val pendingOfflineRepository: PendingOfflineAlertsRepository,
    private val configOverrideService: ConfigOverrideService,
    private val appConfig: AppConfig,
    private val wakeSignal: BambuWakeSignal,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }
  private val utcZone = ZoneId.of("UTC")

  /**
   * Whether [authorizationHeader] is authorized against the configured secret. The route
   * checks this BEFORE reading the request body; [handle] re-checks it so direct callers
   * get the same fail-closed behaviour.
   */
  fun isRequestAuthorized(authorizationHeader: String?): Boolean {
    val secret = configuredSecret() ?: return false
    return isAuthorized(authorizationHeader, secret)
  }

  suspend fun handle(
      authorizationHeader: String?,
      body: String,
      remoteHost: String? = null,
  ): HttpStatusCode {
    val secret = configuredSecret()
    if (secret == null || !isAuthorized(authorizationHeader, secret)) {
      // Source host only (design §15.2) — never the header value or the secret.
      log.warn("bambu webhook auth failure remoteHost=${sanitizeForLog(remoteHost)}")
      return HttpStatusCode.Unauthorized
    }
    val payload =
        try {
          json.decodeFromString(BambuddyWebhookPayload.serializer(), body)
        } catch (e: Exception) {
          log.warn("bambu webhook malformed JSON body length=${body.length}")
          return HttpStatusCode.BadRequest
        }
    return try {
      route(payload)
    } catch (e: Exception) {
      log.error("bambu webhook failed event=${sanitizeForLog(payload.event)}: ${e.message}", e)
      HttpStatusCode.InternalServerError
    }
  }

  private suspend fun route(payload: BambuddyWebhookPayload): HttpStatusCode {
    if (!featureInAnyRollout()) {
      log.info("bambu webhook event=${sanitizeForLog(payload.event)} ignored, feature off")
      return HttpStatusCode.OK
    }
    return when {
      payload.event in PRINT_EVENTS -> handlePrintEvent(payload)
      payload.event == "printer_offline" -> handlePrinterOffline(payload)
      else -> {
        log.info("bambu webhook event=${sanitizeForLog(payload.event)} ignored")
        HttpStatusCode.OK
      }
    }
  }

  private suspend fun handlePrintEvent(payload: BambuddyWebhookPayload): HttpStatusCode {
    if (payload.printer.isNullOrBlank()) {
      log.warn("bambu webhook event=${sanitizeForLog(payload.event)} missing printer field")
      return HttpStatusCode.OK
    }
    val result = correlationService.correlatePrintEvent(payload)
    var fired = 0
    var failed = 0
    val now = LocalDateTime.now(utcZone)
    for (watch in result.watches) {
      try {
        if (watchService.fireWatch(watch, payload.event, payload, now)) fired++
      } catch (e: Exception) {
        failed++
        log.error("bambu webhook fire failed watch=${watch.id}: ${e.message}", e)
      }
    }
    log.info(
        "bambu webhook event=${sanitizeForLog(payload.event)} printer=${sanitizeForLog(payload.printer)} " +
            "correlation=${correlationLabel(result.mode)} watchesFired=$fired watchesFailed=$failed")
    return if (failed > 0) HttpStatusCode.InternalServerError else HttpStatusCode.OK
  }

  private fun correlationLabel(mode: CorrelationMode): String =
      when (mode) {
        CorrelationMode.FIRED -> "fired"
        CorrelationMode.NO_PRINTER -> "no-printer"
        CorrelationMode.NO_WATCH -> "no-watch"
        CorrelationMode.FILENAME_ONLY -> "filename-only"
        CorrelationMode.UNVERIFIED -> "unverified"
        CorrelationMode.UNVERIFIED_NO_FILENAME -> "unverified-no-filename"
        CorrelationMode.STATE_MISMATCH -> "state-mismatch"
      }

  private suspend fun handlePrinterOffline(payload: BambuddyWebhookPayload): HttpStatusCode {
    val printerName = payload.printer
    if (printerName.isNullOrBlank()) {
      log.warn("bambu webhook event=printer_offline missing printer field")
      return HttpStatusCode.OK
    }
    val printerId = correlationService.resolvePrinterId(printerName)
    if (printerId == null) {
      log.info(
          "bambu webhook event=printer_offline printer=${sanitizeForLog(printerName)} correlation=no-printer watchesFired=0")
      return HttpStatusCode.OK
    }
    val holdMinutes = configOverrideService.get(ConfigRegistry.BAMBU_NOTIFICATIONS_OFFLINE_HOLD_MINUTES)
    val now = LocalDateTime.now(utcZone)
    // Atomic insert-if-absent: the pending check and the insert are one transaction in the
    // repository, so two duplicate webhooks racing here cannot create two episodes.
    val inserted =
        pendingOfflineRepository.insertIfNoPending(
            PendingOfflineAlert(
                id = 0,
                printerId = printerId,
                printerName = printerName,
                offlineSince = now,
                releaseAt = now.plusMinutes(holdMinutes.coerceAtLeast(0).toLong()),
                status = PendingOfflineStatus.PENDING,
                resolvedAt = null,
                notificationsCreated = null,
            ))
    if (inserted == null) {
      log.info(
          "bambu webhook printer_offline printer=${sanitizeForLog(printerName)} episode already pending")
    } else {
      wakeSignal.wake()
      log.info(
          "bambu webhook printer_offline printer=${sanitizeForLog(printerName)} episode=${inserted.id} held")
    }
    return HttpStatusCode.OK
  }

  /** Webhooks carry no member identity, so processing runs in any rollout state (design §12.1). */
  private suspend fun featureInAnyRollout(): Boolean =
      configOverrideService.get(ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED).inAnyRollout()

  /**
   * The configured secret, or null when unset / still the dev placeholder outside development
   * (fail closed: the route then 401s everything). Shared resolution: see
   * [configuredBambuddyWebhookSecret].
   */
  internal fun configuredSecret(): String? = configuredBambuddyWebhookSecret(appConfig)

  /** Accepts the secret verbatim or as `Bearer <secret>`; constant-time comparison. */
  internal fun isAuthorized(authorizationHeader: String?, secret: String): Boolean {
    val header = authorizationHeader?.trim().orEmpty()
    if (header.isEmpty()) return false
    val candidate = if (header.startsWith("Bearer ")) header.removePrefix("Bearer ") else header
    return MessageDigest.isEqual(
        candidate.toByteArray(Charsets.UTF_8), secret.toByteArray(Charsets.UTF_8))
  }
}
