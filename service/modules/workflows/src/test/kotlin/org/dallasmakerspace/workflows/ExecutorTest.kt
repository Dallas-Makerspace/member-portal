package org.dallasmakerspace.workflows

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.dallasmakerspace.models.workflows.WorkflowOperatorAction.*
import org.dallasmakerspace.models.workflows.WorkflowReversibility
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowStepStatus

class ExecutorTest {
  private val log = mutableListOf<String>()

  private fun step(id: String, rev: Reversibility = Reversibility.REVERSIBLE, skippable: Boolean = false) =
      TestStep(id, log, rev, skippable)

  private fun harness(vararg steps: TestStep, retry: RetryPolicy? = null, auto: Boolean = false) =
      Harness(
          TestWorkflow(
              steps.map { StepSpec(it) },
              onFailure = if (auto) FailurePolicy.AUTO_ROLLBACK else FailurePolicy.PAUSE,
              retry = retry ?: RetryPolicy(3, Backoff.Fixed(10.seconds), null),
          ))

  private fun permanent(msg: String = "bad input") = PermanentStepException(msg)

  @Test
  fun successPath() = blocking {
    val (a, b, c) = listOf(step("a"), step("b"), step("c"))
    val h = harness(a, b, c)
    val run = h.start()
    assertEquals(WorkflowRunStatus.PENDING, run.status)
    h.drain()

    val done = h.run(run.id)
    assertEquals(WorkflowRunStatus.SUCCEEDED, done.status)
    assertEquals(List(3) { WorkflowStepStatus.SUCCEEDED }, done.stepStatuses)
    assertEquals(listOf("run:a", "run:b", "run:c"), log)
    assertEquals(3, done.currentStepIndex)
    assertEquals("summary of a", done.steps[0].outputSummary)
    assertNotNull(done.finishedAt)
    assertNull(h.service.activeRunFor(Subject("thing", "alpha")), "lock released")
    assertEquals(WorkflowRunStatus.SUCCEEDED, h.bus.runs.last().status)
    assertTrue(h.bus.events.isNotEmpty())
    assertEquals(h.bus.events.map { it.id }.sorted(), h.bus.events.map { it.id }, "events published in order")
    assertFalse(done.toString().contains("out-a"), "raw output never leaves the engine")
  }

  @Test
  fun noOpWithoutPriorAttemptIsSkippedAndKeepsItsOutputForLaterSteps() = blocking {
    val a = step("a")
    val b = step("b")
    a.checkFn = { StepCheck.NoOp("already there", "existing-a") }
    var seen: String? = null
    b.runFn = { ctx -> ctx.output(a).also { seen = it } }
    val h = Harness(TestWorkflow(listOf(StepSpec(a), StepSpec(b, dependsOn = a))))
    val run = h.start()
    h.drain()

    val done = h.run(run.id)
    assertEquals(listOf(WorkflowStepStatus.SKIPPED, WorkflowStepStatus.SUCCEEDED), done.stepStatuses)
    assertEquals(listOf("run:b"), log)
    assertEquals("existing-a", seen)
    assertEquals("already there", done.steps[0].outputSummary)
  }

  @Test
  fun transientFailureRetriesWithBackoffThenSucceeds() = blocking {
    val a = step("a")
    a.failures += TransientStepException("flaky")
    a.failures += RuntimeException("unclassified counts as transient")
    val h = harness(a)
    val run = h.start()
    h.drain()

    var cur = h.run(run.id)
    assertEquals(WorkflowRunStatus.WAITING_RETRY, cur.status)
    assertEquals(1, cur.steps[0].attempts)
    assertEquals("2030-01-01T00:00:10Z", cur.nextAttemptAt)
    assertFalse(h.executor.runOnce(), "not due yet")

    h.clock.advance(10)
    h.drain()
    assertEquals(WorkflowRunStatus.WAITING_RETRY, h.run(run.id).status)
    h.clock.advance(10)
    h.drain()

    cur = h.run(run.id)
    assertEquals(WorkflowRunStatus.SUCCEEDED, cur.status)
    assertEquals(3, cur.steps[0].attempts)
    assertNull(cur.nextAttemptAt)
    assertEquals(3, log.count { it == "run:a" })
  }

  @Test
  fun noOpAfterAFailedAttemptIsSucceededNotSkipped() = blocking {
    val a = step("a")
    a.failures += TransientStepException("died after the effect landed")
    a.checkFn = { if ("run:a" in log) StepCheck.NoOp("landed", "out-a") else StepCheck.WillRun() }
    val h = harness(a)
    val run = h.start()
    h.drain()
    h.clock.advance(10)
    h.drain()

    val done = h.run(run.id)
    assertEquals(WorkflowStepStatus.SUCCEEDED, done.steps[0].status)
    assertEquals(2, done.steps[0].attempts)
    assertEquals(1, log.count { it == "run:a" })
  }

  @Test
  fun retriesExhaustedPausesThenOperatorRetrySucceeds() = blocking {
    val a = step("a")
    repeat(2) { a.failures += TransientStepException("down") }
    val h = harness(a, retry = RetryPolicy(2, Backoff.Fixed(10.seconds), null))
    val run = h.start()
    h.drain()
    h.clock.advance(10)
    h.drain()

    val paused = h.run(run.id)
    assertEquals(WorkflowRunStatus.PAUSED, paused.status)
    assertEquals(WorkflowStepStatus.FAILED, paused.steps[0].status)
    assertTrue(paused.errorSummary!!.contains("after 2 attempts"))
    assertFalse(h.executor.runOnce(), "paused runs are never claimed")
    assertNotNull(h.service.activeRunFor(Subject("thing", "alpha")), "paused run keeps the lock")

    assertIs<OperateResult.Ok>(h.operate(run.id, RETRY))
    h.drain()
    val done = h.run(run.id)
    assertEquals(WorkflowRunStatus.SUCCEEDED, done.status)
    assertEquals(3, done.steps[0].attempts)
    assertNull(done.errorSummary)
  }

  @Test
  fun operatorRetryGetsAFreshAttemptBudget() = blocking {
    val a = step("a")
    repeat(3) { a.failures += TransientStepException("down") }
    val h = harness(a, retry = RetryPolicy(2, Backoff.Fixed(10.seconds), null))
    val run = h.start()
    h.drain()
    h.clock.advance(10)
    h.drain()
    assertEquals(WorkflowRunStatus.PAUSED, h.run(run.id).status)

    h.operate(run.id, RETRY)
    h.drain()
    assertEquals(WorkflowRunStatus.WAITING_RETRY, h.run(run.id).status, "attempt 3 is retried, not paused")
    h.clock.advance(10)
    h.drain()
    assertEquals(WorkflowRunStatus.SUCCEEDED, h.run(run.id).status)
  }

  @Test
  fun permanentErrorPausesWithoutRetry() = blocking {
    val a = step("a")
    a.failures += permanent()
    val h = harness(a)
    val run = h.start()
    h.drain()

    val paused = h.run(run.id)
    assertEquals(WorkflowRunStatus.PAUSED, paused.status)
    assertEquals(1, paused.steps[0].attempts)
    assertEquals("bad input", paused.steps[0].error)
  }

  @Test
  fun blockedValidationAtExecutionTimePausesBeforeRun() = blocking {
    val a = step("a")
    a.validateFn = { ctx ->
      if (ctx.mode == StepMode.EXECUTE) Validation.Blocked(listOf("snapshot is stale")) else Validation.Ok
    }
    val h = harness(a)
    val run = h.start()
    h.drain()

    val paused = h.run(run.id)
    assertEquals(WorkflowRunStatus.PAUSED, paused.status)
    assertTrue(paused.errorSummary!!.contains("snapshot is stale"))
    assertTrue(log.isEmpty())
  }

  @Test
  fun skipOnlyWorksOnSkippableSteps() = blocking {
    val a = step("a")
    a.failures += permanent()
    val h = harness(a, step("b"))
    val run = h.start()
    h.drain()
    assertIs<OperateResult.NotAllowed>(h.operate(run.id, SKIP))
    assertEquals(WorkflowRunStatus.PAUSED, h.run(run.id).status)

    log.clear()
    val a2 = step("a2", skippable = true)
    a2.failures += permanent()
    val b2 = step("b2")
    val h2 = harness(a2, b2)
    val run2 = h2.start("beta")
    h2.drain()
    assertIs<OperateResult.Ok>(h2.operate(run2.id, SKIP))
    h2.drain()
    val done = h2.run(run2.id)
    assertEquals(WorkflowRunStatus.SUCCEEDED, done.status)
    assertEquals(listOf(WorkflowStepStatus.SKIPPED, WorkflowStepStatus.SUCCEEDED), done.stepStatuses)
    assertEquals(listOf("run:a2", "run:b2"), log)
  }

  @Test
  fun abandonLeavesStateAndReleasesTheLock() = blocking {
    val a = step("a")
    a.failures += permanent()
    val h = harness(step("pre"), a)
    val run = h.start()
    h.drain()
    assertIs<OperateResult.Ok>(h.operate(run.id, ABANDON))

    val done = h.run(run.id)
    assertEquals(WorkflowRunStatus.ABANDONED, done.status)
    assertEquals(WorkflowStepStatus.SUCCEEDED, done.steps[0].status, "nothing rolled back")
    assertNull(h.service.activeRunFor(Subject("thing", "alpha")))
    assertFalse(h.executor.runOnce())
    assertTrue(h.service.listEvents(run.id).any { it.message.contains("abandoned") && it.actor == "asmith" })
  }

  @Test
  fun rollbackRunsFailedStepThenCompletedStepsInReverse() = blocking {
    val (a, b, c) = listOf(step("a"), step("b"), step("c"))
    var landed = false
    c.checkFn = { if (landed) StepCheck.NoOp("partly applied", "c-partial") else StepCheck.WillRun() }
    c.runFn = {
      landed = true
      throw permanent("half done")
    }
    val h = harness(a, b, c, step("d"))
    val run = h.start()
    h.drain()
    assertEquals(WorkflowRunStatus.PAUSED, h.run(run.id).status)

    assertIs<OperateResult.Ok>(h.operate(run.id, ROLLBACK))
    assertEquals(WorkflowRunStatus.ROLLING_BACK, h.run(run.id).status)
    h.drain()

    val done = h.run(run.id)
    assertEquals(WorkflowRunStatus.ROLLED_BACK, done.status)
    assertEquals(
        listOf("run:a", "run:b", "run:c", "rollback:c:c-partial", "rollback:b:out-b", "rollback:a:out-a"), log)
    assertEquals(
        List(3) { WorkflowStepStatus.ROLLED_BACK } + WorkflowStepStatus.PENDING, done.stepStatuses)
    assertNull(h.service.activeRunFor(Subject("thing", "alpha")))
    assertNotNull(done.errorSummary, "original failure stays visible")
  }

  @Test
  fun rollbackSkipsNeverAttemptedAndIrreversibleStepsAndFlagsThem() = blocking {
    val a = step("a")
    val skipped = step("skipped")
    skipped.checkFn = { StepCheck.NoOp("not needed", "x") }
    val irreversible = step("irr", Reversibility.IRREVERSIBLE)
    val failing = step("boom")
    failing.failures += permanent()
    val h = harness(a, skipped, irreversible, failing)
    val run = h.start()
    h.drain()
    h.operate(run.id, ROLLBACK)
    h.drain()

    val done = h.run(run.id)
    assertEquals(WorkflowRunStatus.ROLLED_BACK, done.status)
    assertEquals(
        listOf(
            WorkflowStepStatus.ROLLED_BACK,
            WorkflowStepStatus.SKIPPED,
            WorkflowStepStatus.SUCCEEDED,
            WorkflowStepStatus.ROLLED_BACK),
        done.stepStatuses)
    assertEquals(listOf("run:a", "run:irr", "run:boom", "rollback:a:out-a"), log)
    assertTrue(
        h.service.listEvents(run.id).any { it.level == "WARN" && it.message.contains("irreversible") })
  }

  @Test
  fun rollbackFailureEndsInRollbackFailedAndCanBeRetried() = blocking {
    val (a, b) = listOf(step("a"), step("b"))
    val boom = step("boom")
    boom.failures += permanent()
    var aRollbackWorks = false
    a.rollbackFn = { _, _ -> if (!aRollbackWorks) throw TransientStepException("ad down") }
    val h = harness(a, b, boom, retry = RetryPolicy(2, Backoff.Fixed(1.seconds), null))
    val run = h.start()
    h.drain()
    h.operate(run.id, ROLLBACK)
    h.drain()

    val failed = h.run(run.id)
    assertEquals(WorkflowRunStatus.ROLLBACK_FAILED, failed.status)
    assertEquals(
        listOf(WorkflowStepStatus.ROLLBACK_FAILED, WorkflowStepStatus.ROLLED_BACK, WorkflowStepStatus.ROLLED_BACK),
        failed.stepStatuses)
    assertEquals(2, log.count { it == "rollback:a:out-a" }, "rollback retried before giving up")
    assertTrue(log.indexOf("rollback:b:out-b") < log.indexOf("rollback:a:out-a"))
    assertFalse(h.executor.runOnce(), "ROLLBACK_FAILED is never claimed")
    assertNotNull(h.service.activeRunFor(Subject("thing", "alpha")))

    aRollbackWorks = true
    assertIs<OperateResult.Ok>(h.operate(run.id, RETRY))
    h.drain()
    assertEquals(WorkflowRunStatus.ROLLED_BACK, h.run(run.id).status)
    assertEquals(1, log.count { it == "rollback:b:out-b" }, "already rolled back steps are not repeated")
  }

  @Test
  fun autoRollbackFailurePolicyRollsBackWithoutAnOperator() = blocking {
    val boom = step("boom")
    boom.failures += permanent()
    val h = harness(step("a"), boom, auto = true)
    val run = h.start()
    h.drain()

    assertEquals(WorkflowRunStatus.ROLLED_BACK, h.run(run.id).status)
    assertTrue("rollback:a:out-a" in log)
  }

  @Test
  fun autoRollbackIsRefusedForNonReversibleSteps() {
    for (rev in listOf(Reversibility.BEST_EFFORT, Reversibility.IRREVERSIBLE)) {
      val wf =
          TestWorkflow(listOf(StepSpec(step("a")), StepSpec(step("b", rev))), onFailure = FailurePolicy.AUTO_ROLLBACK)
      assertFailsWith<IllegalArgumentException> { WorkflowRegistry(setOf(wf)) }
    }
  }

  @Test
  fun crashBetweenRunAndFinishStepResumesViaNoOpAndKeepsOutputForRollback() = blocking {
    val clock = TestClock()
    val faulty = CrashOnFinishStore(InMemoryWorkflowStore(clock))
    val a = step("a")
    val b = step("b")
    val c = step("c")
    var bLanded = false
    b.runFn = {
      bLanded = true
      "out-b"
    }
    b.checkFn = { if (bLanded) StepCheck.NoOp("already created", "out-b") else StepCheck.WillRun() }
    c.failures += permanent()
    val wf = TestWorkflow(listOf(StepSpec(a), StepSpec(b), StepSpec(c)))
    val first = Harness(wf, clock, faulty, RecordingBus(), "inst-a")
    val second = first.otherInstance("inst-b")
    val run = first.start()

    // crash when b's SUCCEEDED is about to be written
    val original = b.runFn
    b.runFn = { ctx ->
      faulty.crashNext = true
      original(ctx)
    }
    assertTrue(first.executor.runOnce())

    val midRun = first.run(run.id)
    assertEquals(WorkflowRunStatus.RUNNING, midRun.status)
    assertEquals(WorkflowStepStatus.RUNNING, midRun.steps[1].status, "STARTED record without a finish")
    assertFalse(second.executor.runOnce(), "lease still held by the crashed instance")

    clock.advance(31)
    assertTrue(second.executor.runOnce())

    val resumed = second.run(run.id)
    assertEquals(WorkflowRunStatus.PAUSED, resumed.status, "c failed permanently")
    assertEquals(WorkflowStepStatus.SUCCEEDED, resumed.steps[1].status, "NoOp after a prior attempt")
    assertEquals(2, resumed.steps[1].attempts)
    assertEquals(1, log.count { it == "run:b" }, "run() was not repeated")

    second.operate(run.id, ROLLBACK)
    second.drain()
    assertEquals(WorkflowRunStatus.ROLLED_BACK, second.run(run.id).status)
    assertTrue("rollback:b:out-b" in log, "rollback still has b's output")
  }

  @Test
  fun overlappingExecutorsCannotBothAdvanceARun() = blocking {
    val a = step("a")
    val calls = AtomicInteger()
    val started = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    a.runFn = {
      if (calls.incrementAndGet() == 1) {
        started.complete(Unit)
        gate.await()
        "out-A"
      } else "out-B"
    }
    val first = harness(a)
    val second = first.otherInstance("inst-b")
    val run = first.start()

    val stale = launch(Dispatchers.Default) { first.executor.runOnce() }
    started.await()
    first.clock.advance(31)
    assertTrue(second.executor.runOnce(), "expired lease is claimed")
    gate.complete(Unit)
    stale.join()

    val done = first.run(run.id)
    assertEquals(WorkflowRunStatus.SUCCEEDED, done.status)
    val stored = first.store.getSteps(run.id)[0]
    assertEquals("\"out-B\"", stored.outputJson, "the stale instance wrote nothing")
    assertEquals(2, stored.attempts)
  }

  @Test
  fun lostHeartbeatCancelsTheRunningStepAndStopsWriting() = blocking {
    val clock = TestClock()
    val deadStore = DeadHeartbeatStore(InMemoryWorkflowStore(clock))
    val a = step("a")
    val started = CompletableDeferred<Unit>()
    var cancelled = false
    a.runFn = {
      try {
        started.complete(Unit)
        delay(30_000)
      } finally {
        cancelled = true
      }
      "late"
    }
    val h = Harness(TestWorkflow(listOf(StepSpec(a))), clock, deadStore, RecordingBus(), "inst-a")
    h.settings.heartbeatMillis = 20
    val run = h.start()

    withTimeout(10_000) { h.executor.runOnce() }

    assertTrue(cancelled, "step coroutine was cancelled")
    val cur = h.run(run.id)
    assertEquals(WorkflowRunStatus.RUNNING, cur.status)
    assertEquals(WorkflowStepStatus.RUNNING, cur.steps[0].status, "no finish written after losing the lease")
  }

  @Test
  fun attemptTimeoutIsATransientFailure() = blocking {
    val a = step("a")
    a.runFn = {
      delay(30_000)
      "late"
    }
    val h = harness(a, retry = RetryPolicy(1, Backoff.Fixed(1.seconds), 100.milliseconds))
    val run = h.start()
    withTimeout(10_000) { h.drain() }

    val paused = h.run(run.id)
    assertEquals(WorkflowRunStatus.PAUSED, paused.status)
    assertTrue(paused.steps[0].error!!.contains("Timed out"))
  }

  @Test
  fun versionMismatchWithMissingStepPausesWithClearError() = blocking {
    val (a, b, c) = listOf(step("a"), step("b"), step("c"))
    val v1 = Harness(TestWorkflow(listOf(StepSpec(a), StepSpec(b), StepSpec(c)), version = 1))
    val run = v1.start()
    val v2 = Harness(TestWorkflow(listOf(StepSpec(a), StepSpec(c)), version = 2), v1.clock, v1.store, v1.bus, "inst-b")
    v2.drain()

    val paused = v2.run(run.id)
    assertEquals(WorkflowRunStatus.PAUSED, paused.status)
    assertTrue(paused.errorSummary!!.contains("[b]") && paused.errorSummary!!.contains("no longer exist"))
    assertTrue(log.isEmpty(), "nothing executed")

    v2.operate(run.id, ROLLBACK)
    v2.drain()
    assertEquals(WorkflowRunStatus.ROLLBACK_FAILED, v2.run(run.id).status)
  }

  @Test
  fun runOfUnregisteredWorkflowPauses() = blocking {
    val h = harness(step("a"))
    val run = h.start()
    val other = Harness(TestWorkflow(listOf(StepSpec(step("a"))), id = "other.flow"), h.clock, h.store, h.bus, "inst-b")
    other.drain()
    val paused = other.run(run.id)
    assertEquals(WorkflowRunStatus.PAUSED, paused.status)
    assertTrue(paused.errorSummary!!.contains("not registered"))
  }

  @Test
  fun cancelWhileWaitingForRetryEndsTheRun() = blocking {
    val a = step("a")
    a.failures += TransientStepException("flaky")
    val h = harness(a)
    val run = h.start()
    h.drain()
    assertEquals(WorkflowRunStatus.WAITING_RETRY, h.run(run.id).status)

    assertIs<OperateResult.Ok>(h.operate(run.id, CANCEL))
    assertEquals(WorkflowRunStatus.CANCELLED, h.run(run.id).status)
    assertNull(h.service.activeRunFor(Subject("thing", "alpha")))
    h.clock.advance(60)
    assertFalse(h.executor.runOnce())
  }

  @Test
  fun cancelRequestedDuringAStepTakesEffectBetweenSteps() = blocking {
    val (a, b) = listOf(step("a"), step("b"))
    val h = harness(a, b)
    var runId = 0L
    a.runFn = {
      assertIs<OperateResult.Ok>(h.operate(runId, CANCEL))
      "out-a"
    }
    runId = h.start().id
    h.drain()

    val done = h.run(runId)
    assertEquals(WorkflowRunStatus.CANCELLED, done.status)
    assertEquals(WorkflowStepStatus.SUCCEEDED, done.steps[0].status)
    assertEquals(WorkflowStepStatus.PENDING, done.steps[1].status)
    assertEquals(listOf("run:a"), log)
  }

  @Test
  fun checkCancelledInsideALongStepStopsIt() = blocking {
    val a = step("a")
    val h = harness(a)
    h.settings.heartbeatMillis = 20
    var runId = 0L
    a.runFn = { ctx ->
      h.operate(runId, CANCEL)
      withTimeout(10_000) {
        while (true) {
          ctx.checkCancelled()
          delay(10)
        }
      }
      "unreachable"
    }
    runId = h.start().id
    h.drain()

    val done = h.run(runId)
    assertEquals(WorkflowRunStatus.CANCELLED, done.status)
    assertEquals(WorkflowStepStatus.FAILED, done.steps[0].status)
  }

  @Test
  fun backgroundLoopRunsWorkAndShutdownReleasesTheLeaseBetweenSteps() = blocking {
    val (a, b) = listOf(step("a"), step("b"))
    val started = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    a.runFn = {
      started.complete(Unit)
      gate.await()
      "out-a"
    }
    val h = harness(a, b)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    h.executor.start(scope)
    h.executor.start(scope)
    val run = h.start()
    started.await()

    val shutdown = launch { h.executor.shutdown(10_000) }
    delay(100)
    gate.complete(Unit)
    shutdown.join()

    val cur = h.run(run.id)
    assertEquals(WorkflowRunStatus.RUNNING, cur.status)
    assertEquals(WorkflowStepStatus.SUCCEEDED, cur.steps[0].status)
    assertEquals(WorkflowStepStatus.PENDING, cur.steps[1].status, "no new step started after shutdown began")

    val next = h.otherInstance("inst-b")
    assertTrue(next.executor.runOnce(), "lease was released, no need to wait for expiry")
    assertEquals(WorkflowRunStatus.SUCCEEDED, next.run(run.id).status)
    scope.cancel()
  }

  @Test
  fun backgroundLoopProcessesRunsAutomatically() = blocking {
    val h = harness(step("a"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    h.executor.start(scope)
    val run = h.start()
    withTimeout(10_000) {
      while (h.run(run.id).status != WorkflowRunStatus.SUCCEEDED) delay(20)
    }
    h.executor.shutdown(1_000)
    val second = h.start("beta")
    delay(150)
    assertEquals(WorkflowRunStatus.PENDING, h.run(second.id).status, "nothing is claimed after shutdown")
    scope.cancel()
  }

  @Test
  fun stepsReportTheirReversibilityInDtos() = blocking {
    val h = harness(step("a"), step("b", Reversibility.BEST_EFFORT))
    val run = h.start()
    assertEquals(
        listOf(WorkflowReversibility.REVERSIBLE, WorkflowReversibility.BEST_EFFORT),
        run.steps.map { it.reversibility })
  }
}
