package org.dallasmakerspace.workflows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.dallasmakerspace.models.workflows.WorkflowOperatorAction
import org.dallasmakerspace.models.workflows.WorkflowRunStatus

class EventDrivenTest {
  private val log = mutableListOf<String>()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private fun step(id: String) = TestStep(id, log)

  private fun loopHarness(
      vararg steps: TestStep,
      real: Boolean = false,
      calls: CallLog = CallLog(),
      retry: RetryPolicy = RetryPolicy(3, Backoff.Fixed(400.milliseconds), null),
  ): Harness {
    val clock = TestClock(real = real)
    val store = countingStore(InMemoryWorkflowStore(clock), calls)
    return Harness(TestWorkflow(steps.map { StepSpec(it) }, retry = retry), clock, store)
  }

  private suspend fun Harness.awaitStatus(id: Long, status: WorkflowRunStatus, millis: Long = 8_000) =
      withTimeout(millis) {
        while (run(id).status != status) delay(10)
      }

  @Test
  fun idleExecutorMakesNoStoreCallsNoMatterHowMuchTimePasses() = blocking {
    val calls = CallLog()
    val h = loopHarness(step("a"), calls = calls)
    h.executor.start(scope)
    delay(300)

    assertEquals(1, calls.count("claimNext"), "one startup attempt")
    assertEquals(1, calls.count("millisUntilNextWork"))
    val baseline = calls.total
    h.clock.advance(10 * 3600)
    delay(300)
    assertEquals(baseline, calls.total, "no polling while idle")

    h.executor.shutdown(1_000)
    scope.cancel()
  }

  @Test
  fun startingARunWakesAnIdleExecutor() = blocking {
    val calls = CallLog()
    val h = loopHarness(step("a"), calls = calls)
    h.executor.start(scope)
    delay(200)
    val run = h.start()

    h.awaitStatus(run.id, WorkflowRunStatus.SUCCEEDED)
    h.executor.shutdown(1_000)
    scope.cancel()
  }

  @Test
  fun operatorActionWakesAnIdleExecutor() = blocking {
    val a = step("a")
    a.failures += PermanentStepException("bad")
    val h = loopHarness(a)
    h.executor.start(scope)
    val run = h.start()
    h.awaitStatus(run.id, WorkflowRunStatus.PAUSED)
    delay(200)

    h.operate(run.id, WorkflowOperatorAction.RETRY)
    h.awaitStatus(run.id, WorkflowRunStatus.SUCCEEDED)
    h.executor.shutdown(1_000)
    scope.cancel()
  }

  @Test
  fun waitingRetryResumesAtItsNextAttemptTimeViaTheTimer() = blocking {
    val calls = CallLog()
    val a = step("a")
    val times = mutableListOf<Long>()
    a.failures += TransientStepException("flaky")
    a.runFn = {
      times += System.nanoTime()
      "out"
    }
    val failTime = System.nanoTime()
    val h = loopHarness(a, real = true, calls = calls)
    h.executor.start(scope)
    val run = h.start()

    h.awaitStatus(run.id, WorkflowRunStatus.SUCCEEDED)
    val elapsedMs = (System.nanoTime() - failTime) / 1_000_000
    assertTrue(elapsedMs in 380..2_500, "resumed at the backoff time, took $elapsedMs ms")
    assertTrue(calls.count("claimNext") <= 8, "claims: ${calls.count("claimNext")}")
    h.executor.shutdown(1_000)
    scope.cancel()
  }

  @Test
  fun crashedPeersRunIsPickedUpAtItsLeaseExpiry() = blocking {
    val calls = CallLog()
    val h = loopHarness(step("a"), real = true, calls = calls)
    val run = h.start()
    assertNotNull(h.store.claimNext("dead-peer", 1))
    val begin = System.nanoTime()
    h.executor.start(scope)

    h.awaitStatus(run.id, WorkflowRunStatus.SUCCEEDED)
    val elapsedMs = (System.nanoTime() - begin) / 1_000_000
    assertTrue(elapsedMs in 700..4_000, "picked up at lease expiry, took $elapsedMs ms")
    assertTrue(calls.count("claimNext") <= 8, "claims: ${calls.count("claimNext")}")
    h.executor.shutdown(1_000)
    scope.cancel()
  }

  @Test
  fun startupStoreFailureIsNotRetriedOnATimer() = blocking {
    val calls = CallLog()
    calls.failOn = setOf("claimNext", "millisUntilNextWork")
    val h = loopHarness(step("a"), calls = calls)
    h.executor.start(scope)
    delay(500)
    assertEquals(1, calls.count("claimNext"), "one attempt, then idle")

    calls.failOn = emptySet()
    val run = h.start()
    h.awaitStatus(run.id, WorkflowRunStatus.SUCCEEDED)
    h.executor.shutdown(1_000)
    scope.cancel()
  }

  @Test
  fun aWakeArrivingWhileBusyIsNotLost() = blocking {
    val a = step("a")
    val started = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    a.runFn = { ctx ->
      if (ctx.runId == 1L) {
        started.complete(Unit)
        gate.await()
      }
      "out"
    }
    val h = loopHarness(a)
    h.settings.workers = 1
    h.executor.start(scope)
    val first = h.start("one")
    started.await()
    val second = h.start("two")
    delay(100)
    assertEquals(WorkflowRunStatus.PENDING, h.run(second.id).status, "single worker is busy")

    gate.complete(Unit)
    h.awaitStatus(first.id, WorkflowRunStatus.SUCCEEDED)
    h.awaitStatus(second.id, WorkflowRunStatus.SUCCEEDED)
    h.executor.shutdown(1_000)
    scope.cancel()
  }

  @Test
  fun shutdownUnblocksAnIdleLoopImmediately() = blocking {
    val h = loopHarness(step("a"))
    h.executor.start(scope)
    delay(100)
    val begin = System.nanoTime()
    h.executor.shutdown(10_000)
    assertTrue((System.nanoTime() - begin) / 1_000_000 < 2_000)
    scope.cancel()
  }

  @Test
  fun wakeSignalKeepsAWakeThatArrivedBeforeAnyoneWaitedAndConflatesBursts() = blocking {
    val signal = WorkflowWakeSignal()
    signal.wake()
    signal.wake()
    signal.wake()
    assertTrue(signal.await(1_000))
    assertFalse(signal.await(50), "bursts collapse into one wake")
    signal.wake()
    assertTrue(signal.await())
  }

  @Test
  fun wakeSignalTracksInProcessCancelRequests() {
    val signal = WorkflowWakeSignal()
    assertFalse(signal.isCancelRequested(7))
    signal.requestCancel(7)
    assertTrue(signal.isCancelRequested(7))
    signal.clearCancel(7)
    assertFalse(signal.isCancelRequested(7))
  }

  @Test
  fun inMemoryStoreReportsMillisUntilNextWork() = blocking {
    val clock = TestClock()
    val store = InMemoryWorkflowStore(clock)
    assertNull(store.millisUntilNextWork())

    val run =
        (store.createRun(NewRun("wf", 1, "{}", "jdoe", "TEST", listOf("a"), listOf(Subject("thing", "x")), "t"))
                as CreateRunResult.Created)
            .run
    assertEquals(0L, store.millisUntilNextWork(), "PENDING is claimable now")

    val claim = store.claimNext("i1", 30)!!
    assertEquals(30_000L, store.millisUntilNextWork(), "leased: wait for the lease to expire")
    clock.advance(10)
    assertEquals(20_000L, store.millisUntilNextWork())

    val next = clock.instant().plusSeconds(60)
    store.updateRun(claim.fence, RunUpdate(status = WorkflowRunStatus.WAITING_RETRY, nextAttemptAt = next))
    store.releaseLease(claim.fence)
    assertEquals(60_000L, store.millisUntilNextWork(), "WAITING_RETRY: wait for next_attempt_at")
    clock.advance(60)
    assertEquals(0L, store.millisUntilNextWork())

    val again = store.claimNext("i1", 30)!!
    store.updateRun(again.fence, RunUpdate(status = WorkflowRunStatus.PAUSED, clearNextAttempt = true))
    assertNull(store.millisUntilNextWork(), "PAUSED needs an outside event")
    store.updateRun(again.fence, RunUpdate(status = WorkflowRunStatus.ABANDONED, markFinished = true))
    assertNull(store.millisUntilNextWork())
    assertEquals(run.id, again.run.id)
  }
}
