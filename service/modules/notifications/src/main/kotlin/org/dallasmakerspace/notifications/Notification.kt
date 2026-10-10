package org.dallasmakerspace.notifications

import java.time.LocalDateTime

/** Domain model for a notification row (outbox entry and unified communication log). */
data class Notification(
    val id: Long,
    val memberUsername: String,
    val topic: NotificationTopic,
    val channel: Channel,
    val aggregation: Aggregation,
    val templateName: String,
    val payload: String?,
    val subject: String,
    val body: String?,
    val status: NotificationStatus,
    val attempts: Int,
    val nextAttemptAt: LocalDateTime?,
    val dedupeKey: String?,
    val providerId: String?,
    val lastError: String?,
    val sentAt: LocalDateTime?,
    val readAt: LocalDateTime?,
    val createdAt: LocalDateTime,
)
