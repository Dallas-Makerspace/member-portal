package org.dallasmakerspace.server.models

private const val DISCOURSE_BASE_URL = "https://talk.dallasmakerspace.org"
private const val DEFAULT_AVATAR_SIZE = 144

/**
 * Normalize a Discourse avatar path into an absolute URL at [size] px. Handles absolute,
 * protocol-relative ("//...") and site-relative ("/user_avatar/...") forms, and substitutes
 * Discourse's "{size}" placeholder. Returns "" for a null/blank/missing [rawUrl].
 */
fun normalizeAvatarUrl(rawUrl: String?, size: Int = DEFAULT_AVATAR_SIZE): String =
    rawUrl
        ?.takeIf { it.isNotEmpty() }
        ?.let { url ->
          when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            else -> "$DISCOURSE_BASE_URL$url"
          }.replace("{size}", size.toString())
        } ?: ""
