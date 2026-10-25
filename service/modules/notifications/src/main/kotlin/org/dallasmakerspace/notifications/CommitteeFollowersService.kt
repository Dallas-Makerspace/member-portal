package org.dallasmakerspace.notifications

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.notifications.db.CommitteeSubscriptionsRepository

/** One page of a committee's followers (newest first) plus the total across all pages. */
data class CommitteeFollowersPage(val total: Int, val usernames: List<String>)

@Singleton
class CommitteeFollowersService
@Inject
constructor(private val repository: CommitteeSubscriptionsRepository) {

  suspend fun getPage(committeeId: Int, limit: Int, offset: Int): CommitteeFollowersPage {
    val all = repository.getFollowers(committeeId)
    val start = offset.coerceAtLeast(0).coerceAtMost(all.size)
    val end = (start + limit.coerceAtLeast(0)).coerceAtMost(all.size)
    return CommitteeFollowersPage(all.size, all.subList(start, end))
  }
}
