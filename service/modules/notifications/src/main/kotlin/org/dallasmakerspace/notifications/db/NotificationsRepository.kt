package org.dallasmakerspace.notifications.db

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.members.db.ProfileResolver
import org.dallasmakerspace.notifications.Aggregation
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.Notification
import org.dallasmakerspace.notifications.NotificationPreference
import org.dallasmakerspace.notifications.NotificationStatus
import org.dallasmakerspace.notifications.NotificationSuppression
import org.dallasmakerspace.notifications.NotificationTopic
import org.dallasmakerspace.notifications.UnknownTopic
import org.dallasmakerspace.notifications.templates.TemplateRegistry
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
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
    toNotifications(
            NotificationsTable.select { NotificationsTable.dedupeKey eq dedupeKey }.limit(1).toList())
        .firstOrNull()
  }

  /**
   * Every notification a provider event refers to (e.g. SparkPost `transmission_id`). A digest is
   * one transmission covering several rows, so this can return more than one.
   */
  suspend fun getAllByProviderId(providerId: String): List<Notification> = suspendTransaction {
    toNotifications(
        NotificationsTable.select { NotificationsTable.providerId eq providerId }.toList())
  }

  /**
   * Usernames of up to [limit] members with an immediate row due now: QUEUED past its hold window
   * or FAILED past its retry delay. [onlyUsernames] restricts the query (e.g. to pilot testers) so
   * other members' rows can't fill the batch and starve eligible ones.
   */
  suspend fun getMembersWithDueImmediate(
      limit: Int,
      now: LocalDateTime,
      onlyUsernames: Set<String>? = null,
  ): List<String> = suspendTransaction {
    val profileIds = onlyUsernames?.let(::resolveProfileIds)
    if (profileIds != null && profileIds.isEmpty()) return@suspendTransaction emptyList()
    val ids =
        NotificationsTable.slice(NotificationsTable.profileId)
            .select {
              val due = dueImmediateCondition(now)
              if (profileIds == null) due
              else due and (NotificationsTable.profileId inList profileIds)
            }
            .withDistinct()
            .orderBy(NotificationsTable.profileId to SortOrder.ASC)
            .limit(limit)
            .map { it[NotificationsTable.profileId] }
    ProfileResolver.resolveUsernames(ids).values.toList()
  }

  /**
   * The burst to send for [memberUsername] now: every QUEUED row (including ones still inside
   * their hold window, which join the burst early) plus FAILED rows whose retry is due, oldest
   * first.
   */
  suspend fun getImmediateBurst(memberUsername: String, now: LocalDateTime): List<Notification> =
      suspendTransaction {
        val resolvedProfileId =
            ProfileResolver.findProfileId(memberUsername) ?: return@suspendTransaction emptyList()
        val rows =
            NotificationsTable.select {
                  (NotificationsTable.profileId eq resolvedProfileId) and
                      ((NotificationsTable.status eq NotificationStatus.QUEUED.name) or
                          ((NotificationsTable.status eq NotificationStatus.FAILED.name) and
                              dueCondition(now)))
                }
                .orderBy(NotificationsTable.id to SortOrder.ASC)
                .toList()
        toNotifications(rows)
      }

  private fun dueCondition(now: LocalDateTime) =
      NotificationsTable.nextAttemptAt.isNull() or (NotificationsTable.nextAttemptAt lessEq now)

  private fun dueImmediateCondition(now: LocalDateTime) =
      (NotificationsTable.status inList
          listOf(NotificationStatus.QUEUED.name, NotificationStatus.FAILED.name)) and
          dueCondition(now)

  /**
   * Usernames of up to [limit] members with at least one digest row whose delivery window has
   * arrived. Selecting members first (rather than a row batch) guarantees a member's due rows are
   * never split across two digest emails.
   */
  suspend fun getMembersWithDueDigests(
      limit: Int,
      now: LocalDateTime,
      onlyUsernames: Set<String>? = null,
  ): List<String> = suspendTransaction {
    val profileIds = onlyUsernames?.let(::resolveProfileIds)
    if (profileIds != null && profileIds.isEmpty()) return@suspendTransaction emptyList()
    val ids =
        NotificationsTable.slice(NotificationsTable.profileId)
            .select {
              val due = dueDigestCondition(now)
              if (profileIds == null) due
              else due and (NotificationsTable.profileId inList profileIds)
            }
            .withDistinct()
            .orderBy(NotificationsTable.profileId to SortOrder.ASC)
            .limit(limit)
            .map { it[NotificationsTable.profileId] }
    ProfileResolver.resolveUsernames(ids).values.toList()
  }

  /** All of [memberUsername]'s digest rows whose delivery window has arrived, oldest first. */
  suspend fun getDueDigestRows(memberUsername: String, now: LocalDateTime): List<Notification> =
      suspendTransaction {
        val resolvedProfileId =
            ProfileResolver.findProfileId(memberUsername) ?: return@suspendTransaction emptyList()
        val rows =
            NotificationsTable.select {
                  (NotificationsTable.profileId eq resolvedProfileId) and dueDigestCondition(now)
                }
                .orderBy(NotificationsTable.id to SortOrder.ASC)
                .toList()
        toNotifications(rows)
      }

  private fun dueDigestCondition(now: LocalDateTime) =
      (NotificationsTable.status eq NotificationStatus.IN_DIGEST.name) and dueCondition(now)

  /**
   * Atomically moves [id] to SENDING if it is still in one of [fromStatuses]. Returns false when
   * another worker (e.g. an overlapping instance during a deploy) already claimed it, so a row is
   * never sent twice.
   */
  suspend fun claim(id: Long, fromStatuses: Collection<NotificationStatus>): Boolean =
      suspendTransaction {
        NotificationsTable.update({
          (NotificationsTable.idColumn eq id) and
              (NotificationsTable.status inList fromStatuses.map { it.name })
        }) {
          it[status] = NotificationStatus.SENDING.name
          it[updatedAt] = LocalDateTime.now(utcZone)
        } > 0
      }

  /**
   * Recovers rows stuck in SENDING since before [staleBefore] (the process died mid-send): digest
   * rows go back to IN_DIGEST, everything else to FAILED, both due immediately. Returns the count.
   */
  suspend fun requeueStaleSending(staleBefore: LocalDateTime): Int = suspendTransaction {
    val now = LocalDateTime.now(utcZone)
    fun stale() =
        (NotificationsTable.status eq NotificationStatus.SENDING.name) and
            (NotificationsTable.updatedAt less staleBefore)
    val digest =
        NotificationsTable.update({
          stale() and (NotificationsTable.aggregation neq Aggregation.IMMEDIATE.name)
        }) {
          it[status] = NotificationStatus.IN_DIGEST.name
          it[nextAttemptAt] = now
          it[lastError] = STALE_SENDING_ERROR
          it[updatedAt] = now
        }
    val immediate =
        NotificationsTable.update({ stale() }) {
          it[status] = NotificationStatus.FAILED.name
          it[nextAttemptAt] = now
          it[lastError] = STALE_SENDING_ERROR
          it[updatedAt] = now
        }
    digest + immediate
  }

  suspend fun markSent(id: Long, providerId: String?, sentAt: LocalDateTime) {
    suspendTransaction {
      NotificationsTable.update({ NotificationsTable.idColumn eq id }) {
        it[status] = NotificationStatus.SENT.name
        it[this.providerId] = providerId
        it[this.sentAt] = sentAt
        it[lastError] = null
        it[updatedAt] = sentAt
      }
    }
  }

  /** Records a transient failure; the row becomes eligible for retry at [nextAttemptAt]. */
  suspend fun markFailed(id: Long, error: String, nextAttemptAt: LocalDateTime) {
    transition(id, NotificationStatus.FAILED, error, nextAttemptAt, incrementAttempts = true)
    log.warn("Notification $id marked FAILED: $error")
  }

  /**
   * Records a transient digest failure: the row stays IN_DIGEST (so the retry is still a digest,
   * never an individual email) and becomes due again at [nextAttemptAt].
   */
  suspend fun markDigestRetry(id: Long, error: String, nextAttemptAt: LocalDateTime) {
    transition(id, NotificationStatus.IN_DIGEST, error, nextAttemptAt, incrementAttempts = true)
    log.warn("Digest notification $id will retry at $nextAttemptAt: $error")
  }

  /** Records a permanent failure (bad recipient, auth, or retries exhausted). */
  suspend fun markDeadLetter(id: Long, error: String) {
    transition(id, NotificationStatus.DEAD_LETTER, error, null, incrementAttempts = true)
    log.warn("Notification $id marked DEAD_LETTER: $error")
  }

  /**
   * Defers an immediate notification to the [aggregation] digest due at [deliverAt] (e.g.
   * per-member daily cap reached). The row's aggregation is rewritten so it reflects how the
   * notification was actually delivered.
   */
  suspend fun markInDigest(
      id: Long,
      reason: String,
      aggregation: Aggregation,
      deliverAt: LocalDateTime,
  ) {
    transition(
        id, NotificationStatus.IN_DIGEST, reason, deliverAt, incrementAttempts = false, aggregation)
    log.info("Notification $id moved to $aggregation digest due $deliverAt: $reason")
  }

  @Suppress("LongParameterList")
  private suspend fun transition(
      id: Long,
      newStatus: NotificationStatus,
      error: String?,
      nextAttemptAt: LocalDateTime?,
      incrementAttempts: Boolean,
      newAggregation: Aggregation? = null,
  ) {
    suspendTransaction {
      NotificationsTable.update({ NotificationsTable.idColumn eq id }) {
        it[status] = newStatus.name
        newAggregation?.let { agg -> it[aggregation] = agg.name }
        if (incrementAttempts) {
          with(SqlExpressionBuilder) { it.update(attempts, attempts + 1) }
        }
        it[this.nextAttemptAt] = nextAttemptAt
        it[lastError] = error
        it[updatedAt] = LocalDateTime.now(utcZone)
      }
    }
  }

  /**
   * Immediate emails (not rows) sent to a member since [since] (UTC): a combined burst counts once,
   * since the rows it contained share one provider id.
   */
  suspend fun countImmediateEmailsSince(memberUsername: String, since: LocalDateTime): Int =
      suspendTransaction {
        val resolvedProfileId =
            ProfileResolver.findProfileId(memberUsername) ?: return@suspendTransaction 0
        NotificationsTable.slice(NotificationsTable.idColumn, NotificationsTable.providerId)
            .select {
              (NotificationsTable.profileId eq resolvedProfileId) and
                  (NotificationsTable.channel eq Channel.EMAIL.name) and
                  (NotificationsTable.aggregation eq Aggregation.IMMEDIATE.name) and
                  (NotificationsTable.status eq NotificationStatus.SENT.name) and
                  (NotificationsTable.sentAt greaterEq since)
            }
            .map { it[NotificationsTable.providerId] ?: "row-${it[NotificationsTable.idColumn].value}" }
            .distinct()
            .size
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

  /** Delivered notifications [memberUsername] hasn't seen yet. */
  suspend fun countUnread(memberUsername: String): Int = suspendTransaction {
    val resolvedProfileId =
        ProfileResolver.findProfileId(memberUsername) ?: return@suspendTransaction 0
    NotificationsTable.select {
          (NotificationsTable.profileId eq resolvedProfileId) and
              (NotificationsTable.status eq NotificationStatus.SENT.name) and
              NotificationsTable.readAt.isNull()
        }
        .count()
        .toInt()
  }

  /** Marks every delivered, unread notification of [memberUsername] read; returns the count. */
  suspend fun markAllRead(memberUsername: String, readAt: LocalDateTime): Int = suspendTransaction {
    val resolvedProfileId =
        ProfileResolver.findProfileId(memberUsername) ?: return@suspendTransaction 0
    NotificationsTable.update({
      (NotificationsTable.profileId eq resolvedProfileId) and
          (NotificationsTable.status eq NotificationStatus.SENT.name) and
          NotificationsTable.readAt.isNull()
    }) {
      it[this.readAt] = readAt
      it[updatedAt] = readAt
    }
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

  /**
   * Records a suppression (bounce or unsubscribe, mirrored from the provider); a member already
   * suppressed for [reason] is left as-is so repeated bounces don't pile up rows.
   */
  suspend fun insertSuppression(memberUsername: String, email: String?, reason: String) {
    suspendTransaction {
      val resolvedProfileId = ProfileResolver.requireProfileId(memberUsername)
      val exists =
          NotificationSuppressionsTable.select {
                (NotificationSuppressionsTable.profileId eq resolvedProfileId) and
                    (NotificationSuppressionsTable.reason eq reason)
              }
              .any()
      if (exists) return@suspendTransaction
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

  /**
   * Resolves a stored topic key back to its [NotificationTopic]. A key whose producer was removed
   * maps to [UnknownTopic] rather than throwing, so one orphaned row can't wedge the worker loop
   * or break a member's notifications page.
   */
  private fun resolveTopic(key: String): NotificationTopic =
      templateRegistry.findTopic(key) ?: UnknownTopic(key)

  private fun resolveProfileIds(usernames: Set<String>): List<Int> =
      usernames.mapNotNull { ProfileResolver.findProfileId(it) }

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

  private companion object {
    const val STALE_SENDING_ERROR = "Recovered after a send was interrupted; retrying"
  }
}
