package org.dallasmakerspace.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.agent.db.AgentArtifactTable
import org.dallasmakerspace.agent.db.AgentArtifactVersionTable
import org.dallasmakerspace.agent.db.AgentConversationTable
import org.dallasmakerspace.agent.db.AgentFeedbackTable
import org.dallasmakerspace.agent.db.AgentMessageTable
import org.dallasmakerspace.agent.db.AgentToolCallTable
import org.dallasmakerspace.agent.db.AgentUsageTable
import org.dallasmakerspace.agent.db.ArtifactRepository
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
            AgentConversationTable, AgentMessageTable, AgentToolCallTable, AgentFeedbackTable, AgentUsageTable,
            AgentArtifactTable, AgentArtifactVersionTable)
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

  private fun titleModel(reply: String, inputTokens: Int = 0) = ChatModelClient { _, _, _ ->
    flowOf(
        StreamFrame.TextDelta(reply),
        StreamFrame.End(
            "end_turn", ResponseMetaInfo.create(KoogClock.System, inputTokensCount = inputTokens)))
  }

  private fun service(
      dailyCap: Int = 30,
      slowModel: Boolean = false,
      titles: ChatModelClient = titleModel("Short title"),
  ): ChatService {
    val config =
        mock<ConfigOverrideService> {
          onBlocking { get(ConfigRegistry.AGENT_MODEL_DEFAULT, false) } doReturn "model"
          onBlocking { get(ConfigRegistry.AGENT_MONTHLY_SPEND_CAP_USD, false) } doReturn 20
          onBlocking { get(ConfigRegistry.AGENT_USER_DAILY_TURN_CAP, false) } doReturn dailyCap
        }
    val access =
        mock<AgentAccess> {
          onBlocking { groupsOf(any()) } doReturn emptyList()
          onBlocking { canUse(any(), any()) } doReturn true
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
        loggerFactory,
        ArtifactRepository(),
        ChatTitler(titles, PiiMasker(loggerFactory), loggerFactory))
  }

  @Before
  fun setUp() {
    transaction(DBMemberPortalConnection.db) {
      AgentArtifactVersionTable.deleteAll()
      AgentArtifactTable.deleteAll()
      AgentFeedbackTable.deleteAll()
      AgentUsageTable.deleteAll()
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
  fun `an answer carries its model, tokens, cost and time, and each tool call its time`() = runBlocking<Unit> {
    val service = service()
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "When is the laser class?"))
    service.awaitTurn(started.conversationId)

    val answer = assertNotNull(service.detail(started.conversationId, "jdoe")).messages[1]
    val meta = assertNotNull(answer.meta)
    assertEquals("model", meta.model)
    assertTrue(meta.inputTokens > 0 && meta.outputTokens > 0)
    assertNotNull(meta.durationMs)
    assertTrue(answer.toolCalls.single().durationMs >= 0)
  }

  @Test
  fun `a chat that used member data becomes private to its owner`() = runBlocking<Unit> {
    val service = service()
    val started = assertIs<TurnOutcome.Started>(service.startConversation("people", "jdoe", "Who teaches welding?"))
    service.awaitTurn(started.conversationId)

    assertEquals(AgentVisibility.PRIVATE, service.detail(started.conversationId, "jdoe")!!.conversation.visibility)
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
  fun `deleting a chat does not give back its question or its spend`() = runBlocking<Unit> {
    val service = service(dailyCap = 1)
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "Hello"))
    service.awaitTurn(started.conversationId)
    assertTrue(service.delete(started.conversationId, "jdoe"))

    assertEquals(TurnOutcome.Refused(AgentSpendCap.DAILY_CAP_MESSAGE), service.startConversation("ask-ai", "jdoe", "Again"))
    assertTrue(repository.spentSince(ConversationRepository.nowUtc().minusDays(1)) > 0.0)
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
    assertTrue(service.cancel(started.conversationId, "jdoe"))
    service.awaitTurn(started.conversationId)
  }

  @Test
  fun `the model is told when the member edited a document since its last reply`() = runBlocking<Unit> {
    val service = service()
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "Make a quiz"))
    service.awaitTurn(started.conversationId)
    val artifacts = ArtifactRepository()
    val doc = artifacts.create(started.conversationId, "markdown", "Notes", "v1", "assistant")
    artifacts.addVersion(doc.id, "member edit", "jdoe", now = ChatService.now().plusSeconds(5))

    assertIs<TurnOutcome.Started>(service.sendMessage(started.conversationId, "jdoe", "Add a section"))
    service.awaitTurn(started.conversationId)

    val sent = repository.messages(started.conversationId).filter { it.role == "user" }.map { it.content }
    assertTrue(sent.last().contains("Note from the app"), sent.last())
    assertTrue(sent.last().contains(doc.id))
    assertFalse(sent.first().contains("Note from the app"))
    assertEquals("Add a section", repository.messages(started.conversationId).filter { it.role == "user" }.last().displayText)
  }

  @Test
  fun `a new chat gets a written title and tells the page`() = runBlocking<Unit> {
    val service = service(titles = titleModel("Laser class dates"))
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "When is the next laser class please?"))
    val turn = service.activeTurn(started.conversationId)!!
    service.awaitTurn(started.conversationId)

    assertEquals("Laser class dates", repository.get(started.conversationId)!!.title)
    assertEquals("Laser class dates", turn.events.replayCache.first { it.type == "title" }.text)
  }

  @Test
  fun `a follow-up question does not change the title`() = runBlocking<Unit> {
    val service = service(titles = titleModel("Laser class dates"))
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "When is the class?"))
    service.awaitTurn(started.conversationId)
    service.rename(started.conversationId, "jdoe", "My name")

    assertIs<TurnOutcome.Started>(service.sendMessage(started.conversationId, "jdoe", "And after that?"))
    service.awaitTurn(started.conversationId)

    assertEquals("My name", repository.get(started.conversationId)!!.title)
  }

  @Test
  fun `a title the member changed first is left alone`() = runBlocking<Unit> {
    assertEquals(false, repository.renameIfTitle("no-such-chat", "a", "b"))
    val service = service()
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "When is the class?"))
    service.awaitTurn(started.conversationId)

    assertEquals(false, repository.renameIfTitle(started.conversationId, "When is the class?", "Late title"))
    assertEquals("Short title", repository.get(started.conversationId)!!.title)
  }

  @Test
  fun `a question with personal details is never sent for a title`() = runBlocking<Unit> {
    var calls = 0
    val counting = ChatModelClient { p, m, t -> calls++; titleModel("Title").stream(p, m, t) }
    val service = service(titles = counting)
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "Mail jdoe@example.com about it"))
    service.awaitTurn(started.conversationId)

    assertEquals(0, calls)
    assertEquals(ChatService.PRIVATE_TITLE, repository.get(started.conversationId)!!.title)
  }

  @Test
  fun `a failed title keeps the question as the title`() = runBlocking<Unit> {
    val service = service(titles = ChatModelClient { _, _, _ -> flow { error("down") } })
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "When is the class?"))
    service.awaitTurn(started.conversationId)

    assertEquals("When is the class?", repository.get(started.conversationId)!!.title)
  }

  @Test
  fun `the title call counts toward the chat cost`() = runBlocking<Unit> {
    val free = service(titles = titleModel("Title"))
    val a = assertIs<TurnOutcome.Started>(free.startConversation("ask-ai", "jdoe", "When is the class?"))
    free.awaitTurn(a.conversationId)
    val paid = service(titles = titleModel("Title", inputTokens = 100_000))
    val b = assertIs<TurnOutcome.Started>(paid.startConversation("ask-ai", "jdoe", "When is the class?"))
    paid.awaitTurn(b.conversationId)

    assertTrue(repository.get(b.conversationId)!!.totalCostUsd > repository.get(a.conversationId)!!.totalCostUsd)
  }

  @Test
  fun `the owner can rename a chat but not to a title with personal details`() = runBlocking<Unit> {
    val service = service()
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "When is the class?"))
    service.awaitTurn(started.conversationId)

    assertIs<RenameOutcome.Renamed>(service.rename(started.conversationId, "jdoe", "  Laser   class dates "))
    assertEquals("Laser class dates", repository.get(started.conversationId)!!.title)
    assertIs<RenameOutcome.Invalid>(service.rename(started.conversationId, "jdoe", "Mail me at jdoe@example.org"))
    assertIs<RenameOutcome.Invalid>(service.rename(started.conversationId, "jdoe", "   "))
    assertIs<RenameOutcome.NotFound>(service.rename(started.conversationId, "asmith", "Mine now"))
  }

  @Test
  fun `the chat detail carries its documents, so opening a chat needs one request`() = runBlocking<Unit> {
    val service = service()
    val started = assertIs<TurnOutcome.Started>(service.startConversation("ask-ai", "jdoe", "Make notes"))
    service.awaitTurn(started.conversationId)
    ArtifactRepository().create(started.conversationId, "markdown", "Notes", "# hi", "assistant")

    val detail = service.detail(started.conversationId, "jdoe")!!

    assertEquals(listOf("Notes"), detail.artifacts.map { it.title })
    assertEquals(2, detail.messages.size)
    assertNull(service.detail("missing", "jdoe"))
  }
}
