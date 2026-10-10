package org.dallasmakerspace.askai.db

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/** Exposed table for the MariaDB table - ask_ai_cache. */
object AskAiCacheTable : IdTable<Int>("ask_ai_cache") {
  val idColumn = integer("id").autoIncrement().entityId()
  val slug = varchar("slug", 100).uniqueIndex()
  val questionHash = varchar("question_hash", 64).index()
  val questionText = text("question_text")
  val answerText = text("answer_text")
  val sourceLinks = text("source_links") // JSON array of SourceLink objects
  val askedByProfileId =
      integer("asked_by_profile_id").index() // FK to profile.id (enforced at DB level)
  val createdAt = timestamp("created_at").default(Instant.now())
  val hitCount = integer("hit_count").default(0)
  val lastHitAt = timestamp("last_hit_at").nullable()
  val metadata = text("metadata").nullable() // JSON serialized AskAiMetadata
  val totalCostUsd = decimal("total_cost_usd", 10, 6).nullable()
  val embedding = text("embedding").nullable()

  override val id: Column<EntityID<Int>>
    get() = idColumn
}

class AskAiCacheDAO(id: EntityID<Int>) : Entity<Int>(id) {
  companion object : EntityClass<Int, AskAiCacheDAO>(AskAiCacheTable)

  var slug by AskAiCacheTable.slug
  var questionHash by AskAiCacheTable.questionHash
  var questionText by AskAiCacheTable.questionText
  var answerText by AskAiCacheTable.answerText
  var sourceLinks by AskAiCacheTable.sourceLinks
  var askedByProfileId by AskAiCacheTable.askedByProfileId
  var createdAt by AskAiCacheTable.createdAt
  var hitCount by AskAiCacheTable.hitCount
  var lastHitAt by AskAiCacheTable.lastHitAt
  var metadata by AskAiCacheTable.metadata
  var totalCostUsd by AskAiCacheTable.totalCostUsd
  var embedding by AskAiCacheTable.embedding
}

/** Exposed table for the MariaDB table - ask_ai_feedback. */
object AskAiFeedbackTable : IdTable<Int>("ask_ai_feedback") {
  val idColumn = integer("id").autoIncrement().entityId()
  val cacheId = reference("cache_id", AskAiCacheTable)
  val memberId = integer("member_id")
  val isHelpful = bool("is_helpful")
  val createdAt = timestamp("created_at").default(Instant.now())

  override val id: Column<EntityID<Int>>
    get() = idColumn
}

class AskAiFeedbackDAO(id: EntityID<Int>) : Entity<Int>(id) {
  companion object : EntityClass<Int, AskAiFeedbackDAO>(AskAiFeedbackTable)

  var cacheId by AskAiFeedbackTable.cacheId
  var memberId by AskAiFeedbackTable.memberId
  var isHelpful by AskAiFeedbackTable.isHelpful
  var createdAt by AskAiFeedbackTable.createdAt

  var cache by AskAiCacheDAO referencedOn AskAiFeedbackTable.cacheId
}

/** Suspend transaction helper for all Ask AI database operations. */
suspend fun <T> suspendTransaction(block: Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO, statement = block, db = DBMemberPortalConnection.db)
