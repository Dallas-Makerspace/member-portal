package org.dallasmakerspace.bambuddy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Field names verified against a live Bambuddy instance's /openapi.json and sample responses
// (2026-09-17) - GET /api/v1/printers/, GET /api/v1/printers/{id}/status, GET /api/v1/queue/.
// Parsing stays permissive (ignoreUnknownKeys) since Bambuddy's real payloads carry many more
// fields (AMS/filament, temperatures, HMS errors, etc.) than this integration currently surfaces.

/** GET /api/v1/printers/ - static config only; no live state (that's a separate per-printer
 * /status call - see [BambuPrinterStatus]). Note the trailing slash - without it Bambuddy 404s. */
@Serializable
data class BambuPrinter(
    val id: Int,
    val name: String,
    val model: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
)

/** GET /api/v1/printers/{id}/status. `connected = false` means Bambuddy can't currently reach the
 * printer over the LAN - `state` may still hold a stale last-known value in that case, so callers
 * should treat a disconnected printer as offline regardless of `state`. */
@Serializable
data class BambuPrinterStatus(
    val id: Int,
    val name: String,
    val connected: Boolean = true,
    val state: String = "UNKNOWN",
    @SerialName("current_print") val currentPrint: String? = null,
    // Comes back as a float on the wire (e.g. 42.0, 100.0), not an int - verified against a live
    // instance.
    val progress: Double? = null,
    // Despite the field name, this is in MINUTES, not seconds - confirmed by cross-checking a live
    // printer against Bambuddy's own UI (which showed "50h 59m" for a printer this field reported
    // as 3059). An earlier version of this integration wrongly treated it as seconds.
    @SerialName("remaining_time") val remainingTimeMinutes: Int? = null,
    @SerialName("layer_num") val layerNum: Int? = null,
    @SerialName("total_layers") val totalLayers: Int? = null,
)

/** GET /api/v1/queue/ - one entry per queued/printing/completed job across all printers. */
@Serializable
data class BambuQueueItem(
    val id: Int,
    @SerialName("printer_id") val printerId: Int? = null,
    val status: String = "pending",
    @SerialName("archive_name") val archiveName: String? = null,
    @SerialName("print_time_seconds") val printTimeSeconds: Int? = null,
    // Queue display order - lower prints sooner. Used to pick the true "next" pending item rather
    // than an arbitrary one.
    val position: Int = Int.MAX_VALUE,
    @SerialName("created_by_username") val createdByUsername: String? = null,
    // ISO-8601, only set once status = "completed" - used to pick the most recently finished job
    // per printer (position isn't meaningful across completed items, only within pending/printing).
    @SerialName("completed_at") val completedAt: String? = null,
)
