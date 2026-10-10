package org.dallasmakerspace.calendar

import java.time.LocalDateTime
import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory

/** Service for calendar events operations. */
class CalendarService
@Inject
constructor(private val calendarRepository: CalendarRepository, loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  /**
   * Gets events organized by a member, prioritized by proximity to today. Events closest to the
   * current date (whether past or future) are returned first.
   *
   * @param username The username of the member.
   * @param limit The maximum number of events to return (default 5).
   * @return A list of [EventSummary] objects.
   */
  suspend fun getEventsOrganizedByMember(username: String, limit: Int = 5): List<EventSummary> {
    log.info("Getting events organized by member: $username")
    return calendarRepository.getEventsOrganizedByMember(username, limit)
  }

  /**
   * Checks if there are any prerequisite events for a given AD group.
   *
   * @param groupName The AD group name to check.
   * @return true if prerequisite events exist within the time range, false otherwise.
   */
  suspend fun hasPrerequisiteEvents(groupName: String): Boolean {
    log.info("Checking prerequisite events for group: $groupName")
    return calendarRepository.hasPrerequisiteEvents(groupName)
  }

  /**
   * Gets upcoming prerequisite events for multiple AD groups.
   *
   * @param groupNames The list of AD group names.
   * @return A list of [EventSummary] objects for upcoming events.
   */
  suspend fun getUpcomingPrerequisiteEvents(groupNames: List<String>): List<EventSummary> {
    log.info("Getting upcoming prerequisite events for ${groupNames.size} groups")
    return calendarRepository.getUpcomingPrerequisiteEvents(groupNames)
  }

  /**
   * Gets distinct event names that a member has attended, ordered by most recent first.
   *
   * @param username The AD username of the member.
   * @param limit The maximum number of event names to return (default 20).
   * @return A list of event name strings.
   */
  suspend fun getAttendedEventNames(username: String, limit: Int = 20): List<String> {
    log.info("Getting attended event names for: $username")
    return calendarRepository.getAttendedEventNames(username, limit)
  }

  /**
   * Gets upcoming events whose names match any of the given keywords.
   *
   * @param keywords The list of keywords to search for in event names.
   * @param limit The maximum number of events to return (default 5).
   * @return A list of [EventSummary] objects for upcoming matching events.
   */
  suspend fun getUpcomingEventsByKeywords(
      keywords: List<String>,
      limit: Int = 5,
  ): List<EventSummary> {
    log.info("Getting upcoming events by keywords: $keywords")
    return calendarRepository.getUpcomingEventsByKeywords(keywords, limit)
  }

  /**
   * Gets organizer usernames for events a member has attended, ranked by attendance frequency.
   *
   * @param username The AD username of the member.
   * @param limit The maximum number of organizer usernames to return (default 5).
   * @return A list of organizer AD usernames, highest-frequency first.
   */
  suspend fun getAttendedOrganizers(username: String, limit: Int = 5): List<String> {
    log.info("Getting top attended organizers for: $username")
    return calendarRepository.getAttendedOrganizers(username, limit)
  }

  /**
   * Gets upcoming events organized by any of the given organizer usernames.
   *
   * @param organizers The list of organizer AD usernames.
   * @param limit The maximum number of events to return (default 4).
   * @return A list of [EventSummary] objects for upcoming events.
   */
  suspend fun getUpcomingEventsByOrganizers(
      organizers: List<String>,
      limit: Int = 4,
  ): List<EventSummary> {
    log.info("Getting upcoming events for ${organizers.size} organizers")
    return calendarRepository.getUpcomingEventsByOrganizers(organizers, limit)
  }

  /**
   * Gets events whose names match any of the given keywords within a UTC time window. Covers both
   * past and future events so callers can answer questions about recent or upcoming occurrences.
   *
   * @param keywords Keywords to match against event names.
   * @param windowStartUtc Start of the search window (UTC).
   * @param windowEndUtc End of the search window (UTC).
   * @param limit Maximum number of events to return.
   * @return Matching events ordered by start time ascending.
   */
  suspend fun getEventsByKeywords(
      keywords: List<String>,
      windowStartUtc: LocalDateTime,
      windowEndUtc: LocalDateTime,
      limit: Int,
  ): List<EventSummary> {
    log.info("Getting events by keywords: $keywords in window [$windowStartUtc, $windowEndUtc]")
    return calendarRepository.getEventsByKeywords(keywords, windowStartUtc, windowEndUtc, limit)
  }
}
