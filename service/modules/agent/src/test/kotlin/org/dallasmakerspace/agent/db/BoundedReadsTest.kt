package org.dallasmakerspace.agent.db

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BoundedReadsTest {
  @Test
  fun `no more reads run at once than the limit, and all of them finish`() = runBlocking<Unit> {
    val reads = BoundedReads(4)
    val running = AtomicInteger()
    val highest = AtomicInteger()

    val results =
        coroutineScope {
          (1..20)
              .map { n ->
                async {
                  reads.run {
                    highest.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    delay(10)
                    running.decrementAndGet()
                    n
                  }
                }
              }
              .awaitAll()
        }

    assertEquals((1..20).toList(), results)
    assertTrue(highest.get() in 1..4, "peak was ${highest.get()}")
  }
}
