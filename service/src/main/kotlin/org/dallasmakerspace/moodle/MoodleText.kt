package org.dallasmakerspace.moodle

import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

internal object MoodleText {
  private val TAG = Regex("<[^>]*>")
  private val WHITESPACE = Regex("\\s+")
  private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")
  private val NAMED_ENTITIES =
      mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&nbsp;" to " ")

  private val MONTHS =
      Month.entries.associateBy { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() }
  private val DAY_MONTH_YEAR = Regex("(\\d{1,2})\\s+([A-Za-z]+)\\s+(\\d{4})")
  private val ISO_DATE = Regex("(\\d{4})-(\\d{2})-(\\d{2})")

  fun decodeEntities(text: String): String {
    var out = text
    NAMED_ENTITIES.forEach { (entity, value) -> out = out.replace(entity, value) }
    return NUMERIC_ENTITY.replace(out) { m ->
      val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
      code?.let { String(Character.toChars(it)) } ?: m.value
    }
  }

  /** Strips HTML (Moodle summaries, report-builder cells) down to one line of plain text. */
  fun plainText(html: String?): String =
      decodeEntities(TAG.replace(html.orEmpty(), " ")).replace(WHITESPACE, " ").trim()

  fun normalizeKey(value: String?): String = plainText(value).lowercase()

  /**
   * Parses a report-builder date cell. Moodle formats these with the service user's language
   * (English), e.g. "Monday, 6 October 2026, 3:15 PM"; ISO dates are accepted too.
   */
  fun parseDate(value: String?): LocalDate? {
    val text = plainText(value)
    if (text.isEmpty()) return null
    ISO_DATE.find(text)?.let { m ->
      return runCatching {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
          }
          .getOrNull()
    }
    val m = DAY_MONTH_YEAR.find(text) ?: return null
    val month = MONTHS[m.groupValues[2].lowercase()] ?: return null
    return runCatching { LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) }
        .getOrNull()
  }
}
