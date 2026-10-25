package org.dallasmakerspace.notifications.templates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.dallasmakerspace.notifications.topics.BambuPrinterAvailableTopic

/** Template data for "the printer you were waiting on is available" (one-shot watch fired). */
@Serializable
data class BambuPrinterAvailableData(
    val printerName: String = "",
    val freedByJobName: String? = null,
    /** Event time formatted in Central Time, e.g. "Thu, Oct 22 at 3:42 PM CT". */
    val occurredAtText: String = "",
    val statusPageUrl: String = "",
)

/** "{printer} is available". */
class BambuPrinterAvailableTemplate :
    NotificationTemplate<BambuPrinterAvailableData>(
        name = "bambu-printer-available",
        topic = BambuPrinterAvailableTopic,
    ) {
  override val serializer: KSerializer<BambuPrinterAvailableData> =
      BambuPrinterAvailableData.serializer()

  override fun card(data: BambuPrinterAvailableData): NotificationCard =
      NotificationCard(
          kind = "bambu",
          title = renderSubject(data),
          excerpt = "The last print may still need to be cleared from the bed.",
          url = data.statusPageUrl,
          ctaLabel = "View printer status",
          whenLine = data.occurredAtText)

  override fun renderSubject(data: BambuPrinterAvailableData): String =
      "${data.printerName} is available"

  override fun renderText(data: BambuPrinterAvailableData): String = buildString {
    appendLine("${data.printerName} is available now.")
    appendLine()
    data.freedByJobName?.takeIf { it.isNotBlank() }?.let {
      appendLine("The print that was using it (\"$it\") has ended.")
    }
    appendLine("The last print may still need to be cleared from the bed.")
    if (data.occurredAtText.isNotBlank()) appendLine("When: ${data.occurredAtText}")
    appendLine()
    appendLine("Printer status: ${data.statusPageUrl}")
  }

  override fun renderHtml(data: BambuPrinterAvailableData): String = buildString {
    append("<p style=\"margin:0 0 8px;\">${escapeHtml(data.printerName)} is available now.</p>")
    data.freedByJobName?.takeIf { it.isNotBlank() }?.let {
      append(
          "<p style=\"margin:0 0 8px;\">The print that was using it " +
              "(&quot;${escapeHtml(it)}&quot;) has ended.</p>")
    }
    append("<p style=\"margin:0 0 8px;\">The last print may still need to be cleared from the bed.</p>")
    if (data.occurredAtText.isNotBlank()) {
      append("<p style=\"margin:0 0 8px;\">When: ${escapeHtml(data.occurredAtText)}</p>")
    }
    append(
        "<p style=\"margin:0;\"><a href=\"${escapeHtml(data.statusPageUrl)}\">View printer status</a></p>")
  }
}
