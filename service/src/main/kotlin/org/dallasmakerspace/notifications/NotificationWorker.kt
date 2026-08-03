package org.dallasmakerspace.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.channels.Notifier
import org.dallasmakerspace.notifications.channels.SendResult
import org.dallasmakerspace.notifications.db.NotificationsRepository

/**
 * Processes queued notifications: picks up QUEUED/FAILED rows whose retry delay has elapsed,
 * resolves the recipient email (EMAIL channel), sends via the [Notifier], and classifies the
 * outcome (sent / transient failure with backoff / permanent dead-letter).
 */
@Singleton
class NotificationWorker
@Inject
constructor(
    private val repository: NotificationsRepository,
    private val notifier: Notifier,
    private val activeDirectoryService: IActiveDirectoryService,
    private val configOverrideService: ConfigOverrideService,
    private val appConfig: AppConfig,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  private val utcZone = ZoneId.of("UTC")

  private val batchSize: Int
    get() = appConfig.requireIntProperty("app.notifications.worker.batch-size")

  private val maxAttempts: Int
    get() = appConfig.requireIntProperty("app.notifications.worker.max-attempts")

  private val retryBackoffSeconds: Long
    get() = appConfig.requireLongProperty("app.notifications.worker.retry-backoff-seconds")

  private val retryMaxBackoffSeconds: Long
    get() = appConfig.requireLongProperty("app.notifications.worker.retry-max-backoff-seconds")

  /**
   * Processes one batch of due notifications. Returns a summary string for logging/cron output.
   *
   * When the site config flag [ConfigRegistry.NOTIFICATIONS_ENABLED] is false, nothing is sent and
   * notifications remain queued until it is re-enabled. Immediate notifications beyond
   * [ConfigRegistry.NOTIFICATIONS_PER_MEMBER_IMMEDIATE_DAILY_CAP] per member per day are deferred
   * to the next digest instead of being sent.
   */
  suspend fun processPending(): String {
    if (!configOverrideService.get(ConfigRegistry.NOTIFICATIONS_ENABLED)) {
      log.info("Notification worker paused: site config notifications.enabled=false")
      return "processed=0\npaused=true"
    }
    return processDueBatch(LocalDateTime.now(utcZone))
  }

  private suspend fun processDueBatch(now: LocalDateTime): String {
    val pending = repository.getPending(batchSize, now)
    if (pending.isEmpty()) return "processed=0"

    val dailyCap =
        configOverrideService.get(ConfigRegistry.NOTIFICATIONS_PER_MEMBER_IMMEDIATE_DAILY_CAP)
    val dayStartUtc = now.toLocalDate().atStartOfDay()

    var sent = 0
    var transient = 0
    var deadLetter = 0
    var deferredToDigest = 0
    for (notification in pending) {
      if (dailyCap > 0 &&
          notification.aggregation == Aggregation.IMMEDIATE &&
          repository.countImmediateSentSince(notification.memberUsername, dayStartUtc) >=
              dailyCap) {
        repository.markInDigest(
            notification.id,
            "Per-member immediate daily cap ($dailyCap) reached; deferred to digest")
        deferredToDigest++
        continue
      }
      repository.markSending(notification.id)
      when (val result = attemptSend(notification)) {
        is SendResult.Success -> {
          repository.markSent(notification.id, result.providerId, now)
          sent++
        }
        is SendResult.PermanentFailure -> {
          repository.markDeadLetter(notification.id, result.reason)
          deadLetter++
        }
        is SendResult.TransientFailure -> {
          val nextAttempt = notification.attempts + 1
          if (nextAttempt >= maxAttempts) {
            repository.markDeadLetter(
                notification.id, "Retries exhausted after $nextAttempt attempts: ${result.reason}")
            deadLetter++
          } else {
            repository.markFailed(
                notification.id,
                result.reason,
                now.plusSeconds(
                    RetryPolicy.backoffSeconds(
                        nextAttempt, retryBackoffSeconds, retryMaxBackoffSeconds)))
            transient++
          }
        }
      }
    }
    log.info(
        "Notification worker processed ${pending.size} rows" +
            " (sent=$sent, transient=$transient, dead-letter=$deadLetter, deferred-to-digest=$deferredToDigest)")
    return "processed=${pending.size}\nsent=$sent\ntransient=$transient" +
        "\ndead-letter=$deadLetter\ndeferred-to-digest=$deferredToDigest"
  }

  @Suppress("TooGenericExceptionCaught") // Catch-all: any provider/network error is retryable.
  private suspend fun attemptSend(notification: Notification): SendResult {
    return try {
      val recipientEmail =
          if (notification.channel == Channel.EMAIL) {
            activeDirectoryService
                .getMembersByUsernameList(listOf(notification.memberUsername))[
                    notification.memberUsername]
                ?.mail
          } else {
            null
          }
      notifier.send(notification, recipientEmail)
    } catch (e: Exception) {
      SendResult.TransientFailure("Unexpected error: ${e.message}")
    }
  }
}
