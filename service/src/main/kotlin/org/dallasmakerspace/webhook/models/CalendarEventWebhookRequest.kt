package org.dallasmakerspace.webhook.models

import kotlinx.serialization.Serializable

/**
 * Request model for the calendar-event webhook: "an organizer posted a new event." Triggers an
 * EVENT_ANNOUNCEMENT notification to every member following [organizerUsername]. Generic by
 * design - this is meant to be one of possibly several consumers of the calendar's event-lifecycle
 * webhook, not calendar-specific in shape.
 */
@Serializable
data class CalendarEventWebhookRequest(
    /** The calendar's event id; used to build a stable per-follower dedupe key. */
    val eventId: String,
    /** The organizer's DMS/AD username. */
    val organizerUsername: String,
    val eventName: String,
    /** ISO-8601 timestamp with an explicit offset/zone, e.g. "2026-08-10T19:00:00-05:00". */
    val eventStart: String,
    val eventUrl: String,
)
