package org.dallasmakerspace.notifications

import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.db.NotificationsRepository

/**
 * Site-wide monthly limit per channel, a guardrail against a runaway producer. Checked right
 * before every provider call by [NotificationWorker] and [DigestProcessor]; capped rows are
 * dead-lettered, not deferred, so a runaway backlog doesn't flood out next month. The month's
 * count is reloaded from the DB every [REFRESH_MINUTES] while sending (picking up other
 * instances) and kept in memory in between.
 */
@Singleton
class MonthlySendCap
@Inject
constructor(
    private val repository: NotificationsRepository,
    private val configOverrideService: ConfigOverrideService,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val mutex = Mutex()
  private val usage = ConcurrentHashMap<Channel, Usage>()

  private data class Usage(
      val monthStart: LocalDateTime,
      val loadedAt: LocalDateTime,
      val sent: Int,
      val reportedFull: Boolean = false,
  )

  /** The cap for [channel], or null when it has none (0 or no cap configured). */
  suspend fun limit(channel: Channel): Int? =
      CAP_KEYS[channel]?.let { configOverrideService.get(it) }?.takeIf { it > 0 }

  /**
   * Reserves one message on [channel] for this month; false once the cap is reached. A caller
   * whose send then fails must [release] the reservation.
   */
  suspend fun tryAcquire(channel: Channel, nowUtc: LocalDateTime): Boolean {
    val limit = limit(channel) ?: return true
    return mutex.withLock {
      val current = current(channel, nowUtc)
      val allowed = current.sent < limit
      usage[channel] =
          if (allowed) {
            current.copy(sent = current.sent + 1)
          } else {
            if (!current.reportedFull) {
              log.error(
                  "Monthly $channel cap of $limit reached; no more $channel notifications will " +
                      "be sent this month unless the cap is raised in site config")
            }
            current.copy(reportedFull = true)
          }
      allowed
    }
  }

  suspend fun release(channel: Channel) {
    mutex.withLock {
      usage.computeIfPresent(channel) { _, u -> u.copy(sent = maxOf(0, u.sent - 1)) }
    }
  }

  private suspend fun current(channel: Channel, nowUtc: LocalDateTime): Usage {
    val monthStart = DigestSchedule.startOfCentralMonth(nowUtc)
    val cached = usage[channel]
    if (cached != null &&
        cached.monthStart == monthStart &&
        cached.loadedAt.isAfter(nowUtc.minusMinutes(REFRESH_MINUTES))) {
      return cached
    }
    val fresh =
        Usage(
            monthStart = monthStart,
            loadedAt = nowUtc,
            sent = repository.countMessagesSentSince(channel, monthStart),
            reportedFull = cached?.monthStart == monthStart && cached.reportedFull)
    usage[channel] = fresh
    return fresh
  }

  companion object {
    private const val REFRESH_MINUTES = 10L

    private val CAP_KEYS: Map<Channel, ConfigKey<Int>> =
        mapOf(
            Channel.EMAIL to ConfigRegistry.NOTIFICATIONS_MONTHLY_EMAIL_CAP,
            Channel.SMS to ConfigRegistry.NOTIFICATIONS_MONTHLY_SMS_CAP,
        )

    /** Stored as the row's reason when the cap stops a send. */
    fun reason(channel: Channel) = "Monthly ${channel.displayName} limit reached; not sent"
  }
}
