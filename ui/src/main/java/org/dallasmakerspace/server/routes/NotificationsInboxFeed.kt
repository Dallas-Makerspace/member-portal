package org.dallasmakerspace.server.routes

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import org.dallasmakerspace.models.TopicKeys

/** Cards shown before "Load more". */
const val INBOX_PAGE_SIZE = 25

/** `?filter=` values the inbox accepts, mapped to the topic they show. */
val INBOX_FILTER_TOPICS = mapOf("people" to TopicKeys.FOLLOWING, "committees" to TopicKeys.COMMITTEE_UPDATES)

private const val RECENT_DAYS = 6L

/**
 * Builds what the inbox page renders from the service's raw notification rows: delivery metadata,
 * unread flags (taken from `readAt`, so call this before the inbox is marked read), digest bundles,
 * and day groups. SKIPPED rows (topic off or member inactive) are hidden from members.
 *
 * Model keys: `groups` (list of `{label, items}`), `new_count`, `total_count`, `has_any` (anything
 * at all before the filter), `page_size`.
 */
fun buildInboxFeed(
    notifications: List<Map<String, Any?>>,
    today: LocalDate,
    filter: String? = null,
    showErrors: Boolean = false,
): Map<String, Any?> {
  val visible =
      notifications
          .filter { showErrors || it["status"] != "SKIPPED" }
          .map { enrichNotification(it, showErrors) + ("unread" to isUnread(it)) }
  val topic = INBOX_FILTER_TOPICS[filter]
  val filtered = if (topic == null) visible else visible.filter { it["topic"] == topic }
  val items = groupDigests(filtered).mapIndexed { index, row -> inboxItem(row, index + 1) }
  val groups =
      items
          .groupBy { dayLabel(it["when"] as? String, today) }
          .map { (label, group) -> mapOf<String, Any?>("label" to label, "items" to group) }
  return mapOf(
      "groups" to groups,
      "new_count" to items.count { it["unread"] == true },
      "total_count" to items.size,
      "has_any" to visible.isNotEmpty(),
      "page_size" to INBOX_PAGE_SIZE,
  )
}

/** Delivered and not opened yet; anything not sent can't have been seen. */
private fun isUnread(row: Map<String, Any?>): Boolean =
    row["status"] == "SENT" && (row["readAt"] as? String).isNullOrBlank()

/** The group heading for a Central-time ISO timestamp. */
fun dayLabel(centralIso: String?, today: LocalDate): String {
  val date = centralIso?.let { runCatching { LocalDateTime.parse(it).toLocalDate() }.getOrNull() }
  val days = date?.let { ChronoUnit.DAYS.between(it, today) }
  return when {
    days == null -> "Earlier"
    days <= 0 -> "Today"
    days == 1L -> "Yesterday"
    days <= RECENT_DAYS -> "Earlier this week"
    else -> "Earlier"
  }
}

/** One card (or digest bundle) for the template. */
private fun inboxItem(row: Map<String, Any?>, position: Int): Map<String, Any?> =
    (if (row["isDigest"] == true) digestItem(row) else cardItem(row)) + ("position" to position)

@Suppress("UNCHECKED_CAST")
private fun cardItem(row: Map<String, Any?>): Map<String, Any?> {
  val card = row["card"] as? Map<String, Any?>
  val status = row["status"] as? String ?: ""
  val (note, problem) = deliveryNote(status, row["nextAttemptAt"] as? String)
  val url = safeUrl(card?.get("url") as? String)
  val title = (card?.get("title") as? String)?.takeIf { it.isNotBlank() } ?: (row["subject"] as? String ?: "")
  val ctaLabel = if (url != null) (card?.get("ctaLabel") as? String)?.ifBlank { "Open" } ?: "Open" else ""
  return mapOf(
      "id" to row["id"],
      "isDigest" to false,
      "unread" to (row["unread"] == true),
      "kind" to (card?.get("kind") as? String ?: "message"),
      "title" to title,
      "excerpt" to (card?.get("excerpt") as? String ?: ""),
      "imageUrl" to (safeUrl(card?.get("imageUrl") as? String) ?: ""),
      "url" to (url ?: ""),
      "ctaLabel" to ctaLabel,
      "sender" to (card?.get("sender") as? String ?: ""),
      "whenLine" to (card?.get("whenLine") as? String ?: ""),
      "when" to row["when"],
      "note" to note,
      "noteProblem" to problem,
      "noteDetail" to row["status_reason"],
      "errorDetail" to row["error_detail"],
      "topicDisplay" to row["topic_display"],
  )
}

@Suppress("UNCHECKED_CAST")
private fun digestItem(row: Map<String, Any?>): Map<String, Any?> {
  val children = (row["children"] as? List<Map<String, Any?>>).orEmpty()
  val cards = children.map(::cardItem)
  val first = children.firstOrNull()
  val title = digestTitle(children)
  val shown = cards.take(2).joinToString(", ") { it["title"] as String }
  val more = cards.size - 2
  return mapOf(
      "id" to row["groupId"],
      "isDigest" to true,
      "unread" to children.any { it["unread"] == true },
      "kind" to "digest",
      "title" to title,
      "preview" to if (more > 0) "$shown +$more more" else shown,
      "childCount" to cards.size,
      "children" to cards,
      "when" to (row["when"] ?: first?.get("when")),
      "groupId" to row["groupId"],
  )
}

private fun digestTitle(children: List<Map<String, Any?>>): String {
  val count = children.size
  val kind =
      when {
        children.all { it["aggregation"] == "IMMEDIATE" } -> "Combined email"
        children.any { it["aggregation"] == "WEEKLY" } -> "Weekly digest"
        else -> "Daily digest"
      }
  return "$kind · $count updates"
}

/** Short delivery note for non-sent notifications and whether it is a problem to flag. */
private fun deliveryNote(status: String, nextAttemptAt: String?): Pair<String?, Boolean> =
    when (status) {
      "IN_DIGEST" ->
          (formatCentral(nextAttemptAt)?.let { "In next digest ($it)" } ?: "In next digest") to false
      "QUEUED" -> "Sending soon" to false
      "SENDING" -> "Sending" to false
      "FAILED" -> "Retrying" to true
      "DEAD_LETTER" -> "Not delivered" to true
      "SUPPRESSED" -> "Paused" to true
      "SKIPPED" -> "Not sent" to false
      else -> null to false
    }

/** Payload URLs come from our own producers, but only ever link http(s). */
private fun safeUrl(url: String?): String? =
    url?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
