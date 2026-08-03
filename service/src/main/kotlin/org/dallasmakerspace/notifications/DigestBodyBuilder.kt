package org.dallasmakerspace.notifications

/**
 * Pure helpers for building the per-member digest email from stored notification rows. Kept
 * side-effect free so they can be unit tested without a database.
 *
 * Each stored notification body is already-rendered HTML (including its own unsubscribe link), so
 * the digest HTML body concatenates them inside a light envelope. The plain-text body lists the
 * item subjects since no plain-text form of each item is stored.
 */
object DigestBodyBuilder {
  fun subject(itemCount: Int): String =
      "Dallas Makerspace digest: $itemCount update${if (itemCount == 1) "" else "s"}"

  /**
   * Plain-text body: only subjects are available (only the rendered HTML form of each item is
   * stored, so embedding it would leak raw HTML into the text part).
   */
  fun textBody(items: List<Notification>): String =
      items.joinToString(separator = "\n") { it.subject }

  fun htmlBody(items: List<Notification>): String {
    val itemsHtml =
        items.joinToString(
            separator = "\n<hr style=\"border:none;border-top:1px solid #ddd;margin:16px 0;\"/>\n",
        ) { item ->
          item.body?.takeIf { it.isNotBlank() } ?: item.subject
        }
    return "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#333;\">" +
        "<p>Here are your updates from Dallas Makerspace:</p>\n$itemsHtml</div>"
  }
}
