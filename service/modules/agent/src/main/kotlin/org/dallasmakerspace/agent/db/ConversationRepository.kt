package org.dallasmakerspace.agent.db

import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.db.suspendTransaction
import org.dallasmakerspace.models.agent.AgentVisibility
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update

data class ConversationRow(
    val id: String,
    val botKey: String,
    val ownerUsername: String,
    val title: String,
    val visibility: AgentVisibility,
    val privateReason: String?,
    val totalCostUsd: Double,
    val createdAt: LocalDateTime,
    val lastMessageAt: LocalDateTime,
)

data class MessageRow(
    val id: Long,
    val turnSeq: Int,
    val role: String,
    val content: String,
    val displayText: String?,
    val createdAt: LocalDateTime,
)

data class NewMessage(
    val turnSeq: Int,
    val role: String,
    val content: String,
    val displayText: String?,
    val model: String? = null,
    val usage: TokenCounts = TokenCounts(),
    val costUsd: Double = 0.0,
)

data class TokenCounts(
    val input: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    val output: Int = 0,
)

data class NewToolCall(
    val messageIndex: Int,
    val toolName: String,
    val args: String,
    val resultPreview: String?,
    val sourcesJson: String?,
    val touchesPii: Boolean,
    val ok: Boolean,
    val durationMs: Int,
)

data class ToolCallRow(
    val messageId: Long,
    val toolName: String,
    val args: String,
    val sourcesJson: String?,
    val ok: Boolean,
)

/** Storage for AI assistant chats. */
@Singleton
class ConversationRepository @Inject constructor() {

  suspend fun create(
      id: String,
      botKey: String,
      ownerUsername: String,
      title: String,
      visibility: AgentVisibility,
      privateReason: String?,
      now: LocalDateTime = nowUtc(),
  ) {
    suspendTransaction {
      AgentConversationTable.insert {
        it[AgentConversationTable.id] = id
        it[AgentConversationTable.botKey] = botKey
        it[AgentConversationTable.ownerUsername] = ownerUsername
        it[AgentConversationTable.title] = title.take(TITLE_LENGTH)
        it[AgentConversationTable.visibility] = visibility.name
        it[AgentConversationTable.privateReason] = privateReason
        it[createdAt] = now
        it[lastMessageAt] = now
      }
    }
  }

  suspend fun get(id: String): ConversationRow? = suspendTransaction {
    AgentConversationTable.select { AgentConversationTable.id eq id }
        .singleOrNull()
        ?.toConversation()
  }

  suspend fun listByOwner(botKey: String, owner: String, limit: Int): List<ConversationRow> =
      list(
          (AgentConversationTable.botKey eq botKey) and
              (AgentConversationTable.ownerUsername eq owner),
          limit)

  suspend fun listPublic(botKey: String, limit: Int): List<ConversationRow> =
      list(
          (AgentConversationTable.botKey eq botKey) and
              (AgentConversationTable.visibility eq AgentVisibility.PUBLIC.name),
          limit)

  suspend fun listRecent(botKey: String?, limit: Int): List<ConversationRow> =
      list(botKey?.let { AgentConversationTable.botKey eq it } ?: Op.TRUE, limit)

  private suspend fun list(where: Op<Boolean>, limit: Int): List<ConversationRow> =
      suspendTransaction {
        AgentConversationTable.select { where }
            .orderBy(AgentConversationTable.lastMessageAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toConversation() }
      }

  suspend fun messages(conversationId: String): List<MessageRow> = suspendTransaction {
    AgentMessageTable.select { AgentMessageTable.conversationId eq conversationId }
        .orderBy(AgentMessageTable.id, SortOrder.ASC)
        .map {
          MessageRow(
              id = it[AgentMessageTable.id].value,
              turnSeq = it[AgentMessageTable.turnSeq],
              role = it[AgentMessageTable.role],
              content = it[AgentMessageTable.content],
              displayText = it[AgentMessageTable.displayText],
              createdAt = it[AgentMessageTable.createdAt],
          )
        }
  }

  suspend fun toolCalls(conversationId: String): List<ToolCallRow> = suspendTransaction {
    AgentToolCallTable.select { AgentToolCallTable.conversationId eq conversationId }
        .orderBy(AgentToolCallTable.id, SortOrder.ASC)
        .map {
          ToolCallRow(
              messageId = it[AgentToolCallTable.messageId].value,
              toolName = it[AgentToolCallTable.toolName],
              args = it[AgentToolCallTable.args],
              sourcesJson = it[AgentToolCallTable.sources],
              ok = it[AgentToolCallTable.ok],
          )
        }
  }

  /**
   * Saves one turn's messages and tool calls in a single transaction; tool calls point at
   * messages by index into [messages]. Returns the new message ids in order.
   */
  suspend fun appendTurn(
      conversationId: String,
      messages: List<NewMessage>,
      toolCalls: List<NewToolCall>,
      now: LocalDateTime = nowUtc(),
  ): List<Long> = suspendTransaction {
    val ids =
        messages.map { message ->
          AgentMessageTable.insertAndGetId {
                it[AgentMessageTable.conversationId] = conversationId
                it[turnSeq] = message.turnSeq
                it[role] = message.role
                it[content] = message.content
                it[displayText] = message.displayText
                it[model] = message.model
                it[inputTokens] = message.usage.input
                it[cacheReadTokens] = message.usage.cacheRead
                it[cacheWriteTokens] = message.usage.cacheWrite
                it[outputTokens] = message.usage.output
                it[costUsd] = message.costUsd
                it[createdAt] = now
              }
              .value
        }
    AgentToolCallTable.batchInsert(toolCalls, shouldReturnGeneratedValues = false) { call ->
      this[AgentToolCallTable.conversationId] = conversationId
      this[AgentToolCallTable.messageId] = ids[call.messageIndex]
      this[AgentToolCallTable.toolName] = call.toolName
      this[AgentToolCallTable.args] = call.args
      this[AgentToolCallTable.resultPreview] = call.resultPreview
      this[AgentToolCallTable.sources] = call.sourcesJson
      this[AgentToolCallTable.touchesPii] = call.touchesPii
      this[AgentToolCallTable.ok] = call.ok
      this[AgentToolCallTable.durationMs] = call.durationMs
      this[AgentToolCallTable.createdAt] = now
    }
    val turnCost = messages.sumOf { it.costUsd }
    AgentConversationTable.update({ AgentConversationTable.id eq conversationId }) {
      it[lastMessageAt] = now
      it[totalCostUsd] = AgentConversationTable.totalCostUsd + turnCost
    }
    ids
  }

  /** One-way: a private conversation never becomes public again. */
  suspend fun makePrivate(conversationId: String, reason: String) {
    suspendTransaction {
      AgentConversationTable.update({
        (AgentConversationTable.id eq conversationId) and
            (AgentConversationTable.visibility eq AgentVisibility.PUBLIC.name)
      }) {
        it[visibility] = AgentVisibility.PRIVATE.name
        it[privateReason] = reason
      }
    }
  }

  suspend fun delete(conversationId: String) {
    suspendTransaction {
      AgentConversationTable.deleteWhere { AgentConversationTable.id eq conversationId }
    }
  }

  suspend fun setFeedback(messageId: Long, username: String, helpful: Boolean) {
    suspendTransaction {
      AgentFeedbackTable.deleteWhere {
        (AgentFeedbackTable.messageId eq messageId) and (AgentFeedbackTable.username eq username)
      }
      AgentFeedbackTable.insert {
        it[AgentFeedbackTable.messageId] = messageId
        it[AgentFeedbackTable.username] = username
        it[AgentFeedbackTable.helpful] = helpful
        it[createdAt] = nowUtc()
      }
    }
  }

  suspend fun feedback(messageIds: List<Long>, username: String): Map<Long, Boolean> =
      if (messageIds.isEmpty()) emptyMap()
      else
          suspendTransaction {
            AgentFeedbackTable.select {
                  (AgentFeedbackTable.messageId inList messageIds) and
                      (AgentFeedbackTable.username eq username)
                }
                .associate { it[AgentFeedbackTable.messageId].value to it[AgentFeedbackTable.helpful] }
          }

  suspend fun spentSince(since: LocalDateTime): Double = suspendTransaction {
    val total = AgentMessageTable.costUsd.sum()
    AgentMessageTable.slice(total)
        .select { AgentMessageTable.createdAt greaterEq since }
        .singleOrNull()
        ?.get(total) ?: 0.0
  }

  /** Questions [username] asked since [since], across all assistants. */
  suspend fun userTurnsSince(username: String, since: LocalDateTime): Long = suspendTransaction {
    AgentMessageTable.innerJoin(AgentConversationTable)
        .select {
          (AgentConversationTable.ownerUsername eq username) and
              (AgentMessageTable.role eq AgentRoles.USER) and
              (AgentMessageTable.createdAt greaterEq since)
        }
        .count()
  }

  /** Deletes conversations whose last message is older than [cutoff]; returns how many. */
  suspend fun deleteInactiveSince(cutoff: LocalDateTime): Int = suspendTransaction {
    AgentConversationTable.deleteWhere { AgentConversationTable.lastMessageAt less cutoff }
  }

  private fun ResultRow.toConversation() =
      ConversationRow(
          id = this[AgentConversationTable.id].value,
          botKey = this[AgentConversationTable.botKey],
          ownerUsername = this[AgentConversationTable.ownerUsername],
          title = this[AgentConversationTable.title],
          visibility = AgentVisibility.valueOf(this[AgentConversationTable.visibility]),
          privateReason = this[AgentConversationTable.privateReason],
          totalCostUsd = this[AgentConversationTable.totalCostUsd],
          createdAt = this[AgentConversationTable.createdAt],
          lastMessageAt = this[AgentConversationTable.lastMessageAt],
      )

  companion object {
    const val TITLE_LENGTH = 200

    fun nowUtc(): LocalDateTime = LocalDateTime.now(ZoneId.of("UTC"))
  }
}
