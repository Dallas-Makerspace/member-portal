package org.dallasmakerspace.notifications.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Exposed table for `profile_subscriptions` - member-to-member follow relationships (e.g. follow an
 * event organizer to be notified when they post a new event). Both columns are FKs to `profile.id`
 * (enforced at DB level).
 */
@Suppress("MagicNumber")
object ProfileSubscriptionsTable : Table("profile_subscriptions") {
  val profileId = integer("profile_id") // the follower; FK to profile.id
  val followedProfileId = integer("followed_profile_id").index() // the followee; FK to profile.id
  val createdAt = datetime("created_at")

  override val primaryKey = PrimaryKey(profileId, followedProfileId)
}
