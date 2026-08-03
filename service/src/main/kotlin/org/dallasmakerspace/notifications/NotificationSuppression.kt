package org.dallasmakerspace.notifications

import java.time.LocalDateTime

/** A member suppressed from receiving notifications. */
data class NotificationSuppression(
    val id: Long,
    val memberUsername: String,
    val email: String?,
    val reason: String,
    val createdAt: LocalDateTime,
)
