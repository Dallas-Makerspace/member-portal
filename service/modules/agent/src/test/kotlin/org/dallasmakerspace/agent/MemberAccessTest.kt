package org.dallasmakerspace.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigValueType
import org.dallasmakerspace.config.FeatureFlagValue
import org.junit.Test

class MemberAccessTest {
  private class LookupTool(override val requiredMemberAccess: MemberAccess = MemberAccess.SELF_ONLY) :
      AgentTool {
    var seen: ToolContext? = null
    override val descriptor =
        ToolDescriptor(
            "lookup",
            "Look up a member",
            listOf(ToolParameterDescriptor("username", "Member to look up", ToolParameterType.String)))

    override fun descriptorFor(access: MemberAccess) =
        if (access == MemberAccess.ANY_MEMBER) descriptor else descriptor.copy(optionalParameters = emptyList(), requiredParameters = emptyList())

    override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
      seen = context
      return ToolResult("ok")
    }
  }

  private val flag =
      ConfigKey("test.enabled", "test", "Test", FeatureFlagValue(), ConfigValueType.FeatureFlagType)

  private fun bot(tool: AgentTool, access: MemberAccess) =
      BotDefinition("b", "B", "Prompt", listOf(tool), flag, memberAccess = access)

  @Test
  fun `a self-only context resolves only the asker`() {
    val context = ToolContext("jdoe", "c1", "b")
    assertEquals("jdoe", context.subjectFor(null))
    assertEquals("jdoe", context.subjectFor("  "))
    assertEquals("JDoe", context.subjectFor("JDoe"))
    assertNull(context.subjectFor("asmith"))
  }

  @Test
  fun `an any-member context resolves the requested member`() {
    val context = ToolContext("jdoe", "c1", "b", MemberAccess.ANY_MEMBER)
    assertEquals("asmith", context.subjectFor(" asmith "))
    assertEquals("jdoe", context.subjectFor(null))
  }

  @Test
  fun `a bot cannot carry a tool that needs more member access than the bot has`() {
    assertFailsWith<IllegalArgumentException> {
      bot(LookupTool(MemberAccess.ANY_MEMBER), MemberAccess.SELF_ONLY)
    }
    bot(LookupTool(MemberAccess.ANY_MEMBER), MemberAccess.ANY_MEMBER)
    bot(LookupTool(), MemberAccess.SELF_ONLY)
  }

  @Test
  fun `the runtime gives tools the bot's access and shows the matching descriptor`() = runBlocking<Unit> {
    for (access in MemberAccess.entries) {
      val tool = LookupTool()
      var shown: List<ToolDescriptor> = emptyList()
      var round = 0
      val model =
          ChatModelClient { _, _, tools ->
            shown = tools
            val end = StreamFrame.End("end_turn", ResponseMetaInfo.create(KoogClock.System))
            if (round++ == 0) flowOf(StreamFrame.ToolCallComplete("c1", "lookup", "{}"), end)
            else flowOf(StreamFrame.TextDelta("Done."), end)
          }

      AgentRuntime(model)
          .runTurn(
              bot(tool, access),
              "model",
              ToolContext("jdoe", "c1", "b", MemberAccess.ANY_MEMBER),
              emptyList(),
              TurnState(1, "Who?"),
              AgentEventSink { _, _, _, _, _, _ -> })

      assertEquals(access, tool.seen?.memberAccess)
      val parameters = shown.single().let { it.requiredParameters + it.optionalParameters }
      assertEquals(if (access == MemberAccess.ANY_MEMBER) 1 else 0, parameters.size)
    }
  }
}
