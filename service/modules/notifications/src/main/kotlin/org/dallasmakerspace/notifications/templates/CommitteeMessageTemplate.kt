package org.dallasmakerspace.notifications.templates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.dallasmakerspace.notifications.topics.CommitteeUpdatesTopic

/** Template data for "a chair of a committee you follow sent a message". */
@Serializable
data class CommitteeMessageData(
    val committeeName: String = "",
    /** The chair's text, plain and at most 120 characters. */
    val message: String = "",
    /** Portal short link to the Talk thread for details and discussion. */
    val shortUrl: String = "",
)

/** A chair's short message to their committee's followers. */
class CommitteeMessageTemplate :
    NotificationTemplate<CommitteeMessageData>(
        name = "committee-message",
        topic = CommitteeUpdatesTopic,
    ) {
  override val serializer: KSerializer<CommitteeMessageData> = CommitteeMessageData.serializer()

  override fun card(data: CommitteeMessageData): NotificationCard =
      NotificationCard(
          kind = CardKinds.MESSAGE,
          title = data.message,
          url = data.shortUrl,
          ctaLabel = "Join the discussion",
          sender = data.committeeName)

  override fun renderSubject(data: CommitteeMessageData): String =
      "${data.committeeName}: ${data.message}"

  override fun renderText(data: CommitteeMessageData): String = buildString {
    appendLine("A message from the ${data.committeeName} committee:")
    appendLine()
    appendLine(data.message)
    appendLine()
    appendLine("Details and discussion: ${data.shortUrl}")
    appendLine()
    appendLine("You are receiving this because you follow ${data.committeeName}.")
  }

  override fun renderHtml(data: CommitteeMessageData): String = buildString {
    append("<p style=\"margin:0 0 8px;\">A message from the ${escapeHtml(data.committeeName)} committee:</p>")
    append("<h3 style=\"margin:0 0 10px;\">${escapeHtml(data.message)}</h3>")
    append("<p style=\"margin:0;\"><a href=\"${escapeHtml(data.shortUrl)}\">Details and discussion</a></p>")
    append("<p style=\"color:#888;font-size:13px;line-height:18px;margin:14px 0 0;\">You are receiving this because you follow ")
    append(escapeHtml(data.committeeName))
    append(".</p>")
  }
}
