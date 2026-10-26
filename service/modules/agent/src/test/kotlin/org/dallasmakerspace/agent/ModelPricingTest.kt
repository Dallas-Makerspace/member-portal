package org.dallasmakerspace.agent

import java.time.LocalDateTime
import kotlin.test.assertEquals
import org.dallasmakerspace.agent.db.TokenCounts
import org.junit.Test

class ModelPricingTest {
  @Test
  fun `haiku counts cache reads and writes at their own prices`() {
    val cost =
        ModelPricing.cost(
            "us.anthropic.claude-haiku-5-5", TokenCounts(input = 1_000, cacheRead = 10_000, cacheWrite = 2_000, output = 500))
    // (1000*0.10 + 500*0.50 + 2000*0.125 + 10000*0.01) / 1e6
    assertEquals(0.0007, cost, 1e-12)
  }

  @Test
  fun `haiku prompts over 100k tokens cost five times as much`() {
    val small = ModelPricing.cost("us.anthropic.claude-haiku-5-5", TokenCounts(input = 100_000))
    val large = ModelPricing.cost("us.anthropic.claude-haiku-5-5", TokenCounts(input = 100_001))
    assertEquals(small * 5, large, 1e-6)
  }

  @Test
  fun `central day and month boundaries are in UTC`() {
    val now = LocalDateTime.of(2026, 11, 1, 3, 0) // 10pm Oct 31 Central (CDT)
    assertEquals(LocalDateTime.of(2026, 10, 31, 5, 0), AgentSpendCap.startOfCentralDay(now))
    assertEquals(LocalDateTime.of(2026, 10, 1, 5, 0), AgentSpendCap.startOfCentralMonth(now))
  }

  @Test
  fun `cleanup runs at the next 3_30 Central`() {
    val now = LocalDateTime.of(2026, 10, 20, 8, 0) // 3:00 CDT
    assertEquals(30 * 60 * 1000L, ChatService.millisUntilNextCleanup(now))
  }
}
