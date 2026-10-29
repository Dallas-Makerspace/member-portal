package org.dallasmakerspace.agenttools

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Makes stored text safe to show the model: no contact details, short badge digits, one line. */
internal object AuditTextSanitizer {
  private const val MAX_VALUE_CHARS = 80
  private const val MAX_ATTRIBUTES_CHARS = 240
  private const val MAX_ATTRIBUTES = 8
  private const val BADGE_VISIBLE = 4
  private const val MIN_MASKED_DIGITS = 5

  private val UTC = ZoneId.of("UTC")
  private val CHICAGO = ZoneId.of("America/Chicago")
  private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")

  private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
  private val PHONE = Regex("(?<!\\w)(?:\\+?1[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]\\d{3}[\\s.-]\\d{4}")
  private val DIGITS = Regex("\\d{$MIN_MASKED_DIGITS,}")
  private val CONTROL = Regex("[\\p{Cntrl}\\s]+")
  private val SAFE_KEY = Regex("[A-Za-z0-9_.-]{1,30}")
  private val HIDDEN_KEY = Regex("mail|phone|address|passw|token|secret|ssn|credential", RegexOption.IGNORE_CASE)
  private val BADGE_KEY = Regex("badge", RegexOption.IGNORE_CASE)
  private val json = Json

  fun oneLine(text: String, max: Int = MAX_VALUE_CHARS): String =
      CONTROL.replace(text, " ").trim().let { if (it.length > max) it.take(max) + "..." else it }

  /** Emails and separated phone numbers are replaced; digit runs of 5+ keep only the last 4. */
  fun scrub(text: String, max: Int = MAX_VALUE_CHARS): String {
    val noEmail = EMAIL.replace(text, "[email]")
    val noPhone = PHONE.replace(noEmail, "[phone]")
    val masked = DIGITS.replace(noPhone) { maskDigits(it.value) }
    return oneLine(masked, max)
  }

  private fun maskDigits(digits: String) = "****" + digits.takeLast(BADGE_VISIBLE)

  /** The attributes JSON of an activity log entry as `key=value` pairs, or null when there are none. */
  fun attributes(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
    val text =
        if (parsed is JsonObject) {
          parsed.entries
              .take(MAX_ATTRIBUTES)
              .joinToString(", ") { (key, value) -> pair(key, value) }
        } else {
          scrub(raw)
        }
    return text.takeIf { it.isNotBlank() }?.let { oneLine(it, MAX_ATTRIBUTES_CHARS) }
  }

  private fun pair(key: String, value: kotlinx.serialization.json.JsonElement): String {
    if (!SAFE_KEY.matches(key)) return "(field)=[hidden]"
    if (HIDDEN_KEY.containsMatchIn(key)) return "$key=[hidden]"
    val content = (value as? JsonPrimitive)?.content ?: return "$key=[nested]"
    val shown =
        if (BADGE_KEY.containsMatchIn(key) && content.isNotBlank() && content.all { it.isDigit() }) {
          maskDigits(content)
        } else {
          scrub(content)
        }
    return "$key=$shown"
  }

  fun central(utc: LocalDateTime): String = TIME.format(utc.atZone(UTC).withZoneSameInstant(CHICAGO))

  fun central(instant: Instant): String = TIME.format(instant.atZone(CHICAGO))
}
