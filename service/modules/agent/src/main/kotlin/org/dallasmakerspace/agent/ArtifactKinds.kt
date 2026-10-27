package org.dallasmakerspace.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A course outline: sections of activities. */
@Serializable
data class CourseOutline(val title: String, val sections: List<OutlineSection>) {
  @Serializable
  data class OutlineSection(val title: String, val activities: List<OutlineActivity> = emptyList())

  @Serializable
  data class OutlineActivity(
      val title: String,
      val type: String,
      val summary: String = "",
      val minutes: Int? = null,
  )
}

/** A quiz of questions that import into a Moodle question bank. */
@Serializable
data class Quiz(val title: String, val questions: List<QuizQuestion>) {
  @Serializable
  data class QuizQuestion(
      val type: String,
      val text: String,
      val options: List<QuizOption> = emptyList(),
      /** For short_answer: accepted answers. */
      val answers: List<String> = emptyList(),
      /** For matching: pairs of item and match. */
      val pairs: List<MatchPair> = emptyList(),
      val feedback: String = "",
  )

  @Serializable data class QuizOption(val text: String, val correct: Boolean = false)

  @Serializable data class MatchPair(val item: String, val match: String)
}

/** What an artifact may hold; `markdown` is free text, the others are checked JSON. */
object ArtifactKinds {
  const val MARKDOWN = "markdown"
  const val COURSE_OUTLINE = "course_outline"
  const val QUIZ = "quiz"
  val ALL = listOf(MARKDOWN, COURSE_OUTLINE, QUIZ)

  const val MAX_CHARS = 100_000
  private val QUESTION_TYPES = setOf("multiple_choice", "true_false", "short_answer", "matching")
  private val ACTIVITY_TYPES =
      setOf("lecture", "reading", "video", "discussion", "assignment", "quiz", "lab", "other")

  val json = Json { ignoreUnknownKeys = true }

  /** Null when [content] is valid for [kind]; otherwise a message the model can act on. */
  fun problem(kind: String, content: String): String? {
    if (kind !in ALL) return "Unknown kind. Use one of ${ALL.joinToString()}."
    if (content.isBlank()) return "Content is empty."
    if (content.length > MAX_CHARS) return "Content is too long (max $MAX_CHARS characters)."
    return try {
      when (kind) {
        COURSE_OUTLINE -> outlineProblem(json.decodeFromString<CourseOutline>(content))
        QUIZ -> quizProblem(json.decodeFromString<Quiz>(content))
        else -> null
      }
    } catch (e: SerializationException) {
      "Invalid $kind JSON: ${e.message?.lineSequence()?.firstOrNull()?.take(ERROR_CHARS)}"
    } catch (e: IllegalArgumentException) {
      "Invalid $kind JSON: ${e.message?.lineSequence()?.firstOrNull()?.take(ERROR_CHARS)}"
    }
  }

  private fun outlineProblem(outline: CourseOutline): String? {
    if (outline.sections.isEmpty()) return "A course outline needs at least one section."
    outline.sections.forEachIndexed { s, section ->
      section.activities.forEachIndexed { a, activity ->
        if (activity.type !in ACTIVITY_TYPES) {
          return "Section ${s + 1}, activity ${a + 1}: type must be one of ${ACTIVITY_TYPES.joinToString()}."
        }
      }
    }
    return null
  }

  private fun quizProblem(quiz: Quiz): String? {
    if (quiz.questions.isEmpty()) return "A quiz needs at least one question."
    quiz.questions.forEachIndexed { i, q ->
      val n = i + 1
      if (q.type !in QUESTION_TYPES) return "Question $n: type must be one of ${QUESTION_TYPES.joinToString()}."
      if (q.text.isBlank()) return "Question $n has no text."
      when (q.type) {
        "multiple_choice" -> {
          if (q.options.size < 2) return "Question $n needs at least two options."
          if (q.options.none { it.correct }) return "Question $n needs a correct option."
        }
        "true_false" ->
            if (q.options.count { it.correct } != 1) return "Question $n needs exactly one correct option (True or False)."
        "short_answer" -> if (q.answers.isEmpty()) return "Question $n needs at least one accepted answer."
        "matching" -> if (q.pairs.size < 2) return "Question $n needs at least two pairs."
      }
    }
    return null
  }

  private const val ERROR_CHARS = 200
}
