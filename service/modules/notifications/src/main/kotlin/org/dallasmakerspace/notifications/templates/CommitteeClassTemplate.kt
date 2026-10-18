package org.dallasmakerspace.notifications.templates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.dallasmakerspace.notifications.topics.CommitteeUpdatesTopic

/** Template data for "a committee you follow added a class". */
@Serializable
data class CommitteeClassData(
    val eventName: String = "",
    /** Name(s) of the followed committee(s) the class belongs to, e.g. "Laser". */
    val committeeName: String = "",
    /** Start time formatted in Central Time, e.g. "Mon Aug 10 7:00 PM". */
    val eventStart: String = "",
    val eventUrl: String = "",
)

/** "A committee you follow added a class", produced when calendar publishes an event. */
class CommitteeClassTemplate :
    NotificationTemplate<CommitteeClassData>(
        name = "committee-class",
        topic = CommitteeUpdatesTopic,
    ) {
  override val serializer: KSerializer<CommitteeClassData> = CommitteeClassData.serializer()

  /** Includes the start time so repeat sessions of the same class are distinguishable. */
  override fun renderSubject(data: CommitteeClassData): String =
      "New class from ${data.committeeName}: ${data.eventName}" +
          if (data.eventStart.isNotBlank()) " (${data.eventStart})" else ""

  override fun renderText(data: CommitteeClassData): String = buildString {
    appendLine("${data.committeeName} added a new class:")
    appendLine()
    appendLine(data.eventName)
    if (data.eventStart.isNotBlank()) appendLine(data.eventStart)
    appendLine()
    appendLine("View the full details at ${data.eventUrl}")
    appendLine()
    appendLine("You are receiving this because you follow ${data.committeeName}.")
  }

  override fun renderHtml(data: CommitteeClassData): String = buildString {
    append("<p>${escapeHtml(data.committeeName)} added a new class:</p>")
    append("<h3>${escapeHtml(data.eventName)}</h3>")
    if (data.eventStart.isNotBlank()) {
      append("<p>${escapeHtml(data.eventStart)}</p>")
    }
    append("<p><a href=\"${escapeHtml(data.eventUrl)}\">View the full details</a></p>")
    append("<p style=\"color:#888;font-size:13px;\">You are receiving this because you follow ")
    append(escapeHtml(data.committeeName))
    append(".</p>")
  }
}
