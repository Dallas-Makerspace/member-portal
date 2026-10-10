package org.dallasmakerspace.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.channels.NotifierRegistry
import org.dallasmakerspace.notifications.channels.OutgoingMessage
import org.dallasmakerspace.notifications.channels.SendResult
import org.dallasmakerspace.notifications.db.NotificationsRepository
import org.dallasmakerspace.notifications.templates.TemplateRegistry

/**
 * Delivers digests. Rows enter IN_DIGEST with `next_attempt_at` set to their delivery window (see
 * [DigestSchedule]); once a window arrives, every due row of a member is atomically claimed and
 * combined into one message per channel (built by [DigestBodyBuilder]). Members are selected
 * first and then all their due rows fetched, so a member's digest is never split across two
 * emails, and claiming makes overlapping processors (e.g. during a deploy) skip rows already
 * taken. Transient failures keep rows IN_DIGEST with backoff, so a retry is still one digest.
 */
@Singleton
class DigestProcessor
@Inject
constructor(
    private val repository: NotificationsRepository,
    private val notifierRegistry: NotifierRegistry,
    private val templateRegistry: TemplateRegistry,
    private val configOverrideService: ConfigOverrideService,
    private val appConfig: AppConfig,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  private val utcZone = ZoneId.of("UTC")

  /** Max members whose digests are sent per pass. */
  private val batchSize: Int
    get() = appConfig.requireIntProperty("app.notifications.digest.batch-size")

  private val maxAttempts: Int
    get() = appConfig.requireIntProperty("app.notifications.digest.max-attempts")

  private val retryBackoffSeconds: Long
    get() = appConfig.requireLongProperty("app.notifications.digest.retry-backoff-seconds")

  private val retryMaxBackoffSeconds: Long
    get() = appConfig.requireLongProperty("app.notifications.digest.retry-max-backoff-seconds")

  /**
   * Sends every digest whose window has arrived, for up to `batch-size` members. Returns a summary
   * string for logging.
   *
   * When [ConfigRegistry.NOTIFICATIONS_ENABLED] is false, only members in its `enabledUsernames`
   * allowlist are processed; everyone else's rows stay IN_DIGEST until the flag is turned on.
   */
  suspend fun processDigests(): String {
    val flag = configOverrideService.get(ConfigRegistry.NOTIFICATIONS_ENABLED)
    if (!flag.enabled && flag.enabledUsernames.isEmpty()) {
      log.info("Digest processor paused: site config notifications.enabled=false")
      return "processed=0\npaused=true"
    }
    val now = LocalDateTime.now(utcZone)
    val members =
        repository.getMembersWithDueDigests(
            batchSize, now, if (flag.enabled) null else flag.enabledUsernames)
    val totals = Outcome()
    for (member in members) totals += deliverMember(member, now)
    if (members.isNotEmpty()) log.info("Digest processor handled ${members.size} member(s): $totals")
    return "members=${members.size}\nprocessed=${totals.total}\n${totals.summary()}"
  }

  private class Outcome(
      var sent: Int = 0,
      var transient: Int = 0,
      var deadLetter: Int = 0,
  ) {
    val total: Int
      get() = sent + transient + deadLetter

    operator fun plusAssign(other: Outcome) {
      sent += other.sent
      transient += other.transient
      deadLetter += other.deadLetter
    }

    fun summary() = "sent=$sent\ntransient=$transient\ndead-letter=$deadLetter"

    override fun toString() = summary().replace('\n', ' ')
  }

  private suspend fun deliverMember(memberUsername: String, now: LocalDateTime): Outcome {
    val outcome = Outcome()
    val rowsByChannel = repository.getDueDigestRows(memberUsername, now).groupBy { it.channel }
    for ((channel, rows) in rowsByChannel) {
      val notifier = notifierRegistry.find(channel)?.takeIf { it.supportsDigest }
      if (notifier == null) {
        rows.forEach {
          repository.markDeadLetter(it.id, "Channel $channel does not support digest delivery")
        }
        outcome.deadLetter += rows.size
      } else {
        val claimed = rows.filter { repository.claim(it.id, listOf(NotificationStatus.IN_DIGEST)) }
        if (claimed.isNotEmpty()) {
          val result = attemptSend(memberUsername, claimed) { notifier.send(memberUsername, it) }
          outcome += classify(claimed, result, now)
        }
      }
    }
    return outcome
  }

  @Suppress("TooGenericExceptionCaught") // Catch-all: any provider/network error is retryable.
  private suspend fun attemptSend(
      memberUsername: String,
      items: List<Notification>,
      send: suspend (OutgoingMessage) -> SendResult,
  ): SendResult =
      try {
        send(
            OutgoingMessage(
                subject = DigestBodyBuilder.subject(items),
                text = DigestBodyBuilder.textBody(items, ::renderText),
                html = DigestBodyBuilder.htmlBody(items),
            ))
      } catch (e: Exception) {
        log.warn("Digest send to $memberUsername failed unexpectedly", e)
        SendResult.TransientFailure("Unexpected error: ${e.message}")
      }

  private suspend fun classify(
      items: List<Notification>,
      result: SendResult,
      now: LocalDateTime,
  ): Outcome =
      when (result) {
        is SendResult.Success -> {
          items.forEach { repository.markSent(it.id, result.providerId, now) }
          Outcome(sent = items.size)
        }
        is SendResult.PermanentFailure -> {
          items.forEach { repository.markDeadLetter(it.id, result.reason) }
          Outcome(deadLetter = items.size)
        }
        is SendResult.TransientFailure -> {
          val outcome = Outcome()
          for (row in items) {
            val nextAttempt = row.attempts + 1
            if (nextAttempt >= maxAttempts) {
              repository.markDeadLetter(
                  row.id, "Retries exhausted after $nextAttempt attempts: ${result.reason}")
              outcome.deadLetter++
            } else {
              repository.markDigestRetry(
                  row.id,
                  result.reason,
                  now.plusSeconds(
                      RetryPolicy.backoffSeconds(
                          nextAttempt, retryBackoffSeconds, retryMaxBackoffSeconds)))
              outcome.transient++
            }
          }
          outcome
        }
      }

  private fun renderText(notification: Notification): String =
      runCatching { templateRegistry.render(notification.templateName, notification.payload).text }
          .getOrElse { notification.subject }
}
