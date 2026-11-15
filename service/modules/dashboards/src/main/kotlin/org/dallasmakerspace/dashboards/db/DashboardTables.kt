package org.dallasmakerspace.dashboards.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.datetime

/** `dashboard_pages`: one row per page of a committee's TV dashboard. [committeeId] is `Committee.id`. */
@Suppress("MagicNumber")
object DashboardPagesTable : IntIdTable("dashboard_pages") {
  val committeeId = integer("committee_id").index()
  val position = integer("position")
  val layout = varchar("layout", 24)
  val title = varchar("title", 80).default("")
  val durationSeconds = integer("duration_seconds").default(20)
  val enabled = bool("enabled").default(true)
  val updatedBy = varchar("updated_by", 64)
  val updatedAt = datetime("updated_at")
}

/** `dashboard_widgets`: the widget in one slot of a page; [config] is `WidgetConfig` JSON. */
@Suppress("MagicNumber")
object DashboardWidgetsTable : IntIdTable("dashboard_widgets") {
  val pageId = reference("page_id", DashboardPagesTable, onDelete = ReferenceOption.CASCADE)
  val slot = integer("slot")
  val type = varchar("type", 24)
  val config = text("config")
}

/** `dashboard_announcements`: dashboard-only announcements; start/end are UTC, null = no bound. */
@Suppress("MagicNumber")
object DashboardAnnouncementsTable : IntIdTable("dashboard_announcements") {
  val committeeId = integer("committee_id").index()
  val text = varchar("text", 200)
  val startsAt = datetime("starts_at").nullable()
  val endsAt = datetime("ends_at").nullable()
  val createdBy = varchar("created_by", 64)
  val createdAt = datetime("created_at")
}

/** `cast_targets`: a TV the cast agent keeps showing the dashboard on. */
@Suppress("MagicNumber")
object CastTargetsTable : IntIdTable("cast_targets") {
  val committeeId = integer("committee_id").index()
  val name = varchar("name", 80)
  val host = varchar("host", 255)
  val port = integer("port").default(8009)
  val enabled = bool("enabled").default(true)
  val displayStart = varchar("display_start", 5).nullable()
  val displayEnd = varchar("display_end", 5).nullable()
  val reclaimAfterMinutes = integer("reclaim_after_minutes").nullable()
  val tokenGeneration = integer("token_generation").default(1)
  val lastSeenAt = datetime("last_seen_at").nullable()
  val lastStatus = varchar("last_status", 120).nullable()
}
