package org.dallasmakerspace.smartwaiver

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Bump when the mapping changes so the Enrich stage re-mirrors rows made by an older version. */
internal const val MIRROR_VERSION = 1

internal const val STATUS_KIOSK = "Completed At Kiosk"
internal const val STATUS_ONLINE = "Completed Online"

private const val REFERRAL_TYPE_LABEL = "where did you hear"
private const val REFERRAL_SOURCE_LABEL = "did anyone refer"

private val API_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** Cuts text to a column's width: strict SQL mode rejects the whole row for one over-long value. */
internal fun String?.fit(max: Int): String? = this?.take(max)

/** SmartWaiver sends `yyyy-MM-dd HH:mm:ss` in UTC; null for blank or unparseable text. */
internal fun parseApiDateTime(text: String?): LocalDateTime? =
    try {
      text?.takeIf { it.isNotBlank() }?.let { LocalDateTime.parse(it.trim(), API_DATE_TIME) }
    } catch (_: DateTimeParseException) {
      null
    }

internal fun waiverStatusFor(kiosk: Boolean?): String? =
    when (kiosk) {
      true -> STATUS_KIOSK
      false -> STATUS_ONLINE
      null -> null
    }

internal fun tagsJson(tags: List<String>): String? =
    tags.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(ListSerializer(String.serializer()), it) }

/**
 * Values the old system stored in the referral column that are not answers: the dropdown's
 * placeholder and an empty string (nothing chosen), and gender values it picked up when it chose the
 * answer by position and the waiver's first custom field was Gender. SmartWaiver returns an empty
 * answer for "nothing chosen".
 */
internal val REFERRAL_NOT_AN_ANSWER = listOf("", "Please Select...", "Male", "Female")

internal fun isReferralAnswer(value: String?) =
    value != null && REFERRAL_NOT_AN_ANSWER.none { it.equals(value.trim(), ignoreCase = true) }

/**
 * The two referral answers, found by the question text rather than by position so a reordered or
 * dropped question can't put an answer in the wrong column. A placeholder is not an answer.
 */
internal data class Referral(val type: String?, val source: String?)

internal fun referralFrom(waiver: SmartwaiverFullWaiver): Referral {
  fun answer(label: String) =
      waiver.customWaiverFields.values
          .firstOrNull { it.displayText.trim().startsWith(label, ignoreCase = true) }
          ?.value
          ?.takeIf { isReferralAnswer(it) }
  return Referral(answer(REFERRAL_TYPE_LABEL).fit(REFERRAL_WIDTH), answer(REFERRAL_SOURCE_LABEL).fit(REFERRAL_WIDTH))
}

private const val REFERRAL_WIDTH = 100
