package org.dallasmakerspace.askai.openrouter

import org.dallasmakerspace.models.AskAiCacheEntry
import org.dallasmakerspace.models.ClassificationResult
import org.dallasmakerspace.models.SearchResult
import org.dallasmakerspace.models.TokenUsage

/** Result wrapper that includes both content and token usage from an LLM call. */
data class LlmResult<T>(
    val result: T,
    val usage: TokenUsage?,
    val modelName: String? = null,
)

/**
 * Interface for the OpenRouter API client. Provides methods for classifying questions and
 * generating answers using LLM models.
 */
interface IOpenRouterClient {

  /**
   * Classify a user question against cached questions and generate search queries if no match
   * found.
   *
   * @param question The user's question
   * @param cachedQuestions List of previously cached questions to match against
   * @return LlmResult containing ClassificationResult and token usage
   */
  suspend fun classify(
      question: String,
      cachedQuestions: List<AskAiCacheEntry>
  ): LlmResult<ClassificationResult>

  /**
   * Generate a human-readable answer based on search results.
   *
   * @param question The user's original question
   * @param searchResults Search results from various sources (Discourse, Confluence, etc.)
   * @return LlmResult containing generated answer text in markdown format and token usage
   */
  suspend fun generateAnswer(question: String, searchResults: List<SearchResult>): LlmResult<String>

  /**
   * Send a raw chat completion request to OpenRouter.
   *
   * @param messages List of chat messages
   * @param jsonMode Whether to request JSON-formatted response
   * @return LlmResult containing the response message content and token usage
   */
  suspend fun chatCompletion(
      messages: List<ChatMessage>,
      jsonMode: Boolean = false
  ): LlmResult<String>

  /**
   * Generate a short hypothetical answer to help with query expansion (HyDE technique). The answer
   * uses domain vocabulary to improve keyword search recall.
   */
  suspend fun generateHypotheticalAnswer(question: String): LlmResult<String>

  /** Stream an answer token-by-token using the provided callback. */
  suspend fun generateAnswerStream(
      question: String,
      searchResults: List<SearchResult>,
      onToken: suspend (String) -> Unit,
  )

  /**
   * Estimate the cost in USD for a given number of input and output tokens. Each implementation
   * uses the pricing for its own provider and model.
   */
  fun estimateCost(inputTokens: Int, outputTokens: Int): Double
}
