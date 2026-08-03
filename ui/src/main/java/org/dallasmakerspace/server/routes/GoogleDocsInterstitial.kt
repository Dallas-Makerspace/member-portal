package org.dallasmakerspace.server.routes

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import org.dallasmakerspace.server.common.logging.appLogger

private val log = appLogger("GoogleDocsInterstitial")

private val GOOGLE_DOCS_HOSTS =
    setOf(
        "docs.google.com",
        "sheets.google.com",
        "slides.google.com",
        "forms.google.com",
        "forms.gle",
        "drive.google.com",
    )

/**
 * When [location] points at a Google Docs-family destination (Docs, Sheets, Slides, Forms, Drive),
 * returns it with `authuser=<dmsEmail>` applied (replacing any existing value) so Google switches
 * to the member's DMS Workspace session automatically if it's already active in their browser.
 * Returns the original [location] unchanged (still non-null) when [dmsEmail] is null. Returns null
 * when [location] is not a Google Docs-family destination.
 */
fun googleDocsDestinationOrNull(location: String, dmsEmail: String?): String? {
  val url =
      runCatching { Url(location) }
          .onFailure { log.warn("Failed to parse short link destination: $location", it) }
          .getOrNull() ?: return null

  if (url.host !in GOOGLE_DOCS_HOSTS) return null
  if (dmsEmail == null) return location

  return runCatching {
        URLBuilder(url)
            .apply {
              parameters.remove("authuser")
              parameters.append("authuser", dmsEmail)
            }
            .buildString()
      }
      .onFailure { log.warn("Failed to append authuser hint to destination: $location", it) }
      .getOrDefault(location)
}
