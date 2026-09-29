package org.dallasmakerspace.notifications

import org.dallasmakerspace.notifications.channels.OutgoingMessage
import org.dallasmakerspace.notifications.templates.escapeHtml

/**
 * Pure helpers for building one message from several stored notification rows: scheduled digests
 * and immediate bursts combined by the hold window. Kept side-effect free so they can be unit
 * tested without a database.
 *
 * Items are grouped under a heading per topic, and rows with identical rendered content (e.g. the
 * same announcement emitted twice without a dedupe key) are shown once - every row is still marked
 * sent, the reader just doesn't see the repeat.
 */
object DigestBodyBuilder {
  fun subject(items: List<Notification>): String {
    val count = distinctItems(items).size
    val cadence =
        when (items.map { it.aggregation }.distinct().singleOrNull()) {
          Aggregation.WEEKLY -> "weekly "
          Aggregation.DAILY -> "daily "
          else -> ""
        }
    return "Your Dallas Makerspace ${cadence}digest: $count update${if (count == 1) "" else "s"}"
  }

  /** Plain-text body: each item's rendered text (via [textOf]), grouped by topic. */
  fun textBody(items: List<Notification>, textOf: (Notification) -> String): String =
      byTopic(items).entries.joinToString(separator = "\n\n") { (topic, topicItems) ->
        val heading = "== ${topic.displayName} =="
        val body = topicItems.joinToString(separator = "\n\n----\n\n") { textOf(it).trim() }
        "$heading\n\n$body"
      }

  fun htmlBody(items: List<Notification>): String =
      "<p>Here's what you missed at Dallas Makerspace:</p>\n" + sectionsHtml(items, headings = true)

  /**
   * One immediate email for a burst of notifications that arrived within the hold window. A single
   * item is sent exactly as rendered; several are combined under a topic-specific subject.
   */
  fun combined(items: List<Notification>, textOf: (Notification) -> String): OutgoingMessage {
    val distinct = distinctItems(items)
    val single = distinct.singleOrNull()
    if (single != null) {
      return OutgoingMessage(
          subject = single.subject,
          text = textOf(single),
          html = single.body ?: "<p>${escapeHtml(single.subject)}</p>")
    }
    val topics = distinct.map { it.topic }.distinct()
    val subject =
        topics.singleOrNull()?.combinedSubject(distinct.size)
            ?: "${distinct.size} new updates from Dallas Makerspace"
    val text =
        if (topics.size == 1) {
          distinct.joinToString(separator = "\n\n----\n\n") { textOf(it).trim() }
        } else {
          textBody(items, textOf)
        }
    return OutgoingMessage(subject, text, sectionsHtml(items, headings = topics.size > 1))
  }

  /** Appends a short note (e.g. "daily limit reached") to both parts of [message]. */
  fun withNote(message: OutgoingMessage, noteText: String, noteHtml: String): OutgoingMessage =
      message.copy(
          text = "${message.text.trimEnd()}\n\n$noteText",
          html =
              "${message.html}\n<p style=\"margin-top:16px;padding:12px;background:#fff3cd;" +
                  "border-radius:6px;font-size:14px;\">$noteHtml</p>")

  private fun sectionsHtml(items: List<Notification>, headings: Boolean): String =
      byTopic(items).entries.joinToString(separator = "\n") { (topic, topicItems) ->
        val itemsHtml =
            topicItems.joinToString(separator = ITEM_SEPARATOR) { item ->
              item.body?.takeIf { it.isNotBlank() } ?: "<p>${escapeHtml(item.subject)}</p>"
            }
        if (headings) {
          "<h2 style=\"font-size:18px;margin:16px 0 8px;\">${escapeHtml(topic.displayName)}</h2>\n" +
              itemsHtml
        } else {
          itemsHtml
        }
      }

  private fun distinctItems(items: List<Notification>): List<Notification> =
      items.distinctBy { Triple(it.topic.key, it.subject, it.body) }

  private fun byTopic(items: List<Notification>): Map<NotificationTopic, List<Notification>> =
      distinctItems(items).groupBy { it.topic }

  private const val ITEM_SEPARATOR =
      "\n<hr style=\"border:none;border-top:1px solid #ddd;margin:16px 0;\"/>\n"
}
