package org.dallasmakerspace.agent.db

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/** Tables from `service/docs/sql/agent-chat-migration.sql`; all timestamps are UTC. */
@Suppress("MagicNumber")
object AgentConversationTable : IdTable<String>("agent_conversation") {
  override val id: Column<org.jetbrains.exposed.dao.id.EntityID<String>> =
      char("id", 36).entityId()
  override val primaryKey = PrimaryKey(id)
  val botKey = varchar("bot_key", 32)
  val ownerUsername = varchar("owner_username", 64)
  val title = varchar("title", 200)
  val visibility = varchar("visibility", 8).default("PUBLIC")
  val privateReason = varchar("private_reason", 255).nullable()
  val totalCostUsd = double("total_cost_usd").default(0.0)
  val createdAt = datetime("created_at")
  val lastMessageAt = datetime("last_message_at")
}

@Suppress("MagicNumber")
object AgentMessageTable : LongIdTable("agent_message") {
  val conversationId =
      reference("conversation_id", AgentConversationTable, onDelete = ReferenceOption.CASCADE)
  val turnSeq = integer("turn_seq")
  val role = varchar("role", 16)
  val content = text("content")
  val displayText = text("display_text").nullable()
  val model = varchar("model", 64).nullable()
  val inputTokens = integer("input_tokens").default(0)
  val cacheReadTokens = integer("cache_read_tokens").default(0)
  val cacheWriteTokens = integer("cache_write_tokens").default(0)
  val outputTokens = integer("output_tokens").default(0)
  val costUsd = double("cost_usd").default(0.0)
  val durationMs = integer("duration_ms").nullable()
  val createdAt = datetime("created_at")
}

@Suppress("MagicNumber")
object AgentToolCallTable : LongIdTable("agent_tool_call") {
  val conversationId =
      reference("conversation_id", AgentConversationTable, onDelete = ReferenceOption.CASCADE)
  val messageId = reference("message_id", AgentMessageTable, onDelete = ReferenceOption.CASCADE)
  val toolName = varchar("tool_name", 64)
  val args = text("args")
  val resultPreview = text("result_preview").nullable()
  val sources = text("sources").nullable()
  val touchesPii = bool("touches_pii").default(false)
  val ok = bool("ok").default(true)
  val durationMs = integer("duration_ms").default(0)
  val createdAt = datetime("created_at")
}

@Suppress("MagicNumber")
object AgentFeedbackTable : Table("agent_feedback") {
  val messageId = reference("message_id", AgentMessageTable, onDelete = ReferenceOption.CASCADE)
  val username = varchar("username", 64)
  val helpful = bool("helpful")
  val createdAt = datetime("created_at")
  override val primaryKey = PrimaryKey(messageId, username)
}

object AgentRoles {
  const val USER = "user"
  const val ASSISTANT = "assistant"
  const val TOOL = "tool"
}
