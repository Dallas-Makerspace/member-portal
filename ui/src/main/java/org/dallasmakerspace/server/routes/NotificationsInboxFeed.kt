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
 * Digest emails are bundled first and the filter applied to the bundles, so a filtered bundle
 * reads "2 of 5 updates" rather than pretending it only had two. [capped] says the service
 * returned as many rows as it was asked for, so older ones may exist.
 *
 * Model keys: `groups` (list of `{label, items}`), `new_count` (notifications, not bundles),
 * `total_count` (cards), `has_any` (anything at all before the filter), `page_size`, `capped`.
 */
fun buildInboxFeed(
    notifications: List<Map<String, Any?>>,
    today: LocalDate,
    filter: String? = null,
    showErrors: Boolean = false,
    now: LocalDateTime = today.atTime(12, 0),
    capped: Boolean = false,
): Map<String, Any?> {
  val visible =
      notifications
          .filter { showErrors || it["status"] != "SKIPPED" }
          .map { enrichNotification(it, showErrors) + ("unread" to isUnread(it)) }
          // The service lists by id; a row sent late can carry an older id than its neighbours.
          .sortedByDescending { it["when"] as? String ?: "" }
  val rows = applyFilter(groupDigests(visible), INBOX_FILTER_TOPICS[filter])
  val items = rows.mapIndexed { index, row -> inboxItem(row, index + 1, now) }
  val groups =
      items
          .groupBy { dayLabel(it["when"] as? String, today) }
          .map { (label, group) -> mapOf<String, Any?>("label" to label, "items" to group) }
  return mapOf(
      "groups" to groups,
      "new_count" to items.sumOf(::unreadCount),
      "total_count" to items.size,
      "has_any" to visible.isNotEmpty(),
      "page_size" to INBOX_PAGE_SIZE,
      "capped" to capped,
  )
}

/** Keeps the rows of one topic; a bundle keeps only its matching items (one left becomes a card). */
@Suppress("UNCHECKED_CAST")
private fun applyFilter(rows: List<Map<String, Any?>>, topic: String?): List<Map<String, Any?>> {
  if (topic == null) return rows
  return rows.mapNotNull { row ->
    if (row["isDigest"] != true) return@mapNotNull row.takeIf { it["topic"] == topic }
    val children = (row["children"] as? List<Map<String, Any?>>).orEmpty()
    val kept = children.filter { it["topic"] == topic }
    when {
      kept.isEmpty() -> null
      kept.size == 1 -> kept.first() + ("isDigest" to false)
      kept.size == children.size -> row
      else -> row + mapOf("children" to kept, "originalCount" to children.size)
    }
  }
}

/** Notifications still unread inside a card or bundle. */
@Suppress("UNCHECKED_CAST")
private fun unreadCount(item: Map<String, Any?>): Int =
    if (item["isDigest"] == true) {
      (item["children"] as? List<Map<String, Any?>>).orEmpty().count { it["unread"] == true }
    } else if (item["unread"] == true) 1 else 0

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
private fun inboxItem(row: Map<String, Any?>, position: Int, now: LocalDateTime): Map<String, Any?> =
    (if (row["isDigest"] == true) digestItem(row, now) else cardItem(row, now)) + ("position" to position)

@Suppress("UNCHECKED_CAST")
private fun cardItem(row: Map<String, Any?>, now: LocalDateTime): Map<String, Any?> {
  val card = row["card"] as? Map<String, Any?>
  val status = row["status"] as? String ?: ""
  val (note, problem) = deliveryNote(status, row["nextAttemptAt"] as? String)
  val url = safeUrl(card?.get("url") as? String)
  val whenLine = card?.get("whenLine") as? String ?: ""
  val title = (card?.get("title") as? String)?.takeIf { it.isNotBlank() } ?: (row["subject"] as? String ?: "")
  val ctaLabel = if (url != null) (card?.get("ctaLabel") as? String)?.ifBlank { "Open" } ?: "Open" else ""
  return mapOf(
      "id" to row["id"],
      "isDigest" to false,
      "unread" to (row["unread"] == true),
      "kind" to (card?.get("kind") as? String ?: "message"),
      "title" to title,
      "excerpt" to (card?.get("excerpt") as? String ?: ""),
      "imageUrl" to (safeImageUrl(card?.get("imageUrl") as? String) ?: ""),
      "url" to (url ?: ""),
      "ctaLabel" to ctaLabel,
      "sender" to (card?.get("sender") as? String ?: ""),
      "whenLine" to whenLine,
      // The date line is when the thing happens; say when it arrived too.
      "posted" to if (whenLine.isNotBlank()) postedAgo(row["when"] as? String, now) else null,
      "when" to row["when"],
      "note" to note,
      "noteProblem" to problem,
      "noteDetail" to row["status_reason"],
      "errorDetail" to row["error_detail"],
      "topicDisplay" to row["topic_display"],
  )
}

@Suppress("UNCHECKED_CAST")
private fun digestItem(row: Map<String, Any?>, now: LocalDateTime): Map<String, Any?> {
  val children = (row["children"] as? List<Map<String, Any?>>).orEmpty()
  val cards = children.map { cardItem(it, now) }
  val first = children.firstOrNull()
  val title = digestTitle(children, row["originalCount"] as? Int)
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

private fun digestTitle(children: List<Map<String, Any?>>, originalCount: Int?): String {
  val count = children.size
  val kind =
      when {
        children.all { it["aggregation"] == "IMMEDIATE" } -> "Combined email"
        children.any { it["aggregation"] == "WEEKLY" } -> "Weekly digest"
        else -> "Daily digest"
      }
  val of = if (originalCount != null && originalCount != count) " of $originalCount" else ""
  return "$kind · $count$of updates"
}

/** "Posted 2 hours ago" for the last day, then the date. */
fun postedAgo(centralIso: String?, now: LocalDateTime): String? {
  val at = centralIso?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
  val minutes = ChronoUnit.MINUTES.between(at, now)
  return when {
    minutes < 1 -> "Posted just now"
    minutes < 60 -> "Posted $minutes min ago"
    minutes < MINUTES_PER_DAY -> "Posted ${minutes / 60} h ago"
    minutes < 2 * MINUTES_PER_DAY -> "Posted yesterday"
    else -> "Posted " + at.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
  }
}

private const val MINUTES_PER_DAY = 24L * 60

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

/** Payload URLs come from our own producers; link http(s) or a same-site path, nothing else. */
private fun safeUrl(url: String?): String? =
    url?.trim()?.takeIf {
      it.startsWith("https://") ||
          it.startsWith("http://") ||
          (it.startsWith("/") && !it.startsWith("//"))
    }

/** Images are never loaded over plain http (mixed content). */
private fun safeImageUrl(url: String?): String? = url?.trim()?.takeIf { it.startsWith("https://") }
