package org.dallasmakerspace.dashboards

import kotlinx.serialization.Serializable

/** Page layout presets; [slots] is how many widgets the page holds. */
@Suppress("MagicNumber")
@Serializable
enum class DashboardLayout(val slots: Int) {
  FULL(1),
  TWO_COL(2),
  HERO_PLUS_2(3),
  GRID_2X2(4),
}

@Serializable
enum class WidgetType {
  GROUP_COUNT,
  UPCOMING_CLASSES,
  TEXT_IMAGE,
}

/** One flat config for every widget type; each type reads (and validates) only its own fields. */
@Serializable
data class WidgetConfig(
    /** GROUP_COUNT: groups to count. UPCOMING_CLASSES: optional filter; empty = the committee's. */
    val groupNames: List<String> = emptyList(),
    val showTrend: Boolean = true,
    /** UPCOMING_CLASSES: how many classes to list. */
    val limit: Int = DEFAULT_CLASS_LIMIT,
    val heading: String? = null,
    val body: String? = null,
    val imageKey: String? = null,
)

@Serializable
data class WidgetDto(
    val slot: Int,
    val type: WidgetType,
    val config: WidgetConfig = WidgetConfig(),
)

@Serializable
data class PageDto(
    val id: Int? = null,
    val title: String = "",
    val layout: DashboardLayout = DashboardLayout.FULL,
    val durationSeconds: Int = DEFAULT_PAGE_SECONDS,
    val enabled: Boolean = true,
    val widgets: List<WidgetDto> = emptyList(),
)

@Serializable data class DashboardDocument(val pages: List<PageDto> = emptyList())

/** Times are America/Chicago local, `yyyy-MM-ddTHH:mm`; null = no bound. */
@Serializable
data class AnnouncementDto(
    val id: Int? = null,
    val text: String = "",
    val startsAt: String? = null,
    val endsAt: String? = null,
)

/** [displayStart]/[displayEnd] are America/Chicago `HH:mm`; both null = always on. */
@Serializable
data class CastTargetDto(
    val id: Int? = null,
    val name: String = "",
    val host: String = "",
    val port: Int = DEFAULT_CAST_PORT,
    val enabled: Boolean = true,
    val displayStart: String? = null,
    val displayEnd: String? = null,
    val reclaimAfterMinutes: Int? = null,
    val lastSeenAt: String? = null,
    val lastStatus: String? = null,
)

@Serializable
data class GroupCount(val name: String, val count: Int?, val trend: List<Int> = emptyList())

@Serializable data class ClassRow(val name: String, val startLabel: String, val startMs: Long?)

@Serializable
data class RenderedWidget(
    val slot: Int,
    val type: WidgetType,
    val groups: List<GroupCount> = emptyList(),
    val classes: List<ClassRow> = emptyList(),
    val heading: String? = null,
    val body: String? = null,
    val imageKey: String? = null,
)

@Serializable
data class RenderedPage(
    val title: String,
    val layout: DashboardLayout,
    val durationSeconds: Int,
    val widgets: List<RenderedWidget>,
)

@Serializable
data class DashboardPayload(
    val committeeName: String,
    val committeeColor: String?,
    val qrUrl: String,
    val pages: List<RenderedPage>,
    val announcements: List<String>,
    /** Epoch ms of the next time-based change (announcement start/end, class start); null = none. */
    val nextChangeAtMs: Long?,
    val generatedAtMs: Long,
)

/** What the cast agent needs to run one TV. */
@Serializable
data class AgentTarget(
    val id: Int,
    val committeeId: Int,
    val name: String,
    val host: String,
    val port: Int,
    val displayStart: String?,
    val displayEnd: String?,
    val reclaimAfterMinutes: Int?,
)

@Serializable data class TokenResponse(val token: String, val expiresAtMs: Long)

@Serializable data class TargetStatusReport(val app: String? = null, val connected: Boolean = true)

const val DEFAULT_CLASS_LIMIT = 5
const val DEFAULT_PAGE_SECONDS = 20
const val DEFAULT_CAST_PORT = 8009
const val MAX_PAGES = 12
const val MAX_ANNOUNCEMENT_LENGTH = 120
const val MAX_GROUPS_PER_WIDGET = 4
const val MAX_CLASS_LIMIT = 8
const val MIN_PAGE_SECONDS = 10
const val MAX_PAGE_SECONDS = 300
const val MAX_TEXT_LENGTH = 600
const val MAX_HEADING_LENGTH = 80
const val MAX_TARGETS_PER_COMMITTEE = 10
