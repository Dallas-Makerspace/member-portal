package org.dallasmakerspace.askai

import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.dallasmakerspace.askai.bedrock.IEmbeddingClient
import org.dallasmakerspace.askai.db.AskAiCacheRepository
import org.dallasmakerspace.askai.openrouter.IOpenRouterClient
import org.dallasmakerspace.askai.search.RelevanceScorer
import org.dallasmakerspace.askai.search.SearchSourceRouter
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.MemberRepository
import org.dallasmakerspace.models.AskAiCacheEntry
import org.dallasmakerspace.models.AskAiMetadata
import org.dallasmakerspace.models.AskAiResponse
import org.dallasmakerspace.models.AskAiStreamResult
import org.dallasmakerspace.models.SourceLink
import org.dallasmakerspace.models.TokenUsage

/**
 * Configuration for AskAI service parameters.
 *
 * @param topQuestionsLimit Number of recent questions to use for semantic matching
 * @param resultsPerSource Maximum search results to fetch per source
 * @param maxTotalResults Maximum total search results across all sources
 * @param searchTimeoutSeconds Timeout for search operations
 * @param llmRetryAttempts Number of retry attempts for LLM calls
 * @param earlyStopThreshold Stop searching if we have this many quality results
 */
data class AskAiConfig(
    val topQuestionsLimit: Int = 20,
    val resultsPerSource: Int = 5,
    val maxTotalResults: Int = 15,
    val searchTimeoutSeconds: Long = 5,
    val llmRetryAttempts: Int = 3,
    val earlyStopThreshold: Int = 10,
    val monthlyBudgetUsd: Double = 12.0,
)

/**
 * Metrics tracked for each AskAI request.
 *
 * @param requestId Unique identifier for this request
 * @param startTime When the request started
 * @param cacheHit Whether the request was served from cache
 * @param cacheType Type of cache hit ("exact" or "semantic")
 * @param searchCount Number of search queries executed
 * @param totalCost Estimated cost in USD
 * @param responseTimeMs Total response time in milliseconds
 */
data class RequestMetrics(
    val requestId: String = UUID.randomUUID().toString(),
    val startTime: Instant = Instant.now(),
    var cacheHit: Boolean = false,
    var cacheType: String? = null,
    var searchCount: Int = 0,
    var totalCost: Double = 0.0,
    var responseTimeMs: Long = 0,
)

/**
 * Main service for the Ask DMS AI feature. Orchestrates the two-LLM-call flow:
 * 1. Check cache for exact match
 * 2. LLM #1: Classify question and generate search queries (or find semantic match)
 * 3. Execute search queries across all sources
 * 4. LLM #2: Generate answer from search results
 * 5. Cache the result for future use
 */
private const val EMBEDDING_SIMILARITY_THRESHOLD = 0.88
private const val MS_PER_SECOND = 1000L
private const val PER_SOURCE_DEDUP_CAP = 5
private const val EMBEDDING_CACHE_RECENT_LIMIT = 100

@Suppress("TooManyFunctions", "LongParameterList")
@Singleton
class AskAiService
@Inject
constructor(
    private val openRouterClient: IOpenRouterClient,
    private val searchSourceRouter: SearchSourceRouter,
    private val cacheRepository: AskAiCacheRepository,
    private val memberRepository: MemberRepository,
    private val piiMasker: PiiMasker,
    private val config: AskAiConfig,
    private val embeddingClient: IEmbeddingClient,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  /**
   * Process a user question and return an AI-generated answer.
   *
   * @param question The user's question
   * @param memberId Optional member ID for tracking (currently unused)
   * @param username Username of the member asking the question (required)
   * @param forceRefresh If true, bypass cache and generate a fresh answer (dev mode only)
   * @return AskAiResponse containing the answer, sources, and cache status
   * @throws IllegalArgumentException if username is not provided
   */
  @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
  suspend fun ask(
      question: String,
      memberId: Int? = null,
      username: String? = null,
      forceRefresh: Boolean = false,
  ): AskAiResponse {
    val metrics = RequestMetrics()
    log.info("Processing question (requestId=${metrics.requestId}): $question")

    requireNotNull(username) { "Username is required to ask a question" }

    // Look up profile_id from username upfront
    val profileId =
        try {
          memberRepository.getMemberOrInsert(username, enabled = null).id
        } catch (e: Exception) {
          log.error("Failed to look up profile for username: $username", e)
          throw IllegalArgumentException("Could not find profile for username: $username", e)
        }

    // Step 1: Check cache for exact match (fast path) - skip if forceRefresh is true
    if (!forceRefresh) {
      val exactMatch = cacheRepository.findByExactQuestion(question)
      if (exactMatch != null) {
        metrics.cacheHit = true
        metrics.cacheType = "exact"
        metrics.responseTimeMs = Duration.between(metrics.startTime, Instant.now()).toMillis()
        log.info(
            "Found exact cache match for question (id=${exactMatch.id}, requestId=${metrics.requestId}, responseTime=${metrics.responseTimeMs}ms)")
        cacheRepository.incrementHitCount(exactMatch.id)
        val (rewrittenAnswer, orderedSources) =
            reorderSourcesByCitation(exactMatch.answerText, exactMatch.sources)
        return AskAiResponse(
            answer = rewrittenAnswer,
            sources = orderedSources,
            fromCache = true,
            slug = exactMatch.slug,
            cacheId = exactMatch.id,
            askedByUsername = exactMatch.askedByUsername,
            metadata = exactMatch.metadata,
        )
      }
    } else {
      log.info("Force refresh requested - bypassing cache lookup")
    }

    // Step 2: LLM #1 - Classify question and generate search queries
    // Mask PII in the question before sending to LLM
    val maskedQuestion = piiMasker.mask(question)

    // Embedding-based semantic cache match (skip on refresh — result would be discarded anyway)
    val embeddingMatch = if (!forceRefresh) findEmbeddingCacheMatch(maskedQuestion) else null
    if (embeddingMatch != null) {
      metrics.cacheHit = true
      metrics.cacheType = "embedding"
      metrics.responseTimeMs = Duration.between(metrics.startTime, Instant.now()).toMillis()
      log.info(
          "Found embedding cache match (id=${embeddingMatch.id}, requestId=${metrics.requestId})")
      cacheRepository.incrementHitCount(embeddingMatch.id)
      val (rewrittenAnswer, orderedSources) =
          reorderSourcesByCitation(embeddingMatch.answerText, embeddingMatch.sources)
      return AskAiResponse(
          answer = rewrittenAnswer,
          sources = orderedSources,
          fromCache = true,
          slug = embeddingMatch.slug,
          cacheId = embeddingMatch.id,
          askedByUsername = embeddingMatch.askedByUsername,
          metadata = embeddingMatch.metadata,
      )
    }

    // Budget guard: check monthly spend before any LLM calls (cache hits bypass this)
    if (config.monthlyBudgetUsd > 0.0) {
      val monthlySpend = cacheRepository.getMonthlySpendUsd()
      if (monthlySpend >= config.monthlyBudgetUsd) {
        log.warn(
            "Monthly LLM budget exceeded (spent=\$${String.format("%.2f", monthlySpend)}, " +
                "budget=\$${String.format("%.2f", config.monthlyBudgetUsd)})")
        return AskAiResponse(
            answer =
                "The monthly Ask DMS AI budget has been reached. Please try again next month or " +
                    "post your question in the [Ask Dallas Makerspace]" +
                    "(https://talk.dallasmakerspace.org/c/ask-dallas-makerspace/82) forum.",
            sources = emptyList(),
            fromCache = false,
        )
      }
    }

    // Embeddings now handle cache matching; always pass emptyList() so classify focuses on
    // generating search queries rather than semantic matching.
    val maskedCachedQuestions = emptyList<AskAiCacheEntry>()

    val classificationLlmResult =
        retryWithBackoff(config.llmRetryAttempts) {
          openRouterClient.classify(maskedQuestion, maskedCachedQuestions)
        }
    val classificationResult = classificationLlmResult.result

    // Check if LLM found a semantic match in cached questions - skip if forceRefresh is true
    if (!forceRefresh) {
      val matchedCacheId = classificationResult.matchedCacheId
      if (matchedCacheId != null) {
        val cachedEntry = cacheRepository.getById(matchedCacheId)
        if (cachedEntry != null) {
          metrics.cacheHit = true
          metrics.cacheType = "semantic"
          metrics.totalCost = calculateEstimatedCost(classificationLlmResult.usage, null)
          metrics.responseTimeMs = Duration.between(metrics.startTime, Instant.now()).toMillis()
          log.info(
              "LLM found semantic cache match (id=${cachedEntry.id}, requestId=${metrics.requestId}, cost=\$${String.format("%.4f", metrics.totalCost)}, responseTime=${metrics.responseTimeMs}ms)")
          cacheRepository.incrementHitCount(cachedEntry.id)
          val (rewrittenAnswer, orderedSources) =
              reorderSourcesByCitation(cachedEntry.answerText, cachedEntry.sources)
          return AskAiResponse(
              answer = rewrittenAnswer,
              sources = orderedSources,
              fromCache = true,
              slug = cachedEntry.slug,
              cacheId = cachedEntry.id,
              askedByUsername = cachedEntry.askedByUsername,
              metadata = cachedEntry.metadata,
          )
        }
      }
    }

    // Step 3: Execute search queries across all sources
    val hypotheticalAnswer =
        try {
          openRouterClient.generateHypotheticalAnswer(maskedQuestion).result
        } catch (e: Exception) {
          log.debug("HyDE generation failed (non-fatal): ${e.message}")
          null
        }

    val searchQueries =
        classificationResult.searchQueries
            .ifEmpty { listOf(question) }
            .let { queries ->
              if (hypotheticalAnswer != null) queries + hypotheticalAnswer else queries
            }
    metrics.searchCount = searchQueries.size
    log.info("Executing ${searchQueries.size} search queries: $searchQueries")

    // Circuit breaker: execute searches with timeout and graceful degradation
    val allSearchResults =
        searchQueries
            .flatMap { query ->
              try {
                withTimeout(config.searchTimeoutSeconds * MS_PER_SECOND) {
                  searchSourceRouter.searchAll(query, limitPerSource = config.resultsPerSource)
                }
              } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("Search failed for query: $query", e)
                // Continue with remaining queries - graceful degradation
                emptyList()
              }
            }
            .distinctBy { it.url } // Deduplicate by URL

    val deduplicatedResults =
        allSearchResults
            .groupBy { it.source }
            .values
            .flatMap { it.take(PER_SOURCE_DEDUP_CAP) }
            .take(config.maxTotalResults)

    val queryTerms = RelevanceScorer.extractQueryTerms(searchQueries)
    val rankedResults =
        deduplicatedResults
            .map { it.copy(relevanceScore = RelevanceScorer.score(it, queryTerms, question)) }
            .sortedByDescending { it.relevanceScore ?: 0.0 }

    log.debug("Found ${rankedResults.size} unique search results")

    val (extractableResults, additionalResources) =
        rankedResults.partition { result ->
          result.hasExtractableContent && (result.relevanceScore ?: 0.0) > 0.0
        }
    log.debug(
        "Extractable: ${extractableResults.size}, Additional resources: ${additionalResources.size}")

    // If no search results found, return canned response directing user to Talk forum
    if (extractableResults.isEmpty() && additionalResources.isEmpty()) {
      metrics.totalCost = calculateEstimatedCost(classificationLlmResult.usage, null)
      metrics.responseTimeMs = Duration.between(metrics.startTime, Instant.now()).toMillis()
      log.info(
          "No search results found, returning canned response (requestId=${metrics.requestId}, searchQueries=$searchQueries, cost=\$${String.format("%.4f", metrics.totalCost)}, responseTime=${metrics.responseTimeMs}ms)")

      val searchedSourcesList =
          rankedResults.map { it.source }.distinct().sorted().joinToString(", ")
      val searchedSources =
          if (searchedSourcesList.isNotEmpty()) "I searched: $searchedSourcesList"
          else "No search sources were available"

      val cannedAnswer =
          """
          I couldn't find any relevant information to answer your question.

          **What I searched for:**
          ${searchQueries.joinToString("\n") { "* \"$it\"" }}

          **Where I looked:**
          $searchedSources

          I recommend posting your question in the [Ask Dallas Makerspace](https://talk.dallasmakerspace.org/c/ask-dallas-makerspace/82) category on our Talk forum, where community members and staff can help you directly.

          When posting, please include:
          * Any specific details about your question
          * What you've already tried (if applicable)
          * Any relevant photos or links

          Our community is very helpful and you'll usually get a response within a few hours!
          """
              .trimIndent()

      return AskAiResponse(
          answer = cannedAnswer,
          sources = emptyList(),
          fromCache = false,
          slug = null,
          cacheId = null,
          askedByUsername = username,
          metadata =
              AskAiMetadata(
                  searchQueries = searchQueries,
                  sourceBreakdown = emptyMap(),
                  classificationTokens = classificationLlmResult.usage,
                  answerTokens = null,
                  estimatedCostUsd = metrics.totalCost,
                  modelName = classificationLlmResult.modelName,
              ),
          additionalResources = emptyList(),
      )
    }

    // Step 4: LLM #2 - Generate answer from search results
    // Only send extractable content to the LLM, with PII masked
    val maskedSearchResults =
        extractableResults.map { result ->
          result.copy(
              title = piiMasker.mask(result.title),
              snippet = piiMasker.mask(result.snippet),
          )
        }

    val answerLlmResult =
        retryWithBackoff(config.llmRetryAttempts) {
          openRouterClient.generateAnswer(maskedQuestion, maskedSearchResults)
        }
    val answer = answerLlmResult.result

    // Step 5: Cache the result
    // Sources used in answer generation (extractable content only)
    val sources = extractableResults.map { SourceLink(it.title, it.url, it.source) }
    val (rewrittenAnswer, orderedSources) = reorderSourcesByCitation(answer, sources)
    // Additional resources not used in generation (PDFs, attachments)
    val additionalResourceLinks =
        additionalResources.map { SourceLink(it.title, it.url, it.source) }

    // Build metadata with search queries, token usage, and cost estimate
    val estimatedCost = calculateEstimatedCost(classificationLlmResult.usage, answerLlmResult.usage)
    val sourceBreakdown = rankedResults.groupingBy { it.source }.eachCount()
    log.debug("Source breakdown: $sourceBreakdown")

    // Update metrics
    metrics.totalCost = estimatedCost
    metrics.responseTimeMs = Duration.between(metrics.startTime, Instant.now()).toMillis()

    val metadata =
        AskAiMetadata(
            searchQueries = searchQueries,
            sourceBreakdown = sourceBreakdown,
            classificationTokens = classificationLlmResult.usage,
            answerTokens = answerLlmResult.usage,
            estimatedCostUsd = estimatedCost,
            modelName = answerLlmResult.modelName,
        )

    var slug: String? = null
    var cacheId: Int? = null

    try {
      val allSources = orderedSources + additionalResourceLinks
      // On refresh: update existing entry in-place so the slug stays stable and subsequent exact
      // cache lookups return the fresh answer instead of the old one
      val existingEntry = if (forceRefresh) cacheRepository.findByExactQuestion(question) else null
      val cached =
          if (existingEntry != null) {
            log.info(
                "Refreshing existing cache entry (id=${existingEntry.id}, slug=${existingEntry.slug})")
            cacheRepository.updateExisting(existingEntry.id, rewrittenAnswer, allSources, metadata)
          } else {
            cacheRepository.save(question, rewrittenAnswer, allSources, profileId, metadata)
          }
      slug = cached.slug
      cacheId = cached.id
      log.info(
          "Cached answer for question (slug=$slug, cacheId=$cacheId, profileId=$profileId, refresh=$forceRefresh)")

      try {
        val vector = embeddingClient.embed(question)
        if (vector != null) {
          cacheRepository.saveEmbedding(cached.id, vector)
        }
      } catch (e: Exception) {
        log.warn("Failed to save embedding for cache entry ${cached.id} (non-fatal)", e)
      }
    } catch (e: Exception) {
      log.warn("Failed to cache answer", e)
    }

    log.info(
        "Generated fresh answer (requestId=${metrics.requestId}, searchQueries=${metrics.searchCount}, sources=${sourceBreakdown.size}, cost=\$${String.format("%.4f", metrics.totalCost)}, responseTime=${metrics.responseTimeMs}ms)")

    return AskAiResponse(
        answer = rewrittenAnswer,
        sources = orderedSources,
        fromCache = false,
        slug = slug,
        cacheId = cacheId,
        askedByUsername = username,
        metadata = metadata,
        additionalResources = additionalResourceLinks,
    )
  }

  /**
   * Process a user question and stream the AI-generated answer token-by-token. Mirrors the full
   * ask() pipeline including cache lookups, HyDE, re-ranking, and cache writes.
   *
   * @param question The user's question
   * @param username Username of the member asking the question (required)
   * @param forceRefresh If true, bypass cache and regenerate a fresh answer
   * @param onToken Callback invoked for each text token received from the LLM
   * @return Ordered list of source links cited in the answer
   */
  @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "MaxLineLength")
  suspend fun askStream(
      question: String,
      username: String,
      forceRefresh: Boolean = false,
      onToken: suspend (String) -> Unit,
  ): AskAiStreamResult {
    val metrics = RequestMetrics()
    log.info("Stream: processing question (requestId=${metrics.requestId}): $question")

    val profileId =
        try {
          memberRepository.getMemberOrInsert(username, enabled = null).id
        } catch (e: Exception) {
          log.error("Failed to look up profile for username: $username", e)
          throw IllegalArgumentException("Could not find profile for username: $username", e)
        }
    log.info("Stream: profile resolved (profileId=$profileId, requestId=${metrics.requestId})")

    // Step 1: exact cache hit — stream cached answer directly
    if (!forceRefresh) {
      val exactMatch = cacheRepository.findByExactQuestion(question)
      if (exactMatch != null) {
        log.info("Stream: exact cache hit (id=${exactMatch.id}, requestId=${metrics.requestId})")
        cacheRepository.incrementHitCount(exactMatch.id)
        val (rewrittenAnswer, orderedSources) =
            reorderSourcesByCitation(exactMatch.answerText, exactMatch.sources)
        onToken(rewrittenAnswer)
        return AskAiStreamResult(
            sources = orderedSources,
            cacheId = exactMatch.id,
            slug = exactMatch.slug,
            fromCache = true,
            metadata = exactMatch.metadata,
        )
      }
    }
    log.info(
        "Stream: no exact cache hit, checking embedding match (requestId=${metrics.requestId})")

    val maskedQuestion = piiMasker.mask(question)

    // Embedding cache hit — skip on refresh
    val embeddingMatch = if (!forceRefresh) findEmbeddingCacheMatch(maskedQuestion) else null
    if (embeddingMatch != null) {
      log.info(
          "Stream: embedding cache hit (id=${embeddingMatch.id}, requestId=${metrics.requestId})")
      cacheRepository.incrementHitCount(embeddingMatch.id)
      val (rewrittenAnswer, orderedSources) =
          reorderSourcesByCitation(embeddingMatch.answerText, embeddingMatch.sources)
      onToken(rewrittenAnswer)
      return AskAiStreamResult(
          sources = orderedSources,
          cacheId = embeddingMatch.id,
          slug = embeddingMatch.slug,
          fromCache = true,
          metadata = embeddingMatch.metadata,
      )
    }

    log.info("Stream: no embedding cache hit, checking budget (requestId=${metrics.requestId})")

    // Budget guard — cache hits bypass this
    if (config.monthlyBudgetUsd > 0.0) {
      val monthlySpend = cacheRepository.getMonthlySpendUsd()
      if (monthlySpend >= config.monthlyBudgetUsd) {
        log.warn(
            "Monthly LLM budget exceeded (spent=\$${String.format("%.2f", monthlySpend)}, " +
                "budget=\$${String.format("%.2f", config.monthlyBudgetUsd)})")
        onToken(
            "The monthly Ask DMS AI budget has been reached. Please try again next month or " +
                "post your question in the [Ask Dallas Makerspace]" +
                "(https://talk.dallasmakerspace.org/c/ask-dallas-makerspace/82) forum.")
        return AskAiStreamResult(sources = emptyList())
      }
    }

    // LLM #1: classify and generate search queries
    val classificationLlmResult =
        retryWithBackoff(config.llmRetryAttempts) {
          openRouterClient.classify(maskedQuestion, emptyList())
        }
    val classificationResult = classificationLlmResult.result

    // LLM semantic cache hit — skip on refresh
    if (!forceRefresh) {
      val matchedCacheId = classificationResult.matchedCacheId
      if (matchedCacheId != null) {
        val cachedEntry = cacheRepository.getById(matchedCacheId)
        if (cachedEntry != null) {
          log.info(
              "Stream: LLM semantic cache hit (id=${cachedEntry.id}, requestId=${metrics.requestId})")
          cacheRepository.incrementHitCount(cachedEntry.id)
          val (rewrittenAnswer, orderedSources) =
              reorderSourcesByCitation(cachedEntry.answerText, cachedEntry.sources)
          onToken(rewrittenAnswer)
          return AskAiStreamResult(
              sources = orderedSources,
              cacheId = cachedEntry.id,
              slug = cachedEntry.slug,
              fromCache = true,
              metadata = cachedEntry.metadata,
          )
        }
      }
    }

    // HyDE: generate hypothetical answer as extra search query
    val hypotheticalAnswer =
        try {
          openRouterClient.generateHypotheticalAnswer(maskedQuestion).result
        } catch (e: Exception) {
          log.debug("HyDE generation failed (non-fatal): ${e.message}")
          null
        }

    val searchQueries =
        classificationResult.searchQueries
            .ifEmpty { listOf(question) }
            .let { if (hypotheticalAnswer != null) it + hypotheticalAnswer else it }
    metrics.searchCount = searchQueries.size
    log.info("Stream: executing ${searchQueries.size} search queries: $searchQueries")

    // Search
    val allSearchResults =
        searchQueries
            .flatMap { query ->
              try {
                withTimeout(config.searchTimeoutSeconds * MS_PER_SECOND) {
                  searchSourceRouter.searchAll(query, limitPerSource = config.resultsPerSource)
                }
              } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("Stream: search failed for query: $query", e)
                emptyList()
              }
            }
            .distinctBy { it.url }

    val deduplicatedResults =
        allSearchResults
            .groupBy { it.source }
            .values
            .flatMap { it.take(PER_SOURCE_DEDUP_CAP) }
            .take(config.maxTotalResults)

    val queryTerms = RelevanceScorer.extractQueryTerms(searchQueries)
    val rankedResults =
        deduplicatedResults
            .map { it.copy(relevanceScore = RelevanceScorer.score(it, queryTerms, question)) }
            .sortedByDescending { it.relevanceScore ?: 0.0 }

    val (extractableResults, _) =
        rankedResults.partition { result ->
          result.hasExtractableContent && (result.relevanceScore ?: 0.0) > 0.0
        }

    if (extractableResults.isEmpty()) {
      onToken(
          "I couldn't find relevant information for that question. Please try the [Ask Dallas Makerspace](https://talk.dallasmakerspace.org/c/ask-dallas-makerspace/82) forum.")
      return AskAiStreamResult(sources = emptyList())
    }

    // LLM #2: stream answer
    val maskedSearchResults =
        extractableResults.map { result ->
          result.copy(
              title = piiMasker.mask(result.title),
              snippet = piiMasker.mask(result.snippet),
          )
        }

    val answerBuilder = StringBuilder()
    openRouterClient.generateAnswerStream(maskedQuestion, maskedSearchResults) { token ->
      answerBuilder.append(token)
      onToken(token)
    }

    val answer = answerBuilder.toString()
    // For fresh streaming answers the text is already sent, so keep sources in the original
    // relevance-ranked order — the LLM cited [1],[2],[3] in that order, so they already match.
    val orderedSources = extractableResults.map { SourceLink(it.title, it.url, it.source) }

    val estimatedCost = calculateEstimatedCost(classificationLlmResult.usage, null)
    metrics.totalCost = estimatedCost
    metrics.responseTimeMs = Duration.between(metrics.startTime, Instant.now()).toMillis()

    val metadata =
        AskAiMetadata(
            searchQueries = searchQueries,
            sourceBreakdown = rankedResults.groupingBy { it.source }.eachCount(),
            classificationTokens = classificationLlmResult.usage,
            answerTokens = null,
            estimatedCostUsd = estimatedCost,
            modelName = classificationLlmResult.modelName,
        )

    var slug: String? = null
    var cacheId: Int? = null

    try {
      val existingEntry = if (forceRefresh) cacheRepository.findByExactQuestion(question) else null
      val cached =
          if (existingEntry != null) {
            log.info(
                "Stream refresh: updating existing cache entry (id=${existingEntry.id}, slug=${existingEntry.slug})")
            cacheRepository.updateExisting(existingEntry.id, answer, orderedSources, metadata)
          } else {
            cacheRepository.save(question, answer, orderedSources, profileId, metadata)
          }
      slug = cached.slug
      cacheId = cached.id
      log.info(
          "Stream: cached answer (slug=${cached.slug}, cacheId=${cached.id}, refresh=$forceRefresh)")

      try {
        val vector = embeddingClient.embed(question)
        if (vector != null) cacheRepository.saveEmbedding(cached.id, vector)
      } catch (e: Exception) {
        log.warn("Failed to save embedding for cache entry ${cached.id} (non-fatal)", e)
      }
    } catch (e: Exception) {
      log.warn("Stream: failed to cache answer", e)
    }

    log.info(
        "Stream: generated fresh answer (requestId=${metrics.requestId}, searchQueries=${metrics.searchCount}, cost=\$${String.format("%.4f", metrics.totalCost)}, responseTime=${metrics.responseTimeMs}ms)")

    return AskAiStreamResult(
        sources = orderedSources,
        cacheId = cacheId,
        slug = slug,
        responseTimeMs = metrics.responseTimeMs,
        metadata = metadata,
    )
  }

  /**
   * Record user feedback for a cached answer.
   *
   * @param cacheId The cache entry ID
   * @param memberId The member providing feedback
   * @param isHelpful Whether the answer was helpful
   */
  suspend fun recordFeedback(cacheId: Int, memberId: Int, isHelpful: Boolean) {
    cacheRepository.recordFeedback(cacheId, memberId, isHelpful)
    log.info("Recorded feedback for cache entry $cacheId: helpful=$isHelpful")
  }

  /** Get the list of registered search sources. */
  fun getRegisteredSources(): List<String> = searchSourceRouter.getRegisteredSources()

  /**
   * Get recent cached questions without negative feedback for display on the initial page.
   *
   * @param limit Maximum number of questions to return
   * @return List of recent questions without negative feedback
   */
  suspend fun getTopQuestions(limit: Int = 10): List<TopQuestion> {
    return cacheRepository.getRecentQuestionsWithoutNegativeFeedback(limit).map { entry ->
      TopQuestion(
          id = entry.id,
          slug = entry.slug,
          question = entry.questionText,
          hitCount = entry.hitCount,
      )
    }
  }

  /**
   * Get a cached answer by its slug.
   *
   * @param slug The URL-friendly slug
   * @return AskAiResponse if found, null otherwise
   */
  suspend fun getBySlug(slug: String): AskAiResponse? {
    val entry = cacheRepository.findBySlug(slug) ?: return null
    cacheRepository.incrementHitCount(entry.id)
    val (rewrittenAnswer, orderedSources) =
        reorderSourcesByCitation(entry.answerText, entry.sources)
    return AskAiResponse(
        answer = rewrittenAnswer,
        sources = orderedSources,
        fromCache = true,
        slug = entry.slug,
        cacheId = entry.id,
        questionText = entry.questionText,
        askedByUsername = entry.askedByUsername,
        metadata = entry.metadata,
    )
  }

  private fun calculateEstimatedCost(
      classificationUsage: TokenUsage?,
      answerUsage: TokenUsage?,
  ): Double {
    val inputTokens = (classificationUsage?.promptTokens ?: 0) + (answerUsage?.promptTokens ?: 0)
    val outputTokens =
        (classificationUsage?.completionTokens ?: 0) + (answerUsage?.completionTokens ?: 0)
    return openRouterClient.estimateCost(inputTokens, outputTokens)
  }

  /**
   * Reorder sources so that cited sources appear first (in order of first citation in answer),
   * followed by uncited sources.
   *
   * Citations in the answer can be in multiple formats due to LLM variance:
   * - Square brackets: [1], [2], [3]
   * - Guillemets: «1», «2», «3» Citations are 1-indexed, while the sources list is 0-indexed.
   */
  /**
   * Reorders sources so cited ones appear first (in citation order), then rewrites the citation
   * numbers in the answer text to match the new positions. Returns both the rewritten answer and
   * the reordered sources so callers can use a consistent pair.
   *
   * Without rewriting the answer text, [3] in the text would point to whatever source ends up at
   * position 3 after reordering — which is the wrong source.
   */
  private fun reorderSourcesByCitation(
      answer: String,
      sources: List<SourceLink>,
  ): Pair<String, List<SourceLink>> {
    val citationPatterns =
        listOf(
            Regex("""\[(\d+)\]"""), // Square brackets: [1]
            Regex("""«(\d+)»"""), // Guillemets: «1»
        )

    val allMatches =
        citationPatterns
            .flatMap { pattern -> pattern.findAll(answer).toList() }
            .sortedBy { it.range.first }

    val citedIndices =
        allMatches
            .map { it.groupValues[1].toInt() - 1 }
            .filter { it in sources.indices }
            .distinct()
            .toList()

    if (citedIndices.isEmpty()) {
      return Pair(answer, sources)
    }

    val citedSources = citedIndices.map { sources[it] }
    val uncitedSources = sources.filterIndexed { index, _ -> index !in citedIndices }
    val reorderedSources = citedSources + uncitedSources

    // Build old 1-based → new 1-based mapping, then rewrite all citation markers in one pass.
    val oldToNew =
        citedIndices.mapIndexed { newIdx, oldIdx -> (oldIdx + 1) to (newIdx + 1) }.toMap()
    var rewrittenAnswer = answer
    for (pattern in citationPatterns) {
      rewrittenAnswer =
          pattern.replace(rewrittenAnswer) { match ->
            val oldNum = match.groupValues[1].toInt()
            val newNum = oldToNew[oldNum] ?: return@replace match.value
            match.value.replace(oldNum.toString(), newNum.toString())
          }
    }

    return Pair(rewrittenAnswer, reorderedSources)
  }

  /**
   * Retry a suspending block with exponential backoff.
   *
   * @param maxAttempts Maximum number of retry attempts
   * @param block The suspending function to retry
   * @return Result of the block
   * @throws Exception if all attempts fail
   */
  private suspend fun <T> retryWithBackoff(maxAttempts: Int = 3, block: suspend () -> T): T {
    repeat(maxAttempts - 1) { attempt ->
      try {
        return block()
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        val delayMs = 100L * (1 shl attempt) // Exponential backoff: 100ms, 200ms, 400ms...
        log.warn("Attempt ${attempt + 1} failed, retrying in ${delayMs}ms", e)
        delay(delayMs)
      }
    }
    return block() // Last attempt without catch
  }

  @Suppress("ReturnCount", "TooGenericExceptionCaught")
  private suspend fun findEmbeddingCacheMatch(question: String): AskAiCacheEntry? {
    val questionVector =
        try {
          embeddingClient.embed(question)
        } catch (e: Exception) {
          log.warn("Embedding generation failed, skipping semantic cache match", e)
          null
        } ?: return null
    val candidates = cacheRepository.getRecentWithEmbeddings(EMBEDDING_CACHE_RECENT_LIMIT)
    if (candidates.isEmpty()) return null

    var bestId = -1
    var bestScore = 0.0

    for ((id, vector) in candidates) {
      val similarity = cosineSimilarity(questionVector, vector)
      if (similarity > bestScore) {
        bestScore = similarity
        bestId = id
      }
    }

    if (bestScore < EMBEDDING_SIMILARITY_THRESHOLD) return null
    return cacheRepository.getById(bestId)
  }

  private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
    // Titan vectors are normalized, so dot product == cosine similarity
    var dot = 0.0
    val len = minOf(a.size, b.size)
    for (i in 0 until len) dot += a[i] * b[i]
    return dot
  }
}

/** Summary of a cached question for display in the UI. */
@kotlinx.serialization.Serializable
data class TopQuestion(
    val id: Int,
    val slug: String,
    val question: String,
    val hitCount: Int,
)
