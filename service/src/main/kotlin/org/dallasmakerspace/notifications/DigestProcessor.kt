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
 * Processes notification digests: picks up IN_DIGEST rows, groups them per member, delivers one
 * digest email per member (built from the stored per-item bodies via [DigestBodyBuilder]), and
 * classifies the outcome per row (sent / transient failure with backoff / permanent dead-letter).
 *
 * Only EMAIL-channel rows are eligible; anything else sitting in IN_DIGEST is dead-lettered.
 */
@Singleton
class DigestProcessor
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
    get() = appConfig.requireIntProperty("app.notifications.digest.batch-size")

  private val maxAttempts: Int
    get() = appConfig.requireIntProperty("app.notifications.digest.max-attempts")

  private val retryBackoffSeconds: Long
    get() = appConfig.requireLongProperty("app.notifications.digest.retry-backoff-seconds")

  private val retryMaxBackoffSeconds: Long
    get() = appConfig.requireLongProperty("app.notifications.digest.retry-max-backoff-seconds")

  /**
   * Processes one batch of rows awaiting digest delivery. Returns a summary string for logging/cron
   * output.
   *
   * When the site config flag [ConfigRegistry.NOTIFICATIONS_ENABLED] is false, nothing is sent and
   * rows remain in IN_DIGEST until it is re-enabled.
   */
  suspend fun processDigests(): String {
    if (!configOverrideService.get(ConfigRegistry.NOTIFICATIONS_ENABLED).enabled) {
      log.info("Digest processor paused: site config notifications.enabled=false")
      return "processed=0\npaused=true"
    }
    return processDueBatch(LocalDateTime.now(utcZone))
  }

  private suspend fun processDueBatch(now: LocalDateTime): String {
    val rows = repository.getInDigest(batchSize)
    if (rows.isEmpty()) return "processed=0"

    var sent = 0
    var transient = 0
    var deadLetter = 0
    for ((memberUsername, group) in rows.groupBy { it.memberUsername }) {
      val outcome = deliverGroup(memberUsername, group, now)
      sent += outcome.sent
      transient += outcome.transient
      deadLetter += outcome.deadLetter
    }
    log.info(
        "Digest processor processed ${rows.size} rows" +
            " (sent=$sent, transient=$transient, dead-letter=$deadLetter)")
    return "processed=${rows.size}\nsent=$sent\ntransient=$transient\ndead-letter=$deadLetter"
  }

  private data class GroupOutcome(
      val sent: Int = 0,
      val transient: Int = 0,
      val deadLetter: Int = 0,
  )

  /**
   * Delivers one digest email for [group] (all rows of a single member) and classifies the rows.
   */
  private suspend fun deliverGroup(
      memberUsername: String,
      group: List<Notification>,
      now: LocalDateTime,
  ): GroupOutcome {
    var sent = 0
    var transient = 0
    var deadLetter = 0
    val eligible = group.filter { it.channel == Channel.EMAIL }
    group
        .filterNot { it.channel == Channel.EMAIL }
        .forEach { row ->
          repository.markDeadLetter(
              row.id, "Channel ${row.channel} does not support digest delivery")
          deadLetter++
        }
    if (eligible.isEmpty()) return GroupOutcome(deadLetter = deadLetter)

    eligible.forEach { repository.markSending(it.id) }
    val recipientEmail =
        activeDirectoryService
            .getMembersByUsernameList(listOf(memberUsername))[memberUsername]
            ?.mail
    when (val result = attemptSend(memberUsername, recipientEmail, eligible)) {
      is SendResult.Success -> {
        eligible.forEach { repository.markSent(it.id, result.providerId, now) }
        sent += eligible.size
      }
      is SendResult.PermanentFailure -> {
        eligible.forEach { repository.markDeadLetter(it.id, result.reason) }
        deadLetter += eligible.size
      }
      is SendResult.TransientFailure -> {
        eligible.forEach { row ->
          val nextAttempt = row.attempts + 1
          if (nextAttempt >= maxAttempts) {
            repository.markDeadLetter(
                row.id, "Retries exhausted after $nextAttempt attempts: ${result.reason}")
            deadLetter++
          } else {
            repository.markFailed(
                row.id,
                result.reason,
                now.plusSeconds(
                    RetryPolicy.backoffSeconds(
                        nextAttempt, retryBackoffSeconds, retryMaxBackoffSeconds)))
            transient++
          }
        }
      }
    }
    return GroupOutcome(sent, transient, deadLetter)
  }

  @Suppress("TooGenericExceptionCaught") // Catch-all: any provider/network error is retryable.
  private suspend fun attemptSend(
      memberUsername: String,
      recipientEmail: String?,
      items: List<Notification>,
  ): SendResult {
    return try {
      notifier.sendDigest(
          memberUsername = memberUsername,
          recipientEmail = recipientEmail,
          subject = DigestBodyBuilder.subject(items.size),
          textBody = DigestBodyBuilder.textBody(items),
          htmlBody = DigestBodyBuilder.htmlBody(items),
      )
    } catch (e: Exception) {
      SendResult.TransientFailure("Unexpected error: ${e.message}")
    }
  }
}
