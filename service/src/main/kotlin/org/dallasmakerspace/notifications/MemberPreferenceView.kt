package org.dallasmakerspace.notifications

/**
 * Effective notification preference for a member for one topic/channel combination, merging stored
 * rows with the system defaults. Returned by [NotificationService.getPreferences].
 */
data class MemberPreferenceView(
    val memberUsername: String,
    val topic: NotificationTopic,
    val channel: Channel,
    val enabled: Boolean,
    /** Member aggregation override; null = use the channel default. */
    val aggregationOverride: Aggregation?,
    val effectiveAggregation: Aggregation,
    /** Whether the channel can batch notifications into daily/weekly digests. */
    val digestSupported: Boolean,
)
