package org.dallasmakerspace.smartwaiver

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.core.AppConfig

internal enum class RequestCategory {
  SEARCH,
  OTHER,
}

private const val DEFAULT_SEARCH_INTERVAL_MS = 700L
private const val DEFAULT_OTHER_INTERVAL_MS = 150L
private const val DEFAULT_RETRY_AFTER_SECONDS = 10L
private const val MAX_RETRY_AFTER_SECONDS = 120L

/** Spaces out requests per category and lets a 429 pause every caller until `retryAfter` passes. */
internal class RequestThrottle(
    private val minIntervalMs: Map<RequestCategory, Long>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
  private val mutex = Mutex()
  private val nextAllowedAt = mutableMapOf<RequestCategory, Long>()
  private var pausedUntil = 0L

  suspend fun acquire(category: RequestCategory) {
    val waitMs =
        mutex.withLock {
          val now = nowMs()
          val start = maxOf(now, pausedUntil, nextAllowedAt[category] ?: 0L)
          nextAllowedAt[category] = start + (minIntervalMs[category] ?: 0L)
          start - now
        }
    if (waitMs > 0) sleep(waitMs)
  }

  suspend fun pauseFor(ms: Long) {
    mutex.withLock { pausedUntil = maxOf(pausedUntil, nowMs() + ms) }
  }

  companion object {
    fun fromConfig(appConfig: AppConfig) =
        RequestThrottle(
            mapOf(
                RequestCategory.SEARCH to
                    appConfig.getLongProperty(
                        "app.smartwaiver.search-interval-ms", DEFAULT_SEARCH_INTERVAL_MS),
                RequestCategory.OTHER to
                    appConfig.getLongProperty(
                        "app.smartwaiver.request-interval-ms", DEFAULT_OTHER_INTERVAL_MS),
            ))
  }
}

/**
 * Seconds to wait after a 429: the `Retry-After` header, else the body's `rate_limit.retryAfter`
 * (`{"type":"rate_limit","rate_limit":{"retryAfter":11}}`), else a default; clamped to a sane range.
 */
internal fun parseRetryAfterSeconds(header: String?, body: String): Long {
  val fromBody =
      runCatching {
            Json.parseToJsonElement(body)
                .jsonObject["rate_limit"]
                ?.jsonObject
                ?.get("retryAfter")
                ?.jsonPrimitive
                ?.int
                ?.toLong()
          }
          .getOrNull()
  val seconds = header?.trim()?.toLongOrNull() ?: fromBody ?: DEFAULT_RETRY_AFTER_SECONDS
  return seconds.coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
}
