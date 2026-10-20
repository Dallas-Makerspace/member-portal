package org.dallasmakerspace.notifications.templates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.dallasmakerspace.notifications.topics.FollowingTopic

/**
 * Template data for "a member you follow posted a project" (Show & Tell image post). Emails link
 * to the member's profile, whose Featured Projects list shows the post.
 */
@Serializable
data class ProjectPostData(
    /** Poster's full display name, e.g. "Jane Doe". */
    val memberName: String = "",
    /** Poster's DMS username, e.g. "jdoe" - links to their member profile. */
    val memberUsername: String = "",
    /** Title of the Show & Tell topic the post is in, e.g. "Show and Tell September". */
    val topicTitle: String = "",
    val imageUrl: String = "",
    /** Short plain-text preview of the post body; may be blank. */
    val excerpt: String = "",
)

/** "A member you follow posted a project" - produced by the Discourse post webhook. */
class ProjectPostTemplate :
    NotificationTemplate<ProjectPostData>(
        name = "project-post",
        topic = FollowingTopic,
    ) {
  override val serializer: KSerializer<ProjectPostData> = ProjectPostData.serializer()

  override fun renderSubject(data: ProjectPostData): String =
      "New project from ${data.memberName}"

  /** The payload has no project title or post link, so the card names the member and opens their profile. */
  override fun card(data: ProjectPostData): NotificationCard =
      NotificationCard(
          kind = CardKinds.PROJECT,
          title = renderSubject(data),
          excerpt = data.excerpt,
          imageUrl = data.imageUrl,
          url = portalProfilePath(data.memberUsername),
          ctaLabel = "See projects",
          sender = "@${data.memberUsername}")

  override fun renderText(data: ProjectPostData): String = buildString {
    appendLine("${memberLabel(data)} shared a project in ${data.topicTitle}:")
    if (data.excerpt.isNotBlank()) {
      appendLine()
      appendLine(data.excerpt)
    }
    appendLine()
    appendLine("See their projects at ${memberProfileUrl(data.memberUsername)}")
    appendLine()
    appendLine("You are receiving this because you follow ${memberLabel(data)}.")
  }

  /** Thumbnail left, text right (see [thumbnailColumnsHtml]); the footer spans both columns. */
  override fun renderHtml(data: ProjectPostData): String = buildString {
    val profile = memberProfileUrl(data.memberUsername)
    val body = buildString {
      append("<p style=\"margin:0 0 8px;\">${memberHtmlLabel(data)} shared a project in ")
      append("${escapeHtml(data.topicTitle)}:</p>")
      if (data.excerpt.isNotBlank()) {
        append("<p style=\"margin:0 0 10px;color:#555;\">${escapeHtml(data.excerpt)}</p>")
      }
      append("<p style=\"margin:0;\"><a href=\"$profile\">See their projects</a></p>")
    }
    append(thumbnailColumnsHtml(data.imageUrl, data.topicTitle, profile, body))
    append("<p style=\"color:#888;font-size:13px;line-height:18px;margin:14px 0 0;\">")
    append("You are receiving this because you follow ")
    append(memberHtmlLabel(data))
    append(".</p>")
  }

  /** Plain-text "Full Name (@username)" label. */
  private fun memberLabel(data: ProjectPostData): String =
      "${data.memberName} (@${data.memberUsername})"

  /** HTML "Full Name (@username)" label with the username linked to their DMS profile. */
  private fun memberHtmlLabel(data: ProjectPostData): String =
      "${escapeHtml(data.memberName)} (<a href=\"${memberProfileUrl(data.memberUsername)}\">" +
          "@${escapeHtml(data.memberUsername)}</a>)"
}
