package org.dallasmakerspace.itbot.tools

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.dallasmakerspace.agent.PiiMasker
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.itbot.LogLine
import org.dallasmakerspace.itbot.LogQuery
import org.dallasmakerspace.itbot.LogResult
import org.dallasmakerspace.itbot.LogSearch
import org.dallasmakerspace.itbot.LogSource
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.slf4j.Logger

class QueryLogsToolTest {
  private class FakeLogs(val lines: List<LogLine>) : LogSearch {
    var last: LogQuery? = null

    override suspend fun search(query: LogQuery): LogResult {
      last = query
      return LogResult(lines.size.toLong(), lines)
    }
  }

  private val loggerFactory = mock<LoggerFactory> { on { create(any()) } doReturn mock<Logger>() }
  private val context = ToolContext("jdoe", "c1", "it-assistant")
  private val line =
      LogLine(
          "2026-01-01T12:00:00Z", "ERROR", "svc", "Mailer", "s-1", "asmith",
          "Send failed for asmith@example.org\nstack")

  private fun run(logs: FakeLogs, vararg pairs: Pair<String, String>) = runBlocking {
    QueryLogsTool(logs, PiiMasker(loggerFactory))
        .run(buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }, context)
  }

  @Test
  fun `it marks chats private and hides emails in messages`() {
    val logs = FakeLogs(listOf(line))

    assertTrue(QueryLogsTool(logs, PiiMasker(loggerFactory)).touchesPersonalInfo)
    val result = run(logs, "text" to "send failed")
    assertFalse(result.isError)
    assertFalse(result.output.contains("asmith@example.org"))
    assertTrue(result.output.contains("session=s-1"))
    assertFalse(result.output.contains("\nstack"))
  }

  @Test
  fun `defaults to the last hour of app logs and passes filters through`() {
    val logs = FakeLogs(listOf(line))

    run(logs, "minSeverity" to "error", "sessionId" to "s-1", "size" to "500")

    val query = logs.last!!
    assertEquals(LogSource.APP, query.source)
    assertEquals("now-1h", query.from)
    assertEquals("now", query.to)
    assertEquals("ERROR", query.minSeverity)
    assertEquals(50, query.size)
  }

  @Test
  fun `rejects odd times, severities and ids`() {
    val logs = FakeLogs(emptyList())

    assertTrue(run(logs, "from" to "yesterday-ish").isError)
    assertTrue(run(logs, "minSeverity" to "LOUD").isError)
    assertTrue(run(logs, "sessionId" to "a b; drop").isError)
    assertTrue(run(logs, "source" to "everything").isError)
    assertEquals(null, logs.last)
    assertEquals("No log lines matched in that window.", run(logs).output)
  }
}
