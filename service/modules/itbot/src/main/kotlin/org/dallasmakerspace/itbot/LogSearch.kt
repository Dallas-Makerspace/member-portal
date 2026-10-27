package org.dallasmakerspace.itbot

/** Which log index to read: application logs, or one line per HTTP request. */
enum class LogSource { APP, REQUESTS }

data class LogQuery(
    val source: LogSource,
    val text: String?,
    val app: String?,
    val minSeverity: String?,
    val sessionId: String?,
    val from: String,
    val to: String,
    val size: Int,
)

data class LogLine(
    val timestamp: String,
    val severity: String?,
    val app: String?,
    val logger: String?,
    val sessionId: String?,
    val userId: String?,
    val message: String,
)

data class LogResult(val total: Long, val lines: List<LogLine>)

/** Read-only view of the centralized logs. */
interface LogSearch {
  suspend fun search(query: LogQuery): LogResult
}
