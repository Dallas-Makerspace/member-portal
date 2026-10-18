package org.dallasmakerspace.webhook

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.webhook.handlers.AnnounceResult
import org.dallasmakerspace.webhook.handlers.MoodleCourseAnnouncer
import org.dallasmakerspace.webhook.handlers.MoodleCourseWebhook
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MoodleCourseWebhookTest {

  private val announcer = mock<MoodleCourseAnnouncer>()
  private val handler = MoodleCourseWebhook(announcer)

  private val body = """{"event":"course_updated","payload":"{\"courseid\":42}"}"""

  @Test
  fun `routes under moodle-course`() {
    assertEquals("moodle-course", handler.getName())
  }

  @Test
  fun `runs a pass and passes its outcome through`() = runBlocking<Unit> {
    whenever(announcer.announce()).thenReturn(AnnounceResult(true, "Moodle courses: nothing new"))

    val result = handler.handleWebhook(body)

    assertTrue(result.success)
    assertEquals("Moodle courses: nothing new", result.message)
  }

  @Test
  fun `a pass that needs a retry is reported as a failure`() = runBlocking<Unit> {
    whenever(announcer.announce()).thenReturn(AnnounceResult(false, "1 to retry"))

    assertFalse(handler.handleWebhook(body).success)
  }

  @Test
  fun `an exception becomes a failure without leaking details`() = runBlocking<Unit> {
    whenever(announcer.announce()).thenThrow(IllegalStateException("secret internal detail"))

    val result = handler.handleWebhook(body)

    assertFalse(result.success)
    assertTrue(result.message.contains("IllegalStateException"))
    assertFalse(result.message.contains("secret internal detail"))
  }

  @Test
  fun `a malformed or empty body still runs a pass, since the signal is only a hint`() =
      runBlocking<Unit> {
        whenever(announcer.announce()).thenReturn(AnnounceResult(true, "ok"))

        assertTrue(handler.handleWebhook("not json").success)
        assertTrue(handler.handleWebhook("{}").success)
        verify(announcer, times(2)).announce()
      }
}
