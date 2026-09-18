package org.dallasmakerspace.server.bambustatus

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BambuStatusViewModelTest {

  @Test
  fun `formatRemainingTime shows hours and minutes when over an hour`() {
    // remaining_time is in MINUTES on the wire, not seconds - see BambuddyModels.kt.
    assertEquals("1h 12m", formatRemainingTime(72))
  }

  @Test
  fun `formatRemainingTime shows only minutes when under an hour`() {
    assertEquals("45m", formatRemainingTime(45))
  }

  @Test
  fun `formatRemainingTime is blank for null, zero, or negative values`() {
    assertEquals("", formatRemainingTime(null))
    assertEquals("", formatRemainingTime(0))
    assertEquals("", formatRemainingTime(-5))
  }

  // Fixed reference instant (noon America/Chicago) so ETA tests don't depend on when they run.
  private val noonChicago = Instant.parse("2026-01-15T18:00:00Z") // 12:00 PM America/Chicago (CST)

  @Test
  fun `formatEta shows a plain time for later the same day`() {
    assertEquals("12:45 PM", formatEta(45, noonChicago))
  }

  @Test
  fun `formatEta includes the day name when it crosses midnight`() {
    // 50h59m from noon on Thursday the 15th lands at 2:59 PM Saturday the 17th.
    assertEquals("Sat 2:59 PM", formatEta(50 * 60 + 59, noonChicago))
  }

  @Test
  fun `formatEta is blank for null, zero, or negative values`() {
    assertEquals("", formatEta(null))
    assertEquals("", formatEta(0))
    assertEquals("", formatEta(-5))
  }

  private fun printer(
      state: String,
      progress: Int? = null,
      remainingTimeMinutes: Int? = null,
      currentJobName: String? = null,
      nextJobName: String? = null,
  ): Map<String, Any?> =
      mapOf(
          "id" to "p1",
          "name" to "Printer 1",
          "model" to "X1C",
          "state" to state,
          "progress" to progress,
          "remainingTimeMinutes" to remainingTimeMinutes,
          "layerNum" to null,
          "totalLayers" to null,
          "currentJobName" to currentJobName,
          "nextJobName" to nextJobName,
      )

  @Test
  fun `a running printer is marked printing with a friendly state label`() {
    val vm = buildPrinterViewModel(printer(state = "RUNNING", progress = 42, remainingTimeMinutes = 45))

    assertTrue(vm["isPrinting"] as Boolean)
    assertFalse(vm["isOffline"] as Boolean)
    assertEquals("Printing", vm["stateLabel"])
    assertEquals("text-bg-primary", vm["badgeClass"])
    assertEquals(42, vm["progress"])
    assertEquals("45m", vm["remainingTimeDisplay"])
  }

  @Test
  fun `an idle printer is neither printing nor offline`() {
    val vm = buildPrinterViewModel(printer(state = "IDLE", nextJobName = "Bracket.3mf"))

    assertFalse(vm["isPrinting"] as Boolean)
    assertFalse(vm["isOffline"] as Boolean)
    assertTrue(vm["isIdle"] as Boolean)
    assertFalse(vm["needsAttention"] as Boolean)
    assertEquals("Available", vm["stateLabel"])
    assertEquals("text-bg-success", vm["badgeClass"])
    assertEquals("Bracket.3mf", vm["nextJobName"])
  }

  @Test
  fun `an offline printer is flagged offline regardless of other fields`() {
    val vm = buildPrinterViewModel(printer(state = "OFFLINE"))

    assertTrue(vm["isOffline"] as Boolean)
    assertFalse(vm["isPrinting"] as Boolean)
    assertEquals("text-bg-secondary", vm["badgeClass"])
  }

  @Test
  fun `an unknown state falls back to offline styling rather than looking available`() {
    val vm = buildPrinterViewModel(printer(state = "SOME_NEW_FIRMWARE_STATE"))

    assertTrue(vm["isOffline"] as Boolean)
    assertEquals("Unknown", vm["stateLabel"])
  }

  @Test
  fun `a paused printer is distinguished from an actively running one`() {
    val vm = buildPrinterViewModel(printer(state = "PAUSE", progress = 50))

    assertTrue(vm["isPrinting"] as Boolean) // still shows the progress/job block
    assertFalse(vm["needsAttention"] as Boolean)
    assertEquals("Paused", vm["stateLabel"])
    assertEquals("text-bg-warning", vm["badgeClass"])
  }

  @Test
  fun `a failed printer needs attention and is never colored as available`() {
    val vm = buildPrinterViewModel(printer(state = "FAILED"))

    assertTrue(vm["needsAttention"] as Boolean)
    assertFalse(vm["isPrinting"] as Boolean)
    assertFalse(vm["isIdle"] as Boolean)
    assertEquals("text-bg-danger", vm["badgeClass"])
  }

  @Test
  fun `a finished printer is its own category, not needs-attention, and never colored as available`() {
    // Firmware only reports whether a NEW print has started, not whether a person removed the
    // part - so FINISH must not inflate "needs attention" the way an actual FAILED does.
    val vm = buildPrinterViewModel(printer(state = "FINISH"))

    assertTrue(vm["isFinished"] as Boolean)
    assertFalse(vm["needsAttention"] as Boolean)
    assertFalse(vm["isIdle"] as Boolean)
    assertEquals("finished", vm["filterCategory"])
    assertEquals("text-bg-info", vm["badgeClass"])
  }

  @Test
  fun `a finished printer surfaces who ran the previous print`() {
    val vm =
        buildPrinterViewModel(
            printer(state = "FINISH") + mapOf("previousPrintedByUsername" to "asmith"))

    assertEquals("asmith", vm["previousPrintedByUsername"])
  }
}
