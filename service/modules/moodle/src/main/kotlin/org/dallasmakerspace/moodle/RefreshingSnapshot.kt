package org.dallasmakerspace.moodle

import io.ktor.util.logging.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * A cached value that loads inline on first use, refreshes in the background once older than
 * [ttl], and keeps the last good value when a refresh fails.
 */
internal class RefreshingSnapshot<T : Any>(
    private val name: String,
    private val ttl: Duration,
    private val scope: CoroutineScope,
    private val log: Logger,
    private val clock: Clock = Clock.System,
    private val retryAfterFailure: Duration = 1.minutes,
    private val loader: suspend () -> T,
) {
  private val value = AtomicReference<Pair<Instant, T>?>(null)
  private val lastFailure = AtomicReference<Instant?>(null)
  private val isRefreshing = AtomicBoolean(false)
  private val initialLoad = Mutex()

  suspend fun get(): T? {
    val current = value.get()
    if (current == null) {
      val failedAt = lastFailure.get()
      // Don't make every page load wait on a Moodle that's down.
      if (failedAt != null && clock.now() - failedAt < retryAfterFailure) return null
      initialLoad.withLock { if (value.get() == null) refresh() }
      return value.get()?.second
    }
    if (clock.now() - current.first >= ttl && isRefreshing.compareAndSet(false, true)) {
      scope.launch {
        try {
          refresh()
        } finally {
          isRefreshing.set(false)
        }
      }
    }
    return current.second
  }

  @Suppress("TooGenericExceptionCaught")
  suspend fun refresh() {
    try {
      value.set(clock.now() to loader())
      lastFailure.set(null)
    } catch (e: Exception) {
      lastFailure.set(clock.now())
      log.warn("Failed to refresh $name; keeping last snapshot", e)
    }
  }
}
