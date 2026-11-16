package org.dallasmakerspace.dashboards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.dallasmakerspace.models.Committee

class DashboardValidatorTest {
  private val committee =
      Committee(id = 99, name = "Test Shop", chairGroupName = "Test Chair", groupPrefixes = listOf("TS"))

  private fun page(
      layout: DashboardLayout = DashboardLayout.TWO_COL,
      vararg widgets: WidgetDto,
      seconds: Int = 20,
  ) = PageDto(layout = layout, durationSeconds = seconds, widgets = widgets.toList())

  private fun counts(slot: Int, vararg groups: String) =
      WidgetDto(slot, WidgetType.GROUP_COUNT, WidgetConfig(groupNames = groups.toList()))

  private fun validate(vararg pages: PageDto) =
      DashboardValidator.validateDocument(committee, DashboardDocument(pages.toList()))

  @Test
  fun `a well formed page passes`() {
    validate(
        page(
            DashboardLayout.TWO_COL,
            counts(0, "TS Basics"),
            WidgetDto(1, WidgetType.UPCOMING_CLASSES, WidgetConfig(limit = 3)),
        ))
  }

  @Test
  fun `layouts have fixed slot counts`() {
    assertEquals(
        listOf(1, 2, 3, 4),
        listOf(DashboardLayout.FULL, DashboardLayout.TWO_COL, DashboardLayout.HERO_PLUS_2, DashboardLayout.GRID_2X2)
            .map { it.slots })
  }

  @Test
  fun `a widget outside the layout is rejected`() {
    assertFailsWith<DashboardException> { validate(page(DashboardLayout.FULL, counts(1, "TS Basics"))) }
  }

  @Test
  fun `two widgets in one slot are rejected`() {
    assertFailsWith<DashboardException> {
      validate(page(DashboardLayout.TWO_COL, counts(0, "TS Basics"), counts(0, "TS Advanced")))
    }
  }

  @Test
  fun `counting a group the committee does not own is rejected`() {
    assertFailsWith<DashboardException> { validate(page(DashboardLayout.FULL, counts(0, "Other Group"))) }
  }

  @Test
  fun `show time and page count are bounded`() {
    assertFailsWith<DashboardException> { validate(page(DashboardLayout.FULL, counts(0, "TS A"), seconds = 1)) }
    assertFailsWith<DashboardException> {
      validate(*Array(MAX_PAGES + 1) { page(DashboardLayout.FULL, counts(0, "TS A")) })
    }
  }

  @Test
  fun `a text card needs content and a safe image key`() {
    fun card(config: WidgetConfig) = page(DashboardLayout.FULL, WidgetDto(0, WidgetType.TEXT_IMAGE, config))

    assertFailsWith<DashboardException> { validate(card(WidgetConfig())) }
    assertFailsWith<DashboardException> { validate(card(WidgetConfig(imageKey = "../secret"))) }
    validate(card(WidgetConfig(heading = "Safety first", imageKey = "3f2a6c1e-0b7d-4a55-9c1e-2d4b6a8c0e11.jpg")))
  }

  @Test
  fun `announcements need text and a sensible window`() {
    DashboardValidator.validateAnnouncement(AnnouncementDto(text = "Shop closed Monday"))
    assertFailsWith<DashboardException> { DashboardValidator.validateAnnouncement(AnnouncementDto(text = " ")) }
    assertFailsWith<DashboardException> {
      DashboardValidator.validateAnnouncement(AnnouncementDto(text = "x".repeat(MAX_ANNOUNCEMENT_LENGTH + 1)))
    }
    assertFailsWith<DashboardException> {
      DashboardValidator.validateAnnouncement(
          AnnouncementDto(text = "x", startsAt = "2026-05-02T10:00", endsAt = "2026-05-01T10:00"))
    }
  }

  @Test
  fun `a TV needs a host, a port and consistent hours`() {
    val ok = CastTargetDto(name = "Shop TV", host = "tv-1.example.internal")
    DashboardValidator.validateTarget(ok)
    DashboardValidator.validateTarget(ok.copy(displayStart = "08:00", displayEnd = "21:30", reclaimAfterMinutes = 60))

    listOf(
            ok.copy(name = ""),
            ok.copy(host = "bad host!"),
            ok.copy(port = 0),
            ok.copy(displayStart = "08:00"),
            ok.copy(displayStart = "08:00", displayEnd = "08:00"),
            ok.copy(displayStart = "8am", displayEnd = "9pm"),
            ok.copy(reclaimAfterMinutes = 0),
        )
        .forEach { assertFailsWith<DashboardException>("for $it") { DashboardValidator.validateTarget(it) } }
  }

  @Test
  fun `a moved TV needs a valid host`() {
    DashboardValidator.validateHost("192.0.2.77")
    DashboardValidator.validateHost("tv-1.example.internal")
    assertFailsWith<DashboardException> { DashboardValidator.validateHost("bad host!") }
    assertFailsWith<DashboardException> { DashboardValidator.validateHost("") }
  }

  private fun list(vararg usernames: String) =
      page(
          DashboardLayout.FULL,
          WidgetDto(0, WidgetType.MEMBER_LIST, WidgetConfig(members = usernames.toList())))

  @Test
  fun `a member list needs members and valid usernames`() {
    validate(list("jdoe", "asmith"))
    assertFailsWith<DashboardException> { validate(list()) }
    assertFailsWith<DashboardException> { validate(list("bad name")) }
    assertFailsWith<DashboardException> { validate(list("jdoe", "jdoe")) }
  }

  @Test
  fun `a member list is capped`() {
    val tooMany = (1..MAX_LIST_MEMBERS + 1).map { "user$it" }.toTypedArray()
    val error = assertFailsWith<DashboardException> { validate(list(*tooMany)) }
    assertTrue(error.message!!.contains("too many members"))
    validate(list(*tooMany.take(MAX_LIST_MEMBERS).toTypedArray()))
  }
}
