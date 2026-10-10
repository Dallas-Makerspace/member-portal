package org.dallasmakerspace.notifications

/** Request to create a notification for a member. */
data class CreateNotificationRequest(
    val memberUsername: String,
    val topic: NotificationTopic,
    val templateName: String,
    /** JSON object string used to render the template; may be null for templates without data. */
    val payload: String? = null,
    val channel: Channel = Channel.EMAIL,
    /** Caller-supplied idempotency key; a repeat create with the same key returns the original. */
    val dedupeKey: String? = null,
)
