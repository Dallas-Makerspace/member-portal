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
  MEMBER_LIST,
  TOOL_STATUS,
}

/** TOOL_STATUS: which tools the card lists. */
@Serializable
enum class ToolMode {
  IN_USE,
  MAINTENANCE,
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
    /** MEMBER_LIST: usernames, in display order. */
    val members: List<String> = emptyList(),
    /** TOOL_STATUS: the tools to list. */
    val toolMode: ToolMode = ToolMode.IN_USE,
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

/** Unsaved pages to preview; [pin] shows only that page (by position), even if it is hidden or empty. */
@Serializable data class DraftPreviewRequest(val pages: List<PageDto> = emptyList(), val pin: Int? = null)

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
    /**
     * What the TV calls itself on the network (its Cast name). The agent fills it in once it has seen the
     * TV; a chair can enter it first so the agent can find a TV whose address is unknown or has changed.
     */
    val deviceName: String? = null,
)

@Serializable
data class GroupCount(val name: String, val count: Int?, val trend: List<Int> = emptyList())

@Serializable data class ClassRow(val name: String, val startLabel: String, val startMs: Long?)

/** [photoUrl] is a public picture address; [hasPhoto] means an uploaded photo the TV fetches with its token. */
/** One tool on a tool-status card; [operator] is who is using it, when the system can tell. */
@Serializable
data class ToolRow(
    val name: String,
    val operator: MemberRow? = null,
    val operatorName: String? = null,
)

@Serializable
data class MemberRow(
    val username: String,
    val displayName: String,
    val photoUrl: String? = null,
    val hasPhoto: Boolean = false,
)

@Serializable
data class RenderedWidget(
    val slot: Int,
    val type: WidgetType,
    val groups: List<GroupCount> = emptyList(),
    val classes: List<ClassRow> = emptyList(),
    val members: List<MemberRow> = emptyList(),
    val tools: List<ToolRow> = emptyList(),
    val toolMode: ToolMode? = null,
    /** False when the tool system could not be reached; the TV says so instead of "none". */
    val toolsAvailable: Boolean = true,
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

/** One TV on the TVs / Displays page. [online] is null until the agent has ever reported on it. */
@Serializable
data class DisplayDto(
    val id: Int,
    val name: String,
    /** Null for a device the network sweep found that no committee has set up. */
    val committeeName: String? = null,
    val committeeSlug: String? = null,
    val managed: Boolean = true,
    /** One of ONLINE, OFFLINE, UNSEEN, DISABLED (set up), or ON_NETWORK, AWAY (found by the sweep only). */
    val state: String = DisplayState.UNSEEN,
    val host: String,
    val deviceName: String? = null,
    val enabled: Boolean,
    val online: Boolean? = null,
    val lastSeenAt: String? = null,
    val showing: String,
    val showingDashboard: Boolean = false,
    /** A web address for what is playing, when the TV's app shares one. */
    val mediaUrl: String? = null,
    val dashboardPages: List<String> = emptyList(),
    val schedule: String,
    val inHours: Boolean = true,
    val reclaimAfterMinutes: Int? = null,
)

object DisplayState {
  const val ONLINE = "ONLINE"
  const val OFFLINE = "OFFLINE"
  const val UNSEEN = "UNSEEN"
  const val DISABLED = "DISABLED"
  const val ON_NETWORK = "ON_NETWORK"
  const val AWAY = "AWAY"
}

/** What a Cast device was playing when the sweep looked; [url] is a web address when the app shares one. */
@Serializable
data class DiscoveredMedia(
    val state: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val url: String? = null,
    val contentType: String? = null,
)

/** A Cast device the cast agent's network sweep found, with what it was running. */
@Serializable
data class DiscoveredDevice(
    val deviceId: String,
    val name: String? = null,
    val host: String,
    val appName: String? = null,
    val statusText: String? = null,
    val media: DiscoveredMedia? = null,
)

@Serializable data class DiscoveredBatch(val devices: List<DiscoveredDevice> = emptyList())

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
    val deviceId: String? = null,
    val deviceName: String? = null,
)

/** The TV's own id and name, reported by the agent so it can find the TV again if its address changes. */
@Serializable data class TargetIdentity(val deviceId: String, val deviceName: String? = null)

@Serializable data class TargetHost(val host: String)

/** Body of the editor's "add members" and "add a group" calls. */
@Serializable data class UsernamePick(val usernames: List<String> = emptyList())

@Serializable data class GroupPick(val group: String = "")

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
const val MAX_LIST_MEMBERS = 20
const val MAX_GROUP_MEMBERS_TO_ADD = 10
