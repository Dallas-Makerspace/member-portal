package org.dallasmakerspace.workflows

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/** Tunables shared by the executor and the service; tests override them. */
@Singleton
class EngineSettings @Inject constructor() {
  /** Clock for backoff timestamps and step contexts (lease timing is the store's clock). */
  @Volatile var clock: Clock = Clock.systemUTC()
  @Volatile var instanceId: String = "wf-" + UUID.randomUUID().toString().take(8)
  @Volatile var leaseSeconds: Int = 30
  /** Heartbeat period while a step runs; null derives it from the lease (a third of it). */
  @Volatile var heartbeatMillis: Long? = null
  @Volatile var workers: Int = 2
  @Volatile var preflightStepTimeoutMillis: Long = 30_000
  /** Sleeps between in-process rollback retries. */
  @Volatile var sleep: suspend (Long) -> Unit = { delay(it) }

  fun heartbeatInterval(): Long = heartbeatMillis ?: (leaseSeconds * 1000L / 3).coerceAtLeast(1)
}
