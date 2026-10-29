package org.dallasmakerspace.agent.db

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Caps how many parallel reads hold a pooled database connection at once, across all callers. */
internal class BoundedReads(permits: Int) {
  private val semaphore = Semaphore(permits)

  suspend fun <T> run(block: suspend () -> T): T = semaphore.withPermit { block() }
}
