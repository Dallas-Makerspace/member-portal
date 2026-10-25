package org.dallasmakerspace.bambuddy.notifications

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * In-process "a new deadline may exist" signal. Producers (arm, retarget, offline insert) call
 * [wake]; [BambuNotificationScheduler] sleeps on [await]. Conflated, so bursts collapse into one
 * wake. Separate from the scheduler so producers don't depend on the sweeper they are woken by.
 */
@Singleton
class BambuWakeSignal @Inject constructor() {
  private val channel = Channel<Unit>(Channel.CONFLATED)

  fun wake() {
    channel.trySend(Unit)
  }

  /** Suspends until woken (true) or [timeoutMs] elapses (false); null waits indefinitely. */
  suspend fun await(timeoutMs: Long?): Boolean =
      if (timeoutMs == null) {
        channel.receive()
        true
      } else {
        withTimeoutOrNull(timeoutMs) { channel.receive() } != null
      }
}
