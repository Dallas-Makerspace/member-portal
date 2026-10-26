package org.dallasmakerspace.agent

import org.dallasmakerspace.agent.db.TokenCounts

/** Dollars per million tokens. */
data class ModelPrice(
    val input: Double,
    val output: Double,
    val cacheWrite: Double,
    val cacheRead: Double,
    /** Prompts above this many tokens cost [longPromptMultiplier] times as much. */
    val longPromptThreshold: Int? = null,
    val longPromptMultiplier: Double = 1.0,
)

/** List prices (Oct 2026); unknown models are priced like the most expensive known one. */
object ModelPricing {
  private const val PER_MILLION = 1_000_000.0

  private val HAIKU_5_5 =
      ModelPrice(
          input = 0.10,
          output = 0.50,
          cacheWrite = 0.125,
          cacheRead = 0.01,
          longPromptThreshold = 100_000,
          longPromptMultiplier = 5.0)
  private val SONNET_5_5 = ModelPrice(input = 2.0, output = 10.0, cacheWrite = 2.5, cacheRead = 0.2)
  private val GPT_6_1_SOL =
      ModelPrice(
          input = 2.0,
          output = 10.0,
          cacheWrite = 2.5,
          cacheRead = 0.10,
          longPromptThreshold = 272_000,
          longPromptMultiplier = 2.0)

  fun priceFor(modelId: String): ModelPrice =
      when {
        "claude-haiku-5-5" in modelId -> HAIKU_5_5
        "claude-sonnet-5-5" in modelId -> SONNET_5_5
        "gpt-6.1-sol" in modelId -> GPT_6_1_SOL
        else -> SONNET_5_5
      }

  fun cost(modelId: String, usage: TokenCounts): Double {
    val price = priceFor(modelId)
    val promptTokens = usage.input + usage.cacheRead + usage.cacheWrite
    val multiplier =
        if (price.longPromptThreshold != null && promptTokens > price.longPromptThreshold) {
          price.longPromptMultiplier
        } else 1.0
    return multiplier *
        (usage.input * price.input +
            usage.output * price.output +
            usage.cacheWrite * price.cacheWrite +
            usage.cacheRead * price.cacheRead) / PER_MILLION
  }
}
