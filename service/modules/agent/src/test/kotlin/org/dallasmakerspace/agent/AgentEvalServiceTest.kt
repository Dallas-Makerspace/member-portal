package org.dallasmakerspace.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.agent.db.ArtifactRepository
import org.dallasmakerspace.agent.db.ConversationRepository
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.ConfigValueType
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.agent.AgentSourceLink
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.slf4j.Logger

class AgentEvalServiceTest {
  private class Tool : AgentTool {
    override val descriptor =
        ToolDescriptor(
            "search_wiki",
            "Search",
            listOf(ToolParameterDescriptor("query", "Search text", ToolParameterType.String)))

    override suspend fun run(args: JsonObject, context: ToolContext) =
        ToolResult(
            "Title: Laser class\nLink: https://wiki.example.org/laser\nExcerpt: Tuesdays.",
            listOf(AgentSourceLink("Laser class", "https://wiki.example.org/laser", "Wiki")))
  }

  private val flag =
      ConfigKey("test.enabled", "test", "Test", FeatureFlagValue(), ConfigValueType.FeatureFlagType)
  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }

  private fun service(enabled: Boolean): AgentEvalService {
    val config =
        mock<ConfigOverrideService> {
          onBlocking { get(ConfigRegistry.AGENT_MODEL_DEFAULT, false) } doReturn
              "us.anthropic.claude-haiku-5-5"
          onBlocking { get(ConfigRegistry.AGENT_MONTHLY_SPEND_CAP_USD, false) } doReturn 20
          onBlocking { get(ConfigRegistry.AGENT_USER_DAILY_TURN_CAP, false) } doReturn 30
        }
    val spendCap = AgentSpendCap(ConversationRepository(), config, loggerFactory)
    return AgentEvalService(
        setOf(BotDefinition("ask-ai", "Ask AI", "Help.", listOf(Tool()), flag)),
        MockChatModelClient(0),
        config,
        spendCap,
        mock<AppConfig> {
          on { getStringProperty("app.agent-eval-enabled", "false") } doReturn enabled.toString()
        },
        ConversationRepository(),
        ArtifactRepository())
  }

  @Test
  fun `is off unless the service runs with eval enabled`() = runBlocking<Unit> {
    assertIs<EvalOutcome.Disabled>(service(false).run("ask-ai", listOf("Hi"), null))
  }

  @Test
  fun `runs each question in one chat and reports tool calls and sources`() = runBlocking<Unit> {
    val done =
        assertIs<EvalOutcome.Done>(
            service(true).run("ask-ai", listOf("When is the laser class?", "And the next one?"), null))

    val turns = done.response.turns
    assertEquals(2, turns.size)
    assertEquals(listOf("search_wiki"), turns[0].toolCalls.map { it.name })
    assertEquals("https://wiki.example.org/laser", turns[0].sources.single().url)
    assertTrue(turns[0].answer.contains("[Laser class](https://wiki.example.org/laser)"))
    assertEquals("us.anthropic.claude-haiku-5-5", done.response.model)
  }

  @Test
  fun `rejects unknown bots, models and empty messages`() = runBlocking<Unit> {
    val service = service(true)
    assertIs<EvalOutcome.UnknownBot>(service.run("nope", listOf("Hi"), null))
    assertIs<EvalOutcome.BadRequest>(service.run("ask-ai", listOf("Hi"), "some-other-model"))
    assertIs<EvalOutcome.BadRequest>(service.run("ask-ai", emptyList(), null))
    assertIs<EvalOutcome.BadRequest>(service.run("ask-ai", listOf("  "), null))
  }
}
