package org.dallasmakerspace.agent

import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.slf4j.Logger

class ChatTitlerTest {
  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }

  private fun titler(reply: String) =
      ChatTitler(
          ChatModelClient { _, _, _ ->
            flowOf(StreamFrame.TextDelta(reply), StreamFrame.End("end_turn", ResponseMetaInfo.create(KoogClock.System)))
          },
          PiiMasker(loggerFactory),
          loggerFactory)

  @Test
  fun `a clean reply becomes the title`() = runBlocking<Unit> {
    assertEquals("Laser cutter access", titler("Laser cutter access").suggest("How do I use the laser?", "model")?.title)
  }

  @Test
  fun `wrapping quotes, markdown and ending punctuation are removed`() {
    assertEquals("Auto area mess, Thursday", ChatTitler.cleanTitle("\"Auto area mess, Thursday.\"\n"))
    assertEquals("Laser access", ChatTitler.cleanTitle("# **Laser access**"))
  }

  @Test
  fun `a reply that can't be a title is refused`() {
    assertNull(ChatTitler.cleanTitle("   "))
    assertNull(ChatTitler.cleanTitle("x".repeat(61)))
    assertNull(ChatTitler.cleanTitle("<b>Laser</b> access"))
    assertNull(ChatTitler.cleanTitle("[Laser](https://example.test)"))
  }

  @Test
  fun `a reply with personal details is refused`() = runBlocking<Unit> {
    assertNull(titler("Email jdoe@example.com about laser").suggest("question", "model"))
  }

  @Test
  fun `a model failure is not an error for the chat`() = runBlocking<Unit> {
    val failing = ChatModelClient { _, _, _ -> flow { error("model down") } }
    assertNull(ChatTitler(failing, PiiMasker(loggerFactory), loggerFactory).suggest("question", "model"))
  }

  @Test
  fun `only the question is sent, marked as data, with no tools`() = runBlocking<Unit> {
    var sent = ""
    var toolCount = -1
    val model =
        ChatModelClient { prompt, _, tools ->
          sent = prompt.messages.last().parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
          toolCount = tools.size
          flowOf(StreamFrame.TextDelta("Laser class"), StreamFrame.End("end_turn", ResponseMetaInfo.create(KoogClock.System)))
        }
    assertNotNull(ChatTitler(model, PiiMasker(loggerFactory), loggerFactory).suggest("x".repeat(900), "model"))
    assertEquals(0, toolCount)
    assertTrue(sent.startsWith("<question>"))
    assertEquals("<question>".length + 500 + "</question>".length, sent.length)
  }
}
