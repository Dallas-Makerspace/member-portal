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
