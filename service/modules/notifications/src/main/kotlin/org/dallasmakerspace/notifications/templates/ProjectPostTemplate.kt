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

  /**
   * Two columns - thumbnail left, text right - built the "hybrid" way: inline-block columns that
   * sit side by side when there's room and stack when there isn't (no media-query support
   * needed), an MSO ghost table for Outlook desktop (which ignores inline-block), and `dms-col` /
   * `dms-thumb` classes that the email shell's mobile media query uses to make the stacked
   * thumbnail full width. The footer line spans both columns.
   */
  override fun renderHtml(data: ProjectPostData): String = buildString {
    val profile = memberProfileUrl(data.memberUsername)
    val hasImage = data.imageUrl.isNotBlank()
    append("<div style=\"font-size:0;line-height:0;\">")
    if (hasImage) {
      append("<!--[if mso]><table role=\"presentation\" width=\"$COLUMNS_WIDTH\"><tr>")
      append("<td width=\"$THUMB_WIDTH\" valign=\"top\"><![endif]-->")
      append("<div class=\"dms-col\" style=\"display:inline-block;vertical-align:top;")
      append("width:${THUMB_WIDTH}px;max-width:100%;font-size:16px;line-height:22px;\">")
      append("<a href=\"$profile\" style=\"text-decoration:none;\">")
      append("<img class=\"dms-thumb\" src=\"${escapeHtml(data.imageUrl)}\" ")
      append("alt=\"${escapeHtml(data.topicTitle)}\" width=\"$THUMB_WIDTH\" ")
      append("height=\"$THUMB_HEIGHT\" style=\"display:block;width:${THUMB_WIDTH}px;")
      append("height:${THUMB_HEIGHT}px;object-fit:cover;border:0;border-radius:12px;")
      append("background-color:#1a1a1a;box-shadow:0 2px 10px rgba(0,0,0,0.09);\"></a></div>")
      append("<!--[if mso]></td><td width=\"${COLUMNS_WIDTH - THUMB_WIDTH}\" valign=\"top\"><![endif]-->")
    }
    append("<div class=\"dms-col\" style=\"display:inline-block;vertical-align:top;width:100%;")
    append("max-width:${if (hasImage) TEXT_WIDTH else COLUMNS_WIDTH}px;font-size:16px;line-height:22px;\">")
    append("<div class=\"dms-text\" style=\"padding:0")
    if (hasImage) append(" 0 0 $GUTTER")
    append(";\">")
    append("<p style=\"margin:0 0 8px;\">${memberHtmlLabel(data)} shared a project in ")
    append("${escapeHtml(data.topicTitle)}:</p>")
    if (data.excerpt.isNotBlank()) {
      append("<p style=\"margin:0 0 10px;color:#555;\">${escapeHtml(data.excerpt)}</p>")
    }
    append("<p style=\"margin:0;\"><a href=\"$profile\">See their projects</a></p>")
    append("</div></div>")
    if (hasImage) append("<!--[if mso]></td></tr></table><![endif]-->")
    append("</div>")
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

  private companion object {
    /** Matches `.featured-project-img-wrap` on the profile page. */
    const val THUMB_WIDTH = 218
    const val THUMB_HEIGHT = 147

    /** Usable width inside the email shell's card (600px - 2x30px padding - 2x20px card padding). */
    const val COLUMNS_WIDTH = 500
    const val TEXT_WIDTH = COLUMNS_WIDTH - THUMB_WIDTH
    const val GUTTER = "16px"
  }
}
