package org.dallasmakerspace.agent

/** A downloadable rendering of an artifact. */
data class ExportFile(val fileName: String, val contentType: String, val body: String)

/** Turns artifacts into files: Markdown for notes and outlines, GIFT and Moodle XML for quizzes. */
object ArtifactExport {
  const val MARKDOWN = "md"
  const val GIFT = "gift"
  const val MOODLE_XML = "xml"

  /** Formats that make sense for [kind]; the first is the default. */
  fun formatsFor(kind: String): List<String> =
      if (kind == ArtifactKinds.QUIZ) listOf(GIFT, MOODLE_XML, MARKDOWN) else listOf(MARKDOWN)

  /** Null when [format] is not offered for [kind] or [content] does not parse. */
  fun render(kind: String, title: String, content: String, format: String): ExportFile? {
    if (format !in formatsFor(kind)) return null
    val base = slug(title)
    return try {
      when (kind) {
        ArtifactKinds.MARKDOWN -> ExportFile("$base.md", "text/markdown", content)
        ArtifactKinds.COURSE_OUTLINE ->
            ExportFile("$base.md", "text/markdown", outlineMarkdown(ArtifactKinds.json.decodeFromString(content)))
        ArtifactKinds.QUIZ -> {
          val quiz = ArtifactKinds.json.decodeFromString<Quiz>(content)
          when (format) {
            GIFT -> ExportFile("$base.gift.txt", "text/plain", gift(quiz))
            MOODLE_XML -> ExportFile("$base.xml", "application/xml", moodleXml(quiz))
            else -> ExportFile("$base.md", "text/markdown", quizMarkdown(quiz))
          }
        }
        else -> null
      }
    } catch (e: kotlinx.serialization.SerializationException) {
      null
    }
  }

  fun slug(title: String): String =
      title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifEmpty { "document" }

  fun outlineMarkdown(outline: CourseOutline): String = buildString {
    appendLine("# ${outline.title}")
    outline.sections.forEachIndexed { i, section ->
      appendLine()
      appendLine("## ${i + 1}. ${section.title}")
      section.activities.forEach { a ->
        val minutes = a.minutes?.let { " (${it} min)" }.orEmpty()
        val summary = a.summary.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        appendLine("- **${a.title}** [${a.type}]$minutes$summary")
      }
    }
  }

  fun quizMarkdown(quiz: Quiz): String = buildString {
    appendLine("# ${quiz.title}")
    quiz.questions.forEachIndexed { i, q ->
      appendLine()
      appendLine("${i + 1}. ${q.text}")
      q.options.forEach { appendLine("   - [${if (it.correct) "x" else " "}] ${it.text}") }
      if (q.answers.isNotEmpty()) appendLine("   - Accepted: ${q.answers.joinToString(", ")}")
      q.pairs.forEach { appendLine("   - ${it.item} → ${it.match}") }
    }
  }

  /** Moodle's GIFT import format. */
  fun gift(quiz: Quiz): String =
      quiz.questions.mapIndexed { i, q -> giftQuestion(i + 1, q) }.joinToString("\n\n") + "\n"

  private fun giftQuestion(number: Int, q: Quiz.QuizQuestion): String {
    val name = "::Q$number:: "
    val feedback = q.feedback.takeIf { it.isNotBlank() }?.let { "####${giftEscape(it)}" }.orEmpty()
    val body =
        when (q.type) {
          "true_false" -> {
            val answer = q.options.firstOrNull { it.correct }?.text?.trim()?.lowercase() == "true"
            "{${if (answer) "TRUE" else "FALSE"}$feedback}"
          }
          "short_answer" -> "{\n" + q.answers.joinToString("\n") { "=${giftEscape(it)}" } + feedback + "\n}"
          "matching" ->
              "{\n" + q.pairs.joinToString("\n") { "=${giftEscape(it.item)} -> ${giftEscape(it.match)}" } + feedback + "\n}"
          else -> {
            val correct = q.options.count { it.correct }
            val lines =
                q.options.joinToString("\n") { o ->
                  val mark =
                      when {
                        correct == 1 && o.correct -> "="
                        o.correct -> "~%${PERCENT_TOTAL / correct}%"
                        correct > 1 -> "~%-$PERCENT_TOTAL%"
                        else -> "~"
                      }
                  "$mark${giftEscape(o.text)}"
                }
            "{\n$lines$feedback\n}"
          }
        }
    return name + giftEscape(q.text) + " " + body
  }

  private fun giftEscape(text: String): String =
      text.replace(Regex("""([~=#{}:\\])"""), "\\\\$1").replace("\n", " ")

  /** Moodle XML question bank (Question bank > Import > Moodle XML format). */
  fun moodleXml(quiz: Quiz): String = buildString {
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine("<quiz>")
    quiz.questions.forEachIndexed { i, q ->
      val type =
          when (q.type) {
            "true_false" -> "truefalse"
            "short_answer" -> "shortanswer"
            "matching" -> "matching"
            else -> "multichoice"
          }
      appendLine("""  <question type="$type">""")
      appendLine("    <name><text>${xml("Q${i + 1}")}</text></name>")
      appendLine("""    <questiontext format="html"><text>${xml(q.text)}</text></questiontext>""")
      if (q.feedback.isNotBlank()) {
        appendLine("""    <generalfeedback format="html"><text>${xml(q.feedback)}</text></generalfeedback>""")
      }
      appendLine("    <defaultgrade>1</defaultgrade>")
      when (type) {
        "truefalse" -> {
          val answer = q.options.firstOrNull { it.correct }?.text?.trim()?.lowercase() == "true"
          appendLine("""    <answer fraction="${if (answer) 100 else 0}"><text>true</text></answer>""")
          appendLine("""    <answer fraction="${if (answer) 0 else 100}"><text>false</text></answer>""")
        }
        "shortanswer" -> {
          appendLine("    <usecase>0</usecase>")
          q.answers.forEach { appendLine("""    <answer fraction="100"><text>${xml(it)}</text></answer>""") }
        }
        "matching" ->
            q.pairs.forEach {
              appendLine("    <subquestion format=\"html\"><text>${xml(it.item)}</text>")
              appendLine("      <answer><text>${xml(it.match)}</text></answer></subquestion>")
            }
        else -> {
          val correct = q.options.count { it.correct }
          appendLine("    <single>${correct <= 1}</single>")
          appendLine("    <shuffleanswers>true</shuffleanswers>")
          q.options.forEach { o ->
            val fraction =
                when {
                  o.correct -> if (correct == 0) 0 else PERCENT_TOTAL / correct
                  correct > 1 -> -PERCENT_TOTAL
                  else -> 0
                }
            appendLine("""    <answer fraction="$fraction"><text>${xml(o.text)}</text></answer>""")
          }
        }
      }
      appendLine("  </question>")
    }
    appendLine("</quiz>")
  }

  private fun xml(text: String): String =
      text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private const val PERCENT_TOTAL = 100
}
