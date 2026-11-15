package org.dallasmakerspace.dashboards

import org.dallasmakerspace.models.Committee

enum class DashboardError {
  NOT_FOUND,
  FORBIDDEN,
  INVALID,
  UNAVAILABLE,
}

class DashboardException(
    message: String,
    val error: DashboardError = DashboardError.INVALID,
) : Exception(message)

/** Checks what chairs submit, so the receiver only ever sees well-formed pages. */
object DashboardValidator {
  private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,253}[A-Za-z0-9])?$")
  private const val MAX_GROUP_NAME = 100
  private const val MAX_FILTER_GROUPS = 20
  private const val MAX_NAME = 80
  private const val MAX_PORT = 65535
  private const val MAX_RECLAIM_MINUTES = 1440

  fun validateDocument(committee: Committee, document: DashboardDocument) {
    fail(document.pages.size > MAX_PAGES, "A dashboard can have at most $MAX_PAGES pages")
    document.pages.forEachIndexed { index, page -> validatePage(committee, index + 1, page) }
  }

  private fun validatePage(committee: Committee, number: Int, page: PageDto) {
    val where = "Page $number"
    fail(page.title.length > MAX_NAME, "$where: title is too long")
    fail(
        page.durationSeconds !in MIN_PAGE_SECONDS..MAX_PAGE_SECONDS,
        "$where: show time must be $MIN_PAGE_SECONDS-$MAX_PAGE_SECONDS seconds")
    val slots = page.widgets.map { it.slot }
    fail(slots.any { it !in 0 until page.layout.slots }, "$where: a widget is outside the layout")
    fail(slots.toSet().size != slots.size, "$where: two widgets share a slot")
    page.widgets.forEach { validateWidget(committee, where, it) }
  }

  @Suppress("CyclomaticComplexMethod")
  private fun validateWidget(committee: Committee, where: String, widget: WidgetDto) {
    val config = widget.config
    when (widget.type) {
      WidgetType.GROUP_COUNT -> {
        fail(config.groupNames.isEmpty(), "$where: pick at least one group to count")
        fail(
            config.groupNames.size > MAX_GROUPS_PER_WIDGET,
            "$where: count at most $MAX_GROUPS_PER_WIDGET groups per widget")
        config.groupNames.forEach {
          fail(it.isBlank() || it.length > MAX_GROUP_NAME, "$where: invalid group name")
          fail(!committee.ownsGroup(it), "$where: '$it' is not one of ${committee.name}'s groups")
        }
      }
      WidgetType.UPCOMING_CLASSES -> {
        fail(config.limit !in 1..MAX_CLASS_LIMIT, "$where: list 1-$MAX_CLASS_LIMIT classes")
        fail(config.groupNames.size > MAX_FILTER_GROUPS, "$where: too many groups")
        config.groupNames.forEach {
          fail(it.isBlank() || it.length > MAX_GROUP_NAME, "$where: invalid group name")
          fail(!committee.ownsGroup(it), "$where: '$it' is not one of ${committee.name}'s groups")
        }
      }
      WidgetType.TEXT_IMAGE -> {
        fail((config.heading?.length ?: 0) > MAX_HEADING_LENGTH, "$where: heading is too long")
        fail((config.body?.length ?: 0) > MAX_TEXT_LENGTH, "$where: text is too long")
        config.imageKey?.let {
          fail(
              !DashboardImageService.isValidFile(it),
              "$where: invalid image")
        }
        fail(
            config.heading.isNullOrBlank() &&
                config.body.isNullOrBlank() &&
                config.imageKey.isNullOrBlank(),
            "$where: a text/image card needs a heading, text or image")
      }
    }
  }

  fun validateAnnouncement(dto: AnnouncementDto) {
    fail(dto.text.isBlank(), "Announcement text is required")
    fail(
        dto.text.length > MAX_ANNOUNCEMENT_LENGTH,
        "Announcements are at most $MAX_ANNOUNCEMENT_LENGTH characters")
    val starts = DashboardTime.chicagoToUtc(dto.startsAt)
    val ends = DashboardTime.chicagoToUtc(dto.endsAt)
    fail(starts != null && ends != null && !ends.isAfter(starts), "End must be after the start")
  }

  fun validateTarget(dto: CastTargetDto) {
    fail(dto.name.isBlank() || dto.name.length > MAX_NAME, "A TV needs a name")
    fail(!HOST.matches(dto.host), "Enter the TV's host name or address")
    fail(dto.port !in 1..MAX_PORT, "Invalid port")
    val start = DashboardTime.parseClock(dto.displayStart)
    val end = DashboardTime.parseClock(dto.displayEnd)
    fail((start == null) != (end == null), "Set both display hours or neither")
    fail(start != null && start == end, "Display hours must differ")
    fail(
        dto.reclaimAfterMinutes != null && dto.reclaimAfterMinutes !in 1..MAX_RECLAIM_MINUTES,
        "Take-back time must be 1-$MAX_RECLAIM_MINUTES minutes")
  }

  private fun fail(condition: Boolean, message: String) {
    if (condition) throw DashboardException(message)
  }
}
