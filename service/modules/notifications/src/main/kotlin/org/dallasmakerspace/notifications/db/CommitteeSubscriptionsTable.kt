package org.dallasmakerspace.notifications.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Exposed table for `committee_subscriptions` - members following a committee (e.g. to be notified
 * when it adds a class). [profileId] is an FK to `profile.id`; [committeeId] is `Committee.id` from
 * common-models, which is stable (the slug is derived from the name, so it is not used).
 */
object CommitteeSubscriptionsTable : Table("committee_subscriptions") {
  val profileId = integer("profile_id") // the follower; FK to profile.id
  val committeeId = integer("committee_id").index()
  val createdAt = datetime("created_at")

  override val primaryKey = PrimaryKey(profileId, committeeId)
}
