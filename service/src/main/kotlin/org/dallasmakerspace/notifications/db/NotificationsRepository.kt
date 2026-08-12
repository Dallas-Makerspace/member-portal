package org.dallasmakerspace.notifications.db

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.db.ProfileResolver
import org.dallasmakerspace.members.db.suspendTransaction
import org.dallasmakerspace.notifications.Aggregation
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.Notification
import org.dallasmakerspace.notifications.NotificationPreference
import org.dallasmakerspace.notifications.NotificationStatus
import org.dallasmakerspace.notifications.NotificationSuppression
import org.dallasmakerspace.notifications.NotificationTopic
import org.dallasmakerspace.notifications.templates.TemplateRegistry
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

/**
 * Outbox CRUD + status transitions for the `notifications` family of tables. Rows are stored keyed
 * by `profile_id` (FK to `profile.id`); this repository is the boundary that translates to/from
 * member usernames, which is what the rest of the notifications feature (service, worker, routes)
 * works with.
 */
@Singleton
@Suppress("TooManyFunctions") // Outbox CRUD + status transitions; kept together for cohesion.
class NotificationsRepository
@Inject
constructor(loggerFactory: LoggerFactory, private val templateRegistry: TemplateRegistry) {
  private val log = loggerFactory.create(javaClass)

  private val utcZone = ZoneId.of("UTC")

  /**
   * Inserts a notification. When a [Notification.dedupeKey] is set and a row with the same key
   * already exists, the existing row is returned instead (idempotent create).
   */
  @Suppress("TooGenericExceptionCaught") // Duplicate-key detection spans DB driver exceptions.
  suspend fun create(notification: Notification): Notification {
    val id = suspendTransaction {
      try {
        val resolvedProfileId = ProfileResolver.requireProfileId(notification.memberUsername)
        val statement =
            NotificationsTable.insert {
              it[profileId] = resolvedProfileId
              it[topic] = notification.topic.key
              it[channel] = notification.channel.name
              it[aggregation] = notification.aggregation.name
              it[templateName] = notification.templateName
              it[payload] = notification.payload
              it[subject] = notification.subject
              it[body] = notification.body
              it[status] = notification.status.name
              it[attempts] = notification.attempts
              it[nextAttemptAt] = notification.nextAttemptAt
              it[dedupeKey] = notification.dedupeKey
              it[providerId] = notification.providerId
              it[lastError] = notification.lastError
              it[sentAt] = notification.sentAt
              it[readAt] = notification.readAt
              it[createdAt] = notification.createdAt
              it[updatedAt] = notification.createdAt
            }
        requireNotNull(statement[NotificationsTable.idColumn]) { "Generated key missing" }.value
      } catch (e: Exception) {
        if (notification.dedupeKey != null && isDuplicateKey(e)) {
          null
        } else {
          throw e
        }
      }
    }
    if (id != null) {
      return notification.copy(id = id)
    }
    val existing =
        requireNotNull(getByDedupeKey(notification.dedupeKey!!)) {
          "Duplicate dedupe_key '${notification.dedupeKey}' but no existing row found"
        }
    log.info(
        "Notification with dedupe_key '${notification.dedupeKey}' already exists, returning existing")
    return existing
  }

  private fun isDuplicateKey(e: Exception): Boolean =
      e.message?.contains("Duplicate") == true || e.message?.contains("unique") == true

  suspend fun getByDedupeKey(dedupeKey: String): Notification? = suspendTransaction {
    NotificationsTable.select { NotificationsTable.dedupeKey eq dedupeKey }
        .firstOrNull()
        ?.let { toNotification(it) }
  }

  suspend fun getById(id: Long): Notification? = suspendTransaction {
    NotificationsTable.select { NotificationsTable.idColumn eq id }
        .firstOrNull()
        ?.let { toNotification(it) }
  }

  /** Looks up the notification a provider event refers to (e.g. SparkPost `transmission_id`). */
  suspend fun getByProviderId(providerId: String): Notification? = suspendTransaction {
    NotificationsTable.select { NotificationsTable.providerId eq providerId }
        .firstOrNull()
        ?.let { toNotification(it) }
  }

  /** Rows the worker may attempt now: QUEUED or FAILED with no delay or an elapsed delay. */
  suspend fun getPending(limit: Int, now: LocalDateTime): List<Notification> = suspendTransaction {
    val rows =
        NotificationsTable.select {
              (NotificationsTable.status inList
                  listOf(NotificationStatus.QUEUED.name, NotificationStatus.FAILED.name)) and
                  (NotificationsTable.nextAttemptAt.isNull() or
                      (NotificationsTable.nextAttemptAt lessEq now))
            }
            .orderBy(NotificationsTable.id to SortOrder.ASC)
            .limit(limit)
            .toList()
    toNotifications(rows)
  }

  /**
   * Rows awaiting digest delivery, oldest first (stable digest order). Optionally restricted to one
   * [Aggregation] (e.g. DAILY vs WEEKLY when schedules differ).
   */
  suspend fun getInDigest(limit: Int, aggregation: Aggregation? = null): List<Notification> =
      suspendTransaction {
        val conditions = buildList {
          add(NotificationsTable.status eq NotificationStatus.IN_DIGEST.name)
          aggregation?.let { add(NotificationsTable.aggregation eq it.name) }
        }
        val rows =
            NotificationsTable.select { conditions.reduce { acc, cond -> acc and cond } }
                .orderBy(NotificationsTable.id to SortOrder.ASC)
                .limit(limit)
                .toList()
        toNotifications(rows)
      }

  suspend fun markSending(id: Long) {
    suspendTransaction {
      NotificationsTable.update({ NotificationsTable.idColumn eq id }) {
        it[status] = NotificationStatus.SENDING.name
        it[updatedAt] = LocalDateTime.now(utcZone)
      }
    }
  }

  suspend fun markSent(id: Long, providerId: String?, sentAt: LocalDateTime) {
    suspendTransaction {
      NotificationsTable.update({ NotificationsTable.idColumn eq id }) {
        it[status] = NotificationStatus.SENT.name
        it[this.providerId] = providerId
        it[this.sentAt] = sentAt
        it[updatedAt] = sentAt
      }
    }
  }

  /** Records a transient failure; the row becomes eligible for retry at [nextAttemptAt]. */
  suspend fun markFailed(id: Long, error: String, nextAttemptAt: LocalDateTime) {
    suspendTransaction {
      val currentAttempts =
          NotificationsTable.select { NotificationsTable.idColumn eq id }
              .first()[NotificationsTable.attempts]
      NotificationsTable.update({ NotificationsTable.idColumn eq id }) {
        it[status] = NotificationStatus.FAILED.name
        it[attempts] = currentAttempts + 1
        it[this.nextAttemptAt] = nextAttemptAt
        it[lastError] = error
        it[updatedAt] = LocalDateTime.now(utcZone)
      }
    }
    log.warn("Notification $id marked FAILED: $error")
  }

  /** Records a permanent failure (bad recipient, auth, or retries exhausted). */
  suspend fun markDeadLetter(id: Long, error: String) {
    suspendTransaction {
      val currentAttempts =
          NotificationsTable.select { NotificationsTable.idColumn eq id }
              .first()[NotificationsTable.attempts]
      NotificationsTable.update({ NotificationsTable.idColumn eq id }) {
        it[status] = NotificationStatus.DEAD_LETTER.name
        it[attempts] = currentAttempts + 1
        it[lastError] = error
        it[updatedAt] = LocalDateTime.now(utcZone)
      }
    }
    log.warn("Notification $id marked DEAD_LETTER: $error")
  }

  /** Records that the notification was deliberately not sent (e.g. disabled preference). */
  suspend fun markSkipped(id: Long, reason: String) {
    suspendTransaction {
      NotificationsTable.update({ NotificationsTable.idColumn eq id }) {
        it[status] = NotificationStatus.SKIPPED.name
        it[lastError] = reason
        it[updatedAt] = LocalDateTime.now(utcZone)
      }
    }
    log.info("Notification $id marked SKIPPED: $reason")
  }

  /** Defers an immediate notification to the next digest (e.g. per-member daily cap reached). */
  suspend fun markInDigest(id: Long, reason: String) {
    suspendTransaction {
      NotificationsTable.update({ NotificationsTable.idColumn eq id }) {
        it[status] = NotificationStatus.IN_DIGEST.name
        it[lastError] = reason
        it[updatedAt] = LocalDateTime.now(utcZone)
      }
    }
    log.info("Notification $id moved to IN_DIGEST: $reason")
  }

  /** Counts immediate-channel notifications sent for a member since [since] (UTC). */
  suspend fun countImmediateSentSince(memberUsername: String, since: LocalDateTime): Long =
      suspendTransaction {
        val resolvedProfileId =
            ProfileResolver.findProfileId(memberUsername) ?: return@suspendTransaction 0L
        NotificationsTable.select {
              (NotificationsTable.profileId eq resolvedProfileId) and
                  (NotificationsTable.aggregation eq Aggregation.IMMEDIATE.name) and
                  (NotificationsTable.status eq NotificationStatus.SENT.name) and
                  (NotificationsTable.sentAt.isNotNull() and
                      (NotificationsTable.sentAt greaterEq since))
            }
            .count()
      }

  /** Marks a notification read, but only if it belongs to [memberUsername]. */
  suspend fun markRead(id: Long, memberUsername: String, readAt: LocalDateTime): Boolean =
      suspendTransaction {
        val resolvedProfileId =
            ProfileResolver.findProfileId(memberUsername) ?: return@suspendTransaction false
        NotificationsTable.update({
          (NotificationsTable.idColumn eq id) and
              (NotificationsTable.profileId eq resolvedProfileId)
        }) {
          it[this.readAt] = readAt
          it[updatedAt] = readAt
        } > 0
      }

  suspend fun getPreference(
      memberUsername: String,
      topic: NotificationTopic,
      channel: Channel,
  ): NotificationPreference? = suspendTransaction {
    val resolvedProfileId =
        ProfileResolver.findProfileId(memberUsername) ?: return@suspendTransaction null
    NotificationPreferencesTable.select {
          (NotificationPreferencesTable.profileId eq resolvedProfileId) and
              (NotificationPreferencesTable.topic eq topic.key) and
              (NotificationPreferencesTable.channel eq channel.name)
        }
        .firstOrNull()
        ?.let { toPreference(memberUsername, it) }
  }

  /** All stored preferences for [memberUsername]. */
  suspend fun getPreferences(memberUsername: String): List<NotificationPreference> =
      suspendTransaction {
        val resolvedProfileId =
            ProfileResolver.findProfileId(memberUsername) ?: return@suspendTransaction emptyList()
        NotificationPreferencesTable.select {
              NotificationPreferencesTable.profileId eq resolvedProfileId
            }
            .map { toPreference(memberUsername, it) }
      }

  /** Inserts or updates a preference (upsert keyed by member/topic/channel). */
  suspend fun setPreference(preference: NotificationPreference): NotificationPreference =
      suspendTransaction {
        val resolvedProfileId = ProfileResolver.requireProfileId(preference.memberUsername)
        val now = LocalDateTime.now(utcZone)
        val updated =
            NotificationPreferencesTable.update({
              (NotificationPreferencesTable.profileId eq resolvedProfileId) and
                  (NotificationPreferencesTable.topic eq preference.topic.key) and
                  (NotificationPreferencesTable.channel eq preference.channel.name)
            }) {
              it[enabled] = preference.enabled
              it[aggregation] = preference.aggregation?.name
              it[updatedAt] = now
            }
        if (updated == 0) {
          NotificationPreferencesTable.insert {
            it[profileId] = resolvedProfileId
            it[topic] = preference.topic.key
            it[channel] = preference.channel.name
            it[enabled] = preference.enabled
            it[aggregation] = preference.aggregation?.name
            it[updatedAt] = now
          }
        }
        preference
      }

  /**
   * The most recent [limit] notifications, optionally filtered by [memberUsername] and/or [status],
   * newest first.
   */
  suspend fun list(
      limit: Int,
      memberUsername: String? = null,
      status: NotificationStatus? = null,
  ): List<Notification> = suspendTransaction {
    val resolvedProfileId =
        memberUsername?.let {
          ProfileResolver.findProfileId(it) ?: return@suspendTransaction emptyList()
        }
    val conditions = buildList {
      resolvedProfileId?.let { add(NotificationsTable.profileId eq it) }
      status?.let { add(NotificationsTable.status eq it.name) }
    }
    val rows =
        NotificationsTable.select {
              if (conditions.isEmpty()) NotificationsTable.idColumn.isNotNull()
              else conditions.reduce { acc, cond -> acc and cond }
            }
            .orderBy(NotificationsTable.id to SortOrder.DESC)
            .limit(limit)
            .toList()
    toNotifications(rows)
  }

  suspend fun getSuppression(memberUsername: String): NotificationSuppression? =
      suspendTransaction {
        val resolvedProfileId =
            ProfileResolver.findProfileId(memberUsername) ?: return@suspendTransaction null
        NotificationSuppressionsTable.select {
              NotificationSuppressionsTable.profileId eq resolvedProfileId
            }
            .firstOrNull()
            ?.let {
              NotificationSuppression(
                  id = it[NotificationSuppressionsTable.id],
                  memberUsername = memberUsername,
                  email = it[NotificationSuppressionsTable.email],
                  reason = it[NotificationSuppressionsTable.reason],
                  createdAt = it[NotificationSuppressionsTable.createdAt],
              )
            }
      }

  /** Appends a suppression record (bounce or unsubscribe, mirrored from the provider). */
  suspend fun insertSuppression(memberUsername: String, email: String?, reason: String) {
    suspendTransaction {
      val resolvedProfileId = ProfileResolver.requireProfileId(memberUsername)
      NotificationSuppressionsTable.insert {
        it[profileId] = resolvedProfileId
        it[this.email] = email
        it[this.reason] = reason
        it[createdAt] = LocalDateTime.now(utcZone)
      }
    }
  }

  /** Maps rows to [Notification]s, dropping (and logging) any whose profile no longer exists. */
  private fun toNotifications(rows: List<ResultRow>): List<Notification> {
    val usernames = ProfileResolver.resolveUsernames(rows.map { it[NotificationsTable.profileId] })
    return rows.mapNotNull { row ->
      val profileId = row[NotificationsTable.profileId]
      val username = usernames[profileId]
      if (username == null) {
        log.warn(
            "Notification ${row[NotificationsTable.idColumn].value} references unknown profile_id $profileId, skipping")
        null
      } else {
        toNotification(row, username)
      }
    }
  }

  /** Resolves a stored topic key back to its [NotificationTopic] instance; crashes loudly on data
   *  corruption (an unknown key means the row predates that topic's producer, or the producer was
   *  removed), matching the enum-`valueOf`-throws behavior this replaces. */
  private fun resolveTopic(key: String) =
      requireNotNull(templateRegistry.findTopic(key)) {
        "Unknown notification topic '$key' in stored row"
      }

  private fun toPreference(memberUsername: String, row: ResultRow): NotificationPreference =
      NotificationPreference(
          memberUsername = memberUsername,
          topic = resolveTopic(row[NotificationPreferencesTable.topic]),
          channel = Channel.valueOf(row[NotificationPreferencesTable.channel]),
          enabled = row[NotificationPreferencesTable.enabled],
          aggregation =
              row[NotificationPreferencesTable.aggregation]?.let { name ->
                Aggregation.valueOf(name)
              },
      )

  private fun toNotification(row: ResultRow): Notification {
    val profileId = row[NotificationsTable.profileId]
    val memberUsername =
        requireNotNull(ProfileResolver.findUsername(profileId)) {
          "Notification references unknown profile_id $profileId"
        }
    return toNotification(row, memberUsername)
  }

  private fun toNotification(row: ResultRow, memberUsername: String): Notification =
      Notification(
          id = row[NotificationsTable.idColumn].value,
          memberUsername = memberUsername,
          topic = resolveTopic(row[NotificationsTable.topic]),
          channel = Channel.valueOf(row[NotificationsTable.channel]),
          aggregation = Aggregation.valueOf(row[NotificationsTable.aggregation]),
          templateName = row[NotificationsTable.templateName],
          payload = row[NotificationsTable.payload],
          subject = row[NotificationsTable.subject],
          body = row[NotificationsTable.body],
          status = NotificationStatus.valueOf(row[NotificationsTable.status]),
          attempts = row[NotificationsTable.attempts],
          nextAttemptAt = row[NotificationsTable.nextAttemptAt],
          dedupeKey = row[NotificationsTable.dedupeKey],
          providerId = row[NotificationsTable.providerId],
          lastError = row[NotificationsTable.lastError],
          sentAt = row[NotificationsTable.sentAt],
          readAt = row[NotificationsTable.readAt],
          createdAt = row[NotificationsTable.createdAt],
      )
}
