package org.dallasmakerspace.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.agent.db.AgentConversationTable
import org.dallasmakerspace.agent.db.AgentFeedbackTable
import org.dallasmakerspace.agent.db.AgentMessageTable
import org.dallasmakerspace.agent.db.AgentToolCallTable
import org.dallasmakerspace.agent.db.ConversationRepository
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.ConfigValueType
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.agent.AgentSourceLink
import org.dallasmakerspace.models.agent.AgentVisibility
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.slf4j.Logger

class ChatServiceTest {
  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      val db =
          Database.connect(
              "jdbc:h2:mem:agent-chat-service;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
              driver = "org.h2.Driver")
      DBMemberPortalConnection.db = db
      transaction(db) {
        SchemaUtils.create(
            AgentConversationTable, AgentMessageTable, AgentToolCallTable, AgentFeedbackTable)
      }
    }
  }

  private class Tool(override val touchesPersonalInfo: Boolean) : AgentTool {
    override val descriptor =
        ToolDescriptor(
            if (touchesPersonalInfo) "lookup_member" else "search_wiki",
            "Search",
            listOf(ToolParameterDescriptor("query", "Search text", ToolParameterType.String)))

    override suspend fun run(args: JsonObject, context: ToolContext) =
        ToolResult("Found it.", listOf(AgentSourceLink("Page", "https://wiki.example.org/p", "Wiki")))
  }

  private val flag =
      ConfigKey("test.enabled", "test", "Test", FeatureFlagValue(), ConfigValueType.FeatureFlagType)
  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }
  private val repository = ConversationRepository()

  private fun service(dailyCap: Int = 30, slowModel: Boolean = false): ChatService {
    val config =
        mock<ConfigOverrideService> {
          onBlocking { get(ConfigRegistry.AGENT_MODEL_DEFAULT, false) } doReturn "model"
          onBlocking { get(ConfigRegistry.AGENT_MONTHLY_SPEND_CAP_USD, false) } doReturn 20
          onBlocking { get(ConfigRegistry.AGENT_USER_DAILY_TURN_CAP, false) } doReturn dailyCap
        }
    val access =
        mock<AgentAccess> {
          onBlocking { groupsOf(any()) } doReturn emptyList()
          onBlocking { canUse(any(), any(), any()) } doReturn true
        }
    val bots =
        setOf(
            BotDefinition("ask-ai", "Ask AI", "Help.", listOf(Tool(false)), flag),
            BotDefinition("people", "People", "Help.", listOf(Tool(true)), flag))
    return ChatService(
        bots,
        repository,
        access,
        AgentSpendCap(repository, config, loggerFactory),
        MockChatModelClient(if (slowModel) 200 else 0),
        config,
        PiiMasker(loggerFactory),
        TurnStreams(),
        loggerFactory)
  }

  @Before
  fun setUp() {
    transaction(DBMemberPortalConnection.db) {
      AgentFeedbackTable.deleteAll()
      AgentToolCallTable.deleteAll()
      AgentMessageTable.deleteAll()
      AgentConversationTable.deleteAll()
    }
  }

  private suspend fun ChatService.awaitTurn(id: String) {
    withTimeout(10_000) {
      activeTurn(id)!!.events.first { it.type == "turn-end" }
    }
  }

  @Test
  fun `a question is answered, saved and shown as one question and one answer`() = runBlocking<Unit> {
    val service = service()
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "When is the laser class?"))
    service.awaitTurn(started.conversationId)

    val detail = assertNotNull(service.detail(started.conversationId, "jdoe"))
    assertEquals(listOf("user", "assistant"), detail.messages.map { it.role })
    assertEquals("search_wiki", detail.messages[1].toolCalls.single().name)
    assertEquals("https://wiki.example.org/p", detail.messages[1].sources.single().url)
    assertEquals(AgentVisibility.PUBLIC, detail.conversation.visibility)

    val followUp = assertIs<TurnOutcome.Started>(service.sendMessage(started.conversationId, "jdoe", "And on weekends?"))
    assertEquals(2, followUp.turnSeq)
    service.awaitTurn(started.conversationId)
    assertEquals(4, service.detail(started.conversationId, "jdoe")!!.messages.size)
  }

  @Test
  fun `a chat that used member data becomes private to its owner`() = runBlocking<Unit> {
    val service = service()
    val started = assertIs<TurnOutcome.Started>(service.startConversation("people", "jdoe", "Who teaches welding?"))
    service.awaitTurn(started.conversationId)

    assertEquals(AgentVisibility.PRIVATE, service.detail(started.conversationId, "jdoe")!!.conversation.visibility)
    assertNull(service.detail(started.conversationId, "asmith"))
    assertTrue(service.list("people", "asmith", mine = false, limit = 10)!!.isEmpty())
  }

  @Test
  fun `a question with personal details starts private`() = runBlocking<Unit> {
    val service = service()
    val started =
        assertIs<TurnOutcome.Started>(
            service.startConversation("ask-ai", "jdoe", "My email is jdoe@example.com, can you help?"))
    service.awaitTurn(started.conversationId)

    val detail = service.detail(started.conversationId, "jdoe")!!
    assertEquals(ChatService.PII_REASON, detail.privateReason)
    assertFalse(detail.conversation.title.contains("jdoe@example.com"))
  }

  @Test
  fun `only the owner can continue a chat`() = runBlocking<Unit> {
    val service = service()
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "Hello"))
    service.awaitTurn(started.conversationId)

    assertEquals(TurnOutcome.Forbidden, service.sendMessage(started.conversationId, "asmith", "Hi"))
    assertNotNull(service.detail(started.conversationId, "asmith"))
  }

  @Test
  fun `the daily question limit refuses further questions`() = runBlocking<Unit> {
    val service = service(dailyCap = 1)
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "Hello"))
    service.awaitTurn(started.conversationId)

    assertEquals(
        TurnOutcome.Refused(AgentSpendCap.DAILY_CAP_MESSAGE),
        service.sendMessage(started.conversationId, "jdoe", "Again"))
  }

  @Test
  fun `stopping a turn keeps the partial answer`() = runBlocking<Unit> {
    val service = service(slowModel = true)
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "Tell me everything"))
    val turn = service.activeTurn(started.conversationId)!!
    withTimeout(10_000) { turn.events.first { it.type == "delta" } }
    delay(50)

    assertTrue(service.cancel(started.conversationId, "jdoe"))
    service.awaitTurn(started.conversationId)

    val answer = service.detail(started.conversationId, "jdoe")!!.messages.last()
    assertEquals("assistant", answer.role)
    assertTrue(answer.text.endsWith(ChatService.STOPPED_SUFFIX), answer.text)
    assertEquals(TurnOutcome.Started(started.conversationId, 2), service.sendMessage(started.conversationId, "jdoe", "Shorter please"))
    service.awaitTurn(started.conversationId)
  }
}
