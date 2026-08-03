package org.dallasmakerspace.notifications

import kotlin.test.assertEquals
import org.junit.Test

class RetryPolicyTest {

  @Test
  fun `backoffSeconds should start at base for first attempt`() {
    assertEquals(60L, RetryPolicy.backoffSeconds(attempt = 1, baseSeconds = 60, maxSeconds = 21600))
    assertEquals(
        300L, RetryPolicy.backoffSeconds(attempt = 1, baseSeconds = 300, maxSeconds = 21600))
  }

  @Test
  fun `backoffSeconds should double with each attempt`() {
    assertEquals(120L, RetryPolicy.backoffSeconds(2, 60, 21600))
    assertEquals(240L, RetryPolicy.backoffSeconds(3, 60, 21600))
    assertEquals(480L, RetryPolicy.backoffSeconds(4, 60, 21600))
  }

  @Test
  fun `backoffSeconds should cap at max`() {
    assertEquals(21600L, RetryPolicy.backoffSeconds(10, 60, 21600))
    assertEquals(21600L, RetryPolicy.backoffSeconds(30, 60, 21600))
    assertEquals(1000L, RetryPolicy.backoffSeconds(10, 60, 1000))
  }

  @Test
  fun `backoffSeconds should not overflow for large attempts`() {
    assertEquals(21600L, RetryPolicy.backoffSeconds(1_000_000, 60, 21600))
  }
}
