package org.dallasmakerspace.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.bedrock.BedrockAPIMethod
import ai.koog.prompt.executor.clients.bedrock.BedrockCacheControl
import ai.koog.prompt.executor.clients.bedrock.BedrockLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Manual spike against real Bedrock; runs only with KOOG_SPIKE=1 and AWS credentials. */
class KoogBedrockSpikeTest {
  private val enabled = System.getenv("KOOG_SPIKE") == "1"

  private val client by lazy {
    BedrockLLMClient(
        bedrockClient = BedrockRuntimeClient { region = "us-east-1" },
        apiMethod = BedrockAPIMethod.Converse,
    )
  }

  private fun model(id: String) =
      LLModel(
          provider = LLMProvider.Bedrock,
          id = id,
          capabilities =
              listOf(
                  LLMCapability.Completion,
                  LLMCapability.Tools,
                  LLMCapability.ToolChoice,
                  LLMCapability.PromptCaching,
              ),
          contextLength = 1_000_000,
          maxOutputTokens = 128_000,
      )

  private val weatherTool =
      ToolDescriptor(
          name = "get_weather",
          description = "Get today's weather for a city.",
          requiredParameters =
              listOf(ToolParameterDescriptor("city", "City name", ToolParameterType.String)),
      )

  // Long enough to clear the minimum cacheable prefix.
  private val systemPrompt =
      "You are a terse assistant for a makerspace. Use tools when they help. " +
          (1..200).joinToString(" ") { "Rule $it: be accurate and brief." }

  private fun runToolRoundTrip(modelId: String) = runBlocking {
    val model = model(modelId)
    var p =
        prompt("spike") {
          system(systemPrompt, BedrockCacheControl.Default)
          user("What's the weather in Testville? Use the tool.")
        }

    val first = client.executeStreaming(p, model, listOf(weatherTool)).toList()
    val call = first.filterIsInstance<StreamFrame.ToolCallComplete>().firstOrNull()
    println("[$modelId] turn 1 frames: ${first.map { it::class.simpleName }}")
    println("[$modelId] tool call: $call")
    println("[$modelId] turn 1 end: ${first.filterIsInstance<StreamFrame.End>().firstOrNull()}")
    requireNotNull(call) { "expected a tool call" }

    p =
        prompt(p) {
          toolCall(MessagePart.Tool.Call(call.id, call.name, call.content))
          toolResult(MessagePart.Tool.Result(call.id, call.name, "Sunny, 24C, light wind."))
        }
    val second = client.executeStreaming(p, model, listOf(weatherTool)).toList()
    val text = second.filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text }
    println("[$modelId] turn 2 text: $text")
    println("[$modelId] turn 2 end: ${second.filterIsInstance<StreamFrame.End>().firstOrNull()}")
    assertTrue(text.contains("24") || text.contains("unny"), "answer should use the tool result")
  }

  @Test
  fun `haiku 5_5 tool round trip with streaming and caching`() {
    assumeTrue(enabled)
    runToolRoundTrip("us.anthropic.claude-haiku-5-5")
  }

  @Test
  fun `gpt 6_1 sol tool round trip`() {
    assumeTrue(enabled)
    runToolRoundTrip("us.openai.gpt-6.1-sol")
  }

  @Test
  fun `cancelling a stream mid-way stops cleanly`() = runBlocking {
    assumeTrue(enabled)
    val p = prompt("spike-cancel") { user("Count from 1 to 200, one number per line.") }
    client.executeStreaming(p, model("us.anthropic.claude-haiku-5-5")).take(3).collect()
    println("cancelled after 3 frames")
  }
}
