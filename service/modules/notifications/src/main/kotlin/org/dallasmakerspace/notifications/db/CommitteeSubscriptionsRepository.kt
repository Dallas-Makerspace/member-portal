package org.dallasmakerspace.notifications.db

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.members.db.ProfileResolver
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select

/**
 * Committee follows (`committee_subscriptions`). Rows are keyed by profile id; this repository is
 * the boundary that translates to/from member usernames.
 */
@Singleton
class CommitteeSubscriptionsRepository @Inject constructor(loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  private val utcZone = ZoneId.of("UTC")

  /** Follows [committeeId] as [username]. Idempotent: already-following is a no-op. */
  @Suppress("TooGenericExceptionCaught") // Duplicate-key (already following) is expected & benign.
  suspend fun subscribe(username: String, committeeId: Int) {
    suspendTransaction {
      val id = ProfileResolver.requireProfileId(username)
      try {
        CommitteeSubscriptionsTable.insert {
          it[profileId] = id
          it[CommitteeSubscriptionsTable.committeeId] = committeeId
          it[createdAt] = LocalDateTime.now(utcZone)
        }
      } catch (e: Exception) {
        if (isDuplicateKey(e)) {
          log.info("$username already follows committee $committeeId")
        } else {
          throw e
        }
      }
    }
  }

  /** Unfollows [committeeId] as [username]. Not-currently-following is a no-op. */
  suspend fun unsubscribe(username: String, committeeId: Int) {
    suspendTransaction {
      val id = ProfileResolver.findProfileId(username) ?: return@suspendTransaction
      CommitteeSubscriptionsTable.deleteWhere {
        (profileId eq id) and (CommitteeSubscriptionsTable.committeeId eq committeeId)
      }
    }
  }

  suspend fun isSubscribed(username: String, committeeId: Int): Boolean = suspendTransaction {
    val id = ProfileResolver.findProfileId(username) ?: return@suspendTransaction false
    CommitteeSubscriptionsTable.select {
          (CommitteeSubscriptionsTable.profileId eq id) and
              (CommitteeSubscriptionsTable.committeeId eq committeeId)
        }
        .any()
  }

  /** Usernames following any of [committeeIds], each listed once, sorted. */
  suspend fun getSubscribers(committeeIds: Collection<Int>): List<String> = suspendTransaction {
    if (committeeIds.isEmpty()) return@suspendTransaction emptyList()
    val profileIds =
        CommitteeSubscriptionsTable.select { CommitteeSubscriptionsTable.committeeId inList committeeIds }
            .map { it[CommitteeSubscriptionsTable.profileId] }
            .distinct()
    ProfileResolver.resolveUsernames(profileIds).values.sorted()
  }

  /** SQLSTATE class 23 is an integrity violation, which here can only be the primary key. */
  private fun isDuplicateKey(e: Exception): Boolean =
      (e as? ExposedSQLException)?.sqlState?.startsWith("23") == true ||
          e.message?.contains("Duplicate", ignoreCase = true) == true
}
