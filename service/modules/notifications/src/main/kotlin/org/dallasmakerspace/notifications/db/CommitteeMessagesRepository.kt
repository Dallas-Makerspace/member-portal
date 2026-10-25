package org.dallasmakerspace.notifications.db

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.members.db.ProfileResolver
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

/** How many messages one committee may send. */
data class SendLimits(val perDay: Int, val perWeek: Int)

/** Outcome of [CommitteeMessagesRepository.reserve]. */
sealed interface Reservation {
  data class Reserved(val id: Int) : Reservation

  /** At a limit; sending is allowed again at [nextAllowedAt] (UTC). */
  data class RateLimited(val nextAllowedAt: LocalDateTime) : Reservation
}

/**
 * Chair messages (`committee_messages`). Also where the send limits are enforced, so they hold
 * across instances and deploys.
 */
@Singleton
class CommitteeMessagesRepository @Inject constructor() {

  /** Send times (UTC) of [committeeId]'s messages in the past week that count toward the limits. */
  suspend fun recentSends(committeeId: Int, now: LocalDateTime = nowUtc()): List<LocalDateTime> =
      suspendTransaction { countedSends(committeeId, now) }

  /**
   * Records a PENDING message for [committeeId] by [senderUsername] unless a limit is reached.
   * The check and the insert share a transaction, so concurrent sends can't both slip under it.
   */
  suspend fun reserve(
      committeeId: Int,
      senderUsername: String,
      message: String,
      limits: SendLimits,
      now: LocalDateTime = nowUtc(),
  ): Reservation = suspendTransaction {
    nextAllowedAt(countedSends(committeeId, now), now, limits)?.let {
      return@suspendTransaction Reservation.RateLimited(it)
    }
    val senderId = ProfileResolver.requireProfileId(senderUsername)
    val id =
        CommitteeMessagesTable.insertAndGetId {
          it[CommitteeMessagesTable.committeeId] = committeeId
          it[senderProfileId] = senderId
          it[CommitteeMessagesTable.message] = message
          it[createdAt] = now
        }
    Reservation.Reserved(id.value)
  }

  /** Records the Talk thread a pending message points to. */
  suspend fun attachThread(id: Int, topicId: Int, talkUrl: String, threadCreated: Boolean) {
    suspendTransaction {
      CommitteeMessagesTable.update({ CommitteeMessagesTable.id eq id }) {
        it[talkTopicId] = topicId
        it[CommitteeMessagesTable.talkUrl] = talkUrl
        it[CommitteeMessagesTable.threadCreated] = threadCreated
      }
    }
  }

  suspend fun markSent(id: Int, shortUrl: String, recipientCount: Int) {
    suspendTransaction {
      CommitteeMessagesTable.update({ CommitteeMessagesTable.id eq id }) {
        it[CommitteeMessagesTable.shortUrl] = shortUrl
        it[CommitteeMessagesTable.recipientCount] = recipientCount
        it[status] = CommitteeMessageStatus.SENT
      }
    }
  }

  /** A failed message no longer counts toward the limits. */
  suspend fun markFailed(id: Int) {
    suspendTransaction {
      CommitteeMessagesTable.update({ CommitteeMessagesTable.id eq id }) {
        it[status] = CommitteeMessageStatus.FAILED
      }
    }
  }

  private fun countedSends(committeeId: Int, now: LocalDateTime): List<LocalDateTime> =
      CommitteeMessagesTable.select {
            (CommitteeMessagesTable.committeeId eq committeeId) and
                (CommitteeMessagesTable.status neq CommitteeMessageStatus.FAILED) and
                (CommitteeMessagesTable.createdAt greaterEq now.minus(WEEK))
          }
          .map { it[CommitteeMessagesTable.createdAt] }

  companion object {
    private val DAY: Duration = Duration.ofDays(1)
    private val WEEK: Duration = Duration.ofDays(7)

    fun nowUtc(): LocalDateTime = LocalDateTime.now(ZoneId.of("UTC"))

    /**
     * When the next message may be sent, given the [sends] still inside the past week; null when
     * one may be sent now. A limit frees up when the send that is [limit] places back ages out.
     */
    fun nextAllowedAt(
        sends: List<LocalDateTime>,
        now: LocalDateTime,
        limits: SendLimits,
    ): LocalDateTime? {
      val frees =
          listOfNotNull(
              freeAt(sends.filter { it >= now.minus(DAY) }, limits.perDay, DAY),
              freeAt(sends.filter { it >= now.minus(WEEK) }, limits.perWeek, WEEK))
      return frees.maxOrNull()
    }

    private fun freeAt(sends: List<LocalDateTime>, limit: Int, window: Duration): LocalDateTime? =
        if (sends.size < limit) null else sends.sorted()[sends.size - limit].plus(window)
  }
}
