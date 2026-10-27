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
      val args =
          if (tool.name == "create_artifact") {
            buildJsonObject {
              put("kind", "quiz")
              put("title", "Sample quiz")
              put("content", SAMPLE_QUIZ)
            }
          } else buildJsonObject { put(param, question.take(MAX_ARG_CHARS)) }
      emit(StreamFrame.ToolCallComplete("mock-call-${prompt.messages.size}", tool.name, args.toString()))
      emit(end("tool_use"))
      return@flow
    }
    val created = toolResult?.firstOrNull()?.output?.contains("Created quiz") == true
    val answer =
        if (created) "I made a short quiz for you. Open it in the Documents panel to review, edit or export it."
        else if (toolResult.isNullOrEmpty()) "This is a mock answer to: $question"
        else mockAnswer(toolResult.first().output)
    CHUNK.findAll(answer).forEach { chunk ->
      emit(StreamFrame.TextDelta(chunk.value))
      delay(wordDelayMillis)
    }
    emit(end("end_turn"))
  }

  /** A realistic answer (prose, numbered steps, markdown links) built from the tool's pages. */
  private fun mockAnswer(toolOutput: String): String {
    val pages =
        PAGE.findAll(toolOutput).map { it.groupValues[1].trim() to it.groupValues[2].trim() }.toList()
    val first = pages.firstOrNull()
    val link = first?.let { "[${it.first}](${it.second})" } ?: "the wiki"
    val second = pages.getOrNull(1)?.let { "[${it.first}](${it.second})" }
    return """
        |Here is how it works at Dallas Makerspace. You need to be a keyed member, and each tool has its own training before you can use it unsupervised.
        |
        |1. Read the safety and authorization page on Source: $link.
        |2. Take the in-person class for the tool. Classes are on the calendar, so check the next date and sign up.
        |3. Complete the check-out with an instructor. Your badge is authorized for the tool once they record it.
        |
        |If you get stuck, ask the committee chair for that area${if (second != null) ". More background is in $second." else "."}
        """
        .trimMargin()
        .trim()
  }

  private fun end(reason: String) =
      StreamFrame.End(
          reason,
          ResponseMetaInfo.create(
              KoogClock.System, inputTokensCount = MOCK_INPUT, outputTokensCount = MOCK_OUTPUT))

  companion object {
    private val CHUNK = Regex("""\S+\s*""")
    private val PAGE = Regex("""Title: ([^\n]*)\nLink: (\S+)""")
    private const val WORD_DELAY_MILLIS = 30L
    private const val MAX_ARG_CHARS = 200
    private const val SAMPLE_QUIZ =
        """{"title":"Soldering basics","questions":[""" +
            """{"type":"multiple_choice","text":"Which tool melts solder?","options":[{"text":"Soldering iron","correct":true},{"text":"Hammer"},{"text":"Wire cutter"}]},""" +
            """{"type":"true_false","text":"Flux helps solder flow.","options":[{"text":"True","correct":true},{"text":"False"}]}]}"""
    private const val MOCK_INPUT = 500
    private const val MOCK_OUTPUT = 50
  }
}
