package org.dallasmakerspace.askai.openrouter

import javax.inject.Inject
import kotlinx.coroutines.delay
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.AskAiCacheEntry
import org.dallasmakerspace.models.ClassificationResult
import org.dallasmakerspace.models.SearchResult
import org.dallasmakerspace.models.TokenUsage

/**
 * Mock implementation of IOpenRouterClient for development and testing. Returns canned responses
 * instead of calling the real OpenRouter API.
 */
class OpenRouterClientMock @Inject constructor(loggerFactory: LoggerFactory) : IOpenRouterClient {
  private val log = loggerFactory.create(javaClass)

  override suspend fun classify(
      question: String,
      cachedQuestions: List<AskAiCacheEntry>,
  ): LlmResult<ClassificationResult> {
    log.info("[MOCK] Classifying question: $question")

    // Simple mock logic: check for exact match in cached questions
    val matchingCache =
        cachedQuestions.find {
          it.questionText.lowercase().contains(question.lowercase()) ||
              question.lowercase().contains(it.questionText.lowercase())
        }

    val result =
        if (matchingCache != null) {
          log.info("[MOCK] Found matching cached question: ${matchingCache.id}")
          ClassificationResult(matchedCacheId = matchingCache.id, searchQueries = emptyList())
        } else {
          // Generate simple search queries based on the question
          val queries = generateMockSearchQueries(question)
          log.info("[MOCK] Generated search queries: $queries")
          ClassificationResult(matchedCacheId = null, searchQueries = queries)
        }

    return LlmResult(
        result = result,
        usage = TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150),
        modelName = "mock-model")
  }

  override suspend fun generateAnswer(
      question: String,
      searchResults: List<SearchResult>
  ): LlmResult<String> {
    log.info("[MOCK] Generating answer for: $question with ${searchResults.size} search results")

    val answer =
        if (searchResults.isEmpty()) {
          """
      **I couldn't find specific information about this topic.**

      This is a mock response. In production, the AI would search through Dallas Makerspace's
      Talk forum and Source (Confluence) documentation to find relevant information.

      Please try asking your question on the [DMS Talk forum](https://talk.dallasmakerspace.org).
      """
              .trimIndent()
        } else {
          """
**Here's what I found about your question:**

Based on the search results «1» «2» «3», here's a summary of the relevant information:

${searchResults.take(3).mapIndexed { idx, it -> "- According to «${idx + 1}», from **${it.source}**: ${it.snippet}" }.joinToString("\n\n")}

---
*This is a mock response for development. In production, this would be a real AI-generated summary.*
"""
              .trimIndent()
        }

    return LlmResult(
        result = answer,
        usage = TokenUsage(promptTokens = 500, completionTokens = 200, totalTokens = 700),
        modelName = "mock-model")
  }

  override suspend fun chatCompletion(
      messages: List<ChatMessage>,
      jsonMode: Boolean
  ): LlmResult<String> {
    log.info("[MOCK] Chat completion with ${messages.size} messages, jsonMode=$jsonMode")

    val result =
        if (jsonMode) {
          """{"matchedCacheId": null, "searchQueries": ["mock query"]}"""
        } else {
          "This is a mock response from the OpenRouter client."
        }

    return LlmResult(
        result = result,
        usage = TokenUsage(promptTokens = 50, completionTokens = 25, totalTokens = 75),
        modelName = "mock-model")
  }

  override suspend fun generateHypotheticalAnswer(question: String): LlmResult<String> {
    log.info("[MOCK] Generating hypothetical answer for: $question")
    return LlmResult(
        result =
            "Members can access this by following the standard authorization process at Dallas Makerspace.",
        usage = TokenUsage(promptTokens = 50, completionTokens = 30, totalTokens = 80),
        modelName = "mock-model")
  }

  override suspend fun generateAnswerStream(
      question: String,
      searchResults: List<SearchResult>,
      onToken: suspend (String) -> Unit,
  ) {
    log.info("[MOCK] Streaming answer for: $question")
    val answer = "This is a **mock streaming response** for: $question"
    answer.split(" ").forEach { word ->
      onToken("$word ")
      delay(50)
    }
  }

  override fun estimateCost(inputTokens: Int, outputTokens: Int): Double = 0.0

  private fun generateMockSearchQueries(question: String): List<String> {
    // Extract key terms from the question
    val stopWords =
        setOf(
            "what",
            "where",
            "when",
            "how",
            "why",
            "who",
            "is",
            "are",
            "the",
            "a",
            "an",
            "to",
            "for",
            "of",
            "in",
            "on",
            "at",
            "can",
            "i",
            "do",
            "does",
        )
    val words =
        question
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.length > 2 && it !in stopWords }
            .take(5)

    return if (words.isEmpty()) {
      listOf(question.take(50))
    } else {
      listOf(words.joinToString(" "), words.take(3).joinToString(" "))
    }
  }
}
