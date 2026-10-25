package org.dallasmakerspace.bambuddy.notifications.webhook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * BamBuddy generic-format webhook payload (flat JSON). Only [event], [printer] and [filename]
 * are ever parsed for logic; [title]/[message] are human text and are never parsed. Field-name
 * spellings beyond event/printer/filename must be re-verified against the live DMS BamBuddy
 * instance (upstream main may be ahead of the DMS install). Parsed with ignoreUnknownKeys — the
 * payload carries extra variables, and `image` (a potentially huge base64 string when
 * attach_photo is on; it is OFF in the provider config) is deliberately not declared, so it is
 * never decoded into a field. Decode leniently ([BambuddyWebhookHandler]): a number where a
 * string is declared must not fail the event; undeclared fields (e.g. progress) are ignored.
 */
@Serializable
data class BambuddyWebhookPayload(
    val event: String,
    /** Printer NAME, not id. */
    val printer: String? = null,
    val filename: String? = null,
    val title: String? = null,
    val message: String? = null,
    /** Naive local ISO from BamBuddy; portal receipt time is authoritative. */
    val timestamp: String? = null,
    /** BamBuddy sends these three as strings, with the literal "Unknown" when it has no value. */
    val reason: String? = null,
    val duration: String? = null,
    @SerialName("filament_grams") val filamentGrams: String? = null,
    @SerialName("finish_photo_url") val finishPhotoUrl: String? = null,
) {
  fun reasonText(): String? = presentOrNull(reason)

  fun durationText(): String? = presentOrNull(duration)

  fun filamentGramsText(): String? = presentOrNull(filamentGrams)?.let { "$it g" }

  private fun presentOrNull(value: String?): String? =
      value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Unknown", ignoreCase = true) }
}
