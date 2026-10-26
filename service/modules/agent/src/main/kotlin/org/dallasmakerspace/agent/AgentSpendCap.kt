package org.dallasmakerspace.agent

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dallasmakerspace.agent.db.ConversationRepository
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.LoggerFactory

/**
 * Site-wide monthly dollar cap and per-member daily question cap. The month's spend is reloaded
 * from the DB every [REFRESH_MINUTES] and kept in memory in between; each turn adds its cost.
 */
@Singleton
class AgentSpendCap
@Inject
constructor(
    private val repository: ConversationRepository,
    private val configOverrideService: ConfigOverrideService,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val mutex = Mutex()
  private var monthStart: LocalDateTime? = null
  private var loadedAt: LocalDateTime? = null
  private var spentUsd = 0.0
  private var reportedFull = false

  /** Why [username] can't ask right now, or null when they can. */
  suspend fun refusal(username: String, nowUtc: LocalDateTime): String? {
    val monthlyCap = configOverrideService.get(ConfigRegistry.AGENT_MONTHLY_SPEND_CAP_USD)
    if (monthlyCap > 0 && monthSpend(nowUtc) >= monthlyCap) {
      mutex.withLock {
        if (!reportedFull) {
          log.error("AI assistant monthly spend cap of \$$monthlyCap reached")
          reportedFull = true
        }
      }
      return MONTHLY_CAP_MESSAGE
    }
    val dailyCap = configOverrideService.get(ConfigRegistry.AGENT_USER_DAILY_TURN_CAP)
    if (dailyCap > 0 && repository.userTurnsSince(username, startOfCentralDay(nowUtc)) >= dailyCap) {
      return DAILY_CAP_MESSAGE
    }
    return null
  }

  suspend fun record(costUsd: Double) {
    mutex.withLock { spentUsd += costUsd }
  }

  private suspend fun monthSpend(nowUtc: LocalDateTime): Double =
      mutex.withLock {
        val start = startOfCentralMonth(nowUtc)
        val fresh = loadedAt?.isAfter(nowUtc.minusMinutes(REFRESH_MINUTES)) == true
        if (monthStart != start || !fresh) {
          if (monthStart != start) reportedFull = false
          monthStart = start
          loadedAt = nowUtc
          spentUsd = repository.spentSince(start)
        }
        spentUsd
      }

  companion object {
    private const val REFRESH_MINUTES = 10L
    private val CENTRAL: ZoneId = ZoneId.of("America/Chicago")
    private val UTC: ZoneId = ZoneId.of("UTC")

    const val MONTHLY_CAP_MESSAGE =
        "The AI assistant has reached its monthly limit. Please try again next month."
    const val DAILY_CAP_MESSAGE =
        "You've reached today's limit for AI assistant questions. Please try again tomorrow."

    fun startOfCentralDay(nowUtc: LocalDateTime): LocalDateTime =
        nowUtc.atZone(UTC).withZoneSameInstant(CENTRAL).toLocalDate().atStartOfDay(CENTRAL)
            .withZoneSameInstant(UTC)
            .toLocalDateTime()

    fun startOfCentralMonth(nowUtc: LocalDateTime): LocalDateTime =
        nowUtc.atZone(UTC).withZoneSameInstant(CENTRAL).toLocalDate().withDayOfMonth(1)
            .atStartOfDay(CENTRAL)
            .withZoneSameInstant(UTC)
            .toLocalDateTime()
  }
}
