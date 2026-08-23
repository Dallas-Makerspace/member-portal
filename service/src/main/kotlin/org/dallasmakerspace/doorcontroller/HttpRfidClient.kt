package org.dallasmakerspace.doorcontroller

import io.ktor.client.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.dallasmakerspace.core.DmsHttpEngine
import org.dallasmakerspace.core.LoggerFactory

/**
 * HTTP client for communicating with RFID door controllers via their web interface.
 *
 * @param ip IP address of the controller
 * @param username Web interface username
 * @param password Web interface password
 * @param connectTimeoutSeconds Timeout in seconds for establishing a TCP connection (default: 10)
 * @param requestTimeoutSeconds Timeout in seconds for the full HTTP request/response cycle
 *   (default: 15)
 * @param socketTimeoutSeconds Timeout in seconds for socket reads between data packets
 *   (default: 15)
 * @param loggerFactory Logger factory for creating logger instance
 */
class HttpRfidClient(
    private val ip: String,
    private val username: String,
    private val password: String,
    private val connectTimeoutSeconds: Int = 10,
    private val requestTimeoutSeconds: Int = 15,
    private val socketTimeoutSeconds: Int = 15,
    loggerFactory: LoggerFactory,
) : Closeable {
  private val log = loggerFactory.create(HttpRfidClient::class.java)
  private val httpClient =
      HttpClient(DmsHttpEngine) {
        install(HttpTimeout) {
          connectTimeoutMillis = connectTimeoutSeconds * 1000L
          requestTimeoutMillis = requestTimeoutSeconds * 1000L
          socketTimeoutMillis = socketTimeoutSeconds * 1000L
        }
      }
  private val baseUrl = "http://$ip"

  /** Maximum number of login attempts (includes initial attempt plus retries) */
  private val maxLoginAttempts = 3

  /**
   * Handles timeout exceptions with retry logic.
   *
   * @param e The timeout exception that occurred
   * @param attempt Current attempt number
   * @param operationDescription Description of the operation and exception type for logging
   * @return true if should retry, false if max attempts reached
   */
  private suspend fun handleTimeoutAndRetry(
      e: Exception,
      attempt: Int,
      operationDescription: String
  ): Boolean {
    log.warn("$operationDescription for $ip on attempt $attempt/$maxLoginAttempts: ${e.message}")
    if (attempt < maxLoginAttempts) {
      val delayMs = 1000L * (1 shl (attempt - 1)) // 1s, 2s exponential backoff
      log.info("Retrying operation on $ip in ${delayMs}ms...")
      delay(delayMs)
      return true // Continue retrying
    } else {
      log.error(
          "Operation failed for $ip after $maxLoginAttempts attempts ($operationDescription)", e)
      return false // Max attempts reached
    }
  }

  /**
   * Logs into the door controller web interface with automatic retry on timeout.
   *
   * Retries up to 2 times (3 total attempts) with exponential backoff if timeouts occur.
   * Non-timeout errors (e.g., authentication failures) fail immediately without retry.
   *
   * @return true if login successful
   */
  private suspend fun login(): Boolean =
      withContext(Dispatchers.IO) {
        var attempt = 0

        while (attempt < maxLoginAttempts) {
          attempt++
          try {
            log.debug("Logging into door controller at $ip (attempt $attempt/$maxLoginAttempts)")

            val response: HttpResponse =
                httpClient.submitForm(
                    url = "$baseUrl/ACT_ID_1",
                    formParameters =
                        Parameters.build {
                          append("username", username)
                          append("pwd", password)
                          append("logId", "20101222")
                        },
                )

            val success = response.status.isSuccess()
            if (success) {
              if (attempt > 1) {
                log.info("Successfully logged into $ip after $attempt attempts")
              } else {
                log.debug("Successfully logged into $ip")
              }
            } else {
              log.error("Failed to login to $ip: ${response.status}")
            }
            return@withContext success
          } catch (e: HttpRequestTimeoutException) {
            if (!handleTimeoutAndRetry(e, attempt, "Login timeout")) {
              return@withContext false
            }
          } catch (e: SocketTimeoutException) {
            if (!handleTimeoutAndRetry(e, attempt, "Socket timeout")) {
              return@withContext false
            }
          } catch (e: ConnectTimeoutException) {
            if (!handleTimeoutAndRetry(e, attempt, "Connect timeout")) {
              return@withContext false
            }
          } catch (e: Exception) {
            log.error("Login failed for $ip on attempt $attempt: ${e.message}", e)
            return@withContext false // Don't retry non-timeout errors
          }
        }
        false
      }

  /**
   * Reads recent badge swipe events from the controller web interface.
   *
   * Retries up to 2 times (3 total attempts) with exponential backoff if timeouts occur.
   *
   * @return List of badge swipe events
   */
  suspend fun readRecentSwipes(): List<BadgeSwipeEvent> =
      withContext(Dispatchers.IO) {
        // Login first
        if (!login()) {
          log.error("Cannot read swipes from $ip - login failed")
          return@withContext emptyList()
        }

        // Retry logic for swipes fetch
        var attempt = 0

        while (attempt < maxLoginAttempts) {
          attempt++
          try {
            log.debug("Requesting swipes page from $ip (attempt $attempt/$maxLoginAttempts)")

            // Request swipes page
            val response: HttpResponse =
                httpClient.submitForm(
                    url = "$baseUrl/ACT_ID_21",
                    formParameters = Parameters.build { append("s4", "Swipe") },
                )

            if (!response.status.isSuccess()) {
              log.error("Failed to fetch swipes from $ip: ${response.status}")
              return@withContext emptyList()
            }

            val html = response.bodyAsText()
            log.debug("Received HTML response (${html.length} chars)")

            if (isLoginPage(html)) {
              log.info(
                  "Controller at $ip returned login page — session expired, skipping swipes fetch")
              return@withContext emptyList()
            }

            if (attempt > 1) {
              log.info("Successfully fetched swipes from $ip after $attempt attempts")
            }

            return@withContext parseSwipeEvents(html)
          } catch (e: HttpRequestTimeoutException) {
            if (!handleTimeoutAndRetry(e, attempt, "Swipes fetch timeout")) {
              return@withContext emptyList()
            }
          } catch (e: SocketTimeoutException) {
            if (!handleTimeoutAndRetry(e, attempt, "Swipes fetch socket timeout")) {
              return@withContext emptyList()
            }
          } catch (e: ConnectTimeoutException) {
            if (!handleTimeoutAndRetry(e, attempt, "Swipes fetch connect timeout")) {
              return@withContext emptyList()
            }
          } catch (e: Exception) {
            log.error("Failed to read swipes from $ip on attempt $attempt: ${e.message}", e)
            return@withContext emptyList() // Don't retry non-timeout errors
          }
        }

        emptyList()
      }

  private fun isLoginPage(html: String): Boolean =
      html.contains("action=ACT_ID_1") || (html.contains("UserName:") && html.contains("Password:"))

  /**
   * Parses swipe events from HTML table.
   *
   * Expected format:
   * <tr class=Y><td>517106</td><td>21545540</td><td>&nbsp;</td><td>Allow IN[#3DOOR]</td>
   * <td>2025-11-22 12:08:38</td></tr>
   */
  private fun parseSwipeEvents(html: String): List<BadgeSwipeEvent> {
    val events = mutableListOf<BadgeSwipeEvent>()

    log.debug("Parsing swipe events from HTML")

    // Extract server's current time to calculate clock delta
    // Format: "Page  &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; 2025-11-22 22:28:00</p>"
    val serverTimePattern =
        """Page\s+&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;\s+(.*?)<""".toRegex()

    val serverTimeMatch = serverTimePattern.find(html)
    val timeDeltaMs =
        if (serverTimeMatch != null) {
          val serverTimeStr = serverTimeMatch.groupValues[1].trim()
          log.info("Found server time in pagination: '$serverTimeStr'")

          val serverTime = parseDateTime(serverTimeStr)
          val localTime = System.currentTimeMillis()
          val delta = localTime - serverTime
          val deltaMinutes = delta / 1000 / 60
          val deltaHours = deltaMinutes / 60

          // Format timestamps for readability
          val serverTimeFormatted =
              java.time.Instant.ofEpochMilli(serverTime)
                  .atZone(java.time.ZoneId.of("America/Chicago"))
                  .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
          val localTimeFormatted =
              java.time.Instant.ofEpochMilli(localTime)
                  .atZone(java.time.ZoneId.of("America/Chicago"))
                  .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

          log.info("Time correction calculation:")
          log.info("  Server time: $serverTimeStr → $serverTime ms → $serverTimeFormatted CST")
          log.info("  Local time:  $localTime ms → $localTimeFormatted CST")
          log.info("  Delta: ${delta}ms = ${deltaMinutes} minutes = $deltaHours hours")
          delta
        } else {
          log.warn(
              "Could not find server time in pagination for $ip — timestamps will be inaccurate")
          log.debug(
              "Expected pattern: 'Page  &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; YYYY-MM-DD HH:MM:SS</p>'")
          log.debug("HTML length: ${html.length} chars")
          log.debug(
              "HTML snippet (first 1500 chars): ${html.substring(0, minOf(1500, html.length))}")
          0L
        }

    // Regex to extract table rows (both Y=allowed and N=denied)
    val rowPattern = """<tr\s+class=[YN]>(.*?)</tr>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    val cellPattern = """<td>(.*?)</td>""".toRegex(RegexOption.DOT_MATCHES_ALL)

    val rows = rowPattern.findAll(html)

    for (row in rows) {
      try {
        val cells = cellPattern.findAll(row.value).map { it.groupValues[1].trim() }.toList()

        if (cells.size >= 5) {
          // Parse cells
          val recordId = cells[0]
          val cardNo = cells[1]
          val status = cells[3]
          val dateTime = cells[4]

          // Extract door number from status like "Allow IN[#3DOOR]"
          val doorMatch = """#(\d)DOOR""".toRegex().find(status)
          val doorNumber = doorMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

          // Parse badge number (in comma format) and convert to 10-digit
          val badgeCommaFormat = cardNo.toIntOrNull() ?: 0
          val badge =
              try {
                RfidUtils.commaFormatToTenDigit(badgeCommaFormat)
              } catch (e: Exception) {
                log.debug("Badge conversion failed for $badgeCommaFormat, using as-is")
                badgeCommaFormat
              }

          // Parse timestamp and adjust for server clock delta
          val rawTimestamp = parseDateTime(dateTime)
          val adjustedTimestamp = rawTimestamp + timeDeltaMs

          // Determine event type
          val eventType =
              when {
                status.contains("Allow", ignoreCase = true) -> SwipeEventType.ACCESS_GRANTED
                status.contains("Forbid", ignoreCase = true) -> SwipeEventType.ACCESS_DENIED
                else -> SwipeEventType.UNKNOWN
              }

          events.add(
              BadgeSwipeEvent(
                  controllerName = ip,
                  doorNumber = doorNumber,
                  badge = badge,
                  timestamp = adjustedTimestamp,
                  eventType = eventType,
                  friendlyDoorName = "door_$doorNumber",
                  recordId = recordId.toIntOrNull(),
                  rawStatus = status,
              ))

          log.debug(
              "Parsed event: recordId=$recordId, badge=$badge, door=$doorNumber, " +
                  "status=$status, rawTime=$dateTime ($rawTimestamp), " +
                  "delta=${timeDeltaMs}ms, adjusted=$adjustedTimestamp")
        }
      } catch (e: Exception) {
        log.warn("Failed to parse swipe event row: ${e.message}")
      }
    }

    log.info("Parsed ${events.size} swipe events from $ip")
    return events
  }

  /**
   * Parses datetime string to Unix timestamp in milliseconds.
   *
   * Supports two formats:
   * - "2025-11-22 12:08:38" (YYYY-MM-DD HH:MM:SS)
   * - "4/28/2026 18:27:50" (M/D/YYYY HH:MM:SS)
   */
  private fun parseDateTime(dateTime: String): Long {
    return try {
      log.debug("Parsing datetime: '$dateTime'")
      val parts = dateTime.split(" ")
      log.debug("  Split into ${parts.size} parts: ${parts.joinToString(", ")}")

      if (parts.size == 2) {
        val dateStr = parts[0]
        val timeParts = parts[1].split(":")

        val (year, month, day) =
            if (dateStr.contains('/')) {
              // M/D/YYYY
              val dateParts = dateStr.split("/")
              if (dateParts.size != 3) {
                log.warn("  Invalid slash-delimited date: '$dateStr'")
                return 0L
              }
              Triple(dateParts[2].toInt(), dateParts[0].toInt(), dateParts[1].toInt())
            } else {
              // YYYY-MM-DD
              val dateParts = dateStr.split("-")
              if (dateParts.size != 3) {
                log.warn("  Invalid dash-delimited date: '$dateStr'")
                return 0L
              }
              Triple(dateParts[0].toInt(), dateParts[1].toInt(), dateParts[2].toInt())
            }

        if (timeParts.size != 3) {
          log.warn("  Invalid time part count: ${timeParts.size}")
          return 0L
        }

        val hour = timeParts[0].toInt()
        val minute = timeParts[1].toInt()
        val second = timeParts[2].toInt()

        log.debug(
            "  Parsed: year=$year, month=$month, day=$day, " +
                "hour=$hour, minute=$minute, second=$second")

        val result =
            java.time.LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(java.time.ZoneId.of("America/Chicago"))
                .toInstant()
                .toEpochMilli()

        log.debug("  Result: $result ms")
        result
      } else {
        log.warn("  Invalid split count: ${parts.size} (expected 2)")
        0L
      }
    } catch (e: Exception) {
      log.error("Failed to parse datetime: '$dateTime' - ${e.message}", e)
      0L
    }
  }

  override fun close() {
    httpClient.close()
    log.debug("Closed HTTP client for $ip")
  }
}
