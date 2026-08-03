package org.dallasmakerspace.members.db

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList

/**
 * Username <-> profile_id resolution shared by repositories that store `profile_id` (FK to
 * `profile.id`) but work with usernames at their public boundary. Every function must be called
 * from within an active transaction (e.g. inside [suspendTransaction]).
 */
object ProfileResolver {

  /** Resolves a username to its profile id, or null if the profile does not exist. */
  fun findProfileId(username: String): Int? =
      ProfileDAO.findById(EntityID(username, ProfileTable))?.idColumn?.value

  /** Resolves a username to its profile id, throwing if the profile does not exist. */
  fun requireProfileId(username: String): Int =
      requireNotNull(findProfileId(username)) { "Unknown member username: $username" }

  /** Resolves a profile id to its username, or null if the profile does not exist. */
  fun findUsername(profileId: Int): String? =
      ProfileDAO.find { ProfileTable.idColumn eq profileId }.firstOrNull()?.id?.value

  /** Batch-resolves profile ids to usernames in one query (avoids N+1 for list results). */
  fun resolveUsernames(profileIds: Collection<Int>): Map<Int, String> =
      if (profileIds.isEmpty()) {
        emptyMap()
      } else {
        ProfileDAO.find { ProfileTable.idColumn inList profileIds.distinct() }
            .associate { it.idColumn.value to it.id.value }
      }
}
