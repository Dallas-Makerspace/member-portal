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
 * never decoded into a field.
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
    val reason: String? = null,
    /** Display string passthrough when present. */
    val duration: String? = null,
    @SerialName("filament_grams") val filamentGrams: Double? = null,
    val progress: Double? = null,
    @SerialName("finish_photo_url") val finishPhotoUrl: String? = null,
)
