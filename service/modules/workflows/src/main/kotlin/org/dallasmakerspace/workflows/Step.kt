package org.dallasmakerspace.workflows

import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.dallasmakerspace.models.workflows.WorkflowReversibility
import org.dallasmakerspace.models.workflows.WorkflowSubject

typealias Reversibility = WorkflowReversibility

typealias Subject = WorkflowSubject

internal val EngineJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

/**
 * A file a step produced. [namespace] is a `StorageNamespace` enum name and [key] is relative to
 * it. Never sent to clients: they get name, label and content type only.
 */
data class ArtifactRef(
    /** Unique within the step: lowercase letters, digits, dots, dashes; e.g. "member-list". */
    val name: String,
    val label: String,
    val contentType: String,
    val namespace: String,
    val key: String,
    /** Only infrastructure admins may download it. */
    val infraOnly: Boolean = false,
) {
  companion object {
    val NAME_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
  }
}

/** Result of [Step.check]. Read-only: would run() do anything? */
sealed interface StepCheck<out O> {
  data class WillRun(val summary: String? = null) : StepCheck<Nothing>

  /** The effect is already in place. [output] is what run() would have produced, if known. */
  data class NoOp<out O>(val reason: String, val output: O? = null) : StepCheck<O>

  /** Cannot tell (e.g. depends on an earlier step's output that doesn't exist yet in preflight). */
  data class Unknown(val reason: String) : StepCheck<Nothing>
}

sealed interface Validation {
  data object Ok : Validation

  data class Warnings(val messages: List<String>) : Validation

  data class Blocked(val messages: List<String>) : Validation
}

sealed interface Backoff {
  /** Delay before retry number [retryNumber] (1 = first retry). */
  fun delayBefore(retryNumber: Int): Duration

  data class Fixed(val delay: Duration) : Backoff {
    override fun delayBefore(retryNumber: Int) = delay
  }

  data class Exponential(val initial: Duration, val max: Duration, val factor: Double = 2.0) :
      Backoff {
    override fun delayBefore(retryNumber: Int): Duration {
      val exp = (retryNumber - 1).coerceIn(0, 30)
      return (initial * Math.pow(factor, exp.toDouble())).coerceAtMost(max)
    }
  }
}

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val backoff: Backoff = Backoff.Exponential(initial = 5.seconds, max = 2.minutes),
    /** Per-attempt timeout covering check, validate and run; null = none. */
    val attemptTimeout: Duration? = 5.minutes,
) {
  init {
    require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
  }

  companion object {
    val NO_RETRY = RetryPolicy(maxAttempts = 1)
  }
}

/** Retried per the step's [RetryPolicy]. Also the default for unclassified exceptions. */
class TransientStepException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Not retried; the run pauses immediately. */
class PermanentStepException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Thrown by [StepContext.checkCancelled] when an operator asked for cancellation. */
class RunCancelledException : RuntimeException("Run cancellation requested")

/** An earlier step's output is not available (always the case in preflight for steps that have not run). */
class MissingOutputException(val stepId: String) :
    RuntimeException("Output of step '$stepId' is not available yet")

enum class StepMode {
  PREFLIGHT,
  EXECUTE,
}

/** Typed handle to a step's persisted output, e.g. `ctx.output(ExportMembers.RESULT)`. */
class OutputKey<O>(val stepId: String, val serializer: KSerializer<O>)

/** Implicit, engine-supplied context. Never provided by the caller of a workflow. */
class StepContext(
    val runId: Long,
    val actor: String,
    val triggeredBy: String,
    val startedAt: Instant,
    val clock: Clock,
    val attempt: Int,
    val mode: StepMode,
    private val outputs: Map<String, String> = emptyMap(),
    private val cancelRequested: () -> Boolean = { false },
) {
  fun <O> outputOrNull(key: OutputKey<O>): O? =
      outputs[key.stepId]?.let { EngineJson.decodeFromString(key.serializer, it) }

  fun <O> output(key: OutputKey<O>): O =
      outputOrNull(key) ?: throw MissingOutputException(key.stepId)

  fun <O> output(step: Step<*, O>): O = output(step.outputKey)

  /** Call inside long steps to honour operator cancellation. */
  fun checkCancelled() {
    if (cancelRequested()) throw RunCancelledException()
  }

  internal fun copy(attempt: Int = this.attempt, outputs: Map<String, String> = this.outputs) =
      StepContext(runId, actor, triggeredBy, startedAt, clock, attempt, mode, outputs, cancelRequested)
}

/** Receiver of the args-binding lambda in [Workflow.step]. */
class StepScope<I>(val input: I, val ctx: StepContext) {
  fun <O> output(key: OutputKey<O>): O = ctx.output(key)

  fun <O> output(step: Step<*, O>): O = ctx.output(step)
}

interface Step<A, O> {
  /** Stable, e.g. "ad.delete-group". */
  val id: String
  val displayName: String
    get() = id

  val reversibility: Reversibility
  val skippable: Boolean
    get() = false

  /** Overrides the workflow default. */
  val retry: RetryPolicy?
    get() = null

  /** Output is persisted as JSON: never secrets or bulk PII. */
  val outputSerializer: KSerializer<O>

  val outputKey: OutputKey<O>
    get() = OutputKey(id, outputSerializer)

  /** Read-only. Would run() do anything? Used for preflight and before every run(). */
  suspend fun check(ctx: StepContext, args: A): StepCheck<O>

  /** Read-only. Is it eligible to run now, or likely to fail? Also evaluated just before run(). */
  suspend fun validate(ctx: StepContext, args: A): Validation = Validation.Ok

  /** The action. Must be idempotent by itself, not just guarded by check(). */
  suspend fun run(ctx: StepContext, args: A): O

  /** Undoes run(); receives this step's persisted output. Idempotent. */
  suspend fun rollback(ctx: StepContext, args: A, output: O)

  /** Short human-readable result shown in the timeline (never bulk data). */
  fun summarize(output: O): String? = null

  /**
   * Files this step produced, derived only from its args and persisted [output] (called on demand,
   * for SUCCEEDED/SKIPPED steps). Must be pure and cheap.
   */
  fun artifacts(args: A, output: O): List<ArtifactRef> = emptyList()
}

/** Marker for reusable steps; a step that does not implement it is private to its workflow. */
interface PublicStep<A, O> : Step<A, O>
