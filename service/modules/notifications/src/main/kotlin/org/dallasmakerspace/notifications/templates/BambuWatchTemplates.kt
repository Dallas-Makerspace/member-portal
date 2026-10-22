package org.dallasmakerspace.notifications.templates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.dallasmakerspace.notifications.topics.BambuWatchTopic

/** Template data for a watch lifecycle notice (overdue / expired-final). */
@Serializable
data class BambuWatchNoticeData(
    val printerName: String = "",
    val jobName: String? = null,
    /** "your print" or "the print you were waiting on" — what the watch was watching. */
    val scopeLabel: String = "your print",
    val statusPageUrl: String = "",
    /**
     * Overdue notices only: true when the watch was re-armed and is still being watched;
     * false when this overdue notice is the terminal one (no fresh estimate — the watch is
     * expiring, design §10.2), where "we're still watching it" would be false copy.
     */
    val stillWatching: Boolean = true,
)

/** "Your print is taking longer than expected" — sent once when a watch's TTL expires mid-print. */
class BambuWatchOverdueTemplate :
    NotificationTemplate<BambuWatchNoticeData>(
        name = "bambu-watch-overdue",
        topic = BambuWatchTopic,
    ) {
  override val serializer: KSerializer<BambuWatchNoticeData> = BambuWatchNoticeData.serializer()

  private fun headline(data: BambuWatchNoticeData): String =
      "On ${data.printerName}, ${data.scopeLabel} is taking longer than expected"

  private fun watchingLine(data: BambuWatchNoticeData): String =
      if (data.stillWatching) {
        "We're still watching it and will let you know when it finishes — but please check " +
            "the printer or the status page if you're nearby."
      } else {
        "This reminder is ending now, so please check the printer or the status page " +
            "yourself — we won't be able to confirm when it finishes."
      }

  override fun renderSubject(data: BambuWatchNoticeData): String = headline(data)

  override fun renderText(data: BambuWatchNoticeData): String = buildString {
    appendLine("${headline(data)}.")
    appendLine()
    data.jobName?.takeIf { it.isNotBlank() }?.let { appendLine("Print: $it") }
    appendLine()
    appendLine(watchingLine(data))
    appendLine()
    appendLine("Printer status: ${data.statusPageUrl}")
  }

  override fun renderHtml(data: BambuWatchNoticeData): String = buildString {
    append("<p style=\"margin:0 0 8px;\">${escapeHtml(headline(data))}.</p>")
    data.jobName?.takeIf { it.isNotBlank() }?.let {
      append("<p style=\"margin:0 0 8px;\"><strong>${escapeHtml(it)}</strong></p>")
    }
    append("<p style=\"margin:0 0 8px;\">${escapeHtml(watchingLine(data))}</p>")
    append(
        "<p style=\"margin:0;\"><a href=\"${escapeHtml(data.statusPageUrl)}\">View printer status</a></p>")
  }
}

/** "Please check your print" — the terminal notice when a watch expires without confirmation. */
class BambuWatchExpiredTemplate :
    NotificationTemplate<BambuWatchNoticeData>(
        name = "bambu-watch-expired",
        topic = BambuWatchTopic,
    ) {
  override val serializer: KSerializer<BambuWatchNoticeData> = BambuWatchNoticeData.serializer()

  override fun renderSubject(data: BambuWatchNoticeData): String =
      "Please check your print on ${data.printerName}"

  override fun renderText(data: BambuWatchNoticeData): String = buildString {
    appendLine("We couldn't confirm whether ${data.scopeLabel} on ${data.printerName} finished.")
    appendLine()
    data.jobName?.takeIf { it.isNotBlank() }?.let { appendLine("Print: $it") }
    appendLine()
    appendLine("Please check the printer or the status page yourself — this reminder has ended.")
    appendLine()
    appendLine("Printer status: ${data.statusPageUrl}")
  }

  override fun renderHtml(data: BambuWatchNoticeData): String = buildString {
    append(
        "<p style=\"margin:0 0 8px;\">We couldn't confirm whether " +
            "${escapeHtml(data.scopeLabel)} on ${escapeHtml(data.printerName)} finished.</p>")
    data.jobName?.takeIf { it.isNotBlank() }?.let {
      append("<p style=\"margin:0 0 8px;\"><strong>${escapeHtml(it)}</strong></p>")
    }
    append(
        "<p style=\"margin:0 0 8px;\">Please check the printer or the status page yourself — " +
            "this reminder has ended.</p>")
    append(
        "<p style=\"margin:0;\"><a href=\"${escapeHtml(data.statusPageUrl)}\">View printer status</a></p>")
  }
}
