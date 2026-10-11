package org.dallasmakerspace.workflows.store

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowStepStatus
import org.dallasmakerspace.models.workflows.WorkflowSubject
import org.dallasmakerspace.workflows.ClaimedRun
import org.dallasmakerspace.workflows.CreateRunResult
import org.dallasmakerspace.workflows.Fence
import org.dallasmakerspace.workflows.FenceLostException
import org.dallasmakerspace.workflows.NewEvent
import org.dallasmakerspace.workflows.NewRun
import org.dallasmakerspace.workflows.RunFilter
import org.dallasmakerspace.workflows.RunRecord
import org.dallasmakerspace.workflows.RunUpdate
import org.dallasmakerspace.workflows.StepReset
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.Before
import org.junit.Test

/** Contract tests against H2 in MariaDB mode; "database time" is moved with [TestDbClock]. */
@Suppress("LargeClass")
class ExposedWorkflowStoreTest {
  private val store = ExposedWorkflowStore()
  private val group1 = WorkflowSubject("group", "test-group-1")
  private val group2 = WorkflowSubject("group", "test-group-2")

  @Before
  fun setUp() = TestDb.reset()

  private fun newRun(
      subjects: List<WorkflowSubject> = listOf(group1),
      token: String = "token-${System.nanoTime()}",
      stepIds: List<String> = listOf("s.one", "s.two", "s.three"),
      workflowId: String = "test.flow",
  ) =
      NewRun(
          workflowId = workflowId,
          workflowVersion = 1,
          inputJson = """{"groupName":"test-group-1"}""",
          actor = "jdoe",
          triggeredBy = "UI",
          stepIds = stepIds,
          subjects = subjects,
          idempotencyToken = token,
      )

  private fun create(
      subjects: List<WorkflowSubject> = listOf(group1),
      token: String = "token-${System.nanoTime()}",
  ): RunRecord = runBlocking {
    (store.createRun(newRun(subjects, token)) as CreateRunResult.Created).run
  }

  private fun claim(instance: String = "inst-a", lease: Int = 30): ClaimedRun =
      runBlocking { store.claimNext(instance, lease) }!!

  private fun setStatus(runId: Long, status: WorkflowRunStatus) {
    transaction(TestDb.db) {
      WorkflowRunTable.update({ WorkflowRunTable.id eq runId }) { it[WorkflowRunTable.status] = status.name }
      if (!status.holdsSubjectLocks) {
        WorkflowRunSubjectTable.update({ WorkflowRunSubjectTable.runId eq runId }) {
          it[activeKey] = null
        }
      }
    }
  }

  private fun advance(seconds: Long) {
    TestDbClock.offsetSeconds += seconds
  }

  private fun activeLocks(runId: Long): Int =
      transaction(TestDb.db) {
        WorkflowRunSubjectTable.select {
              (WorkflowRunSubjectTable.runId eq runId) and
                  (WorkflowRunSubjectTable.activeKey eq 1)
            }
            .count()
            .toInt()
      }

  private fun eventCount(): Int = runBlocking { store.listEventsAfter(0, 1000).size }

  // --- create / read ---

  @Test
  fun createRunInsertsRunStepsAndNormalizedLocks() = runBlocking<Unit> {
    val result =
        store.createRun(newRun(subjects = listOf(WorkflowSubject(" Group ", " Test-Group-1 "))))
    val run = (result as CreateRunResult.Created).run

    assertEquals(WorkflowRunStatus.PENDING, run.status)
    assertEquals(listOf("s.one", "s.two", "s.three"), run.stepIds)
    assertEquals(listOf(group1), run.subjects)
    assertEquals(0, run.leaseGen)
    assertNull(run.lockedBy)
    assertNull(run.leaseAgeSeconds)
    assertFalse(run.cancelRequested)
    assertEquals("jdoe", run.actor)
    assertEquals(run, store.getRun(run.id))
    val steps = store.getSteps(run.id)
    assertEquals(listOf("s.one", "s.two", "s.three"), steps.map { it.stepId })
    assertTrue(steps.all { it.status == WorkflowStepStatus.PENDING && it.attempts == 0 })
    assertNull(store.getRun(run.id + 999))
  }

  @Test
  fun createRunTimestampsComeFromDatabaseClock() = runBlocking<Unit> {
    advance(10_000)
    val run = create()
    val jvmNow = Instant.now()
    assertTrue(run.createdAt.isAfter(jvmNow.plusSeconds(9_000)), "createdAt ${run.createdAt}")
  }

  // --- idempotency + subject locks ---

  @Test
  fun sameTokenAndSubjectsReturnsExisting() = runBlocking<Unit> {
    val first = (store.createRun(newRun(token = "t1")) as CreateRunResult.Created).run
    val second = store.createRun(newRun(token = "T1".lowercase()))
    assertEquals(first.id, (second as CreateRunResult.Existing).run.id)
  }

  @Test
  fun existingIsReturnedEvenAfterRunFinishedAndLocksReleased() = runBlocking<Unit> {
    val first = (store.createRun(newRun(token = "t1")) as CreateRunResult.Created).run
    val claimed = claim()
    store.updateRun(claimed.fence, RunUpdate(status = WorkflowRunStatus.SUCCEEDED, markFinished = true))
    val again = store.createRun(newRun(token = "t1"))
    assertEquals(first.id, (again as CreateRunResult.Existing).run.id)
    assertEquals(WorkflowRunStatus.SUCCEEDED, again.run.status)
  }

  @Test
  fun differentTokenOnLockedSubjectReturnsSubjectLockedWithActiveRun() = runBlocking<Unit> {
    val first = (store.createRun(newRun(token = "t1")) as CreateRunResult.Created).run
    val second = store.createRun(newRun(token = "t2"))
    second as CreateRunResult.SubjectLocked
    assertEquals(group1, second.subject)
    assertEquals(first.id, second.activeRun.id)
    assertEquals(first.id, store.activeRunForSubject(group1)?.id)
    assertEquals(first.id, store.activeRunForSubject(WorkflowSubject("GROUP", "Test-Group-1"))?.id)
  }

  @Test
  fun sameTokenWithDifferentSubjectsIsANewRun() = runBlocking<Unit> {
    store.createRun(newRun(subjects = listOf(group1), token = "t1"))
    val other = store.createRun(newRun(subjects = listOf(group2), token = "t1"))
    assertTrue(other is CreateRunResult.Created)
  }

  @Test
  fun lockedSecondSubjectCreatesNothing() = runBlocking<Unit> {
    val holder = create(listOf(group2))
    val result = store.createRun(newRun(subjects = listOf(group1, group2)))
    assertEquals(holder.id, (result as CreateRunResult.SubjectLocked).activeRun.id)
    assertNull(store.activeRunForSubject(group1))
    assertEquals(1, store.listRuns(RunFilter()).size)
  }

  @Test
  fun caseOnlyRenameDoesNotLockAgainstItself() = runBlocking<Unit> {
    val result =
        store.createRun(
            newRun(subjects = listOf(WorkflowSubject("group", "Old-Name"), WorkflowSubject("group", "old-name"))))
    assertEquals(1, (result as CreateRunResult.Created).run.subjects.size)
  }

  @Test
  fun renameLocksBothOldAndNewName() = runBlocking<Unit> {
    create(listOf(group1, group2))
    assertNotNull(store.activeRunForSubject(group1))
    assertNotNull(store.activeRunForSubject(group2))
  }

  @Test
  fun locksHeldWhilePausedAndReleasedWhenRunBecomesTerminal() = runBlocking<Unit> {
    val run = create()
    val claimed = claim()
    store.updateRun(claimed.fence, RunUpdate(status = WorkflowRunStatus.PAUSED, errorSummary = "boom"))
    store.releaseLease(claimed.fence)
    assertEquals(1, activeLocks(run.id))
    assertTrue(store.createRun(newRun()) is CreateRunResult.SubjectLocked)

    assertTrue(
        store.operatorTransition(
            run.id,
            setOf(WorkflowRunStatus.PAUSED),
            RunUpdate(status = WorkflowRunStatus.ABANDONED, markFinished = true)))
    assertEquals(0, activeLocks(run.id))
    assertNull(store.activeRunForSubject(group1))
    assertTrue(store.createRun(newRun()) is CreateRunResult.Created)
    assertEquals(listOf(group1), store.getRun(run.id)!!.subjects)
  }

  @Test
  fun updateRunToTerminalStatusReleasesLocksInSameTransaction() = runBlocking<Unit> {
    val run = create()
    val claimed = claim()
    store.updateRun(
        claimed.fence,
        RunUpdate(status = WorkflowRunStatus.SUCCEEDED, markStarted = true, markFinished = true),
        listOf(NewEvent(null, "INFO", "done")))
    val after = store.getRun(run.id)!!
    assertEquals(WorkflowRunStatus.SUCCEEDED, after.status)
    assertNotNull(after.startedAt)
    assertNotNull(after.finishedAt)
    assertEquals(0, activeLocks(run.id))
    assertEquals(listOf("done"), store.listEvents(run.id).map { it.message })
  }

  @Test
  fun concurrentCreatesWithSameTokenYieldOneRun() = runBlocking<Unit> {
    val results =
        (1..8)
            .map { async(Dispatchers.Default) { store.createRun(newRun(token = "same")) } }
            .awaitAll()
    assertEquals(1, results.count { it is CreateRunResult.Created })
    assertEquals(7, results.count { it is CreateRunResult.Existing })
    assertEquals(1, store.listRuns(RunFilter()).size)
  }

  @Test
  fun concurrentCreatesOnSameSubjectYieldOneRun() = runBlocking<Unit> {
    val results =
        (1..8)
            .map { i -> async(Dispatchers.Default) { store.createRun(newRun(token = "tok-$i")) } }
            .awaitAll()
    assertEquals(1, results.count { it is CreateRunResult.Created })
    assertEquals(7, results.count { it is CreateRunResult.SubjectLocked })
    assertEquals(1, store.listRuns(RunFilter()).size)
  }

  // --- claim / lease ---

  @Test
  fun claimNextSetsLeaseFromDatabaseClockAndIncrementsGeneration() = runBlocking<Unit> {
    val run = create()
    advance(5_000)
    val claimed = claim("inst-a", 30)
    assertEquals(Fence(run.id, "inst-a", 1), claimed.fence)
    assertEquals("inst-a", claimed.run.lockedBy)
    assertEquals(1, claimed.run.leaseGen)
    assertEquals(0, claimed.run.leaseAgeSeconds)
    val leaseUntil = transaction(TestDb.db) {
      WorkflowRunTable.select { WorkflowRunTable.id eq run.id }.first()[WorkflowRunTable.leaseUntil]!!
    }
    assertEquals(TestDbClock.utcTimestamp().plusSeconds(30), leaseUntil)
  }

  @Test
  fun claimNextReturnsNullWhenNothingRunnable() = runBlocking<Unit> {
    assertNull(store.claimNext("inst-a", 30))
    create()
    claim()
    assertNull(store.claimNext("inst-b", 30))
  }

  @Test
  fun claimNextSkipsPausedRollbackFailedAndTerminalRuns() = runBlocking<Unit> {
    val statuses =
        listOf(
            WorkflowRunStatus.PAUSED,
            WorkflowRunStatus.ROLLBACK_FAILED,
            WorkflowRunStatus.SUCCEEDED,
            WorkflowRunStatus.ROLLED_BACK,
            WorkflowRunStatus.ABANDONED,
            WorkflowRunStatus.CANCELLED,
        )
    statuses.forEachIndexed { i, status ->
      val run = create(listOf(WorkflowSubject("group", "g$i")))
      setStatus(run.id, status)
    }
    assertNull(store.claimNext("inst-a", 30))
  }

  @Test
  fun claimNextClaimsEveryRunnableStatus() = runBlocking<Unit> {
    val statuses =
        listOf(
            WorkflowRunStatus.PENDING,
            WorkflowRunStatus.RUNNING,
            WorkflowRunStatus.WAITING_RETRY,
            WorkflowRunStatus.ROLLING_BACK,
        )
    val ids =
        statuses.mapIndexed { i, status ->
          create(listOf(WorkflowSubject("group", "g$i"))).also { setStatus(it.id, status) }.id
        }
    val claimed = (1..4).map { claim("inst-$it").run.id }
    assertEquals(ids.toSet(), claimed.toSet())
    assertNull(store.claimNext("inst-x", 30))
  }

  @Test
  fun claimNextHonoursNextAttemptAtAgainstDatabaseClock() = runBlocking<Unit> {
    val run = create()
    val claimed = claim()
    store.updateRun(
        claimed.fence,
        RunUpdate(status = WorkflowRunStatus.WAITING_RETRY, nextAttemptAt = Instant.now().plusSeconds(600)))
    store.releaseLease(claimed.fence)
    assertNull(store.claimNext("inst-b", 30))
    advance(601)
    val again = claim("inst-b")
    assertEquals(run.id, again.run.id)
    assertEquals(2, again.fence.leaseGen)
  }

  @Test
  fun activeLeaseBlocksClaimUntilItExpiresOnDatabaseClock() = runBlocking<Unit> {
    create()
    claim("inst-a", 30)
    advance(29)
    assertNull(store.claimNext("inst-b", 30))
    advance(2)
    val taken = claim("inst-b")
    assertEquals("inst-b", taken.fence.instanceId)
    assertEquals(2, taken.fence.leaseGen)
  }

  @Test
  fun concurrentClaimsOfOneRunHaveExactlyOneWinner() = runBlocking<Unit> {
    create()
    val results =
        (1..8)
            .map { i -> async(Dispatchers.Default) { store.claimNext("inst-$i", 30) } }
            .awaitAll()
    assertEquals(1, results.count { it != null })
    assertEquals(1L, results.first { it != null }!!.fence.leaseGen)
  }

  @Test
  fun concurrentClaimsOfManyRunsNeverDoubleClaim() = runBlocking<Unit> {
    val ids = (1..6).map { create(listOf(WorkflowSubject("group", "g$it"))).id }
    val claimedIds = CopyOnWriteArrayList<Long>()
    (1..12)
        .map { i -> async(Dispatchers.Default) { store.claimNext("inst-$i", 30)?.let { claimedIds.add(it.run.id) } } }
        .awaitAll()
    assertEquals(ids.size, claimedIds.size)
    assertEquals(ids.toSet(), claimedIds.toSet())
  }

  @Test
  fun leaseAgeIsMeasuredOnDatabaseClockAndHeartbeatResetsIt() = runBlocking<Unit> {
    val run = create()
    val claimed = claim("inst-a", 60)
    advance(20)
    assertEquals(20, store.getRun(run.id)!!.leaseAgeSeconds)
    assertTrue(store.heartbeat(claimed.fence, 60))
    assertEquals(0, store.getRun(run.id)!!.leaseAgeSeconds)
    advance(59)
    assertNull(store.claimNext("inst-b", 30))
  }

  @Test
  fun releaseLeaseMakesRunImmediatelyClaimable() = runBlocking<Unit> {
    val run = create()
    val claimed = claim("inst-a", 300)
    store.releaseLease(claimed.fence)
    assertNull(store.getRun(run.id)!!.lockedBy)
    assertNull(store.getRun(run.id)!!.leaseAgeSeconds)
    val taken = claim("inst-b")
    assertEquals(2, taken.fence.leaseGen)
  }

  // --- fencing ---

  private fun reclaimedBySomeoneElse(): Pair<Fence, Fence> {
    create()
    val stale = claim("inst-a", 30).fence
    advance(31)
    val fresh = claim("inst-b", 30).fence
    return stale to fresh
  }

  @Test
  fun staleFenceCannotHeartbeat() = runBlocking<Unit> {
    val (stale, fresh) = reclaimedBySomeoneElse()
    assertFalse(store.heartbeat(stale, 30))
    assertTrue(store.heartbeat(fresh, 30))
  }

  @Test
  fun staleFenceCannotMarkStepStartedAndWritesNothing() = runBlocking<Unit> {
    val (stale, _) = reclaimedBySomeoneElse()
    assertFailsWith<FenceLostException> {
      store.markStepStarted(stale, 0, listOf(NewEvent(0, "INFO", "started")))
    }
    val step = store.getSteps(stale.runId).first()
    assertEquals(WorkflowStepStatus.PENDING, step.status)
    assertEquals(0, step.attempts)
    assertEquals(0, eventCount())
  }

  @Test
  fun staleFenceCannotFinishStepAndWritesNothing() = runBlocking<Unit> {
    val (stale, fresh) = reclaimedBySomeoneElse()
    store.markStepStarted(fresh, 0)
    assertFailsWith<FenceLostException> {
      store.finishStep(
          stale, 0, WorkflowStepStatus.SUCCEEDED, "{}", "ok", null, listOf(NewEvent(0, "INFO", "finished")))
    }
    val step = store.getSteps(stale.runId).first()
    assertEquals(WorkflowStepStatus.RUNNING, step.status)
    assertNull(step.outputJson)
    assertEquals(0, eventCount())
  }

  @Test
  fun staleFenceCannotUpdateRunOrReleaseLocksOrAppendEvents() = runBlocking<Unit> {
    val (stale, fresh) = reclaimedBySomeoneElse()
    assertFailsWith<FenceLostException> {
      store.updateRun(
          stale,
          RunUpdate(status = WorkflowRunStatus.SUCCEEDED, markFinished = true),
          listOf(NewEvent(null, "INFO", "done")))
    }
    val run = store.getRun(stale.runId)!!
    assertEquals(WorkflowRunStatus.PENDING, run.status)
    assertNull(run.finishedAt)
    assertEquals(1, activeLocks(stale.runId))
    assertEquals(0, eventCount())
    store.updateRun(fresh, RunUpdate(status = WorkflowRunStatus.RUNNING))
    assertEquals(WorkflowRunStatus.RUNNING, store.getRun(stale.runId)!!.status)
  }

  @Test
  fun staleFenceCannotReleaseLease() = runBlocking<Unit> {
    val (stale, fresh) = reclaimedBySomeoneElse()
    assertFailsWith<FenceLostException> { store.releaseLease(stale) }
    assertEquals("inst-b", store.getRun(fresh.runId)!!.lockedBy)
  }

  @Test
  fun wrongInstanceWithRightGenerationIsRejected() = runBlocking<Unit> {
    create()
    val claimed = claim("inst-a")
    val forged = claimed.fence.copy(instanceId = "inst-b")
    assertFalse(store.heartbeat(forged, 30))
    assertFailsWith<FenceLostException> { store.markStepStarted(forged, 0) }
  }

  // --- step records ---

  @Test
  fun stepLifecycleRecordsAttemptsOutputAndTimes() = runBlocking<Unit> {
    create()
    val fence = claim().fence
    store.markStepStarted(fence, 1, listOf(NewEvent(1, "INFO", "starting")))
    var step = store.getSteps(fence.runId)[1]
    assertEquals(WorkflowStepStatus.RUNNING, step.status)
    assertEquals(1, step.attempts)
    assertNotNull(step.startedAt)
    assertNull(step.finishedAt)

    store.finishStep(fence, 1, WorkflowStepStatus.FAILED, error = "transient glitch")
    step = store.getSteps(fence.runId)[1]
    assertEquals(WorkflowStepStatus.FAILED, step.status)
    assertEquals("transient glitch", step.error)
    assertNotNull(step.finishedAt)

    store.markStepStarted(fence, 1)
    assertEquals(2, store.getSteps(fence.runId)[1].attempts)
    store.finishStep(fence, 1, WorkflowStepStatus.SUCCEEDED, """{"k":1}""", "did it")
    step = store.getSteps(fence.runId)[1]
    assertEquals(WorkflowStepStatus.SUCCEEDED, step.status)
    assertEquals("""{"k":1}""", step.outputJson)
    assertEquals("did it", step.outputSummary)
    assertNull(step.error)

    store.finishStep(fence, 1, WorkflowStepStatus.ROLLED_BACK, """{"k":1}""", "undone")
    step = store.getSteps(fence.runId)[1]
    assertEquals("undone", step.outputSummary)
    store.finishStep(fence, 1, WorkflowStepStatus.ROLLED_BACK)
    step = store.getSteps(fence.runId)[1]
    assertNull(step.outputJson)
    assertNull(step.outputSummary)
    assertEquals(listOf("starting"), store.listEvents(fence.runId).map { it.message })
  }

  @Test
  fun unknownStepIndexRollsBackTheWholeMutation() = runBlocking<Unit> {
    create()
    val fence = claim().fence
    assertFailsWith<IllegalArgumentException> {
      store.markStepStarted(fence, 99, listOf(NewEvent(99, "INFO", "x")))
    }
    assertEquals(0, eventCount())
  }

  @Test
  fun updateRunAppliesFieldsAndClearFlags() = runBlocking<Unit> {
    val run = create()
    val fence = claim().fence
    val next = Instant.now().plusSeconds(120)
    store.updateRun(
        fence,
        RunUpdate(
            status = WorkflowRunStatus.WAITING_RETRY,
            currentStepIndex = 2,
            nextAttemptAt = next,
            errorSummary = "step two failed",
            markStarted = true,
            cancelRequested = true))
    var r = store.getRun(run.id)!!
    assertEquals(WorkflowRunStatus.WAITING_RETRY, r.status)
    assertEquals(2, r.currentStepIndex)
    assertEquals(next.epochSecond, r.nextAttemptAt!!.epochSecond)
    assertEquals("step two failed", r.errorSummary)
    assertTrue(r.cancelRequested)
    val startedAt = r.startedAt
    assertNotNull(startedAt)

    advance(10)
    store.updateRun(fence, RunUpdate(clearNextAttempt = true, clearError = true, markStarted = true))
    r = store.getRun(run.id)!!
    assertNull(r.nextAttemptAt)
    assertNull(r.errorSummary)
    assertEquals(WorkflowRunStatus.WAITING_RETRY, r.status)
    assertEquals(startedAt, r.startedAt)
  }

  // --- operatorTransition ---

  @Test
  fun operatorTransitionRequiresStatusInFromSet() = runBlocking<Unit> {
    val run = create()
    val fence = claim().fence
    store.updateRun(fence, RunUpdate(status = WorkflowRunStatus.PAUSED))
    store.releaseLease(fence)
    val applied =
        store.operatorTransition(
            run.id,
            setOf(WorkflowRunStatus.ROLLBACK_FAILED),
            RunUpdate(status = WorkflowRunStatus.ABANDONED),
            events = listOf(NewEvent(null, "INFO", "abandoned")))
    assertFalse(applied)
    assertEquals(WorkflowRunStatus.PAUSED, store.getRun(run.id)!!.status)
    assertEquals(1, activeLocks(run.id))
    assertEquals(0, eventCount())
  }

  @Test
  fun operatorTransitionRefusedWhileLeaseIsLive() = runBlocking<Unit> {
    val run = create()
    claim("inst-a", 30)
    val refused =
        store.operatorTransition(
            run.id,
            setOf(WorkflowRunStatus.PENDING),
            RunUpdate(status = WorkflowRunStatus.CANCELLED, markFinished = true),
            events = listOf(NewEvent(null, "INFO", "cancel")))
    assertFalse(refused)
    assertEquals(WorkflowRunStatus.PENDING, store.getRun(run.id)!!.status)
    assertEquals(0, eventCount())

    advance(31)
    assertTrue(
        store.operatorTransition(
            run.id,
            setOf(WorkflowRunStatus.PENDING),
            RunUpdate(status = WorkflowRunStatus.CANCELLED, markFinished = true)))
    assertEquals(0, activeLocks(run.id))
  }

  @Test
  fun operatorTransitionInvalidatesStaleExecutorFence() = runBlocking<Unit> {
    val run = create()
    val stale = claim("inst-a", 30).fence
    advance(31)
    assertTrue(
        store.operatorTransition(
            run.id,
            setOf(WorkflowRunStatus.PENDING),
            RunUpdate(status = WorkflowRunStatus.ABANDONED, markFinished = true)))
    assertFalse(store.heartbeat(stale, 30))
    assertFailsWith<FenceLostException> {
      store.updateRun(stale, RunUpdate(status = WorkflowRunStatus.RUNNING))
    }
    assertEquals(WorkflowRunStatus.ABANDONED, store.getRun(run.id)!!.status)
  }

  @Test
  fun retryResetsFailedStepKeepsAttemptsAndKeepsLocks() = runBlocking<Unit> {
    val run = create()
    val fence = claim().fence
    store.markStepStarted(fence, 0)
    store.finishStep(fence, 0, WorkflowStepStatus.FAILED, error = "perm failure")
    store.updateRun(fence, RunUpdate(status = WorkflowRunStatus.PAUSED, errorSummary = "perm failure"))
    store.releaseLease(fence)

    val applied =
        store.operatorTransition(
            run.id,
            setOf(WorkflowRunStatus.PAUSED),
            RunUpdate(status = WorkflowRunStatus.RUNNING, clearError = true, clearNextAttempt = true),
            StepReset(0, WorkflowStepStatus.PENDING),
            listOf(NewEvent(0, "INFO", "retry requested", actor = "asmith")))
    assertTrue(applied)
    val step = store.getSteps(run.id)[0]
    assertEquals(WorkflowStepStatus.PENDING, step.status)
    assertEquals(1, step.attempts)
    assertNull(step.error)
    assertNull(step.finishedAt)
    assertEquals(WorkflowRunStatus.RUNNING, store.getRun(run.id)!!.status)
    assertNull(store.getRun(run.id)!!.errorSummary)
    assertEquals(1, activeLocks(run.id))
    assertEquals("asmith", store.listEvents(run.id).single().actor)
    assertEquals(run.id, claim("inst-b").run.id)
  }

  @Test
  fun cancelFlagAloneCanBeSetWhileRunIsLeased() = runBlocking<Unit> {
    val run = create()
    val fence = claim("inst-a", 30).fence
    store.updateRun(fence, RunUpdate(status = WorkflowRunStatus.RUNNING))
    assertTrue(
        store.operatorTransition(
            run.id, setOf(WorkflowRunStatus.RUNNING), RunUpdate(cancelRequested = true)))
    val r = store.getRun(run.id)!!
    assertTrue(r.cancelRequested)
    assertEquals("inst-a", r.lockedBy)
    assertTrue(store.heartbeat(fence, 30))
  }

  @Test
  fun cancelFlagIsRefusedOnTerminalRuns() = runBlocking<Unit> {
    val run = create()
    val fence = claim().fence
    store.updateRun(fence, RunUpdate(status = WorkflowRunStatus.SUCCEEDED, markFinished = true))
    assertFalse(
        store.operatorTransition(
            run.id, WorkflowRunStatus.values().toSet(), RunUpdate(cancelRequested = true)))
    assertFalse(store.getRun(run.id)!!.cancelRequested)
  }

  @Test
  fun retryToWaitingRetryWithClearedNextAttemptIsClaimable() = runBlocking<Unit> {
    val run = create()
    val fence = claim().fence
    store.updateRun(fence, RunUpdate(status = WorkflowRunStatus.PAUSED))
    store.releaseLease(fence)
    assertNull(store.claimNext("inst-b", 30))
    assertTrue(
        store.operatorTransition(
            run.id,
            setOf(WorkflowRunStatus.PAUSED),
            RunUpdate(status = WorkflowRunStatus.WAITING_RETRY, clearNextAttempt = true)))
    assertEquals(run.id, claim("inst-b").run.id)
  }

  // --- millisUntilNextWork ---

  @Test
  fun millisUntilNextWorkIsNullWhenIdle() = runBlocking<Unit> {
    assertNull(store.millisUntilNextWork())
    val run = create()
    val fence = claim().fence
    store.updateRun(fence, RunUpdate(status = WorkflowRunStatus.PAUSED))
    store.releaseLease(fence)
    assertNull(store.millisUntilNextWork())
    setStatus(run.id, WorkflowRunStatus.ROLLBACK_FAILED)
    assertNull(store.millisUntilNextWork())
    setStatus(run.id, WorkflowRunStatus.SUCCEEDED)
    assertNull(store.millisUntilNextWork())
  }

  @Test
  fun millisUntilNextWorkIsZeroForClaimableRuns() = runBlocking<Unit> {
    val run = create()
    assertEquals(0L, store.millisUntilNextWork())
    val fence = claim("inst-a", 30).fence
    store.updateRun(fence, RunUpdate(status = WorkflowRunStatus.RUNNING))
    store.releaseLease(fence)
    assertEquals(0L, store.millisUntilNextWork())
    claim("inst-b", 30)
    assertEquals(30_000L, store.millisUntilNextWork())
    advance(31)
    assertEquals(0L, store.millisUntilNextWork())
    setStatus(run.id, WorkflowRunStatus.ROLLING_BACK)
    assertEquals(0L, store.millisUntilNextWork())
  }

  @Test
  fun millisUntilNextWorkTracksLeaseExpiryOnDatabaseClock() = runBlocking<Unit> {
    create()
    val fence = claim("inst-a", 60).fence
    store.updateRun(fence, RunUpdate(status = WorkflowRunStatus.RUNNING))
    assertEquals(60_000L, store.millisUntilNextWork())
    advance(45)
    assertEquals(15_000L, store.millisUntilNextWork())
    assertTrue(store.heartbeat(fence, 60))
    assertEquals(60_000L, store.millisUntilNextWork())
    advance(61)
    assertEquals(0L, store.millisUntilNextWork())
    assertNotNull(store.claimNext("inst-b", 30))
  }

  @Test
  fun millisUntilNextWorkTracksWaitingRetryDueTime() = runBlocking<Unit> {
    create()
    val fence = claim().fence
    store.updateRun(
        fence,
        RunUpdate(
            status = WorkflowRunStatus.WAITING_RETRY, nextAttemptAt = Instant.now().plusSeconds(120)))
    store.releaseLease(fence)
    val wait = store.millisUntilNextWork()!!
    assertTrue(wait in 118_000L..121_000L, "wait $wait")
    advance(60)
    assertTrue(store.millisUntilNextWork()!! in 58_000L..61_000L)
    advance(100)
    assertEquals(0L, store.millisUntilNextWork())
    assertNotNull(store.claimNext("inst-b", 30))
  }

  @Test
  fun millisUntilNextWorkPicksTheEarliestOfSeveralRuns() = runBlocking<Unit> {
    create(listOf(group1))
    create(listOf(group2))
    val first = claim("inst-a", 300).fence
    val second = claim("inst-a", 40).fence
    store.updateRun(first, RunUpdate(status = WorkflowRunStatus.RUNNING))
    store.updateRun(second, RunUpdate(status = WorkflowRunStatus.RUNNING))
    assertEquals(40_000L, store.millisUntilNextWork())
  }

  @Test
  fun millisUntilNextWorkIgnoresPausedRunsEvenWithLiveLease() = runBlocking<Unit> {
    create()
    val fence = claim("inst-a", 600).fence
    store.updateRun(fence, RunUpdate(status = WorkflowRunStatus.PAUSED))
    assertNull(store.millisUntilNextWork())
  }

  // --- events / listing ---

  @Test
  fun eventsAreOrderedAndReplayableByIdAcrossRuns() = runBlocking<Unit> {
    val run1 = create(listOf(group1))
    val run2 = create(listOf(group2))
    val e1 = store.appendEvent(run1.id, null, "INFO", "one", "jdoe")
    val e2 = store.appendEvent(run2.id, 1, "WARN", "two")
    val e3 = store.appendEvent(run1.id, 0, "ERROR", "three")
    assertTrue(e1.id < e2.id && e2.id < e3.id)
    assertEquals("jdoe", e1.actor)
    assertEquals(1, e2.stepIndex)
    assertEquals(listOf("one", "three"), store.listEvents(run1.id).map { it.message })
    assertEquals(listOf("three"), store.listEvents(run1.id, afterEventId = e1.id).map { it.message })
    assertEquals(listOf("two", "three"), store.listEventsAfter(e1.id).map { it.message })
    assertEquals(listOf("one", "two"), store.listEventsAfter(0, limit = 2).map { it.message })
  }

  @Test
  fun listRunsFiltersAndOrdersNewestFirst() = runBlocking<Unit> {
    val a = create(listOf(group1))
    val b = create(listOf(group2))
    setStatus(b.id, WorkflowRunStatus.SUCCEEDED)
    val c =
        (store.createRun(newRun(subjects = listOf(group2), workflowId = "other.flow")) as CreateRunResult.Created).run

    assertEquals(listOf(c.id, b.id, a.id), store.listRuns(RunFilter()).map { it.id })
    val succeeded = RunFilter(statuses = setOf(WorkflowRunStatus.SUCCEEDED))
    assertEquals(listOf(b.id), store.listRuns(succeeded).map { it.id })
    assertEquals(listOf(c.id, a.id), store.listRuns(RunFilter(activeOnly = true)).map { it.id })
    assertEquals(listOf(c.id), store.listRuns(RunFilter(workflowId = "other.flow")).map { it.id })
    assertEquals(
        listOf(c.id, b.id),
        store.listRuns(RunFilter(subject = WorkflowSubject("Group", "TEST-group-2"))).map { it.id })
    assertEquals(listOf(c.id), store.listRuns(RunFilter(actor = "jdoe", limit = 1)).map { it.id })
    assertTrue(store.listRuns(RunFilter(actor = "nobody")).isEmpty())
  }

  @Test
  fun listInProgressCoversOnlyTheAdminStatuses() = runBlocking<Unit> {
    val statuses = WorkflowRunStatus.values().toList()
    val ids =
        statuses.mapIndexed { i, status ->
          val run = create(listOf(WorkflowSubject("group", "g$i")))
          setStatus(run.id, status)
          status to run.id
        }
    val expected =
        setOf(
            WorkflowRunStatus.RUNNING,
            WorkflowRunStatus.WAITING_RETRY,
            WorkflowRunStatus.ROLLING_BACK,
            WorkflowRunStatus.PAUSED,
            WorkflowRunStatus.ROLLBACK_FAILED)
    assertEquals(
        ids.filter { it.first in expected }.map { it.second }.toSet(),
        store.listInProgress().map { it.id }.toSet())
  }
}
