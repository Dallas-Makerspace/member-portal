package org.dallasmakerspace.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Scripted model for mock mode and tests: calls the first tool once with the question, then
 * answers by quoting the tool result.
 */
class MockChatModelClient(private val wordDelayMillis: Long = WORD_DELAY_MILLIS) : ChatModelClient {
  override fun stream(
      prompt: Prompt,
      modelId: String,
      tools: List<ToolDescriptor>,
  ): Flow<StreamFrame> = flow {
    val last = prompt.messages.last()
    val toolResult = (last as? Message.User)?.parts?.filterIsInstance<MessagePart.Tool.Result>()
    val question =
        prompt.messages
            .filterIsInstance<Message.User>()
            .lastOrNull { user -> user.parts.any { it is MessagePart.Text } }
            ?.parts
            ?.filterIsInstance<MessagePart.Text>()
            ?.joinToString(" ") { it.text } ?: ""
    val tool = tools.firstOrNull()
    if (toolResult.isNullOrEmpty() && tool != null) {
      val param = tool.requiredParameters.firstOrNull()?.name ?: "query"
      val args = buildJsonObject { put(param, question.take(MAX_ARG_CHARS)) }
      emit(StreamFrame.ToolCallComplete("mock-call-${prompt.messages.size}", tool.name, args.toString()))
      emit(end("tool_use"))
      return@flow
    }
    val answer =
        if (toolResult.isNullOrEmpty()) "This is a mock answer to: $question"
        else "Here is what I found «1»: ${toolResult.first().output.take(MAX_QUOTE_CHARS)}"
    answer.split(" ").forEach { word ->
      emit(StreamFrame.TextDelta("$word "))
      delay(wordDelayMillis)
    }
    emit(end("end_turn"))
  }

  private fun end(reason: String) =
      StreamFrame.End(
          reason,
          ResponseMetaInfo.create(
              KoogClock.System, inputTokensCount = MOCK_INPUT, outputTokensCount = MOCK_OUTPUT))

  companion object {
    private const val WORD_DELAY_MILLIS = 30L
    private const val MAX_ARG_CHARS = 200
    private const val MAX_QUOTE_CHARS = 300
    private const val MOCK_INPUT = 500
    private const val MOCK_OUTPUT = 50
  }
}
