package org.dallasmakerspace.moodle

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class MoodleTextTest {

  @Test
  fun `parses Moodle report-builder dates with and without weekday`() {
    assertEquals(LocalDate.of(2026, 10, 6), MoodleText.parseDate("Tuesday, 6 October 2026, 3:15 PM"))
    assertEquals(LocalDate.of(2025, 1, 31), MoodleText.parseDate("31 January 2025, 9:00 AM"))
  }

  @Test
  fun `parses ISO dates and dates wrapped in HTML`() {
    assertEquals(LocalDate.of(2026, 3, 9), MoodleText.parseDate("2026-03-09"))
    assertEquals(LocalDate.of(2026, 3, 9), MoodleText.parseDate("<span>9 March 2026</span>"))
  }

  @Test
  fun `blank or unparseable dates are null`() {
    assertNull(MoodleText.parseDate(null))
    assertNull(MoodleText.parseDate(""))
    assertNull(MoodleText.parseDate("Never"))
    assertNull(MoodleText.parseDate("31 Smarch 2026"))
  }

  @Test
  fun `plainText strips tags, decodes entities and collapses whitespace`() {
    assertEquals(
        "Safety & Orientation <quiz>",
        MoodleText.plainText("<p>Safety &amp; Orientation</p>\n  <b>&lt;quiz&gt;</b>"))
    assertEquals("it's", MoodleText.plainText("it&#39;s"))
  }

  @Test
  fun `normalizeKey makes report cells and catalog shortnames comparable`() {
    assertEquals(
        MoodleText.normalizeKey("EX S & O"), MoodleText.normalizeKey("<a href='x'>EX S &amp; O</a>"))
  }
}
