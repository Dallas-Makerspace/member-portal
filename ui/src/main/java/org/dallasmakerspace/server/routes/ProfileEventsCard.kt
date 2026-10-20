package org.dallasmakerspace.server.routes

/** Events shown while the "Events Organized" card is collapsed. */
const val EVENTS_COLLAPSED_VISIBLE = 5

/** Of those, how many are past events (when there are that many), so it isn't only the future. */
const val EVENTS_COLLAPSED_MIN_PAST = 2

/** Past events fetched in addition to every upcoming one. */
const val EVENTS_PAST_FETCHED = 5

/** Id of the card; `/profile/@user#events-organized` opens it expanded. */
const val EVENTS_ORGANIZED_ANCHOR = "events-organized"

/**
 * Adds `collapsed` to each event: true for the ones hidden until the card is expanded. [events] is
 * in reading order (upcoming soonest first, then past newest first, as the service returns them).
 * The collapsed card shows the next upcoming events first, keeping room for the latest past ones.
 */
fun markCollapsedEvents(events: List<Map<String, Any?>>): List<Map<String, Any?>> {
  val upcoming = events.filter { it["isUpcoming"] == true }
  val past = events.filter { it["isUpcoming"] != true }
  val reservedPast = minOf(past.size, EVENTS_COLLAPSED_MIN_PAST)
  val upcomingShown = minOf(upcoming.size, EVENTS_COLLAPSED_VISIBLE - reservedPast)
  val pastShown = minOf(past.size, EVENTS_COLLAPSED_VISIBLE - upcomingShown)
  val visible = (upcoming.take(upcomingShown) + past.take(pastShown)).map { it["id"] }.toSet()
  return events.map { it + ("collapsed" to (it["id"] !in visible)) }
}
