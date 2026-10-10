package org.dallasmakerspace.moodle

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.dallasmakerspace.core.LoggerFactory

private const val REPORT_PAGE_SIZE = 1_000
private const val MAX_REPORT_PAGES = 100

/** One enrolment row from the stats report; learners are anonymous ids, never usernames. */
data class EnrolmentRecord(
    val courseKey: String,
    val learnerId: Int,
    val enrolledOn: LocalDate?,
    val completedOn: LocalDate?,
)

/**
 * Enrolment/completion records pulled from the Moodle custom report configured as
 * `app.moodle.stats-report-id` (columns: course short name, username, time enrolled, time
 * completed), refreshed every 6 hours.
 */
@Singleton
class MoodleStatsService
@Inject
constructor(
    private val moodleApiClient: IMoodleApiClient,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  private val reportId: Int
    get() = moodleApiClient.statsReportId

  private val snapshot =
      RefreshingSnapshot(
          name = "Moodle learning stats",
          ttl = 6.hours,
          scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
          log = log,
      ) {
        loadRecords()
      }

  val isConfigured: Boolean
    get() = reportId > 0

  /** Null when the report isn't configured or Moodle hasn't been reachable yet. */
  suspend fun getRecords(): List<EnrolmentRecord>? = if (isConfigured) snapshot.get() else null

  private suspend fun loadRecords(): List<EnrolmentRecord> {
    val rows = mutableListOf<List<String?>>()
    var page = 0
    while (page < MAX_REPORT_PAGES) {
      val data = moodleApiClient.getReportPage(reportId, page, REPORT_PAGE_SIZE)
      rows += data.rows.map { it.columns }
      if (data.rows.isEmpty() || rows.size >= data.totalrowcount) break
      page++
    }
    val records = toRecords(rows)
    log.info("Loaded ${records.size} DMS Learn enrolment records from report $reportId")
    return records
  }

  companion object {
    internal fun toRecords(rows: List<List<String?>>): List<EnrolmentRecord> {
      val learnerIds = HashMap<String, Int>()
      return rows.mapNotNull { columns ->
        val courseKey = MoodleText.normalizeKey(columns.getOrNull(0))
        val username = MoodleText.normalizeKey(columns.getOrNull(1))
        if (courseKey.isEmpty() || username.isEmpty()) return@mapNotNull null
        EnrolmentRecord(
            courseKey = courseKey,
            learnerId = learnerIds.getOrPut(username) { learnerIds.size + 1 },
            enrolledOn = MoodleText.parseDate(columns.getOrNull(2)),
            completedOn = MoodleText.parseDate(columns.getOrNull(3)),
        )
      }
    }
  }
}
