package org.dallasmakerspace.agent

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.dallasmakerspace.agent.db.AgentArtifactTable
import org.dallasmakerspace.agent.db.AgentArtifactVersionTable
import org.dallasmakerspace.agent.db.AgentConversationTable
import org.dallasmakerspace.agent.db.AgentFeedbackTable
import org.dallasmakerspace.agent.db.AgentMessageTable
import org.dallasmakerspace.agent.db.AgentToolCallTable
import org.dallasmakerspace.agent.db.AgentUsageTable
import org.dallasmakerspace.agent.db.ArtifactRepository
import org.dallasmakerspace.agent.db.ConversationRepository
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.dallasmakerspace.models.agent.AgentVisibility
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

class ArtifactToolsTest {
  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      val db =
          Database.connect(
              "jdbc:h2:mem:agent-artifacts;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
              driver = "org.h2.Driver")
      DBMemberPortalConnection.db = db
      transaction(db) {
        SchemaUtils.create(
            AgentConversationTable, AgentMessageTable, AgentToolCallTable, AgentFeedbackTable, AgentUsageTable,
            AgentArtifactTable, AgentArtifactVersionTable)
      }
    }
  }

  private val repository = ArtifactRepository()
  private val create = CreateArtifactTool(repository)
  private val update = UpdateArtifactTool(repository)
  private val read = ReadArtifactTool(repository)
  private val list = ListArtifactsTool(repository)
  private val context = ToolContext("jdoe", "chat-1", "course-bot")

  private val outline =
      """{"title":"Intro to Soldering","sections":[{"title":"Basics","activities":[{"title":"Safety talk","type":"lecture","minutes":15}]}]}"""
  private val quiz =
      """{"title":"Quiz","questions":[{"type":"multiple_choice","text":"Which is hot?","options":[{"text":"Iron","correct":true},{"text":"Ice"}]}]}"""

  @Before
  fun setUp() {
    transaction(DBMemberPortalConnection.db) {
      AgentArtifactVersionTable.deleteAll()
      AgentArtifactTable.deleteAll()
      AgentConversationTable.deleteAll()
    }
    runBlocking {
      listOf("chat-1", "chat-2").forEach {
        ConversationRepository().create(it, "course-bot", "jdoe", "Chat", AgentVisibility.PRIVATE, null)
      }
    }
  }

  private fun args(vararg pairs: Pair<String, String>): JsonObject =
      buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

  private fun idOf(result: ToolResult) =
      ArtifactKinds.json.parseToJsonElement(result.artifactChange!!).jsonObject["id"]!!.jsonPrimitive.content

  @Test
  fun `creating saves version one and announces it`() = runBlocking<Unit> {
    val result = create.run(args("kind" to "markdown", "title" to "Notes", "content" to "# Hello"), context)

    assertFalse(result.isError, result.output)
    val change = ArtifactKinds.json.parseToJsonElement(result.artifactChange!!).jsonObject
    assertEquals("1", change["version"]!!.jsonPrimitive.content)
    assertEquals("markdown", change["kind"]!!.jsonPrimitive.contentOrNull)
    assertEquals("# Hello", repository.version(idOf(result), 1)!!.content)
  }

  @Test
  fun `invalid content is rejected with a message the model can fix`() = runBlocking<Unit> {
    val badKind = create.run(args("kind" to "poem", "title" to "T", "content" to "x"), context)
    val badJson = create.run(args("kind" to "quiz", "title" to "T", "content" to "{not json"), context)
    val noCorrect =
        create.run(
            args("kind" to "quiz", "title" to "T",
                "content" to """{"title":"Q","questions":[{"type":"multiple_choice","text":"?","options":[{"text":"a"},{"text":"b"}]}]}"""),
            context)
    val badActivity =
        create.run(
            args("kind" to "course_outline", "title" to "T",
                "content" to """{"title":"C","sections":[{"title":"S","activities":[{"title":"A","type":"nap"}]}]}"""),
            context)

    val blankOption =
        create.run(
            args("kind" to "quiz", "title" to "T",
                "content" to """{"title":"Q","questions":[{"type":"multiple_choice","text":"?","options":[{"text":"a","correct":true},{"text":" "}]}]}"""),
            context)
    assertTrue(blankOption.output.contains("empty option"), blankOption.output)

    listOf(badKind, badJson, noCorrect, badActivity, blankOption).forEach {
      assertTrue(it.isError)
      assertNull(it.artifactChange)
    }
    assertTrue(noCorrect.output.contains("correct option"), noCorrect.output)
    assertTrue(badActivity.output.contains("type must be one of"), badActivity.output)
    assertEquals("No documents yet.", list.run(JsonObject(emptyMap()), context).output)
  }

  @Test
  fun `valid outlines and quizzes are accepted`() = runBlocking<Unit> {
    assertFalse(create.run(args("kind" to "course_outline", "title" to "O", "content" to outline), context).isError)
    assertFalse(create.run(args("kind" to "quiz", "title" to "Q", "content" to quiz), context).isError)
    assertEquals(2, list.run(JsonObject(emptyMap()), context).output.lines().size)
  }

  @Test
  fun `a targeted edit adds a version and keeps the old one`() = runBlocking<Unit> {
    val id = idOf(create.run(args("kind" to "markdown", "title" to "N", "content" to "alpha beta"), context))

    val edited = update.run(args("id" to id, "old" to "beta", "new" to "gamma"), context)

    assertFalse(edited.isError, edited.output)
    assertEquals("alpha gamma", repository.version(id, 2)!!.content)
    assertEquals("alpha beta", repository.version(id, 1)!!.content)
    assertEquals(2, repository.get(id)!!.currentVersion)
    assertTrue(read.run(args("id" to id), context).output.contains("alpha gamma"))
    assertTrue(read.run(args("id" to id, "version" to "1"), context).output.contains("alpha beta"))
  }

  @Test
  fun `an edit must match exactly once and still be valid`() = runBlocking<Unit> {
    val id = idOf(create.run(args("kind" to "markdown", "title" to "N", "content" to "a a"), context))
    val quizId = idOf(create.run(args("kind" to "quiz", "title" to "Q", "content" to quiz), context))

    assertTrue(update.run(args("id" to id, "old" to "a", "new" to "b"), context).isError)
    assertTrue(update.run(args("id" to id, "old" to "zzz", "new" to "b"), context).isError)
    assertTrue(update.run(args("id" to id), context).isError)
    val broken = update.run(args("id" to quizId, "old" to "\"correct\":true", "new" to "\"correct\":false"), context)
    assertTrue(broken.isError, broken.output)
    assertEquals(1, repository.get(quizId)!!.currentVersion)
  }

  @Test
  fun `documents belong to their chat`() = runBlocking<Unit> {
    val id = idOf(create.run(args("kind" to "markdown", "title" to "N", "content" to "x"), context))
    val other = ToolContext("jdoe", "chat-2", "course-bot")

    assertTrue(read.run(args("id" to id), other).isError)
    assertTrue(update.run(args("id" to id, "content" to "y"), other).isError)
    assertEquals("No documents yet.", list.run(JsonObject(emptyMap()), other).output)
    assertNotNull(repository.get(id))
  }

  @Test
  fun `a chat holds a limited number of documents`() = runBlocking<Unit> {
    repeat(ArtifactToolInfo.MAX_PER_CONVERSATION) {
      assertFalse(create.run(args("kind" to "markdown", "title" to "N$it", "content" to "x"), context).isError)
    }

    assertTrue(create.run(args("kind" to "markdown", "title" to "One more", "content" to "x"), context).isError)
  }

  @Test
  fun `a stale save is refused`() = runBlocking<Unit> {
    val id = idOf(create.run(args("kind" to "markdown", "title" to "N", "content" to "x"), context))

    assertEquals(2, repository.addVersion(id, "member edit", "jdoe", basedOn = 1))
    assertNull(repository.addVersion(id, "late edit", "assistant", basedOn = 1))
  }
}
