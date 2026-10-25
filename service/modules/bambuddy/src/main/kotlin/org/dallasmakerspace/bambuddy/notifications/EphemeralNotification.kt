package org.dallasmakerspace.bambuddy.notifications

import java.time.LocalDateTime
import kotlinx.serialization.Serializable

/** What a one-shot watch is watching. */
enum class WatchScope {
  OWN_JOB,
  PRINTER_AVAILABLE,
}

/** Watch lifecycle: ARMED is the only live state; the rest are tombstones (purged after retention). */
enum class WatchStatus {
  ARMED,
  FIRED,
  EXPIRED,
  CANCELLED,
}

/**
 * A one-shot watch (`EphemeralNotification` in the design): a member clicked "notify me" for one
 * specific print (or for a busy printer becoming available). Firing/expire creates a normal
 * [org.dallasmakerspace.notifications.Notification] via NotificationService; the watch row itself
 * is the click-consent audit record and is kept as a tombstone after it resolves.
 */
data class EphemeralNotification(
    val id: Long,
    val memberUsername: String,
    val scope: WatchScope,
    val printerId: Int,
    val printerName: String,
    /** OWN_JOB: the watched queue item. PRINTER_AVAILABLE: the blocking (currently printing) item. */
    val queueId: Int?,
    /** Queue archive_name snapshot; correlation cross-check against the webhook payload. */
    val filename: String?,
    /** Display name for emails (same value as [filename] in v1). */
    val jobName: String?,
    val triggerEvents: List<String>,
    val status: WatchStatus,
    /** 0 or 1; at most one re-arm is allowed. */
    val rearmCount: Int,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val terminalAt: LocalDateTime?,
    val firedNotificationId: Long?,
    val terminalReason: String?,
)

/** States of a held printer-offline committee alert; PROCESSING is the sweeper's transient claim. */
enum class PendingOfflineStatus {
  PENDING,
  PROCESSING,
  SENT,
  SUPPRESSED,
}

/** Outcome of processing one due watch at expiry (design §10.2), switched on exhaustively. */
enum class ExpiryOutcome {
  FIRED,
  OVERDUE_REARMED,
  RETARGETED,
  EXPIRED,
  CLAIM_LOST,
}

/** A printer-offline episode held for the re-check window before any notification is created. */
data class PendingOfflineAlert(
    val id: Long,
    val printerId: Int,
    val printerName: String,
    val offlineSince: LocalDateTime,
    val releaseAt: LocalDateTime,
    val status: PendingOfflineStatus,
    val resolvedAt: LocalDateTime?,
    val notificationsCreated: Int?,
)

/** POST /bambu/watches body. No identity field by construction — the actor is the X-Username header. */
@Serializable
data class CreateBambuWatchRequest(
    val scope: String,
    val printerId: Int,
    val queueId: Int? = null,
)

@Serializable
data class BambuWatchDTO(
    val id: Long,
    val scope: String,
    val printerId: Int,
    val printerName: String,
    val queueId: Int?,
    val jobName: String?,
    val status: String,
    val createdAt: String,
    val expiresAt: String,
    val rearmCount: Int,
)

fun EphemeralNotification.toDTO(): BambuWatchDTO =
    BambuWatchDTO(
        id = id,
        scope = scope.name,
        printerId = printerId,
        printerName = printerName,
        queueId = queueId,
        jobName = jobName,
        status = status.name,
        createdAt = createdAt.toString(),
        expiresAt = expiresAt.toString(),
        rearmCount = rearmCount,
    )

/** Arm/cancel failure carrying the HTTP status the watch routes should respond with. */
class BambuWatchException(val statusCode: Int, message: String) : Exception(message)
