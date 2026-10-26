package org.dallasmakerspace.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.agent.db.AgentRoles
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigValueType
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.models.agent.AgentSourceLink
import org.junit.Test

class AgentRuntimeTest {
  private class FakeTool(
      override val touchesPersonalInfo: Boolean = false,
      private val output: String = "Laser class is on Tuesdays.",
  ) : AgentTool {
    var calls = 0
    override val descriptor =
        ToolDescriptor(
            "search_wiki",
            "Search the wiki",
            listOf(ToolParameterDescriptor("query", "Search text", ToolParameterType.String)))

    override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
      calls++
      return ToolResult(output, listOf(AgentSourceLink("Laser", "https://wiki.example.org/laser", "Wiki")))
    }
  }

  private val flag =
      ConfigKey("test.enabled", "test", "Test", FeatureFlagValue(), ConfigValueType.FeatureFlagType)

  private fun bot(tool: AgentTool, maxCalls: Int = 8) =
      BotDefinition("ask-ai", "Ask AI", "You help members.", listOf(tool), flag, maxToolCallsPerTurn = maxCalls)

  private val context = ToolContext("jdoe", "c1", "ask-ai")
  private val events = mutableListOf<String>()
  private val sink = AgentEventSink { type, _, _, _, _, _ -> events += type }

  @Test
  fun `a tool round trip ends with an answer that quotes the tool`() = runBlocking<Unit> {
    val tool = FakeTool()
    val state = TurnState(1, "When is the laser class?")

    AgentRuntime(MockChatModelClient(0)).runTurn(bot(tool), "model", context, emptyList(), state, sink)

    assertEquals(1, tool.calls)
    assertEquals(
        listOf(AgentRoles.USER, AgentRoles.ASSISTANT, AgentRoles.TOOL, AgentRoles.ASSISTANT),
        state.messages.map { it.second.role })
    assertTrue(state.messages.last().second.displayText!!.contains("Laser class is on Tuesdays"))
    assertEquals("search_wiki", state.toolCalls.single().toolName)
    assertEquals(1, state.toolCalls.single().messageIndex)
    assertEquals(1, state.sources.size)
    assertNull(state.privateReason)
    assertTrue(events.containsAll(listOf("tool-start", "tool-end", "delta")))
  }

  @Test
  fun `a tool that sees member data makes the turn private`() = runBlocking<Unit> {
    val state = TurnState(1, "Who teaches the laser class?")

    AgentRuntime(MockChatModelClient(0))
        .runTurn(bot(FakeTool(touchesPersonalInfo = true)), "model", context, emptyList(), state, sink)

    assertNotNull(state.privateReason)
  }

  @Test
  fun `a model that never stops calling tools is cut off with a valid history`() = runBlocking<Unit> {
    val loopingModel = ChatModelClient { prompt, _, _ ->
      flowOf(
          StreamFrame.ToolCallComplete("call-${prompt.messages.size}", "search_wiki", "{\"query\":\"x\"}"),
          StreamFrame.End("tool_use", ResponseMetaInfo.create(KoogClock.System)))
    }
    val tool = FakeTool()
    val state = TurnState(1, "Loop forever")

    AgentRuntime(loopingModel).runTurn(bot(tool, maxCalls = 2), "model", context, emptyList(), state, sink)

    assertEquals(2, tool.calls)
    assertEquals(AgentRoles.ASSISTANT, state.messages.last().second.role)
    assertEquals(AgentRuntime.GAVE_UP_ANSWER, state.messages.last().second.displayText)
  }

  @Test
  fun `only the last message carries a cache point`() {
    val meta = RequestMetaInfo.create(KoogClock.System)
    val messages =
        listOf(
            Message.User("first", meta),
            Message.Assistant("answer", ResponseMetaInfo.create(KoogClock.System)),
            Message.User("second", meta))

    val cached = AgentRuntime.withCacheOnLast(messages)

    assertNull((cached[0] as Message.User).parts.single().cacheControl)
    assertNotNull((cached[2] as Message.User).parts.single().cacheControl)
  }

  @Test
  fun `long histories drop old tool output and then old turns`() {
    val meta = RequestMetaInfo.create(KoogClock.System)
    val big = "x".repeat(130_000)
    val history =
        (1..8).flatMap { i ->
          listOf(
              Message.User("question $i", meta),
              Message.Assistant(
                  listOf(MessagePart.Tool.Call("c$i", "search_wiki", "{}")),
                  ResponseMetaInfo.create(KoogClock.System)),
              Message.User(listOf(MessagePart.Tool.Result("c$i", "search_wiki", big)), meta),
              Message.Assistant("answer $i", ResponseMetaInfo.create(KoogClock.System)))
        }

    val compacted = AgentRuntime.compact(history)

    assertTrue(compacted.size < history.size)
    assertTrue(compacted.first().let { it is Message.User && it.parts.single() is MessagePart.Text })
    assertEquals("answer 8", (compacted.last() as Message.Assistant).parts.filterIsInstance<MessagePart.Text>().single().text)
  }

  @Test
  fun `messages survive a JSON round trip`() {
    val state = TurnState(1, "hello")
    val decoded = TurnState.decode(state.messages.single().second.content)
    assertEquals("hello", ((decoded as Message.User).parts.single() as MessagePart.Text).text)
  }
}
