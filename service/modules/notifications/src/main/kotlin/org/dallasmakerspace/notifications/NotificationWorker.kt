package org.dallasmakerspace.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.channels.Notifier
import org.dallasmakerspace.notifications.channels.NotifierRegistry
import org.dallasmakerspace.notifications.channels.OutgoingMessage
import org.dallasmakerspace.notifications.channels.SendResult
import org.dallasmakerspace.notifications.db.NotificationsRepository
import org.dallasmakerspace.notifications.templates.TemplateRegistry
import org.dallasmakerspace.notifications.templates.escapeHtml
import org.dallasmakerspace.notifications.templates.notificationPreferencesUrl

/**
 * Sends immediate notifications. Rows are created with a short hold (see
 * [NotificationService]); once a member's earliest row is due, every queued row for that member is
 * atomically claimed and sent as one email per channel (built by [DigestBodyBuilder.combined]), so
 * a burst - e.g. an organizer posting several sessions at once - arrives together. Outcomes are
 * classified as sent / transient failure with backoff / permanent dead-letter. Each pass first
 * recovers rows left in SENDING by a process that died mid-send.
 */
@Singleton
@Suppress("LongParameterList")
class NotificationWorker
@Inject
constructor(
    private val repository: NotificationsRepository,
    private val notifierRegistry: NotifierRegistry,
    private val templateRegistry: TemplateRegistry,
    private val configOverrideService: ConfigOverrideService,
    private val monthlySendCap: MonthlySendCap,
    private val appConfig: AppConfig,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  private val utcZone = ZoneId.of("UTC")

  /** Max members whose pending emails are sent per pass. */
  private val batchSize: Int
    get() = appConfig.requireIntProperty("app.notifications.worker.batch-size")

  private val maxAttempts: Int
    get() = appConfig.requireIntProperty("app.notifications.worker.max-attempts")

  private val retryBackoffSeconds: Long
    get() = appConfig.requireLongProperty("app.notifications.worker.retry-backoff-seconds")

  private val retryMaxBackoffSeconds: Long
    get() = appConfig.requireLongProperty("app.notifications.worker.retry-max-backoff-seconds")

  /**
   * Sends every member's due burst, for up to `batch-size` members. Returns a summary string (row
   * counts) for logging.
   *
   * When [ConfigRegistry.NOTIFICATIONS_ENABLED] is false, only members in its `enabledUsernames`
   * allowlist (pilot testers) are selected. `enabledGroupNames` is not honored here (it would need
   * an AD group lookup per recipient). [ConfigRegistry.NOTIFICATIONS_PER_MEMBER_IMMEDIATE_DAILY_CAP]
   * is a safety valve on emails per member per Central day: the email that reaches it says so,
   * and later notifications that day go to the next daily digest.
   */
  suspend fun processPending(): String {
    val now = LocalDateTime.now(utcZone)
    val recovered = repository.requeueStaleSending(now.minusMinutes(STALE_SENDING_MINUTES))
    if (recovered > 0) log.warn("Requeued $recovered notification(s) stuck in SENDING")

    val flag = configOverrideService.get(ConfigRegistry.NOTIFICATIONS_ENABLED)
    if (!flag.enabled && flag.enabledUsernames.isEmpty()) {
      log.info("Notification worker paused: site config notifications.enabled=false")
      return "processed=0\npaused=true"
    }
    val members =
        repository.getMembersWithDueImmediate(
            batchSize, now, if (flag.enabled) null else flag.enabledUsernames)
    val dailyCap =
        configOverrideService.get(ConfigRegistry.NOTIFICATIONS_PER_MEMBER_IMMEDIATE_DAILY_CAP)

    val counts = mutableMapOf<String, Int>()
    for (member in members) {
      deliverBurst(member, now, dailyCap).forEach { (outcome, n) ->
        counts.merge(outcome, n, Int::plus)
      }
    }
    val processed = counts.values.sum()
    val summary = OUTCOMES.joinToString("\n") { "$it=${counts[it] ?: 0}" }
    if (processed > 0) {
      log.info(
          "Notification worker sent to ${members.size} member(s), $processed row(s): " +
              summary.replace('\n', ' '))
    }
    return "processed=$processed\n$summary"
  }

  /** Sends [memberUsername]'s pending rows, one email per channel. Returns row counts by outcome. */
  private suspend fun deliverBurst(
      memberUsername: String,
      now: LocalDateTime,
      dailyCap: Int,
  ): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()
    val byChannel = repository.getImmediateBurst(memberUsername, now).groupBy { it.channel }
    for ((channel, rows) in byChannel) {
      val claimed = rows.filter { repository.claim(it.id, CLAIMABLE) }
      counts.merge(CLAIMED_ELSEWHERE, rows.size - claimed.size, Int::plus)
      if (claimed.isEmpty()) continue
      val notifier = notifierRegistry.find(channel)
      val outcome =
          if (notifier == null) {
            claimed.forEach {
              repository.markDeadLetter(it.id, "No notifier configured for channel $channel")
            }
            mapOf(DEAD_LETTER to claimed.size)
          } else {
            sendOrDefer(memberUsername, notifier, claimed, now, dailyCap)
          }
      outcome.forEach { (key, n) -> counts.merge(key, n, Int::plus) }
    }
    return counts.filterValues { it > 0 }
  }

  private suspend fun sendOrDefer(
      memberUsername: String,
      notifier: Notifier,
      rows: List<Notification>,
      now: LocalDateTime,
      dailyCap: Int,
  ): Map<String, Int> {
    var message = DigestBodyBuilder.combined(rows, ::renderText)
    if (dailyCap > 0 && notifier.supportsDigest) {
      val sentToday =
          repository.countImmediateEmailsSince(memberUsername, DigestSchedule.startOfCentralDay(now))
      if (sentToday >= dailyCap) {
        val deliverAt = requireNotNull(DigestSchedule.nextDelivery(Aggregation.DAILY, now))
        rows.forEach {
          repository.markInDigest(it.id, "$DAILY_CAP_REASON ($dailyCap)", Aggregation.DAILY, deliverAt)
        }
        return mapOf(DEFERRED to rows.size)
      }
      if (sentToday + 1 == dailyCap) message = withCapNote(message, memberUsername, dailyCap)
    }
    return send(memberUsername, notifier, rows, message, now)
  }

  @Suppress("TooGenericExceptionCaught") // Catch-all: any provider/network error is retryable.
  private suspend fun send(
      memberUsername: String,
      notifier: Notifier,
      rows: List<Notification>,
      message: OutgoingMessage,
      now: LocalDateTime,
  ): Map<String, Int> {
    if (!monthlySendCap.tryAcquire(notifier.channel, now)) {
      rows.forEach { repository.markDeadLetter(it.id, MonthlySendCap.reason(notifier.channel)) }
      return mapOf(MONTHLY_CAP to rows.size)
    }
    val result =
        try {
          notifier.send(memberUsername, message)
        } catch (e: Exception) {
          SendResult.TransientFailure("Unexpected error: ${e.message}")
        }
    if (result !is SendResult.Success) monthlySendCap.release(notifier.channel)
    return when (result) {
      is SendResult.Success -> {
        rows.forEach { repository.markSent(it.id, result.providerId, now) }
        mapOf(SENT to rows.size)
      }
      is SendResult.PermanentFailure -> {
        rows.forEach { repository.markDeadLetter(it.id, result.reason) }
        mapOf(DEAD_LETTER to rows.size)
      }
      is SendResult.TransientFailure -> retryOrDeadLetter(rows, result.reason, now)
    }
  }

  private suspend fun retryOrDeadLetter(
      rows: List<Notification>,
      reason: String,
      now: LocalDateTime,
  ): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()
    for (row in rows) {
      val nextAttempt = row.attempts + 1
      if (nextAttempt >= maxAttempts) {
        repository.markDeadLetter(row.id, "Retries exhausted after $nextAttempt attempts: $reason")
        counts.merge(DEAD_LETTER, 1, Int::plus)
      } else {
        repository.markFailed(
            row.id,
            reason,
            now.plusSeconds(
                RetryPolicy.backoffSeconds(nextAttempt, retryBackoffSeconds, retryMaxBackoffSeconds)))
        counts.merge(TRANSIENT, 1, Int::plus)
      }
    }
    return counts
  }

  private fun withCapNote(message: OutgoingMessage, memberUsername: String, cap: Int): OutgoingMessage {
    val url = notificationPreferencesUrl(memberUsername)
    val note =
        "You've reached today's limit of $cap notification emails, so anything else today will " +
            "arrive in tomorrow morning's digest."
    return DigestBodyBuilder.withNote(
        message,
        "$note Getting a lot of email? Switch to a daily digest: $url",
        "${escapeHtml(note)} Getting a lot of email? " +
            "<a href=\"${escapeHtml(url)}\">Switch to a daily digest</a>.")
  }

  /**
   * The stored row keeps only the rendered HTML, so the plain-text part is re-rendered from the
   * stored payload (falling back to the subject if the template has since been removed).
   */
  private fun renderText(notification: Notification): String =
      runCatching { templateRegistry.render(notification.templateName, notification.payload).text }
          .getOrElse { notification.subject }

  companion object {
    /** Stored as the deferred row's reason; the UI keys its explanation off this prefix. */
    const val DAILY_CAP_REASON = "Daily email limit reached; moved to the next daily digest"

    private const val STALE_SENDING_MINUTES = 15L
    private val CLAIMABLE = listOf(NotificationStatus.QUEUED, NotificationStatus.FAILED)

    private const val SENT = "sent"
    private const val TRANSIENT = "transient"
    private const val DEAD_LETTER = "dead-letter"
    private const val DEFERRED = "deferred-to-digest"
    private const val CLAIMED_ELSEWHERE = "claimed-elsewhere"
    private const val MONTHLY_CAP = "monthly-cap"
    private val OUTCOMES =
        listOf(SENT, TRANSIENT, DEAD_LETTER, DEFERRED, CLAIMED_ELSEWHERE, MONTHLY_CAP)
  }
}
