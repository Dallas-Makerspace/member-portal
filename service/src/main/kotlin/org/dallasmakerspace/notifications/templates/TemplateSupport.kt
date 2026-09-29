package org.dallasmakerspace.notifications.templates

private const val MEMBER_PORTAL_BASE_URL = "https://members.dallasmakerspace.org"

/** Escapes user-provided text for safe embedding in HTML. */
fun escapeHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

/** Link to a member's public profile page (e.g. to credit an event organizer). */
fun memberProfileUrl(username: String): String = "$MEMBER_PORTAL_BASE_URL/profile/@$username"

/** Link to a member's notification inbox and preferences page. */
fun notificationPreferencesUrl(username: String): String =
    "$MEMBER_PORTAL_BASE_URL/profile/@$username/notifications"

private val HTML_TAG_REGEX = Regex("<[^>]*>")
private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * Plain-text preview of rendered HTML (e.g. a Discourse post's `cooked` body): tags stripped,
 * entities for the common characters decoded, whitespace collapsed, truncated to [maxLength] with
 * an ellipsis. The result is plain text; escape it before embedding in HTML.
 */
fun htmlExcerpt(html: String, maxLength: Int = 200): String {
  val text =
      html
          .replace(HTML_TAG_REGEX, " ")
          .replace("&nbsp;", " ")
          .replace("&lt;", "<")
          .replace("&gt;", ">")
          .replace("&quot;", "\"")
          .replace("&#39;", "'")
          .replace("&amp;", "&")
          .replace(WHITESPACE_REGEX, " ")
          .trim()
  return if (text.length <= maxLength) text else text.take(maxLength).trimEnd() + "…"
}
