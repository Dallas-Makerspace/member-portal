package org.dallasmakerspace.notifications.templates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.dallasmakerspace.notifications.topics.BambuPrintActionRequiredTopic

/** Template data for "your print failed / was stopped" (one-shot watch fired). */
@Serializable
data class BambuPrintActionRequiredData(
    val printerName: String = "",
    val jobName: String = "",
    /** "failed" or "was stopped". */
    val eventLabel: String = "failed",
    val reason: String? = null,
    /** Event time formatted in Central Time, e.g. "Thu, Oct 22 at 3:42 PM CT". */
    val occurredAtText: String = "",
    val statusPageUrl: String = "",
)

/** "Your print needs attention on {printer}". */
class BambuPrintActionRequiredTemplate :
    NotificationTemplate<BambuPrintActionRequiredData>(
        name = "bambu-print-action-required",
        topic = BambuPrintActionRequiredTopic,
    ) {
  override val serializer: KSerializer<BambuPrintActionRequiredData> =
      BambuPrintActionRequiredData.serializer()

  override fun card(data: BambuPrintActionRequiredData): NotificationCard =
      NotificationCard(
          kind = "bambu",
          title = renderSubject(data),
          excerpt = "Your print ${data.eventLabel} — please check the printer.",
          url = data.statusPageUrl,
          ctaLabel = "View printer status",
          whenLine = data.occurredAtText)

  override fun renderSubject(data: BambuPrintActionRequiredData): String =
      "Your print needs attention on ${data.printerName}"

  override fun renderText(data: BambuPrintActionRequiredData): String = buildString {
    appendLine("Your print ${data.eventLabel} on ${data.printerName} and needs attention.")
    appendLine()
    appendLine("Print: ${data.jobName}")
    if (data.occurredAtText.isNotBlank()) appendLine("When: ${data.occurredAtText}")
    data.reason?.takeIf { it.isNotBlank() }?.let { appendLine("Reason: $it") }
    appendLine()
    appendLine("Please check the printer when you can.")
    appendLine()
    appendLine("Printer status: ${data.statusPageUrl}")
  }

  override fun renderHtml(data: BambuPrintActionRequiredData): String = buildString {
    append(
        "<p style=\"margin:0 0 8px;\">Your print ${escapeHtml(data.eventLabel)} on " +
            "${escapeHtml(data.printerName)} and needs attention.</p>")
    append("<p style=\"margin:0 0 8px;\"><strong>${escapeHtml(data.jobName)}</strong></p>")
    if (data.occurredAtText.isNotBlank()) {
      append("<p style=\"margin:0 0 8px;\">When: ${escapeHtml(data.occurredAtText)}</p>")
    }
    data.reason?.takeIf { it.isNotBlank() }?.let {
      append("<p style=\"margin:0 0 8px;\">Reason: ${escapeHtml(it)}</p>")
    }
    append("<p style=\"margin:0 0 8px;\">Please check the printer when you can.</p>")
    append(
        "<p style=\"margin:0;\"><a href=\"${escapeHtml(data.statusPageUrl)}\">View printer status</a></p>")
  }
}
