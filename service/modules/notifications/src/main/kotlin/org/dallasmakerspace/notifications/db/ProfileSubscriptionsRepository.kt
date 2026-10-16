package org.dallasmakerspace.notifications.db

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.members.db.ProfileResolver
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select

/**
 * Follow relationships (`profile_subscriptions`): [ProfileSubscriptionsTable.profileId] follows
 * [ProfileSubscriptionsTable.followedProfileId]. Rows are stored keyed by profile id; this
 * repository is the boundary that translates to/from member usernames.
 */
@Singleton
class ProfileSubscriptionsRepository @Inject constructor(loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  private val utcZone = ZoneId.of("UTC")

  /** Follows [followedUsername] as [followerUsername]. Idempotent: already-following is a no-op. */
  @Suppress("TooGenericExceptionCaught") // Duplicate-key (already following) is expected & benign.
  suspend fun follow(followerUsername: String, followedUsername: String) {
    suspendTransaction {
      val followerId = ProfileResolver.requireProfileId(followerUsername)
      val followedId = ProfileResolver.requireProfileId(followedUsername)
      try {
        ProfileSubscriptionsTable.insert {
          it[profileId] = followerId
          it[followedProfileId] = followedId
          it[createdAt] = LocalDateTime.now(utcZone)
        }
      } catch (e: Exception) {
        if (isDuplicateKey(e)) {
          log.info("$followerUsername already follows $followedUsername")
        } else {
          throw e
        }
      }
    }
  }

  /** Unfollows [followedUsername] as [followerUsername]. Not-currently-following is a no-op. */
  suspend fun unfollow(followerUsername: String, followedUsername: String) {
    suspendTransaction {
      val followerId = ProfileResolver.findProfileId(followerUsername) ?: return@suspendTransaction
      val followedId = ProfileResolver.findProfileId(followedUsername) ?: return@suspendTransaction
      ProfileSubscriptionsTable.deleteWhere {
        (ProfileSubscriptionsTable.profileId eq followerId) and
            (ProfileSubscriptionsTable.followedProfileId eq followedId)
      }
    }
  }

  suspend fun isFollowing(followerUsername: String, followedUsername: String): Boolean =
      suspendTransaction {
        val followerId =
            ProfileResolver.findProfileId(followerUsername) ?: return@suspendTransaction false
        val followedId =
            ProfileResolver.findProfileId(followedUsername) ?: return@suspendTransaction false
        ProfileSubscriptionsTable.select {
              (ProfileSubscriptionsTable.profileId eq followerId) and
                  (ProfileSubscriptionsTable.followedProfileId eq followedId)
            }
            .any()
      }

  /** Usernames [username] follows. */
  suspend fun getFollowing(username: String): List<String> = suspendTransaction {
    val id = ProfileResolver.findProfileId(username) ?: return@suspendTransaction emptyList()
    val followedIds =
        ProfileSubscriptionsTable.select { ProfileSubscriptionsTable.profileId eq id }
            .map { it[ProfileSubscriptionsTable.followedProfileId] }
    ProfileResolver.resolveUsernames(followedIds).values.sorted()
  }

  /** Usernames that follow [username]. */
  suspend fun getFollowers(username: String): List<String> = suspendTransaction {
    val id = ProfileResolver.findProfileId(username) ?: return@suspendTransaction emptyList()
    val followerIds =
        ProfileSubscriptionsTable.select { ProfileSubscriptionsTable.followedProfileId eq id }
            .map { it[ProfileSubscriptionsTable.profileId] }
    ProfileResolver.resolveUsernames(followerIds).values.sorted()
  }

  /**
   * Enabled members followed by any of [followerUsernames], most-followed-by-that-group first (ties
   * by username). The followers themselves are not excluded - callers filter as needed.
   */
  suspend fun getMostFollowedBy(followerUsernames: Collection<String>, limit: Int): List<String> =
      suspendTransaction {
        val followerIds = ProfileResolver.findProfileIds(followerUsernames).values
        if (followerIds.isEmpty()) return@suspendTransaction emptyList()
        val counts =
            ProfileSubscriptionsTable.select {
                  ProfileSubscriptionsTable.profileId inList followerIds
                }
                .groupingBy { it[ProfileSubscriptionsTable.followedProfileId] }
                .eachCount()
        val usernames = ProfileResolver.resolveEnabledUsernames(counts.keys)
        counts.entries
            .mapNotNull { (id, count) -> usernames[id]?.let { it to count } }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(limit)
            .map { it.first }
      }

  private fun isDuplicateKey(e: Exception): Boolean =
      e.message?.contains("Duplicate") == true || e.message?.contains("unique") == true
}
