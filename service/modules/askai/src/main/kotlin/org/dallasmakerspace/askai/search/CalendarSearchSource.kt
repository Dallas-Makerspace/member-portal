package org.dallasmakerspace.askai.search

import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.calendar.CalendarService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.SearchResult

private const val WINDOW_DAYS = 30L
private const val CALENDAR_BASE_URL = "https://calendar.dallasmakerspace.org/events/view"

// Generic event-type words that match hundreds of event names and drown out subject keywords.
// Removed from the SQL LIKE query so "crochet class" only searches for "crochet", not "class".
private val CALENDAR_GENERIC_TERMS =
    setOf(
        "class",
        "classes",
        "workshop",
        "workshops",
        "event",
        "events",
        "schedule",
        "session",
        "sessions",
        "meeting",
        "meetings",
        "training",
        "intro",
        "introduction",
        "basics",
        "sig",
    )

/**
 * Search source for DMS calendar events. Searches event names within a ±30-day window so AskAi can
 * answer questions about upcoming and recent classes, SIGs, and other events.
 *
 * Uses MariaDB server-side max_statement_time (1.5 s) for timeout protection — see
 * CalendarRepository for rationale on why coroutine withTimeout is not used here.
 */
@Singleton
class CalendarSearchSource
@Inject
constructor(
    private val calendarService: CalendarService,
    loggerFactory: LoggerFactory,
) : SearchSource {
  private val log = loggerFactory.create(javaClass)

  override fun getName(): String = "Calendar"

  override fun getPriority(): Int = 3

  override suspend fun search(query: String, limit: Int): List<SearchResult> {
    val keywords = RelevanceScorer.extractQueryTerms(listOf(query))
    if (keywords.isEmpty()) {
      log.debug("No meaningful keywords extracted from query for calendar search")
      return emptyList()
    }

    // Strip generic event-type words so "crochet class" only matches on "crochet".
    // Fall back to the full list if stripping leaves nothing (e.g. query is just "any classes?").
    val sqlKeywords = keywords.filter { it !in CALENDAR_GENERIC_TERMS }.ifEmpty { keywords }

    val now = LocalDateTime.now(ZoneOffset.UTC)
    log.info(
        "CalendarSearch: keywords=$keywords sqlKeywords=$sqlKeywords window=[${now.minusDays(WINDOW_DAYS)}, ${now.plusDays(WINDOW_DAYS)}] limit=$limit")

    val events =
        calendarService.getEventsByKeywords(
            keywords = sqlKeywords,
            windowStartUtc = now.minusDays(WINDOW_DAYS),
            windowEndUtc = now.plusDays(WINDOW_DAYS),
            limit = limit,
        )

    log.info("CalendarSearch: ${events.size} event(s) returned")
    events.forEach { ev ->
      log.info(
          "CalendarSearch: event id=${ev.id} name='${ev.name}' start=${ev.eventStart} status=${ev.status} utcMs=${ev.eventStartUtcMs} organizer=${ev.organizerUsername}")
    }

    return events.map { ev ->
      val snippet = buildString {
        append(ev.name)
        if (ev.organizerUsername != null) append(", organized by @${ev.organizerUsername}")
        append(". Starts ${ev.eventStart}.")
        append(" Status: ${ev.status}.")
      }
      SearchResult(
          title = "${ev.name} — ${ev.eventStart}",
          snippet = snippet,
          url = "$CALENDAR_BASE_URL/${ev.id}",
          source = getName(),
          sourceCategory = SearchResult.EVENTS,
          eventDateUtc = ev.eventStartUtcMs,
      )
    }
  }
}
