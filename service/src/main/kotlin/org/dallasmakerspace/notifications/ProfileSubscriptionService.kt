package org.dallasmakerspace.notifications

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.notifications.db.ProfileSubscriptionsRepository

/** Follow/unfollow another member, and who-follows-who listings (e.g. follow an organizer). */
@Singleton
class ProfileSubscriptionService
@Inject
constructor(private val repository: ProfileSubscriptionsRepository) {

  suspend fun follow(followerUsername: String, followedUsername: String) {
    require(followerUsername != followedUsername) { "Cannot follow yourself" }
    repository.follow(followerUsername, followedUsername)
  }

  suspend fun unfollow(followerUsername: String, followedUsername: String) =
      repository.unfollow(followerUsername, followedUsername)

  suspend fun isFollowing(followerUsername: String, followedUsername: String): Boolean =
      repository.isFollowing(followerUsername, followedUsername)

  /** Usernames [username] follows. */
  suspend fun getFollowing(username: String): List<String> = repository.getFollowing(username)

  /** Usernames that follow [username]. */
  suspend fun getFollowers(username: String): List<String> = repository.getFollowers(username)
}
