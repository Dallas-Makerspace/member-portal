package org.dallasmakerspace.askai.search

import org.dallasmakerspace.models.SearchResult

object RelevanceScorer {

  private const val SCORE_OFFICIAL_DOCS = 8.0
  private const val SCORE_TALK_FORUM = 5.0
  private const val SCORE_EVENTS = 4.0
  private const val SCORE_MEETING_NOTES = 2.0
  private const val SCORE_DEFAULT = 3.0
  private const val MEETING_NOTE_PENALTY = 10.0
  private const val TITLE_MATCH_BONUS = 3.0
  private const val SNIPPET_MATCH_BONUS = 1.0
  private const val SCORE_TEMPORAL_SIGNAL_BONUS = 5.0
  private const val SCORE_PROXIMITY_NEAR = 3.0
  private const val SCORE_PROXIMITY_MID = 1.5
  private const val DAY_MS = 24L * 60 * 60 * 1000

  private val TEMPORAL_SIGNAL_REGEX =
      Regex(
          "\\b(next|upcoming|tomorrow|tonight|today|this\\s+(week|weekend|month)|coming\\s+up|" +
              "soon|classes?|workshop|event|schedule|when'?s|when\\s+is)\\b",
          RegexOption.IGNORE_CASE,
      )

  fun containsTemporalSignal(question: String): Boolean =
      TEMPORAL_SIGNAL_REGEX.containsMatchIn(question)

  fun score(
      result: SearchResult,
      queryTerms: List<String>,
      originalQuestion: String = "",
      nowUtcMs: Long = System.currentTimeMillis(),
  ): Double {
    var score = 0.0

    // Base score by source category
    score +=
        when (result.sourceCategory) {
          SearchResult.OFFICIAL_DOCS -> SCORE_OFFICIAL_DOCS
          SearchResult.TALK_FORUM -> SCORE_TALK_FORUM
          SearchResult.EVENTS -> SCORE_EVENTS
          SearchResult.MEETING_NOTES -> SCORE_MEETING_NOTES
          else -> SCORE_DEFAULT
        }

    // Meeting note penalty (stacks with base)
    if (result.sourceCategory == SearchResult.MEETING_NOTES) {
      score -= MEETING_NOTE_PENALTY
    }

    // Title match bonus per matching query term
    val titleLower = result.title.lowercase()
    score += queryTerms.count { titleLower.contains(it.lowercase()) } * TITLE_MATCH_BONUS

    // Snippet match bonus per matching query term
    val snippetLower = result.snippet.lowercase()
    score += queryTerms.count { snippetLower.contains(it.lowercase()) } * SNIPPET_MATCH_BONUS

    // Calendar event bonuses: temporal signal in original question + proximity to now
    if (result.sourceCategory == SearchResult.EVENTS) {
      if (originalQuestion.isNotEmpty() && containsTemporalSignal(originalQuestion)) {
        score += SCORE_TEMPORAL_SIGNAL_BONUS
      }
      result.eventDateUtc?.let { eventMs ->
        val daysAway = kotlin.math.abs(eventMs - nowUtcMs) / DAY_MS
        score +=
            when {
              daysAway <= 7 -> SCORE_PROXIMITY_NEAR
              daysAway <= 30 -> SCORE_PROXIMITY_MID
              else -> 0.0
            }
      }
    }

    return score
  }

  /**
   * Extract meaningful query terms from a list of search queries. Filters out short words and
   * common stop words.
   */
  fun extractQueryTerms(queries: List<String>): List<String> {
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
            "that",
            "get",
            "use",
            "need")
    return queries
        .flatMap { it.split(Regex("\\s+")) }
        .map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
        .filter { it.length > 2 && it !in stopWords }
        .distinct()
  }
}
