package org.dallasmakerspace.notifications.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Exposed table for `committee_messages` - a chair's short broadcast to a committee's followers.
 * One row per send: the audit log, the source for the send limits, and the id in dedupe keys.
 * [committeeId] is `Committee.id` from common-models; [senderProfileId] is an FK to `profile.id`.
 */
@Suppress("MagicNumber")
object CommitteeMessagesTable : IntIdTable("committee_messages") {
  val committeeId = integer("committee_id")
  val senderProfileId = integer("sender_profile_id")
  val message = varchar("message", 120)
  val talkTopicId = integer("talk_topic_id").nullable()
  val talkUrl = varchar("talk_url", 255).nullable()
  val shortUrl = varchar("short_url", 255).nullable()
  val threadCreated = bool("thread_created").default(false)
  val status = varchar("status", 16).default(CommitteeMessageStatus.PENDING)
  val recipientCount = integer("recipient_count").default(0)
  val createdAt = datetime("created_at")
}

/** Values of `committee_messages.status`. Only FAILED rows are ignored by the send limits. */
object CommitteeMessageStatus {
  const val PENDING = "PENDING"
  const val SENT = "SENT"
  const val FAILED = "FAILED"
}
