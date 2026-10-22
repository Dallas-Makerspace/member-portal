package org.dallasmakerspace.notifications.templates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.dallasmakerspace.notifications.topics.BambuPrintFinishedTopic

/** Template data for "your print finished" (one-shot watch fired on print_complete). */
@Serializable
data class BambuPrintFinishedData(
    val printerName: String = "",
    val jobName: String = "",
    /** Completion time formatted in Central Time, e.g. "Thu, Oct 22 at 3:42 PM CT". */
    val completedAtText: String = "",
    val durationText: String? = null,
    val filamentGramsText: String? = null,
    val statusPageUrl: String = "",
)

/** "Your print finished on {printer}" — asks the member to collect it and clear the bed. */
class BambuPrintFinishedTemplate :
    NotificationTemplate<BambuPrintFinishedData>(
        name = "bambu-print-finished",
        topic = BambuPrintFinishedTopic,
    ) {
  override val serializer: KSerializer<BambuPrintFinishedData> = BambuPrintFinishedData.serializer()

  override fun card(data: BambuPrintFinishedData): NotificationCard =
      NotificationCard(
          kind = "bambu",
          title = renderSubject(data),
          excerpt = "Please collect your print and clear the bed.",
          url = data.statusPageUrl,
          ctaLabel = "View printer status",
          whenLine = data.completedAtText)

  override fun renderSubject(data: BambuPrintFinishedData): String =
      "Your print finished on ${data.printerName}"

  override fun renderText(data: BambuPrintFinishedData): String = buildString {
    appendLine("Your print finished on ${data.printerName}.")
    appendLine()
    appendLine("Print: ${data.jobName}")
    if (data.completedAtText.isNotBlank()) appendLine("Finished: ${data.completedAtText}")
    data.durationText?.takeIf { it.isNotBlank() }?.let { appendLine("Duration: $it") }
    data.filamentGramsText?.takeIf { it.isNotBlank() }?.let { appendLine("Filament used: $it") }
    appendLine()
    appendLine("Please collect your print and clear the bed for the next member.")
    appendLine()
    appendLine("Printer status: ${data.statusPageUrl}")
  }

  override fun renderHtml(data: BambuPrintFinishedData): String = buildString {
    append("<p style=\"margin:0 0 8px;\">Your print finished on ${escapeHtml(data.printerName)}.</p>")
    append("<p style=\"margin:0 0 8px;\"><strong>${escapeHtml(data.jobName)}</strong></p>")
    if (data.completedAtText.isNotBlank()) {
      append("<p style=\"margin:0 0 8px;\">Finished: ${escapeHtml(data.completedAtText)}</p>")
    }
    data.durationText?.takeIf { it.isNotBlank() }?.let {
      append("<p style=\"margin:0 0 8px;\">Duration: ${escapeHtml(it)}</p>")
    }
    data.filamentGramsText?.takeIf { it.isNotBlank() }?.let {
      append("<p style=\"margin:0 0 8px;\">Filament used: ${escapeHtml(it)}</p>")
    }
    append("<p style=\"margin:0 0 8px;\">Please collect your print and clear the bed for the next member.</p>")
    append(
        "<p style=\"margin:0;\"><a href=\"${escapeHtml(data.statusPageUrl)}\">View printer status</a></p>")
  }
}
