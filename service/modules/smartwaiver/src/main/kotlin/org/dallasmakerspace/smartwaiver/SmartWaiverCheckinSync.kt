package org.dallasmakerspace.smartwaiver

import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory

private const val RECENT_FALLBACK_DAYS = 7L
private const val OVERLAP_DAYS = 1L

/** Pulls check-ins from the API into the mirror. Re-reading a range is harmless (check-ins are keyed by id). */
@Singleton
class SmartWaiverCheckinSync
@Inject
constructor(
    private val client: ISmartwaiverApiClient,
    private val repository: SmartWaiverMirrorRepository,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  /**
   * Stores every check-in from [from] to [to]. The API returns at most 100 oldest-first with a
   * `moreCheckins` flag, so the next request starts at the last date seen. [onPage] gets the number
   * of new check-ins after each page. Returns the total new.
   */
  suspend fun syncRange(
      from: LocalDateTime,
      to: LocalDateTime,
      onPage: suspend (newCheckins: Int, cursor: LocalDateTime) -> Unit = { _, _ -> },
  ): Int {
    var cursor = from
    var total = 0
    while (true) {
      val page = client.getCheckins(cursor, to)
      val added = repository.insertNewCheckins(page.checkins)
      total += added
      val last = page.checkins.lastOrNull()?.let { parseApiDateTime(it.date) }
      onPage(added, last ?: cursor)
      if (!page.moreCheckins || last == null) break
      // 100 check-ins in one second would never advance; step past that second instead of looping.
      cursor = if (last.isAfter(cursor)) last else last.plusSeconds(1)
      log.info("Check-ins synced up to $cursor ($total new so far)")
    }
    return total
  }

  /** Catches up from just before the newest stored check-in (or the last week) to now. */
  suspend fun syncRecent(): Int {
    val now = LocalDateTime.now(ZoneOffset.UTC).withNano(0)
    val from = (repository.latestCheckinAt() ?: now.minusDays(RECENT_FALLBACK_DAYS)).minusDays(OVERLAP_DAYS)
    return syncRange(from, now)
  }
}
