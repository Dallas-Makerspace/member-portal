package org.dallasmakerspace.notifications.templates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.dallasmakerspace.notifications.topics.BambuCommitteeAlertsTopic

/** Template data for "a 3D printer is offline" (committee alerts, after the hold + re-check). */
@Serializable
data class BambuPrinterOfflineData(
    val printerName: String = "",
    /** Offline-since time formatted in Central Time, e.g. "Thu, Oct 22 at 3:42 PM CT". */
    val offlineSinceText: String = "",
    val statusPageUrl: String = "",
    /** The configured hold before the re-check; null falls back to generic wording. */
    val holdMinutes: Int? = null,
)

private fun BambuPrinterOfflineData.recheckLine(): String =
    "It was still offline when we checked again " +
        (holdMinutes?.takeIf { it > 0 }?.let { "$it minute${if (it == 1) "" else "s"} later." }
            ?: "a few minutes later.")

/** "3D printer offline: {printer}". */
class BambuPrinterOfflineTemplate :
    NotificationTemplate<BambuPrinterOfflineData>(
        name = "bambu-printer-offline",
        topic = BambuCommitteeAlertsTopic,
    ) {
  override val serializer: KSerializer<BambuPrinterOfflineData> =
      BambuPrinterOfflineData.serializer()

  override fun card(data: BambuPrinterOfflineData): NotificationCard =
      NotificationCard(
          kind = "bambu",
          title = renderSubject(data),
          excerpt = "Offline since ${data.offlineSinceText}.",
          url = data.statusPageUrl,
          ctaLabel = "View printer status",
          whenLine = data.offlineSinceText)

  override fun renderSubject(data: BambuPrinterOfflineData): String =
      "3D printer offline: ${data.printerName}"

  override fun renderText(data: BambuPrinterOfflineData): String = buildString {
    appendLine("${data.printerName} is offline.")
    appendLine()
    if (data.offlineSinceText.isNotBlank()) appendLine("Offline since: ${data.offlineSinceText}")
    appendLine(data.recheckLine())
    appendLine()
    appendLine("Printer status: ${data.statusPageUrl}")
    appendLine()
    appendLine("You are receiving this because you subscribed to 3D printer offline alerts.")
  }

  override fun renderHtml(data: BambuPrinterOfflineData): String = buildString {
    append("<p style=\"margin:0 0 8px;\">${escapeHtml(data.printerName)} is offline.</p>")
    if (data.offlineSinceText.isNotBlank()) {
      append("<p style=\"margin:0 0 8px;\">Offline since: ${escapeHtml(data.offlineSinceText)}</p>")
    }
    append("<p style=\"margin:0 0 8px;\">${escapeHtml(data.recheckLine())}</p>")
    append(
        "<p style=\"margin:0 0 8px;\"><a href=\"${escapeHtml(data.statusPageUrl)}\">View printer status</a></p>")
    append(
        "<p style=\"color:#888;font-size:13px;line-height:18px;margin:14px 0 0;\">" +
            "You are receiving this because you subscribed to 3D printer offline alerts.</p>")
  }
}
