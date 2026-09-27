package org.dallasmakerspace.models

private const val DISCOURSE_BASE_URL = "https://talk.dallasmakerspace.org"
private const val DEFAULT_AVATAR_SIZE = 144

/**
 * Normalize a Discourse avatar path into an absolute URL at [size] px. Handles absolute,
 * protocol-relative ("//...") and site-relative ("/user_avatar/...") forms, and substitutes
 * Discourse's "{size}" placeholder. Also passes an already-relative same-origin "/avatar/..." path
 * (our own uploaded-photo route, see [DMSMember.displayAvatarUrl]) through unchanged rather than
 * prefixing it with the Discourse host. Returns "" for a null/blank/missing [rawUrl].
 *
 * The single shared implementation - previously reimplemented (inconsistently; several copies were
 * missing the absolute-URL passthrough and would double-prefix an already-qualified URL) in both
 * `service` and `ui` across a dozen call sites.
 */
fun normalizeAvatarUrl(rawUrl: String?, size: Int = DEFAULT_AVATAR_SIZE): String {
  val url = rawUrl?.takeIf { it.isNotEmpty() } ?: return ""
  if (url.startsWith("/avatar/")) return url
  return when {
    url.startsWith("http://") || url.startsWith("https://") -> url
    url.startsWith("//") -> "https:$url"
    else -> "$DISCOURSE_BASE_URL$url"
  }.replace("{size}", size.toString())
}
