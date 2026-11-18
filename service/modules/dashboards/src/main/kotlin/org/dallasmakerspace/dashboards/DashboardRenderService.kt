package org.dallasmakerspace.dashboards

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDateTime
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.calendar.CalendarService
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.dashboards.db.DashboardRepository
import org.dallasmakerspace.members.GroupHistoryRepository
import org.dallasmakerspace.models.ActionType
import org.dallasmakerspace.models.Committee
import org.dallasmakerspace.notifications.db.CommitteeMessagesRepository

/**
 * The pages a TV rotates through: the enabled ones that have widgets. A pinned page (the editor's
 * preview of the page being edited) is shown on its own whatever its state.
 */
internal fun pagesToShow(pages: List<PageDto>, pin: Int?): List<PageDto> =
    if (pin != null) listOfNotNull(pages.getOrNull(pin))
    else pages.filter { it.enabled && it.widgets.isNotEmpty() }

/**
 * Builds what a TV shows: the committee's enabled pages with their widgets filled in, the bottom
 * announcement row and the QR target. Counts and classes are aggregate; only a member-list card a
 * chair filled in names people.
 */
@Singleton
class DashboardRenderService
@Suppress("LongParameterList")
@Inject
constructor(
    private val repository: DashboardRepository,
    private val activeDirectoryService: IActiveDirectoryService,
    private val calendarService: CalendarService,
    private val groupHistory: GroupHistoryRepository,
    private val chairMessages: CommitteeMessagesRepository,
    private val members: DashboardMembers,
    private val interlock: InterlockService,
    private val appConfig: AppConfig,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val groupCounts = TtlCache<String, GroupCount>(COUNT_TTL)
  private val committeeGroups = TtlCache<Int, List<String>>(GROUPS_TTL)

  /** What a TV shows now, or with [draft] what it would show if the unsaved pages were saved. */
  suspend fun render(
      committee: Committee,
      nowMs: Long = System.currentTimeMillis(),
      draft: DraftPreviewRequest? = null,
  ): DashboardPayload {
    val candidates = mutableListOf<Long>()
    val source = draft?.pages ?: repository.loadPages(committee.id)
    val pages =
        pagesToShow(source, draft?.pin).map { page ->
          RenderedPage(
              title = page.title,
              layout = page.layout,
              durationSeconds = page.durationSeconds,
              widgets = page.widgets.sortedBy { it.slot }.map { renderWidget(committee, it, candidates) })
        }
    val announcements = announcements(committee, candidates)
    return DashboardPayload(
        committeeName = committee.name,
        committeeColor = committee.color,
        qrUrl = "${portalUrl()}/committees/${committee.slug}",
        pages = pages,
        announcements = announcements,
        nextChangeAtMs = DashboardMath.nextChangeAt(nowMs, candidates, COUNT_TTL.toMillis()),
        generatedAtMs = nowMs)
  }

  private suspend fun announcements(
      committee: Committee,
      candidates: MutableList<Long>,
  ): List<String> {
    val now = DashboardTime.nowUtc()
    val own = repository.announcements(committee.id)
    own.forEach {
      DashboardTime.chicagoToUtc(it.startsAt)?.let { s -> candidates += DashboardTime.utcMillis(s) }
      DashboardTime.chicagoToUtc(it.endsAt)?.let { e -> candidates += DashboardTime.utcMillis(e) }
    }
    val active =
        own.filter {
          val starts = DashboardTime.chicagoToUtc(it.startsAt)
          val ends = DashboardTime.chicagoToUtc(it.endsAt)
          (starts == null || !starts.isAfter(now)) && (ends == null || ends.isAfter(now))
        }
    val chair =
        runCatching { chairMessages.recentMessages(committee.id, now.minus(CHAIR_WINDOW)) }
            .onFailure { log.warn("Could not load chair messages for ${committee.name}", it) }
            .getOrDefault(emptyList())
    chair.forEach { candidates += DashboardTime.utcMillis(it.first.plus(CHAIR_WINDOW)) }
    return active.map { it.text } + chair.map { it.second }
  }

  private suspend fun renderWidget(
      committee: Committee,
      widget: WidgetDto,
      candidates: MutableList<Long>,
  ): RenderedWidget =
      when (widget.type) {
        WidgetType.GROUP_COUNT ->
            RenderedWidget(
                slot = widget.slot,
                type = widget.type,
                heading = widget.config.heading,
                groups = widget.config.groupNames.map { groupCount(it, widget.config.showTrend) })
        WidgetType.UPCOMING_CLASSES -> {
          val classes = upcomingClasses(committee, widget.config)
          classes.mapNotNull { it.startMs }.forEach { candidates += it }
          RenderedWidget(
              slot = widget.slot,
              type = widget.type,
              heading = widget.config.heading,
              classes = classes)
        }
        WidgetType.MEMBER_LIST ->
            RenderedWidget(
                slot = widget.slot,
                type = widget.type,
                heading = widget.config.heading,
                members = members.rows(widget.config.members))
        WidgetType.TOOL_STATUS -> {
          val list = interlock.list(widget.config.toolMode)
          candidates += System.currentTimeMillis() + TOOL_REFRESH.toMillis()
          RenderedWidget(
              slot = widget.slot,
              type = widget.type,
              heading = widget.config.heading,
              tools = list.rows,
              toolMode = widget.config.toolMode,
              toolsAvailable = list.available)
        }
        WidgetType.TEXT_IMAGE ->
            RenderedWidget(
                slot = widget.slot,
                type = widget.type,
                heading = widget.config.heading,
                body = widget.config.body,
                imageKey = widget.config.imageKey)
      }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun groupCount(name: String, withTrend: Boolean): GroupCount =
      groupCounts.getOrLoad("$name|$withTrend") {
        try {
          val count =
              withContext(Dispatchers.IO) {
                activeDirectoryService.getGroup(name).members.count { it.enabled }
              }
          GroupCount(name, count, if (withTrend) trend(name, count) else emptyList())
        } catch (e: Exception) {
          log.warn("Could not count group $name for a dashboard", e)
          GroupCount(name, null)
        }
      }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun trend(name: String, count: Int): List<Int> =
      try {
        val changes =
            groupHistory.getGroupHistoryByName(name).map {
              it.eventTimestamp.toJavaLocalDateTime() to
                  (if (it.actionType == ActionType.ADD_USER) 1 else -1)
            }
        DashboardMath.weeklyTrend(count, changes, DashboardTime.nowUtc())
      } catch (e: Exception) {
        log.warn("Could not build the trend for group $name", e)
        emptyList()
      }

  @Suppress("TooGenericExceptionCaught", "ReturnCount")
  private suspend fun upcomingClasses(committee: Committee, config: WidgetConfig): List<ClassRow> {
    val groups = config.groupNames.ifEmpty { ownedGroups(committee) }
    if (groups.isEmpty()) return emptyList()
    val events =
        try {
          calendarService.getUpcomingPrerequisiteEvents(groups)
        } catch (e: Exception) {
          log.warn("Could not load upcoming classes for ${committee.name}", e)
          return emptyList()
        }
    val nowMs = System.currentTimeMillis()
    return events
        .filter { (it.eventStartUtcMs ?: Long.MAX_VALUE) > nowMs }
        .sortedBy { it.eventStartUtcMs }
        .distinctBy { it.id }
        .take(config.limit.coerceIn(1, MAX_CLASS_LIMIT))
        .map { ClassRow(it.name, startLabel(it.eventStartUtcMs), it.eventStartUtcMs) }
  }

  private suspend fun ownedGroups(committee: Committee): List<String> =
      committeeGroups.getOrLoad(committee.id) {
        withContext(Dispatchers.IO) {
          activeDirectoryService.getAllGroups().map { it.cn }.filter { committee.ownsGroup(it) }
        }
      }

  private fun startLabel(startMs: Long?): String =
      startMs
          ?.let { LABEL.format(Instant.ofEpochMilli(it).atZone(DashboardTime.CHICAGO)) }
          .orEmpty()

  private fun portalUrl(): String =
      appConfig.getStringProperty("app.dashboard.portal-url", "http://localhost:8000").trimEnd('/')

  private companion object {
    val COUNT_TTL: Duration = Duration.ofMinutes(30)
    val GROUPS_TTL: Duration = Duration.ofHours(1)
    val CHAIR_WINDOW: Duration = Duration.ofDays(14)
    /** Tool status changes by the minute and has no push source, so a TV asks again this often. */
    val TOOL_REFRESH: Duration = Duration.ofSeconds(60)
    val LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)
  }
}
