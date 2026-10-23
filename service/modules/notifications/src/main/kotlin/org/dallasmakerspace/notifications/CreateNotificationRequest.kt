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
    /**
     * When true, a member's explicit per-topic `enabled = false` preference does not drop this
     * notification. For one-shot, click-scoped consent (e.g. a Bambu print watch): the click is
     * newer, narrower consent than the standing toggle. Suppression, inactive-member and
     * aggregation/digest rules still apply unchanged.
     */
    val consentOverride: Boolean = false,
)
