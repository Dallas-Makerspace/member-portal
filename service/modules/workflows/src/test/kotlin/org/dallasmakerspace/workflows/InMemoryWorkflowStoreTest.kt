package org.dallasmakerspace.workflows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowStepStatus
import org.dallasmakerspace.models.workflows.WorkflowSubject

class InMemoryWorkflowStoreTest {
  private val clock = TestClock()
  private val store = InMemoryWorkflowStore(clock)

  private fun newRun(token: String = "t1", vararg subjects: WorkflowSubject) =
      NewRun(
          "wf",
          1,
          "{}",
          "jdoe",
          "TEST",
          listOf("a", "b"),
          subjects.toList().ifEmpty { listOf(WorkflowSubject("thing", "Alpha ")) },
          token)

  private suspend fun create(token: String = "t1", vararg subjects: WorkflowSubject) =
      assertIs<CreateRunResult.Created>(store.createRun(newRun(token, *subjects))).run

  @Test
  fun createNormalizesSubjectsAndLocksThem() = blocking {
    val run = create()
    assertEquals(listOf(WorkflowSubject("thing", "alpha")), run.subjects)
    assertEquals(WorkflowRunStatus.PENDING, run.status)
    assertEquals(2, store.getSteps(run.id).size)
    assertEquals(run.id, store.activeRunForSubject(WorkflowSubject("THING", " ALPHA"))?.id)

    val locked = assertIs<CreateRunResult.SubjectLocked>(store.createRun(newRun("t2")))
    assertEquals(run.id, locked.activeRun.id)
    assertEquals(WorkflowSubject("thing", "alpha"), locked.subject)
  }

  @Test
  fun sameTokenReturnsExistingEvenWhenLockedOrFinished() = blocking {
    val run = create()
    assertEquals(run.id, assertIs<CreateRunResult.Existing>(store.createRun(newRun("t1"))).run.id)
    val claim = store.claimNext("i1", 30)!!
    store.updateRun(claim.fence, RunUpdate(status = WorkflowRunStatus.SUCCEEDED, markFinished = true))
    assertEquals(run.id, assertIs<CreateRunResult.Existing>(store.createRun(newRun("t1"))).run.id)
  }

  @Test
  fun terminalStatusReleasesLocksButPausedKeepsThem() = blocking {
    val run = create()
    val claim = store.claimNext("i1", 30)!!
    store.updateRun(claim.fence, RunUpdate(status = WorkflowRunStatus.PAUSED))
    assertNotNull(store.activeRunForSubject(WorkflowSubject("thing", "alpha")))
    store.updateRun(claim.fence, RunUpdate(status = WorkflowRunStatus.ABANDONED, markFinished = true))
    assertNull(store.activeRunForSubject(WorkflowSubject("thing", "alpha")))
    assertNotNull(store.getRun(run.id)!!.finishedAt)
    assertIs<CreateRunResult.Created>(store.createRun(newRun("t9")))
  }

  @Test
  fun leaseIsExclusiveUntilItExpiresAndGenerationFencesTheOldHolder() = blocking {
    create()
    val first = store.claimNext("i1", 30)!!
    assertNull(store.claimNext("i2", 30), "leased")
    clock.advance(29)
    assertNull(store.claimNext("i2", 30))
    assertTrue(store.heartbeat(first.fence, 30))
    clock.advance(29)
    assertNull(store.claimNext("i2", 30), "heartbeat extended the lease")
    clock.advance(2)
    val second = store.claimNext("i2", 30)!!
    assertEquals(first.fence.leaseGen + 1, second.fence.leaseGen)

    assertFalse(store.heartbeat(first.fence, 30))
    assertFailsWith<FenceLostException> { store.markStepStarted(first.fence, 0) }
    assertFailsWith<FenceLostException> {
      store.finishStep(first.fence, 0, WorkflowStepStatus.SUCCEEDED)
    }
    assertFailsWith<FenceLostException> { store.updateRun(first.fence, RunUpdate(status = WorkflowRunStatus.RUNNING)) }
    store.releaseLease(first.fence)
    assertNull(store.claimNext("i3", 30), "stale release is ignored")
    store.markStepStarted(second.fence, 0)
    assertEquals(1, store.getSteps(second.run.id)[0].attempts)
  }

  @Test
  fun releaseLeaseMakesTheRunClaimableImmediately() = blocking {
    create()
    val claim = store.claimNext("i1", 30)!!
    store.releaseLease(claim.fence)
    assertNotNull(store.claimNext("i2", 30))
  }

  @Test
  fun onlyRunnableDueRunsAreClaimed() = blocking {
    val run = create()
    var claim = store.claimNext("i1", 30)!!
    store.updateRun(claim.fence, RunUpdate(status = WorkflowRunStatus.WAITING_RETRY, nextAttemptAt = clock.instant().plusSeconds(60)))
    store.releaseLease(claim.fence)
    assertNull(store.claimNext("i1", 30), "next_attempt_at in the future")
    clock.advance(60)
    claim = store.claimNext("i1", 30)!!
    for (status in listOf(WorkflowRunStatus.PAUSED, WorkflowRunStatus.ROLLBACK_FAILED)) {
      store.updateRun(claim.fence, RunUpdate(status = status, clearNextAttempt = true))
      store.releaseLease(claim.fence)
      assertNull(store.claimNext("i2", 30), "$status is never claimed")
    }
    store.updateRun(claim.fence, RunUpdate(status = WorkflowRunStatus.ROLLING_BACK))
    assertNotNull(store.claimNext("i2", 30))
    assertEquals(run.id, store.listInProgress().single().id)
  }

  @Test
  fun operatorTransitionRespectsStatusAndLeaseButCancelRequestBypassesLease() = blocking {
    val run = create()
    val claim = store.claimNext("i1", 30)!!
    store.updateRun(claim.fence, RunUpdate(status = WorkflowRunStatus.RUNNING))

    val blocked = store.operatorTransition(run.id, setOf(WorkflowRunStatus.RUNNING), RunUpdate(status = WorkflowRunStatus.ABANDONED))
    assertFalse(blocked, "lease is live")
    assertTrue(store.operatorTransition(run.id, setOf(WorkflowRunStatus.RUNNING), RunUpdate(cancelRequested = true)))
    assertTrue(store.getRun(run.id)!!.cancelRequested)

    clock.advance(31)
    assertFalse(store.operatorTransition(run.id, setOf(WorkflowRunStatus.PAUSED), RunUpdate(status = WorkflowRunStatus.ABANDONED)), "wrong status")
    store.finishStep(claim.fence, 0, WorkflowStepStatus.FAILED, error = "x")
    assertTrue(
        store.operatorTransition(
            run.id,
            setOf(WorkflowRunStatus.RUNNING),
            RunUpdate(status = WorkflowRunStatus.WAITING_RETRY),
            StepReset(0, WorkflowStepStatus.PENDING),
            listOf(NewEvent(null, "INFO", "retry", "asmith"))))
    val step = store.getSteps(run.id)[0]
    assertEquals(WorkflowStepStatus.PENDING, step.status)
    assertNull(step.error)
    assertEquals("asmith", store.listEvents(run.id).last().actor)
  }

  @Test
  fun eventsAreOrderedAndFilterable() = blocking {
    val run = create()
    store.appendEvent(run.id, 0, "INFO", "one")
    val two = store.appendEvent(run.id, null, "WARN", "two", "jdoe")
    assertEquals(listOf("Run created", "one", "two"), store.listEvents(run.id).map { it.message })
    assertEquals(listOf("two"), store.listEvents(run.id, two.id - 1).map { it.message })
    assertEquals(1, store.listEventsAfter(two.id - 1).size)
  }

  @Test
  fun listRunsFilters() = blocking {
    val a = create("t1", WorkflowSubject("thing", "alpha"))
    val b = create("t2", WorkflowSubject("thing", "beta"))
    assertEquals(listOf(b.id, a.id), store.listRuns(RunFilter()).map { it.id })
    assertEquals(listOf(a.id), store.listRuns(RunFilter(subject = WorkflowSubject("Thing", "ALPHA"))).map { it.id })
    assertEquals(0, store.listRuns(RunFilter(statuses = setOf(WorkflowRunStatus.PAUSED))).size)
    assertEquals(1, store.listRuns(RunFilter(limit = 1)).size)
  }
}
