package org.dallasmakerspace.askai.db

import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.db.ProfileDAO
import org.dallasmakerspace.members.db.ProfileTable
import org.dallasmakerspace.models.AskAiCacheEntry
import org.dallasmakerspace.models.SourceLink
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.sum

/**
 * Repository for managing the Ask AI cache. Provides data access layer for caching question-answer
 * pairs.
 */
@Suppress("TooManyFunctions")
class AskAiCacheRepository @Inject constructor(loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  /**
   * Save a new question-answer pair to the cache.
   *
   * @param question The user's question
   * @param answer The generated answer
   * @param sources List of source links used to generate the answer
   * @param profileId The profile ID of the member who asked the question
   * @param metadata Optional metadata about the AI response generation process
   * @return The created cache entry
   */
  suspend fun save(
      question: String,
      answer: String,
      sources: List<SourceLink>,
      profileId: Int,
      metadata: org.dallasmakerspace.models.AskAiMetadata? = null
  ): AskAiCacheEntry = suspendTransaction {
    val hash = hashQuestion(question)
    val sourcesJson = json.encodeToString(sources)
    val generatedSlug = generateUniqueSlug(question)
    val metadataJson = metadata?.let { json.encodeToString(it) }
    val totalCost = metadata?.estimatedCostUsd?.let { java.math.BigDecimal.valueOf(it) }

    val dao =
        AskAiCacheDAO.new {
          slug = generatedSlug
          questionHash = hash
          questionText = question
          answerText = answer
          sourceLinks = sourcesJson
          askedByProfileId = profileId
          hitCount = 0
          this.metadata = metadataJson
          totalCostUsd = totalCost
        }

    daoToModel(dao)
  }

  /**
   * Find a cached answer by exact question hash match.
   *
   * @param question The question to look up
   * @return The cached entry if found, null otherwise
   */
  suspend fun findByExactQuestion(question: String): AskAiCacheEntry? = suspendTransaction {
    val hash = hashQuestion(question)
    AskAiCacheDAO.find { AskAiCacheTable.questionHash eq hash }
        .firstOrNull()
        ?.let { daoToModel(it) }
  }

  /**
   * Get a cached entry by its ID.
   *
   * @param id The cache entry ID
   * @return The cached entry if found, null otherwise
   */
  suspend fun getById(id: Int): AskAiCacheEntry? = suspendTransaction {
    AskAiCacheDAO.findById(id)?.let { daoToModel(it) }
  }

  /**
   * Find a cached entry by its slug.
   *
   * @param slug The URL-friendly slug
   * @return The cached entry if found, null otherwise
   */
  suspend fun findBySlug(slug: String): AskAiCacheEntry? = suspendTransaction {
    AskAiCacheDAO.find { AskAiCacheTable.slug eq slug }.firstOrNull()?.let { daoToModel(it) }
  }

  /**
   * Get the top cached questions ordered by hit count (most popular first).
   *
   * @param limit Maximum number of entries to return
   * @return List of cached entries
   */
  suspend fun getTopQuestions(limit: Int = 20): List<AskAiCacheEntry> = suspendTransaction {
    AskAiCacheDAO.all().orderBy(AskAiCacheTable.hitCount to SortOrder.DESC).limit(limit).map {
      daoToModel(it)
    }
  }

  /**
   * Get recent cached questions ordered by creation time (newest first).
   *
   * @param limit Maximum number of entries to return
   * @return List of cached entries
   */
  suspend fun getRecentQuestions(limit: Int = 20): List<AskAiCacheEntry> = suspendTransaction {
    AskAiCacheDAO.all().orderBy(AskAiCacheTable.createdAt to SortOrder.DESC).limit(limit).map {
      daoToModel(it)
    }
  }

  /**
   * Get recent cached questions without negative feedback, ordered by creation time (newest first).
   * Excludes questions that have any feedback where is_helpful = false.
   *
   * @param limit Maximum number of entries to return
   * @return List of cached entries without negative feedback
   */
  suspend fun getRecentQuestionsWithoutNegativeFeedback(limit: Int = 20): List<AskAiCacheEntry> =
      suspendTransaction {
        // Get all cache entries with their feedback
        val cacheEntriesWithoutNegativeFeedback =
            AskAiCacheDAO.all()
                .orderBy(AskAiCacheTable.createdAt to SortOrder.DESC)
                .filter { cacheDao ->
                  // Get all feedback for this cache entry
                  val feedbacks =
                      AskAiFeedbackDAO.find { AskAiFeedbackTable.cacheId eq cacheDao.id }
                  // Include only if there is no negative feedback
                  feedbacks.none { !it.isHelpful }
                }
                .take(limit)
                .map { daoToModel(it) }

        cacheEntriesWithoutNegativeFeedback
      }

  /**
   * Increment the hit count for a cached entry and update last hit timestamp.
   *
   * @param id The cache entry ID
   */
  suspend fun incrementHitCount(id: Int) = suspendTransaction {
    AskAiCacheDAO.findById(id)?.apply {
      hitCount += 1
      lastHitAt = java.time.Instant.now()
    }
  }

  /**
   * Delete old cached entries that haven't been accessed recently.
   *
   * @param olderThanDays Delete entries not accessed for this many days
   * @return Number of entries deleted
   */
  suspend fun deleteStaleEntries(olderThanDays: Int): Int = suspendTransaction {
    val cutoff = java.time.Instant.now().minusSeconds(olderThanDays.toLong() * 24 * 60 * 60)
    var deleted = 0

    AskAiCacheDAO.all().forEach { dao ->
      val lastAccess = dao.lastHitAt ?: dao.createdAt
      if (lastAccess.isBefore(cutoff)) {
        dao.delete()
        deleted++
      }
    }

    log.info("Deleted $deleted stale Ask AI cache entries older than $olderThanDays days")
    deleted
  }

  /**
   * Record feedback for a cached answer.
   *
   * @param cacheId The cache entry ID
   * @param memberId The member providing feedback
   * @param isHelpful Whether the answer was helpful
   */
  suspend fun recordFeedback(cacheId: Int, memberId: Int, isHelpful: Boolean) = suspendTransaction {
    AskAiFeedbackDAO.new {
      this.cacheId = AskAiCacheDAO[cacheId].id
      this.memberId = memberId
      this.isHelpful = isHelpful
    }
  }

  /**
   * Get feedback statistics for a cached entry.
   *
   * @param cacheId The cache entry ID
   * @return Pair of (helpful count, not helpful count)
   */
  suspend fun getFeedbackStats(cacheId: Int): Pair<Int, Int> = suspendTransaction {
    val feedbacks = AskAiFeedbackDAO.find { AskAiFeedbackTable.cacheId eq cacheId }
    val helpful = feedbacks.count { it.isHelpful }
    val notHelpful = feedbacks.count { !it.isHelpful }
    Pair(helpful.toInt(), notHelpful.toInt())
  }

  suspend fun getMonthlySpendUsd(): Double = suspendTransaction {
    val startOfMonth =
        ZonedDateTime.now(CHICAGO_ZONE)
            .with(TemporalAdjusters.firstDayOfMonth())
            .withHour(0)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .withZoneSameInstant(ZoneId.of("UTC"))
            .toInstant()
    val sumExpr = AskAiCacheTable.totalCostUsd.sum()
    AskAiCacheTable.slice(sumExpr)
        .select { AskAiCacheTable.createdAt greaterEq startOfMonth }
        .firstOrNull()
        ?.getOrNull(sumExpr)
        ?.toDouble() ?: 0.0
  }

  suspend fun updateExisting(
      id: Int,
      answer: String,
      sources: List<SourceLink>,
      metadata: org.dallasmakerspace.models.AskAiMetadata?,
  ): AskAiCacheEntry = suspendTransaction {
    val dao = AskAiCacheDAO.findById(id) ?: error("Cache entry $id not found during refresh update")
    val sourcesJson = json.encodeToString(sources)
    val metadataJson = metadata?.let { json.encodeToString(it) }
    val totalCost = metadata?.estimatedCostUsd?.let { java.math.BigDecimal.valueOf(it) }
    dao.apply {
      answerText = answer
      sourceLinks = sourcesJson
      this.metadata = metadataJson
      totalCostUsd = totalCost
      createdAt = java.time.Instant.now()
      embedding = null // cleared; caller saves fresh embedding separately
    }
    daoToModel(dao)
  }

  suspend fun saveEmbedding(id: Int, vector: FloatArray) = suspendTransaction {
    AskAiCacheDAO.findById(id)?.apply { embedding = json.encodeToString(vector.toList()) }
  }

  suspend fun getRecentWithEmbeddings(limit: Int = 100): List<Pair<Int, FloatArray>> =
      suspendTransaction {
        AskAiCacheDAO.all()
            .orderBy(AskAiCacheTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .filter { it.embedding != null }
            .mapNotNull { dao ->
              val vectorJson = dao.embedding ?: return@mapNotNull null
              try {
                val vector = json.decodeFromString<List<Float>>(vectorJson).toFloatArray()
                Pair(dao.id.value, vector)
              } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                log.warn("Failed to parse embedding for cache entry ${dao.id.value}", e)
                null
              }
            }
      }

  private fun hashQuestion(question: String): String {
    val normalized = question.lowercase().trim()
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(normalized.toByteArray(Charsets.UTF_8))
    return hashBytes.joinToString("") { "%02x".format(it) }
  }

  /**
   * Generate a URL-friendly slug from a question. Takes first 5-7 significant words, lowercases,
   * removes special chars, joins with hyphens.
   */
  private fun generateSlug(question: String): String {
    val stopWords =
        setOf(
            "a",
            "an",
            "the",
            "is",
            "are",
            "was",
            "were",
            "do",
            "does",
            "did",
            "can",
            "could",
            "would",
            "should",
            "how",
            "what",
            "when",
            "where",
            "why",
            "who",
            "which",
            "i",
            "my",
            "me",
            "to",
            "for",
            "of",
            "on",
            "in",
            "at",
            "and",
            "or",
            "it",
            "its",
            "this",
            "that")

    val words =
        question
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "") // Remove special chars
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in stopWords }
            .take(6)

    val slug = words.joinToString("-").take(MAX_SLUG_LENGTH)
    return slug.ifBlank { "question-${System.currentTimeMillis()}" }
  }

  /** Generate a unique slug, appending a number if the base slug already exists. */
  private fun generateUniqueSlug(question: String): String {
    val baseSlug = generateSlug(question)

    // Check if base slug exists
    val existing = AskAiCacheDAO.find { AskAiCacheTable.slug eq baseSlug }.firstOrNull()
    if (existing == null) return baseSlug

    // Find unique slug by appending number
    var counter = 2
    while (counter < MAX_SLUG_COLLISION_ATTEMPTS) {
      val candidateSlug = "$baseSlug-$counter"
      val exists = AskAiCacheDAO.find { AskAiCacheTable.slug eq candidateSlug }.firstOrNull()
      if (exists == null) return candidateSlug
      counter++
    }

    // Fallback: append timestamp
    return "$baseSlug-${System.currentTimeMillis()}"
  }

  private fun daoToModel(dao: AskAiCacheDAO): AskAiCacheEntry {
    val sources: List<SourceLink> =
        try {
          json.decodeFromString(dao.sourceLinks)
        } catch (e: Exception) {
          log.warn("Failed to parse source links JSON for cache entry ${dao.id.value}", e)
          emptyList()
        }

    val metadata: org.dallasmakerspace.models.AskAiMetadata? =
        dao.metadata?.let { metadataJson ->
          try {
            json.decodeFromString(metadataJson)
          } catch (e: Exception) {
            log.warn("Failed to parse metadata JSON for cache entry ${dao.id.value}", e)
            null
          }
        }

    // Resolve profile_id to username
    val username =
        ProfileDAO.find { ProfileTable.idColumn eq dao.askedByProfileId }.firstOrNull()?.id?.value

    return AskAiCacheEntry(
        id = dao.id.value,
        slug = dao.slug,
        questionHash = dao.questionHash,
        questionText = dao.questionText,
        answerText = dao.answerText,
        sources = sources,
        askedByUsername = username,
        createdAt = toKotlinInstant(dao.createdAt),
        hitCount = dao.hitCount,
        lastHitAt = dao.lastHitAt?.let { toKotlinInstant(it) },
        metadata = metadata)
  }

  companion object {
    private const val MAX_SLUG_LENGTH = 60
    private const val MAX_SLUG_COLLISION_ATTEMPTS = 100
    private val CHICAGO_ZONE = ZoneId.of("America/Chicago")
  }

  private fun toKotlinInstant(javaInstant: java.time.Instant): kotlinx.datetime.Instant {
    return kotlinx.datetime.Instant.fromEpochSeconds(javaInstant.epochSecond, javaInstant.nano)
  }
}
