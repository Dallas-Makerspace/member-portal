package org.dallasmakerspace.workflows

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowSubject
import org.junit.Test

class WorkflowSubjectLocksTest {

  private val service = FakeWorkflowService()

  @Test
  fun `no message when no run holds the group`() = runBlocking {
    assertNull(service.groupLockMessage("Test Group"))
  }

  @Test
  fun `message names the run while it holds the group, matched case-insensitively`() =
      runBlocking {
        service.locks[WorkflowSubject("group", "test group")] =
            testRun(12, WorkflowRunStatus.PAUSED)
        val message = assertNotNull(service.groupLockMessage("Test Group"))
        assertTrue(message.contains("12"))
        assertTrue(message.contains("PAUSED"))
        assertTrue(message.contains("Test Group"))
        assertNull(service.groupLockMessage("Other Group"))
      }

  @Test
  fun `a failing lookup fails open`() = runBlocking {
    service.lockLookupFails = true
    assertNull(service.groupLockMessage("Test Group"))
  }

  @Test
  fun `cancellation is not swallowed`() {
    val cancelling =
        object : WorkflowService by service {
          override suspend fun activeRunFor(subject: WorkflowSubject) =
              throw CancellationException("cancelled")
        }
    val thrown = runCatching { runBlocking { cancelling.groupLockMessage("Test Group") } }
    assertEquals(true, thrown.exceptionOrNull() is CancellationException)
  }
}
