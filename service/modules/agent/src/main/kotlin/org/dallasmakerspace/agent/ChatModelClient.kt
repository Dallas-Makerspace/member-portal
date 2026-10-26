package org.dallasmakerspace.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow

/** Streams one model call. A seam so tests and mock mode don't need Bedrock. */
fun interface ChatModelClient {
  fun stream(prompt: Prompt, modelId: String, tools: List<ToolDescriptor>): Flow<StreamFrame>
}

/** Koog client (Bedrock Converse) behind [ChatModelClient]. */
class KoogChatModelClient(private val client: LLMClient) : ChatModelClient {
  override fun stream(
      prompt: Prompt,
      modelId: String,
      tools: List<ToolDescriptor>,
  ): Flow<StreamFrame> = client.executeStreaming(prompt, bedrockModel(modelId), tools)

  companion object {
    private const val CONTEXT_LENGTH = 1_000_000L
    private const val MAX_OUTPUT_TOKENS = 16_000L

    fun bedrockModel(modelId: String) =
        LLModel(
            provider = LLMProvider.Bedrock,
            id = modelId,
            capabilities =
                listOf(
                    LLMCapability.Completion,
                    LLMCapability.Tools,
                    LLMCapability.ToolChoice,
                    LLMCapability.PromptCaching,
                ),
            contextLength = CONTEXT_LENGTH,
            maxOutputTokens = MAX_OUTPUT_TOKENS,
        )
  }
}
