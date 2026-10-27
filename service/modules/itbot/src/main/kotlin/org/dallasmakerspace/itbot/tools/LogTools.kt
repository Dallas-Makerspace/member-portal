package org.dallasmakerspace.itbot.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.agent.AgentTool
import org.dallasmakerspace.agent.PiiMasker
import org.dallasmakerspace.agent.ToolContext
import org.dallasmakerspace.agent.ToolResult
import org.dallasmakerspace.itbot.LogQuery
import org.dallasmakerspace.itbot.LogSearch
import org.dallasmakerspace.itbot.LogSource

private const val DEFAULT_SIZE = 20
private const val MAX_SIZE = 50
private const val MESSAGE_CHARS = 400
private val RELATIVE_TIME = Regex("""now(-\d{1,2}[mhd])?""")
private val ISO_TIME = Regex("""\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}(:\d{2})?(\.\d+)?(Z|[+-]\d{2}:?\d{2})?)?""")
private val SEVERITIES = setOf("ERROR", "WARN", "INFO", "DEBUG")
private val WORD = Regex("""[A-Za-z0-9_.:@/-]{1,100}""")

/** Searches the member portal's application and request logs. */
@Singleton
class QueryLogsTool
@Inject
constructor(private val logs: LogSearch, private val masker: PiiMasker) : AgentTool {
  override val touchesPersonalInfo = true

  override val descriptor =
      ToolDescriptor(
          "query_logs",
          "Search the logs of the member portal, newest first. source \"app\" (default) has " +
              "application log lines with severity, logger and a session id that ties together " +
              "one request; source \"requests\" has one line per HTTP request. Times are " +
              "relative (now-30m, now-2h, now-1d) or ISO dates; the default window is the last hour.",
          requiredParameters = emptyList(),
          optionalParameters =
              listOf(
                  ToolParameterDescriptor("source", "app or requests", ToolParameterType.String),
                  ToolParameterDescriptor("text", "Words that must appear in the message or URL", ToolParameterType.String),
                  ToolParameterDescriptor("minSeverity", "ERROR, WARN or INFO (app logs only)", ToolParameterType.String),
                  ToolParameterDescriptor("app", "Application name, e.g. member-profile-service", ToolParameterType.String),
                  ToolParameterDescriptor("sessionId", "Show the lines of one request/session", ToolParameterType.String),
                  ToolParameterDescriptor("from", "Start of the window, default now-1h", ToolParameterType.String),
                  ToolParameterDescriptor("to", "End of the window, default now", ToolParameterType.String),
                  ToolParameterDescriptor("size", "Lines to return, default $DEFAULT_SIZE, at most $MAX_SIZE", ToolParameterType.Integer)))

  @Suppress("ReturnCount")
  override suspend fun run(args: JsonObject, context: ToolContext): ToolResult {
    val source =
        when (args.string("source")?.trim()?.lowercase()) {
          null, "", "app" -> LogSource.APP
          "requests" -> LogSource.REQUESTS
          else -> return ToolResult("source must be app or requests.", isError = true)
        }
    val from = args.string("from")?.trim()?.ifEmpty { null } ?: "now-1h"
    val to = args.string("to")?.trim()?.ifEmpty { null } ?: "now"
    if (!validTime(from) || !validTime(to)) {
      return ToolResult("Times must look like now-2h or 2026-10-01.", isError = true)
    }
    val severity = args.string("minSeverity")?.trim()?.uppercase()?.ifEmpty { null }
    if (severity != null && severity !in SEVERITIES) return ToolResult("Invalid minSeverity.", isError = true)
    val text = args.string("text")?.trim()?.ifEmpty { null }
    if (text != null && text.length > 200) return ToolResult("text is too long.", isError = true)
    val app = args.string("app")?.trim()?.ifEmpty { null }
    val session = args.string("sessionId")?.trim()?.ifEmpty { null }
    if ((app != null && !WORD.matches(app)) || (session != null && !WORD.matches(session))) {
      return ToolResult("Invalid app or sessionId.", isError = true)
    }
    val size = (args.int("size") ?: DEFAULT_SIZE).coerceIn(1, MAX_SIZE)

    val result = logs.search(LogQuery(source, text, app, severity, session, from, to, size))
    if (result.lines.isEmpty()) return ToolResult("No log lines matched in that window.")
    val body =
        result.lines.joinToString("\n") { line ->
          val head =
              listOfNotNull(line.timestamp, line.severity, line.app, line.logger, line.sessionId?.let { "session=$it" }, line.userId?.let { "user=$it" })
                  .joinToString(" ")
          "$head | ${masker.mask(line.message).replace('\n', ' ').take(MESSAGE_CHARS)}"
        }
    val note = if (result.total > result.lines.size) "\n(${result.total} lines matched; showing the newest ${result.lines.size}.)" else ""
    return ToolResult(body + note)
  }

  private fun validTime(value: String) = RELATIVE_TIME.matches(value) || ISO_TIME.matches(value)
}
