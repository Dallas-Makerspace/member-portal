package org.dallasmakerspace.askai.tools

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.askai.confluence.ConfluenceApiClientMock
import org.dallasmakerspace.askai.search.CalendarSearchSource
import org.dallasmakerspace.askai.search.DiscourseSearchSource
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.slf4j.Logger

class AskAiToolsTest {
  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }
  private val appConfig =
      mock<AppConfig> {
        on { requireStringProperty("app.confluence.baseUrl") } doReturn "https://source.example.org"
      }
  private val client = ConfluenceApiClientMock(loggerFactory)
  private val context = ToolContext("jdoe", "c1", "ask-ai")

  private fun args(name: String, value: String) = buildJsonObject { put(name, value) }

  @Test
  fun `search_source returns titles, links, ids and excerpts without markup`() = runBlocking {
    val result = SearchSourceWikiTool(client, appConfig).run(args("query", "laser"), context)

    assertFalse(result.isError)
    assertTrue(result.output.contains("Title: Safety Guidelines for Makerspace"))
    assertTrue(result.output.contains("Link: https://source.example.org/wiki/spaces/DMS/pages/12345"))
    assertTrue(result.output.contains("Id: 12345"))
    assertFalse(result.output.contains("<b>"))
    assertEquals("Source", result.sources.first().source)
  }

  @Test
  fun `search_source asks for a query when it is missing`() = runBlocking {
    val result = SearchSourceWikiTool(client, appConfig).run(args("other", "x"), context)

    assertTrue(result.isError)
  }

  @Test
  fun `read_source_page only accepts numeric ids`() = runBlocking {
    val tool = ReadSourcePageTool(client, appConfig)

    assertTrue(tool.run(args("id", "12345/../../x"), context).isError)
    assertTrue(tool.run(args("id", ""), context).isError)
  }

  @Test
  fun `only the calendar tool marks chats as seeing member information`() {
    val calendar = SearchCalendarTool(mock<CalendarSearchSource>())
    val talk = SearchTalkTool(mock<DiscourseSearchSource>())

    assertTrue(calendar.touchesPersonalInfo)
    assertFalse(talk.touchesPersonalInfo)
    assertEquals("search_calendar", calendar.descriptor.name)
  }
}
