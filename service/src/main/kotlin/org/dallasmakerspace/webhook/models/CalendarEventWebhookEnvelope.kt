package org.dallasmakerspace.webhook.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Envelope for calendar's event-lifecycle webhook. [eventType] distinguishes the lifecycle stage
 * (e.g. "event.published", "event.updated", "event.deleted"); only "event.published" triggers a
 * notification - see `CalendarEventWebhook.EVENT_TYPE_PUBLISHED`. Field names/casing here must
 * match calendar's WebhookComponent payload shape exactly, since a mismatch fails deserialization
 * and calendar sees it as a 400.
 */
@Serializable
data class CalendarEventWebhookEnvelope(
    @SerialName("event_type") val eventType: String,
    @SerialName("occurred_at") val occurredAt: String? = null,
    val data: CalendarEventWebhookData,
)

@Serializable
data class CalendarEventWebhookData(
    val id: Long,
    @SerialName("organizer_username") val organizerUsername: String,
    val name: String,
    /** ISO-8601 local timestamp; combine with [eventStartTimezone] to get an absolute instant. */
    @SerialName("event_start") val eventStart: String,
    @SerialName("event_start_timezone") val eventStartTimezone: String? = null,
    val url: String,
)
