package org.dallasmakerspace.notifications.templates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.dallasmakerspace.notifications.topics.EventAnnouncementTopic

/** Template data for an event announcement (organizer-following feature). */
@Serializable
data class EventAnnouncementData(
    val eventName: String = "",
    /** Organizer's full display name, e.g. "Jane Phelps". */
    val organizerName: String = "",
    /** Organizer's DMS username, e.g. "jdoe" - links to their member profile. */
    val organizerUsername: String = "",
    /** Start time formatted in Central Time, e.g. "Mon Aug 10 7:00 PM". */
    val eventStart: String = "",
    val eventUrl: String = "",
)

/** "An organizer you follow posted a new event" - flagship use case for the calendar webhook. */
class EventAnnouncementTemplate :
    NotificationTemplate<EventAnnouncementData>(
        name = "event-announcement",
        topic = EventAnnouncementTopic,
    ) {
  override val serializer: KSerializer<EventAnnouncementData> = EventAnnouncementData.serializer()

  override fun renderSubject(data: EventAnnouncementData): String =
      "New event from ${data.organizerName}: ${data.eventName}"

  override fun renderText(data: EventAnnouncementData): String = buildString {
    appendLine("${organizerLabel(data)} posted a new event:")
    appendLine()
    appendLine(data.eventName)
    if (data.eventStart.isNotBlank()) appendLine(data.eventStart)
    appendLine()
    appendLine("View the full details at ${data.eventUrl}")
    appendLine()
    appendLine("You are receiving this because you follow ${organizerLabel(data)}.")
  }

  override fun renderHtml(data: EventAnnouncementData): String = buildString {
    append("<p>${organizerHtmlLabel(data)} posted a new event:</p>")
    append("<h3>${escapeHtml(data.eventName)}</h3>")
    if (data.eventStart.isNotBlank()) {
      append("<p>${escapeHtml(data.eventStart)}</p>")
    }
    append("<p><a href=\"${escapeHtml(data.eventUrl)}\">View the full details</a></p>")
    append("<p style=\"color:#888;font-size:13px;\">You are receiving this because you follow ")
    append(organizerHtmlLabel(data))
    append(".</p>")
  }

  /** Plain-text "Full Name (@username)" label. */
  private fun organizerLabel(data: EventAnnouncementData): String =
      "${data.organizerName} (@${data.organizerUsername})"

  /** HTML "Full Name (@username)" label with the username linked to their DMS profile. */
  private fun organizerHtmlLabel(data: EventAnnouncementData): String =
      "${escapeHtml(data.organizerName)} (<a href=\"${memberProfileUrl(data.organizerUsername)}\">" +
          "@${escapeHtml(data.organizerUsername)}</a>)"
}
