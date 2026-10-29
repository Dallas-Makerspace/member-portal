package org.dallasmakerspace.agent.db

import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.dallasmakerspace.models.agent.AgentVisibility
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/** Chat storage against H2 in MariaDB mode. */
class ConversationRepositoryTest {

  companion object {
    private val now = LocalDateTime.of(2026, 10, 20, 15, 0)

    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      val db =
          Database.connect(
              "jdbc:h2:mem:agent-chat;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
              driver = "org.h2.Driver")
      DBMemberPortalConnection.db = db
      transaction(db) {
        SchemaUtils.create(
            AgentConversationTable, AgentMessageTable, AgentToolCallTable, AgentFeedbackTable, AgentUsageTable)
      }
    }
  }

  private val repository = ConversationRepository()

  @Before
  fun setUp() {
    transaction(DBMemberPortalConnection.db) {
      AgentFeedbackTable.deleteAll()
      AgentUsageTable.deleteAll()
      AgentToolCallTable.deleteAll()
      AgentMessageTable.deleteAll()
      AgentConversationTable.deleteAll()
    }
  }

  private fun create(id: String, owner: String = "jdoe", at: LocalDateTime = now) = runBlocking {
    repository.create(id, "ask-ai", owner, "How do I use the laser?", AgentVisibility.PUBLIC, null, at)
  }

  private fun turn(id: String, at: LocalDateTime = now, cost: Double = 0.01) = runBlocking {
    repository.appendTurn(
        id,
        listOf(
            NewMessage(1, AgentRoles.USER, "{}", "How do I use the laser?"),
            NewMessage(1, AgentRoles.ASSISTANT, "{}", null, "model", TokenCounts(10, 0, 0, 5), cost),
            NewMessage(1, AgentRoles.TOOL, "{}", null),
            NewMessage(1, AgentRoles.ASSISTANT, "{}", "Take the class.", "model", costUsd = cost),
        ),
        listOf(NewToolCall(1, "search_wiki", "{\"query\":\"laser\"}", "result", null, false, true, 12)),
        at)
  }

  @Test
  fun `a turn saves its messages, tool calls and cost`() {
    create("c1")
    val ids = turn("c1")

    assertEquals(4, ids.size)
    val messages = runBlocking { repository.messages("c1") }
    assertEquals(listOf("user", "assistant", "tool", "assistant"), messages.map { it.role })
    val calls = runBlocking { repository.toolCalls("c1") }
    assertEquals(ids[1], calls.single().messageId)
    assertEquals(0.02, runBlocking { repository.get("c1") }!!.totalCostUsd, 1e-9)
  }

  @Test
  fun `public listing leaves out private chats`() {
    create("c1")
    create("c2", owner = "asmith")
    runBlocking { repository.makePrivate("c2", "Used a member lookup") }

    val public = runBlocking { repository.listPublic("ask-ai", 10) }
    assertEquals(listOf("c1"), public.map { it.id })
    val mine = runBlocking { repository.listByOwner("ask-ai", "asmith", 10) }
    assertEquals(AgentVisibility.PRIVATE, mine.single().visibility)
    assertEquals("Used a member lookup", mine.single().privateReason)
  }

  @Test
  fun `feedback is one row per member per message`() {
    create("c1")
    val answer = turn("c1")[3]
    runBlocking {
      repository.setFeedback(answer, "jdoe", true)
      repository.setFeedback(answer, "jdoe", false)
    }

    assertEquals(mapOf(answer to false), runBlocking { repository.feedback(listOf(answer), "jdoe") })
  }

  @Test
  fun `spend and question counts only include the window`() {
    create("c1")
    turn("c1", at = now.minusDays(40), cost = 1.0)
    turn("c1", at = now, cost = 0.5)

    assertEquals(1.0, runBlocking { repository.spentSince(now.minusDays(1)) }, 1e-9)
    assertEquals(1L, runBlocking { repository.userTurnsSince("jdoe", now.minusDays(1)) })
    assertEquals(0L, runBlocking { repository.userTurnsSince("asmith", now.minusDays(1)) })
  }

  @Test
  fun `usage outlives its chat and is purged by age`() {
    create("c1")
    turn("c1", at = now.minusMonths(14), cost = 1.0)
    turn("c1", at = now, cost = 0.5)
    runBlocking { repository.delete("c1") }

    assertEquals(1.0, runBlocking { repository.spentSince(now.minusDays(1)) }, 1e-9)
    assertEquals(1L, runBlocking { repository.userTurnsSince("jdoe", now.minusDays(1)) })
    assertEquals(1, runBlocking { repository.deleteUsageBefore(now.minusMonths(13)) })
    assertEquals(1.0, runBlocking { repository.spentSince(now.minusYears(2)) }, 1e-9)
  }

  @Test
  fun `retention deletes old chats with everything in them`() {
    create("old", at = now.minusYears(3))
    turn("old", at = now.minusYears(3))
    create("new")

    assertEquals(1, runBlocking { repository.deleteInactiveSince(now.minusYears(2)) })
    assertNull(runBlocking { repository.get("old") })
    assertEquals(
        0, transaction(DBMemberPortalConnection.db) { AgentMessageTable.selectAll().count() }.toInt())
  }
}
