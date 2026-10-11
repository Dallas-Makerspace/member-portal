package org.dallasmakerspace.workflows

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowReversibility
import org.dallasmakerspace.models.workflows.WorkflowStepStatus

@Singleton
class DefaultWorkflowExecutor
@Inject
constructor(
    private val store: WorkflowStore,
    private val registry: WorkflowRegistry,
    bus: WorkflowEventBus,
    private val settings: EngineSettings,
    private val wake: WorkflowWakeSignal,
    loggerFactory: LoggerFactory,
) : WorkflowExecutor {
  private val log = loggerFactory.create(DefaultWorkflowExecutor::class.java)
  private val publisher = RunPublisher(store, bus, RunMapper(registry), log)

  @Volatile private var stopping = false
  private var loopJob: Job? = null
  private val active = AtomicInteger(0)

  @Synchronized
  override fun start(scope: CoroutineScope) {
    if (loopJob?.isActive == true) return
    stopping = false
    loopJob = scope.launch(Dispatchers.IO) { coroutineScope { mainLoop(this) } }
  }

  override suspend fun shutdown(graceMillis: Long) {
    stopping = true
    wake.wake()
    val job = loopJob ?: return
    if (withTimeoutOrNull(graceMillis) { job.join() } == null) job.cancelAndJoin()
  }

  /** Claims while a worker slot is free, then sleeps until the store's next due time or a wake. */
  private suspend fun mainLoop(scope: CoroutineScope) {
    var zeroStreak = 0
    while (!stopping) {
      var wait: Long?
      try {
        var drained = false
        while (!stopping && active.get() < settings.workers.coerceAtLeast(1)) {
          val claim = store.claimNext(settings.instanceId, settings.leaseSeconds)
          if (claim == null) {
            drained = true
            break
          }
          active.incrementAndGet()
          scope.launch {
            try {
              process(claim)
            } finally {
              active.decrementAndGet()
              wake.wake()
            }
          }
        }
        if (stopping) break
        wait = if (drained) store.millisUntilNextWork() else null
        if (wait == 0L) {
          // Claimable per the store but claimNext found nothing (lost a race): retry briefly.
          if (++zeroStreak <= 2) continue
          wait = 250
        } else {
          zeroStreak = 0
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.warn("Workflow executor could not reach the store; idle until the next wake: ${e.message}")
        wait = null
      }
      wake.await(wait)
    }
  }

  /** Claims and processes one runnable run until it yields; false if nothing was claimable. */
  internal suspend fun runOnce(): Boolean {
    val claim = store.claimNext(settings.instanceId, settings.leaseSeconds) ?: return false
    process(claim)
    return true
  }

  private class RunSession(val fence: Fence) {
    val runId get() = fence.runId
    val cancelFlag = AtomicBoolean(false)
    @Volatile var lost = false
    var cursor = 0L
  }

  private suspend fun process(claim: ClaimedRun) {
    val s = RunSession(claim.fence)
    s.cancelFlag.set(claim.run.cancelRequested)
    s.cursor = publisher.cursor(s.runId)
    try {
      coroutineScope {
        val work = launch { drive(s, claim.run) }
        val beat = launch { heartbeats(s) { work.cancel() } }
        work.join()
        beat.cancel()
      }
      if (s.lost) log.warn("Lease lost for workflow run ${s.runId}; stopped")
    } catch (e: FenceLostException) {
      log.warn("Fence lost for workflow run ${s.runId}; stopped: ${e.message}")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.error("Workflow run ${s.runId} aborted (lease will expire): ${e.message}")
    } finally {
      wake.clearCancel(s.runId)
    }
  }

  private fun cancelRequested(s: RunSession) = s.cancelFlag.get() || wake.isCancelRequested(s.runId)

  private suspend fun heartbeats(s: RunSession, onLost: () -> Unit) {
    var lastOk = System.nanoTime()
    while (true) {
      delay(settings.heartbeatInterval())
      try {
        if (!store.heartbeat(s.fence, settings.leaseSeconds)) {
          s.lost = true
          onLost()
          return
        }
        lastOk = System.nanoTime()
        if (store.getRun(s.runId)?.cancelRequested == true) s.cancelFlag.set(true)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.warn("Heartbeat for workflow run ${s.runId} failed: ${e.message}")
        if ((System.nanoTime() - lastOk) / 1_000_000 > settings.leaseSeconds * 1000L) {
          s.lost = true
          onLost()
          return
        }
      }
    }
  }

  private suspend fun drive(s: RunSession, run: RunRecord) {
    val rollingBack = run.status == WorkflowRunStatus.ROLLING_BACK
    val wf = registry.get(run.workflowId)
    if (wf == null) return defective(s, rollingBack, "Workflow '${run.workflowId}' is not registered")
    val steps =
        try {
          wf.bind(run.inputJson).associateBy { it.id }
        } catch (e: Exception) {
          return defective(s, rollingBack, "Run input no longer matches workflow '${wf.id}': ${e.message}")
        }
    val missing = run.stepIds.filter { it !in steps }
    if (missing.isNotEmpty()) {
      return defective(
          s,
          rollingBack,
          "Step(s) $missing of run started on '${wf.id}' v${run.workflowVersion} no longer exist " +
              "(current v${wf.version})")
    }
    if (rollingBack) rollback(s, wf, steps) else forward(s, wf, steps)
  }

  private suspend fun defective(s: RunSession, rollingBack: Boolean, message: String) {
    val status = if (rollingBack) WorkflowRunStatus.ROLLBACK_FAILED else WorkflowRunStatus.PAUSED
    store.updateRun(
        s.fence,
        RunUpdate(status = status, errorSummary = message),
        listOf(NewEvent(null, "ERROR", message)))
    yieldRun(s)
  }

  /** Releases the lease and publishes the run's current state. */
  private suspend fun yieldRun(s: RunSession) {
    releaseQuietly(s)
    publish(s)
  }

  private suspend fun releaseQuietly(s: RunSession) {
    try {
      store.releaseLease(s.fence)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.warn("Could not release lease of run ${s.runId}: ${e.message}")
    }
  }

  private suspend fun publish(s: RunSession) {
    s.cursor = publisher.publish(s.runId, s.cursor)
  }

  private fun context(
      s: RunSession,
      run: RunRecord,
      attempt: Int,
      recs: List<StepRecord>,
      cancellable: Boolean,
  ) =
      StepContext(
          runId = run.id,
          actor = run.actor,
          triggeredBy = run.triggeredBy,
          startedAt = run.startedAt ?: run.createdAt,
          clock = settings.clock,
          attempt = attempt,
          mode = StepMode.EXECUTE,
          outputs = recs.mapNotNull { r -> r.outputJson?.let { r.stepId to it } }.toMap(),
          cancelRequested = { cancellable && cancelRequested(s) },
      )

  // ---- forward -------------------------------------------------------------------------------

  private sealed interface Attempt {
    class Done(
        val status: WorkflowStepStatus,
        val outputJson: String?,
        val summary: String?,
        val message: String,
        val warnings: List<String> = emptyList(),
    ) : Attempt

    class Failed(val error: Throwable, val permanent: Boolean) : Attempt

    data object Cancelled : Attempt
  }

  private suspend fun forward(s: RunSession, wf: Workflow<*>, steps: Map<String, ErasedStep>) {
    val first = store.getRun(s.runId) ?: return
    store.updateRun(
        s.fence,
        RunUpdate(
            status = WorkflowRunStatus.RUNNING,
            markStarted = first.startedAt == null,
            clearNextAttempt = true,
        ),
        listOf(
            NewEvent(
                null,
                "INFO",
                if (first.startedAt == null) "Run started" else "Run resumed on ${settings.instanceId}")))
    publish(s)

    while (true) {
      if (stopping) return yieldRun(s)
      val cur = store.getRun(s.runId) ?: return
      if (cur.cancelRequested || cancelRequested(s)) return finishCancelled(s, null)
      val recs = store.getSteps(s.runId)
      var idx = cur.currentStepIndex
      while (idx < recs.size && recs[idx].status in DONE) idx++
      if (idx >= recs.size) {
        store.updateRun(
            s.fence,
            RunUpdate(
                status = WorkflowRunStatus.SUCCEEDED,
                currentStepIndex = recs.size,
                markFinished = true,
                clearNextAttempt = true,
                clearError = true),
            listOf(NewEvent(null, "INFO", "Run succeeded")))
        return yieldRun(s)
      }
      val rec = recs[idx]
      val step = steps.getValue(rec.stepId)
      val attempt = rec.attempts + 1
      val policy = step.retry ?: wf.retry
      val name = step.displayName

      store.markStepStarted(
          s.fence, idx, listOf(NewEvent(idx, "INFO", "Step '$name' started (attempt $attempt)")))
      if (idx != cur.currentStepIndex) {
        store.updateRun(s.fence, RunUpdate(currentStepIndex = idx))
      }
      publish(s)

      when (val result = attemptStep(step, context(s, cur, attempt, recs, true), rec.attempts, policy)) {
        is Attempt.Done -> {
          store.finishStep(
              s.fence,
              idx,
              result.status,
              result.outputJson,
              result.summary,
              null,
              result.warnings.map { NewEvent(idx, "WARN", it) } +
                  NewEvent(idx, "INFO", "Step '$name' ${result.message}"))
          store.updateRun(
              s.fence, RunUpdate(currentStepIndex = idx + 1, clearError = true, clearNextAttempt = true))
          publish(s)
        }
        Attempt.Cancelled -> {
          store.finishStep(
              s.fence, idx, WorkflowStepStatus.FAILED, rec.outputJson, rec.outputSummary, "Cancelled")
          return finishCancelled(s, idx)
        }
        is Attempt.Failed -> return failed(s, wf, idx, rec, step, attempt, policy, result, steps)
      }
    }
  }

  private suspend fun finishCancelled(s: RunSession, stepIndex: Int?) {
    store.updateRun(
        s.fence,
        RunUpdate(status = WorkflowRunStatus.CANCELLED, markFinished = true, clearNextAttempt = true),
        listOf(NewEvent(stepIndex, "WARN", "Run cancelled; completed steps were left as is")))
    yieldRun(s)
  }

  private suspend fun failed(
      s: RunSession,
      wf: Workflow<*>,
      idx: Int,
      rec: StepRecord,
      step: ErasedStep,
      attempt: Int,
      policy: RetryPolicy,
      result: Attempt.Failed,
      steps: Map<String, ErasedStep>,
  ) {
    val msg = describe(result.error)
    val name = step.displayName
    val exhausted = result.permanent || attempt % policy.maxAttempts == 0
    if (!exhausted) {
      val retryNo = attempt % policy.maxAttempts
      val delay = policy.backoff.delayBefore(retryNo)
      val next = settings.clock.instant().plus(delay.toJavaDuration())
      store.finishStep(
          s.fence,
          idx,
          WorkflowStepStatus.FAILED,
          rec.outputJson,
          rec.outputSummary,
          msg,
          listOf(NewEvent(idx, "WARN", "Step '$name' attempt $attempt failed: $msg; retrying in $delay")))
      store.updateRun(
          s.fence,
          RunUpdate(
              status = WorkflowRunStatus.WAITING_RETRY,
              nextAttemptAt = next,
              errorSummary = "Step '$name' failed (attempt $attempt of ${policy.maxAttempts}): $msg"))
      return yieldRun(s)
    }
    val summary =
        if (result.permanent) "Step '$name' failed: $msg"
        else "Step '$name' failed after ${policy.maxAttempts} attempts: $msg"
    store.finishStep(
        s.fence,
        idx,
        WorkflowStepStatus.FAILED,
        rec.outputJson,
        rec.outputSummary,
        msg,
        listOf(NewEvent(idx, "ERROR", summary)))
    if (wf.onFailure == FailurePolicy.AUTO_ROLLBACK) {
      store.updateRun(
          s.fence,
          RunUpdate(status = WorkflowRunStatus.ROLLING_BACK, errorSummary = summary),
          listOf(NewEvent(null, "WARN", "Rolling back automatically")))
      publish(s)
      return rollback(s, wf, steps)
    }
    store.updateRun(
        s.fence,
        RunUpdate(status = WorkflowRunStatus.PAUSED, errorSummary = summary),
        listOf(NewEvent(idx, "ERROR", "Run paused; waiting for an operator")))
    yieldRun(s)
  }

  private suspend fun attemptStep(
      step: ErasedStep,
      ctx: StepContext,
      priorAttempts: Int,
      policy: RetryPolicy,
  ): Attempt {
    val timeout = policy.attemptTimeout?.inWholeMilliseconds
    return try {
      val block: suspend () -> Attempt = { doAttempt(step, ctx, priorAttempts) }
      if (timeout == null) block()
      else
          withTimeoutOrNull(timeout) { block() }
              ?: Attempt.Failed(TransientStepException("Timed out after ${policy.attemptTimeout}"), false)
    } catch (e: TimeoutCancellationException) {
      Attempt.Failed(TransientStepException("Timed out: ${e.message}"), false)
    } catch (e: CancellationException) {
      throw e
    } catch (e: RunCancelledException) {
      Attempt.Cancelled
    } catch (e: PermanentStepException) {
      Attempt.Failed(e, true)
    } catch (e: MissingOutputException) {
      Attempt.Failed(e, true)
    } catch (e: Exception) {
      Attempt.Failed(e, false)
    }
  }

  private suspend fun doAttempt(step: ErasedStep, ctx: StepContext, priorAttempts: Int): Attempt {
    val check = step.check(ctx)
    if (check is ErasedCheck.NoOp) {
      return if (priorAttempts == 0) {
        Attempt.Done(WorkflowStepStatus.SKIPPED, check.outputJson, check.reason, "skipped: ${check.reason}")
      } else {
        Attempt.Done(
            WorkflowStepStatus.SUCCEEDED,
            check.outputJson,
            check.reason,
            "completed by an earlier attempt: ${check.reason}")
      }
    }
    val warnings =
        when (val v = step.validate(ctx)) {
          is Validation.Blocked ->
              return Attempt.Failed(PermanentStepException("Blocked: ${v.messages.joinToString("; ")}"), true)
          is Validation.Warnings -> v.messages
          Validation.Ok -> emptyList()
        }
    val out = step.run(ctx)
    return Attempt.Done(WorkflowStepStatus.SUCCEEDED, out.json, out.summary, "succeeded", warnings)
  }

  // ---- rollback ------------------------------------------------------------------------------

  private suspend fun rollback(s: RunSession, wf: Workflow<*>, steps: Map<String, ErasedStep>) {
    val run = store.getRun(s.runId) ?: return
    if (run.status != WorkflowRunStatus.ROLLING_BACK) {
      store.updateRun(
          s.fence,
          RunUpdate(status = WorkflowRunStatus.ROLLING_BACK, clearNextAttempt = true),
          listOf(NewEvent(null, "WARN", "Rolling back")))
    }
    val recs = store.getSteps(s.runId)
    for (idx in recs.indices.reversed()) {
      if (stopping) return yieldRun(s)
      val rec = recs[idx]
      if (!ran(rec)) continue
      val step = steps.getValue(rec.stepId)
      val name = step.displayName
      if (step.reversibility == WorkflowReversibility.IRREVERSIBLE) {
        store.appendEvent(s.runId, idx, "WARN", "Step '$name' is irreversible and was not rolled back")
        continue
      }
      store.finishStep(
          s.fence,
          idx,
          WorkflowStepStatus.ROLLING_BACK,
          rec.outputJson,
          rec.outputSummary,
          rec.error,
          listOf(NewEvent(idx, "INFO", "Rolling back step '$name'")))
      publish(s)
      val outcome = rollbackStep(s, wf, run, step, rec, recs)
      if (outcome == null) {
        store.finishStep(
            s.fence,
            idx,
            WorkflowStepStatus.ROLLED_BACK,
            rec.outputJson,
            rec.outputSummary,
            rec.error,
            listOf(NewEvent(idx, "INFO", "Step '$name' rolled back")))
        publish(s)
      } else {
        store.finishStep(
            s.fence,
            idx,
            WorkflowStepStatus.ROLLBACK_FAILED,
            rec.outputJson,
            rec.outputSummary,
            outcome,
            listOf(NewEvent(idx, "ERROR", "Rollback of step '$name' failed: $outcome")))
        store.updateRun(
            s.fence,
            RunUpdate(
                status = WorkflowRunStatus.ROLLBACK_FAILED,
                errorSummary = "Rollback of step '$name' failed: $outcome"),
            listOf(NewEvent(idx, "ERROR", "Rollback failed; waiting for an operator")))
        return yieldRun(s)
      }
    }
    store.updateRun(
        s.fence,
        RunUpdate(status = WorkflowRunStatus.ROLLED_BACK, markFinished = true, clearNextAttempt = true),
        listOf(NewEvent(null, "INFO", "Run rolled back")))
    yieldRun(s)
  }

  /** A step that was never attempted (PENDING/SKIPPED) or is already rolled back needs nothing. */
  private fun ran(rec: StepRecord) =
      when (rec.status) {
        WorkflowStepStatus.SUCCEEDED,
        WorkflowStepStatus.RUNNING,
        WorkflowStepStatus.FAILED,
        WorkflowStepStatus.ROLLING_BACK,
        WorkflowStepStatus.ROLLBACK_FAILED -> true
        WorkflowStepStatus.PENDING -> rec.attempts > 0
        WorkflowStepStatus.SKIPPED,
        WorkflowStepStatus.ROLLED_BACK -> false
      }

  /** Null on success, else the failure message. */
  private suspend fun rollbackStep(
      s: RunSession,
      wf: Workflow<*>,
      run: RunRecord,
      step: ErasedStep,
      rec: StepRecord,
      recs: List<StepRecord>,
  ): String? {
    val policy = step.retry ?: wf.retry
    var outputJson = rec.outputJson
    if (outputJson == null && !step.hasUnitOutput) {
      val recovered =
          try {
            step.check(context(s, run, 1, recs, false))
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            return "No saved output and check() failed: ${describe(e)}"
          }
      when (recovered) {
        is ErasedCheck.WillRun -> return null
        is ErasedCheck.NoOp ->
            outputJson = recovered.outputJson ?: return "No saved output and none recoverable"
        is ErasedCheck.Unknown -> return "No saved output and none recoverable: ${recovered.reason}"
      }
    }
    var n = 1
    while (true) {
      val ctx = context(s, run, n, recs, false)
      try {
        val timeout = policy.attemptTimeout?.inWholeMilliseconds
        if (timeout == null) {
          step.rollback(ctx, outputJson)
        } else {
          withTimeoutOrNull(timeout) { step.rollback(ctx, outputJson) }
              ?: throw TransientStepException("Rollback timed out after ${policy.attemptTimeout}")
        }
        return null
      } catch (e: TimeoutCancellationException) {
        if (n >= policy.maxAttempts) return "Rollback timed out"
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (e is PermanentStepException || n >= policy.maxAttempts) return describe(e)
      }
      settings.sleep(policy.backoff.delayBefore(n).inWholeMilliseconds)
      n++
    }
  }

  private fun describe(e: Throwable): String =
      (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).take(1000)

  private companion object {
    val DONE = setOf(WorkflowStepStatus.SUCCEEDED, WorkflowStepStatus.SKIPPED)
  }
}
