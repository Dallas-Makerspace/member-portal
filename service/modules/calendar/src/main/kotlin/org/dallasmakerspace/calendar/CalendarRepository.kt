package org.dallasmakerspace.calendar

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.serialization.Serializable
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.db.master.GenericRepository

private val SQL_TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** Repository for calendar events data. */
class CalendarRepository
@Inject
constructor(private val genericRepository: GenericRepository, loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  /**
   * Fetches events organized by a member, prioritized by proximity to today. Events closest to the
   * current date (whether past or future) are returned first.
   *
   * @param username The username of the member to fetch events for.
   * @param limit The maximum number of events to return (default 5).
   * @return A list of [EventSummary] objects.
   */
  suspend fun getEventsOrganizedByMember(username: String, limit: Int = 5): List<EventSummary> {
    // Use MariaDB server-side statement timeout (1.5s) so the DB kills the query and frees the
    // JDBC connection. withTimeoutOrNull does NOT work here because blocking JDBC calls on
    // Dispatchers.IO are not responsive to coroutine cancellation, which exhausts the connection
    // pool under load.
    val query =
        """
      SET STATEMENT max_statement_time=1.5 FOR
      SELECT * FROM (
        SELECT
            e.id,
            e.name,
            CONVERT_TZ(e.event_start, '+00:00', 'America/Chicago') AS event_start_cst,
            e.status
        FROM
            `dms-calendar`.events e
        JOIN
            `dms-calendar`.contacts c ON e.contact_id = c.id
        WHERE
            c.ad_username = '$username'
        ORDER BY
            ABS(TIMESTAMPDIFF(SECOND, e.event_start, NOW())) ASC
        LIMIT $limit
      ) AS closest_events
      ORDER BY event_start_cst DESC
    """

    log.debug("Fetching events organized by member: $username (limit: $limit)")

    val result =
        try {
          genericRepository.getReportData(query)
        } catch (ex: Exception) {
          if (ex.isStatementTimeout()) {
            log.warn("Query timed out fetching events for member: $username")
          } else {
            log.warn("Failed fetching events for member: $username - ${ex.message}")
          }
          return emptyList()
        }
    if (result == null) {
      log.warn("No results fetching events for member: $username")
      return emptyList()
    }

    return result.data.map { row ->
      val id = (row["id"] as? Number)?.toInt() ?: 0
      val name = row["name"]?.toString() ?: ""
      val eventStart = row["event_start_cst"]?.toString() ?: ""
      val status = row["status"]?.toString() ?: ""

      EventSummary(id = id, name = name, eventStart = eventStart, status = status)
    }
  }

  /**
   * Checks if there are any prerequisite events for a given AD group within the last 90 days.
   *
   * @param groupName The AD group name to check for prerequisite events.
   * @return true if any prerequisite events exist, false otherwise.
   */
  suspend fun hasPrerequisiteEvents(groupName: String): Boolean {
    val query =
        """
      SET STATEMENT max_statement_time=1.5 FOR
      SELECT EXISTS(
        SELECT 1
        FROM `dms-calendar`.events e
        JOIN `dms-calendar`.prerequisites p ON e.fulfills_prerequisite_id = p.id
        WHERE
            e.event_start >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)
            AND e.event_start < DATE_ADD(CURDATE(), INTERVAL 30 DAY)
            AND p.ad_group = '${groupName.replace("'", "''")}'
      ) AS has_events
    """

    log.debug("Checking prerequisite events for group: $groupName")

    val result =
        try {
          genericRepository.getReportData(query)
        } catch (ex: Exception) {
          if (ex.isStatementTimeout()) {
            log.warn("Query timed out checking prerequisite events for group: $groupName")
          } else {
            log.warn("Failed checking prerequisite events for group: $groupName - ${ex.message}")
          }
          return false
        }
    val hasEvents = result?.data?.firstOrNull()?.get("has_events")
    return hasEvents == 1L || hasEvents == 1 || hasEvents == true
  }

  /**
   * Fetches upcoming prerequisite events for multiple AD groups.
   *
   * @param groupNames The list of AD group names to fetch events for.
   * @return A list of [EventSummary] objects for upcoming events.
   */
  suspend fun getUpcomingPrerequisiteEvents(groupNames: List<String>): List<EventSummary> {
    if (groupNames.isEmpty()) {
      return emptyList()
    }

    // Build SQL IN clause with escaped group names
    val groupNamesList = groupNames.joinToString(",") { "'${it.replace("'", "''")}'" }

    val query =
        """
      SET STATEMENT max_statement_time=1.5 FOR
      SELECT
          e.id,
          e.name,
          CONVERT_TZ(e.event_start, '+00:00', 'America/Chicago') AS event_start_cst,
          e.status,
          c.ad_username as organizer_username
      FROM `dms-calendar`.events e
      JOIN `dms-calendar`.prerequisites p ON e.fulfills_prerequisite_id = p.id
      LEFT JOIN `dms-calendar`.contacts c ON e.contact_id = c.id
      WHERE
          e.event_start >= NOW()
          AND p.ad_group IN ($groupNamesList)
      ORDER BY e.event_start ASC
      LIMIT 20
    """

    log.debug("Fetching upcoming prerequisite events for ${groupNames.size} groups")

    val result =
        try {
          genericRepository.getReportData(query)
        } catch (ex: Exception) {
          if (ex.isStatementTimeout()) {
            log.warn("Query timed out fetching upcoming prerequisite events")
          } else {
            log.warn("Failed fetching upcoming prerequisite events - ${ex.message}")
          }
          return emptyList()
        }

    return result?.data?.map { row ->
      val id = (row["id"] as? Number)?.toInt() ?: 0
      val name = row["name"]?.toString() ?: ""
      val eventStart = row["event_start_cst"]?.toString() ?: ""
      val status = row["status"]?.toString() ?: ""
      val organizerUsername = row["organizer_username"]?.toString()

      EventSummary(
          id = id,
          name = name,
          eventStart = eventStart,
          status = status,
          organizerUsername = organizerUsername,
      )
    } ?: emptyList()
  }

  /**
   * Fetches distinct event names that a member has attended, ordered by most recent first.
   *
   * @param username The AD username of the member.
   * @param limit The maximum number of event names to return (default 20).
   * @return A list of event name strings.
   */
  suspend fun getAttendedEventNames(username: String, limit: Int = 20): List<String> {
    val query =
        """
      SET STATEMENT max_statement_time=1.5 FOR
      SELECT e.name
      FROM `dms-calendar`.registrations r
      JOIN `dms-calendar`.events e ON r.event_id = e.id
      WHERE r.ad_username = '$username'
        AND r.attended = 1
      GROUP BY e.name
      ORDER BY MAX(e.event_start) DESC
      LIMIT $limit
    """

    log.debug("Fetching attended event names for member: $username (limit: $limit)")

    val result =
        try {
          genericRepository.getReportData(query)
        } catch (ex: Exception) {
          if (ex.isStatementTimeout()) {
            log.warn("Query timed out fetching attended event names for member: $username")
          } else {
            log.warn("Failed fetching attended event names for member: $username - ${ex.message}")
          }
          return emptyList()
        }

    return result?.data?.mapNotNull { row -> row["name"]?.toString()?.takeIf { it.isNotBlank() } }
        ?: emptyList()
  }

  /**
   * Fetches the distinct organizer usernames for events a member has attended, ranked by how many
   * of their events the member attended (most attended organizer first).
   *
   * @param username The AD username of the member.
   * @param limit The maximum number of organizer usernames to return (default 5).
   * @return A list of organizer AD usernames, highest-frequency first.
   */
  suspend fun getAttendedOrganizers(username: String, limit: Int = 5): List<String> {
    val query =
        """
      SET STATEMENT max_statement_time=1.5 FOR
      SELECT c.ad_username
      FROM `dms-calendar`.registrations r
      JOIN `dms-calendar`.events e ON r.event_id = e.id
      LEFT JOIN `dms-calendar`.contacts c ON e.contact_id = c.id
      WHERE r.ad_username = '$username'
        AND r.attended = 1
        AND c.ad_username IS NOT NULL
      GROUP BY c.ad_username
      ORDER BY COUNT(*) DESC
      LIMIT $limit
    """

    log.debug("Fetching top attended organizers for member: $username (limit: $limit)")

    val result =
        try {
          genericRepository.getReportData(query)
        } catch (ex: Exception) {
          if (ex.isStatementTimeout()) {
            log.warn("Query timed out fetching attended organizers for member: $username")
          } else {
            log.warn("Failed fetching attended organizers for member: $username - ${ex.message}")
          }
          return emptyList()
        }

    return result?.data?.mapNotNull { row ->
      row["ad_username"]?.toString()?.takeIf { it.isNotBlank() }
    } ?: emptyList()
  }

  /**
   * Fetches usernames of members who attended an event with the same name as one [username]
   * attended, both within the last [months] months. Excludes [username].
   */
  suspend fun getClassmates(username: String, months: Int = 6, limit: Int = 500): List<String> {
    val escaped = username.replace("'", "''")
    val query =
        """
      SET STATEMENT max_statement_time=1.5 FOR
      SELECT DISTINCT r2.ad_username
      FROM `dms-calendar`.registrations r
      JOIN `dms-calendar`.events e ON r.event_id = e.id
      JOIN `dms-calendar`.events e2 ON e2.name = e.name
      JOIN `dms-calendar`.registrations r2 ON r2.event_id = e2.id
      WHERE r.ad_username = '$escaped'
        AND r.attended = 1
        AND e.event_start >= NOW() - INTERVAL $months MONTH
        AND e2.event_start >= NOW() - INTERVAL $months MONTH
        AND r2.attended = 1
        AND r2.ad_username IS NOT NULL
        AND r2.ad_username <> '$escaped'
      LIMIT $limit
    """

    log.debug("Fetching classmates for member: $username (months: $months)")

    val result =
        try {
          genericRepository.getReportData(query)
        } catch (ex: Exception) {
          if (ex.isStatementTimeout()) {
            log.warn("Query timed out fetching classmates for member: $username")
          } else {
            log.warn("Failed fetching classmates for member: $username - ${ex.message}")
          }
          return emptyList()
        }

    val classmates =
        result?.data?.mapNotNull { row ->
          row["ad_username"]?.toString()?.takeIf { it.isNotBlank() }
        } ?: emptyList()
    log.debug("Found ${classmates.size} classmates for member: $username")
    return classmates
  }

  /**
   * Fetches organizer usernames ranked by the number of events they ran in the last [months]
   * months (events already started), most active first.
   */
  suspend fun getTopOrganizers(months: Int = 6, limit: Int = 30): List<String> {
    val query =
        """
      SET STATEMENT max_statement_time=1.5 FOR
      SELECT c.ad_username
      FROM `dms-calendar`.events e
      JOIN `dms-calendar`.contacts c ON e.contact_id = c.id
      WHERE e.event_start >= NOW() - INTERVAL $months MONTH
        AND e.event_start <= NOW()
        AND e.status = 'approved'
        AND c.ad_username IS NOT NULL
      GROUP BY c.ad_username
      ORDER BY COUNT(*) DESC
      LIMIT $limit
    """

    log.debug("Fetching top organizers (months: $months, limit: $limit)")

    val result =
        try {
          genericRepository.getReportData(query)
        } catch (ex: Exception) {
          if (ex.isStatementTimeout()) {
            log.warn("Query timed out fetching top organizers")
          } else {
            log.warn("Failed fetching top organizers - ${ex.message}")
          }
          return emptyList()
        }

    val organizers =
        result?.data?.mapNotNull { row ->
          row["ad_username"]?.toString()?.takeIf { it.isNotBlank() }
        } ?: emptyList()
    log.debug("Found ${organizers.size} top organizers (months: $months)")
    return organizers
  }

  /**
   * Fetches upcoming events organized by any of the given organizer usernames.
   *
   * @param organizers The list of organizer AD usernames to fetch events for.
   * @param limit The maximum number of events to return (default 4).
   * @return A list of [EventSummary] objects for upcoming events.
   */
  suspend fun getUpcomingEventsByOrganizers(
      organizers: List<String>,
      limit: Int = 4,
  ): List<EventSummary> {
    if (organizers.isEmpty()) {
      return emptyList()
    }

    val organizerList = organizers.joinToString(",") { "'${it.replace("'", "''")}'" }

    val query =
        """
      SET STATEMENT max_statement_time=1.5 FOR
      SELECT e.id, e.name,
             CONVERT_TZ(e.event_start, '+00:00', 'America/Chicago') AS event_start_cst,
             e.status,
             c.ad_username AS organizer_username,
             p.ad_group AS fulfills_prerequisite_group
      FROM `dms-calendar`.events e
      JOIN `dms-calendar`.contacts c ON e.contact_id = c.id
      LEFT JOIN `dms-calendar`.prerequisites p ON e.fulfills_prerequisite_id = p.id
      WHERE e.event_start >= NOW()
        AND e.status = 'approved'
        AND c.ad_username IN ($organizerList)
        AND (
          (e.free_spaces = 0 AND e.paid_spaces = 0)
          OR (SELECT COUNT(*) FROM `dms-calendar`.registrations r
                WHERE r.event_id = e.id
                  AND r.status NOT IN ('cancelled', 'rejected'))
             < (e.free_spaces + e.paid_spaces)
        )
      ORDER BY e.event_start ASC
      LIMIT $limit
    """

    log.debug("Fetching upcoming events for ${organizers.size} organizers (limit: $limit)")

    val result =
        try {
          genericRepository.getReportData(query)
        } catch (ex: Exception) {
          if (ex.isStatementTimeout()) {
            log.warn("Query timed out fetching upcoming events by organizers")
          } else {
            log.warn("Failed fetching upcoming events by organizers - ${ex.message}")
          }
          return emptyList()
        }

    return result?.data?.map { row ->
      val id = (row["id"] as? Number)?.toInt() ?: 0
      val name = row["name"]?.toString() ?: ""
      val eventStart = row["event_start_cst"]?.toString() ?: ""
      val status = row["status"]?.toString() ?: ""
      val organizerUsername = row["ad_username"]?.toString()
      val fulfillsPrerequisiteGroup = row["ad_group"]?.toString()

      EventSummary(
          id = id,
          name = name,
          eventStart = eventStart,
          status = status,
          organizerUsername = organizerUsername,
          fulfillsPrerequisiteGroup = fulfillsPrerequisiteGroup,
      )
    } ?: emptyList()
  }

  /**
   * Fetches upcoming events whose names match any of the given keywords.
   *
   * @param keywords The list of keywords to search for in event names.
   * @param limit The maximum number of events to return (default 5).
   * @return A list of [EventSummary] objects for upcoming matching events.
   */
  @Suppress("LongMethod") // SQL query is inherently verbose due to status/capacity/prereq clauses
  suspend fun getUpcomingEventsByKeywords(
      keywords: List<String>,
      limit: Int = 5,
  ): List<EventSummary> {
    if (keywords.isEmpty()) {
      return emptyList()
    }

    val orClause =
        keywords.joinToString(" OR ") { kw ->
          val escaped =
              kw.replace("'", "''").replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
          "LOWER(e.name) LIKE '%$escaped%' ESCAPE '\\\\'"
        }

    val query =
        """
      SET STATEMENT max_statement_time=1.5 FOR
      SELECT e.id, e.name,
             CONVERT_TZ(e.event_start, '+00:00', 'America/Chicago') AS event_start_cst,
             e.status,
             c.ad_username AS organizer_username,
             p.ad_group AS fulfills_prerequisite_group
      FROM `dms-calendar`.events e
      LEFT JOIN `dms-calendar`.contacts c ON e.contact_id = c.id
      LEFT JOIN `dms-calendar`.prerequisites p ON e.fulfills_prerequisite_id = p.id
      WHERE e.event_start >= NOW()
        AND e.status = 'approved'
        AND ($orClause)
        AND (
          (e.free_spaces = 0 AND e.paid_spaces = 0)
          OR (SELECT COUNT(*) FROM `dms-calendar`.registrations r
                WHERE r.event_id = e.id
                  AND r.status NOT IN ('cancelled', 'rejected'))
             < (e.free_spaces + e.paid_spaces)
        )
      ORDER BY e.event_start ASC
      LIMIT $limit
    """

    log.debug("Fetching upcoming events by ${keywords.size} keywords (limit: $limit)")

    val result =
        try {
          genericRepository.getReportData(query)
        } catch (ex: Exception) {
          if (ex.isStatementTimeout()) {
            log.warn("Query timed out fetching upcoming events by keywords")
          } else {
            log.warn("Failed fetching upcoming events by keywords - ${ex.message}")
          }
          return emptyList()
        }

    return result?.data?.map { row ->
      val id = (row["id"] as? Number)?.toInt() ?: 0
      val name = row["name"]?.toString() ?: ""
      val eventStart = row["event_start_cst"]?.toString() ?: ""
      val status = row["status"]?.toString() ?: ""
      val organizerUsername = row["ad_username"]?.toString()
      val fulfillsPrerequisiteGroup = row["ad_group"]?.toString()

      EventSummary(
          id = id,
          name = name,
          eventStart = eventStart,
          status = status,
          organizerUsername = organizerUsername,
          fulfillsPrerequisiteGroup = fulfillsPrerequisiteGroup,
      )
    } ?: emptyList()
  }

  /**
   * Fetches events whose names match any of the given keywords within a UTC time window. Searches
   * both past and future events so callers can ask about recent or upcoming occurrences.
   *
   * Uses MariaDB server-side `max_statement_time=1.5` to cap execution. Do NOT wrap with
   * `withTimeout` — blocking JDBC ignores coroutine cancellation and leaks the connection pool.
   *
   * @param keywords Keywords to match case-insensitively against event names.
   * @param windowStartUtc Start of the search window (UTC).
   * @param windowEndUtc End of the search window (UTC).
   * @param limit Maximum number of events to return.
   * @return Matching events ordered by start time ascending, or empty list on timeout/error.
   */
  @Suppress("TooGenericExceptionCaught", "ReturnCount")
  suspend fun getEventsByKeywords(
      keywords: List<String>,
      windowStartUtc: LocalDateTime,
      windowEndUtc: LocalDateTime,
      limit: Int,
  ): List<EventSummary> {
    if (keywords.isEmpty()) {
      return emptyList()
    }

    val startStr = windowStartUtc.format(SQL_TIMESTAMP_FORMAT)
    val endStr = windowEndUtc.format(SQL_TIMESTAMP_FORMAT)

    val orClause =
        keywords.joinToString(" OR ") { kw ->
          val escaped =
              kw.replace("'", "''").replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
          "LOWER(e.name) LIKE '%$escaped%' ESCAPE '\\\\'"
        }

    val query =
        """
      SET STATEMENT max_statement_time=1.5 FOR
      SELECT
          e.id,
          e.name,
          CONVERT_TZ(e.event_start, '+00:00', 'America/Chicago') AS event_start_cst,
          UNIX_TIMESTAMP(e.event_start) * 1000 AS event_start_utc_ms,
          e.status,
          c.ad_username AS organizer_username
      FROM `dms-calendar`.events e
      LEFT JOIN `dms-calendar`.contacts c ON e.contact_id = c.id
      WHERE e.event_start BETWEEN '$startStr' AND '$endStr'
        AND ($orClause)
      ORDER BY e.event_start ASC
      LIMIT $limit
    """

    log.debug(
        "Fetching events by ${keywords.size} keywords in window [$startStr, $endStr] (limit: $limit)")

    val result =
        try {
          genericRepository.getReportData(query)
        } catch (ex: Exception) {
          if (ex.isStatementTimeout()) {
            log.warn("Query timed out fetching events by keywords")
          } else {
            log.warn("Failed fetching events by keywords - ${ex.message}")
          }
          return emptyList()
        }

    return result?.data?.map { row ->
      val id = (row["id"] as? Number)?.toInt() ?: 0
      val name = row["name"]?.toString() ?: ""
      val eventStart = row["event_start_cst"]?.toString() ?: ""
      val eventStartUtcMs = (row["event_start_utc_ms"] as? Number)?.toLong()
      val status = row["status"]?.toString() ?: ""
      val organizerUsername = row["organizer_username"]?.toString()

      EventSummary(
          id = id,
          name = name,
          eventStart = eventStart,
          eventStartUtcMs = eventStartUtcMs,
          status = status,
          organizerUsername = organizerUsername,
      )
    } ?: emptyList()
  }

  /**
   * Counts the prerequisites tied to an AD group (case and surrounding spaces ignored) and the
   * events that fulfill or require them, split into upcoming and past. Read-only. Unlike the other
   * queries here it throws on any failure, because a caller deciding whether a group is safe to
   * remove must not read an error as "no references".
   */
  suspend fun countPrerequisiteReferences(groupName: String): PrerequisiteReferences {
    val literal = sqlStringLiteral(groupName.trim())
    val query =
        """
      SET STATEMENT max_statement_time=5 FOR
      SELECT
          COUNT(DISTINCT p.id) AS prerequisites,
          COUNT(DISTINCT CASE WHEN e.event_start >= NOW() THEN e.id END) AS upcoming_events,
          COUNT(DISTINCT CASE WHEN e.event_start < NOW() THEN e.id END) AS past_events
      FROM `dms-calendar`.prerequisites p
      LEFT JOIN `dms-calendar`.events e
          ON e.fulfills_prerequisite_id = p.id OR e.requires_prerequisite_id = p.id
      WHERE LOWER(TRIM(p.ad_group)) = LOWER($literal)
    """

    log.debug("Counting prerequisite references to a group")
    val row =
        genericRepository.getReportData(query)?.data?.firstOrNull()
            ?: error("No result counting prerequisite references")
    fun count(column: String) =
        (row[column] as? Number)?.toInt() ?: error("Missing $column in prerequisite count result")
    return PrerequisiteReferences(
        prerequisites = count("prerequisites"),
        upcomingEvents = count("upcoming_events"),
        pastEvents = count("past_events"),
    )
  }

  private fun Exception.isStatementTimeout() =
      message?.contains("max_statement_time exceeded") == true
}

/** Represents a summary of a calendar event. */
@Serializable
data class EventSummary(
    val id: Int,
    val name: String,
    val eventStart: String,
    val status: String,
    val organizerUsername: String? = null,
    val eventStartUtcMs: Long? = null,
    val fulfillsPrerequisiteGroup: String? = null,
)

/** How many calendar prerequisites name an AD group, and how many events use them. */
data class PrerequisiteReferences(
    val prerequisites: Int,
    val upcomingEvents: Int,
    val pastEvents: Int,
)

/**
 * A single-quoted SQL string literal for [value]. Quotes are doubled; backslashes and control
 * characters are rejected rather than escaped, since their meaning depends on the server's
 * sql_mode and no AD group name contains them.
 */
internal fun sqlStringLiteral(value: String): String {
  require(value.none { it == '\\' || it.isISOControl() }) { "Unsupported character in value" }
  return "'" + value.replace("'", "''") + "'"
}
