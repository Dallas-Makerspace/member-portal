package org.dallasmakerspace.workflows

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** In-process "something may be runnable" signal. Conflated: a wake while the executor is busy is kept. */
@Singleton
class WorkflowWakeSignal @Inject constructor() {
  private val pending = AtomicBoolean(false)
  private val channel = Channel<Unit>(Channel.CONFLATED)
  private val cancelled = ConcurrentHashMap.newKeySet<Long>()

  fun wake() {
    pending.set(true)
    channel.trySend(Unit)
  }

  /** Suspends until woken (true) or, if [timeoutMillis] is set, until it elapses (false). */
  suspend fun await(timeoutMillis: Long? = null): Boolean {
    val deadline = timeoutMillis?.let { System.nanoTime() + it * 1_000_000 }
    while (true) {
      if (pending.getAndSet(false)) return true
      if (deadline == null) {
        channel.receive()
      } else {
        val remaining = (deadline - System.nanoTime()) / 1_000_000
        if (remaining <= 0 || withTimeoutOrNull(remaining) { channel.receive() } == null) {
          return pending.getAndSet(false)
        }
      }
    }
  }

  fun requestCancel(runId: Long) {
    cancelled.add(runId)
  }

  fun isCancelRequested(runId: Long) = runId in cancelled

  fun clearCancel(runId: Long) {
    cancelled.remove(runId)
  }
}
