package org.dallasmakerspace.agent

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.agent.db.AgentArtifactTable
import org.dallasmakerspace.agent.db.AgentArtifactVersionTable
import org.dallasmakerspace.agent.db.AgentConversationTable
import org.dallasmakerspace.agent.db.AgentFeedbackTable
import org.dallasmakerspace.agent.db.AgentMessageTable
import org.dallasmakerspace.agent.db.AgentToolCallTable
import org.dallasmakerspace.agent.db.ArtifactRepository
import org.dallasmakerspace.agent.db.ConversationRepository
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.ConfigValueType
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.agent.AgentVisibility
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.slf4j.Logger

class ArtifactServiceTest {
  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      val db =
          Database.connect(
              "jdbc:h2:mem:agent-artifact-service;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
              driver = "org.h2.Driver")
      DBMemberPortalConnection.db = db
      transaction(db) {
        SchemaUtils.create(
            AgentConversationTable, AgentMessageTable, AgentToolCallTable, AgentFeedbackTable,
            AgentArtifactTable, AgentArtifactVersionTable)
      }
    }
  }

  private val flag =
      ConfigKey("test.enabled", "test", "Test", FeatureFlagValue(), ConfigValueType.FeatureFlagType)
  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }
  private val conversations = ConversationRepository()
  private val artifacts = ArtifactRepository()

  private val service: ArtifactService by lazy {
    val config =
        mock<ConfigOverrideService> {
          onBlocking { get(ConfigRegistry.AGENT_MODEL_DEFAULT, false) } doReturn "model"
          onBlocking { get(ConfigRegistry.AGENT_MONTHLY_SPEND_CAP_USD, false) } doReturn 20
          onBlocking { get(ConfigRegistry.AGENT_USER_DAILY_TURN_CAP, false) } doReturn 30
        }
    val access =
        mock<AgentAccess> {
          onBlocking { groupsOf(any()) } doReturn emptyList()
          onBlocking { canUse(any(), any(), any()) } doReturn true
        }
    val chat =
        ChatService(
            setOf(BotDefinition("course-bot", "Course bot", "Help.", emptyList(), flag)),
            conversations,
            access,
            AgentSpendCap(conversations, config, loggerFactory),
            MockChatModelClient(0),
            config,
            PiiMasker(loggerFactory),
            TurnStreams(),
            loggerFactory)
    ArtifactService(chat, artifacts)
  }

  private val quiz =
      """{"title":"Q","questions":[{"type":"true_false","text":"Flux helps?","options":[{"text":"True","correct":true},{"text":"False"}]}]}"""

  @Before
  fun setUp() {
    transaction(DBMemberPortalConnection.db) {
      AgentArtifactVersionTable.deleteAll()
      AgentArtifactTable.deleteAll()
      AgentConversationTable.deleteAll()
    }
  }

  private suspend fun chat(id: String, visibility: AgentVisibility) =
      conversations.create(id, "course-bot", "jdoe", "Chat", visibility, null)

  @Test
  fun `the owner reads, edits and exports while others only read public chats`() = runBlocking<Unit> {
    chat("pub", AgentVisibility.PUBLIC)
    val id = artifacts.create("pub", "quiz", "Quiz", quiz, "assistant").id

    val own = service.detail(id, "jdoe", null)!!
    assertTrue(own.canEdit)
    assertEquals(listOf("gift", "xml", "md"), own.exportFormats)
    assertFalse(service.detail(id, "asmith", null)!!.canEdit)
    assertIs<SaveOutcome.Forbidden>(service.save(id, "asmith", quiz, 1))

    assertEquals(SaveOutcome.Saved(2), service.save(id, "jdoe", quiz.replace("Flux helps?", "Flux helps solder?"), 1))
    assertIs<SaveOutcome.Stale>(service.save(id, "jdoe", quiz, 1))
    assertIs<SaveOutcome.Invalid>(service.save(id, "jdoe", "{not json", 2))
    assertEquals(listOf("assistant", "jdoe"), service.detail(id, "jdoe", null)!!.versions.map { it.author })
    assertTrue(service.export(id, "jdoe", "gift", null)!!.body.contains("Flux helps solder?"))
    assertTrue(service.export(id, "jdoe", "gift", 1)!!.body.contains("Flux helps?"))
    assertNull(service.export(id, "jdoe", "docx", null))
  }

  @Test
  fun `private chats hide their documents from other members`() = runBlocking<Unit> {
    chat("priv", AgentVisibility.PRIVATE)
    val id = artifacts.create("priv", "markdown", "Notes", "# hi", "assistant").id

    assertNotNull(service.list("priv", "jdoe"))
    assertNull(service.list("priv", "asmith"))
    assertNull(service.detail(id, "asmith", null))
    assertNull(service.export(id, "asmith", "md", null))
    assertNull(service.detail("missing", "jdoe", null))
  }

  private fun assertFalse(value: Boolean) = kotlin.test.assertFalse(value)
}
