package org.dallasmakerspace.notifications.templates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.dallasmakerspace.notifications.topics.FollowingTopic

/** Template data for an event announcement (organizer-following feature). */
@Serializable
data class EventAnnouncementData(
    val eventName: String = "",
    /** Organizer's full display name, e.g. "Jane Doe". */
    val organizerName: String = "",
    /** Organizer's DMS username, e.g. "jdoe" - links to their member profile. */
    val organizerUsername: String = "",
    /** Start time formatted in Central Time, e.g. "Mon Aug 10 7:00 PM". */
    val eventStart: String = "",
    val eventUrl: String = "",
    /** Absolute URL of the event's first public image attachment; blank when it has none. */
    val imageUrl: String = "",
    /** Short plain-text preview of the event description; may be blank. */
    val excerpt: String = "",
)

/** "An organizer you follow posted a new event" - flagship use case for the calendar webhook. */
class EventAnnouncementTemplate :
    NotificationTemplate<EventAnnouncementData>(
        name = "event-announcement",
        topic = FollowingTopic,
    ) {
  override val serializer: KSerializer<EventAnnouncementData> = EventAnnouncementData.serializer()

  /** Includes the start time so repeat sessions of the same class are distinguishable. */
  override fun renderSubject(data: EventAnnouncementData): String =
      "New event from ${data.organizerName}: ${data.eventName}" +
          if (data.eventStart.isNotBlank()) " (${data.eventStart})" else ""

  override fun renderText(data: EventAnnouncementData): String = buildString {
    appendLine("${organizerLabel(data)} posted a new event:")
    appendLine()
    appendLine(data.eventName)
    if (data.eventStart.isNotBlank()) appendLine(data.eventStart)
    if (data.excerpt.isNotBlank()) {
      appendLine()
      appendLine(data.excerpt)
    }
    appendLine()
    appendLine("View the full details at ${data.eventUrl}")
    appendLine()
    appendLine("You are receiving this because you follow ${organizerLabel(data)}.")
  }

  override fun renderHtml(data: EventAnnouncementData): String = buildString {
    val body = buildString {
      append("<p style=\"margin:0 0 8px;\">${organizerHtmlLabel(data)} posted a new event:</p>")
      append("<h3 style=\"margin:0 0 8px;\">${escapeHtml(data.eventName)}</h3>")
      if (data.eventStart.isNotBlank()) {
        append("<p style=\"margin:0 0 8px;\">${escapeHtml(data.eventStart)}</p>")
      }
      if (data.excerpt.isNotBlank()) {
        append("<p style=\"margin:0 0 10px;color:#555;\">${escapeHtml(data.excerpt)}</p>")
      }
      append("<p style=\"margin:0;\"><a href=\"${escapeHtml(data.eventUrl)}\">View the full details</a></p>")
    }
    append(thumbnailColumnsHtml(data.imageUrl, data.eventName, data.eventUrl, body, keepAspectRatio = true))
    append("<p style=\"color:#888;font-size:13px;line-height:18px;margin:14px 0 0;\">You are receiving this because you follow ")
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
