package org.dallasmakerspace.notifications

/** Per-member preference for a topic/channel combination. */
data class NotificationPreference(
    val memberUsername: String,
    val topic: NotificationTopic,
    val channel: Channel,
    val enabled: Boolean,
    /** Member aggregation override; null = use the channel default. */
    val aggregation: Aggregation?,
)
