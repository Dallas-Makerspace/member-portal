package org.dallasmakerspace.bambuddy.notifications.db

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.members.db.ProfileResolver
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll

/**
 * Bambu committee-alert subscriptions (`bambu_committee_subscriptions`). A row's presence *is*
 * the subscription — a preference row cannot serve here because notification preferences default
 * to enabled when no row exists (pattern: CommitteeSubscriptionsRepository).
 */
@Singleton
open class BambuCommitteeSubscriptionsRepository @Inject constructor(loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  private val utcZone = ZoneId.of("UTC")

  /** Subscribes [username]. Idempotent: already-subscribed is a no-op. */
  @Suppress("TooGenericExceptionCaught")
  open suspend fun subscribe(username: String) {
    suspendTransaction {
      val id = ProfileResolver.requireProfileId(username)
      try {
        BambuCommitteeSubscriptionsTable.insert {
          it[profileId] = id
          it[createdAt] = LocalDateTime.now(utcZone)
        }
      } catch (e: Exception) {
        if (isDuplicateKey(e)) {
          log.info("$username already subscribed to Bambu committee alerts")
        } else {
          throw e
        }
      }
    }
  }

  /** Unsubscribes [username]. Not-subscribed is a no-op. */
  open suspend fun unsubscribe(username: String) {
    suspendTransaction {
      val id = ProfileResolver.findProfileId(username) ?: return@suspendTransaction
      BambuCommitteeSubscriptionsTable.deleteWhere { profileId eq id }
    }
  }

  open suspend fun isSubscribed(username: String): Boolean = suspendTransaction {
    val id = ProfileResolver.findProfileId(username) ?: return@suspendTransaction false
    BambuCommitteeSubscriptionsTable.select { BambuCommitteeSubscriptionsTable.profileId eq id }
        .any()
  }

  /** Usernames of all subscribers, sorted. */
  open suspend fun getSubscribers(): List<String> = suspendTransaction {
    val profileIds =
        BambuCommitteeSubscriptionsTable.selectAll().map {
          it[BambuCommitteeSubscriptionsTable.profileId]
        }
    ProfileResolver.resolveUsernames(profileIds).values.sorted()
  }

  private fun isDuplicateKey(e: Exception): Boolean =
      (e as? ExposedSQLException)?.sqlState?.startsWith("23") == true ||
          e.message?.contains("Duplicate", ignoreCase = true) == true
}
