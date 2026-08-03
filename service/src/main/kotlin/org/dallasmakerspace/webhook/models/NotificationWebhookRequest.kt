package org.dallasmakerspace.webhook.models

import kotlinx.serialization.Serializable

/**
 * Request model for the notifications webhook. Allows external systems to emit a notification for a
 * member, which flows through the same preference/suppression/dedup pipeline as internal emits.
 */
@Serializable
data class NotificationWebhookRequest(
    val memberUsername: String,
    /** One of the NotificationTopic enum names (EVENT_ANNOUNCEMENT, ACCOUNT, SYSTEM). */
    val topic: String,
    /** Registered template name, e.g. `event-announcement`. */
    val templateName: String,
    /** JSON object string used to render the template. */
    val payload: String? = null,
    /** One of the Channel enum names (EMAIL, SMS, DISCORD, DISCOURSE). Defaults to EMAIL. */
    val channel: String = "EMAIL",
    /** Caller-supplied idempotency key; a repeat emit with the same key is a no-op. */
    val dedupeKey: String? = null,
)
