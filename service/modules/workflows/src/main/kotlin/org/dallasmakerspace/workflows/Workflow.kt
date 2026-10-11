package org.dallasmakerspace.workflows

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.models.workflows.WorkflowInputField

enum class FailurePolicy {
  PAUSE,
  AUTO_ROLLBACK,
}

/** One step of a workflow plus how its args are derived from the workflow input and context. */
class StepBinding<I : Any> internal constructor(
    val step: Step<*, *>,
    internal val factory: (I) -> ErasedStep,
)

abstract class Workflow<I : Any>(
    val id: String,
    val version: Int,
    val displayName: String = id,
    val description: String = "",
    /** Name of org.dallasmakerspace.auth.Permission needed to launch it (enforced by the UI). */
    val permission: String? = null,
    val inputSerializer: KSerializer<I>,
    val inputFields: List<WorkflowInputField> = emptyList(),
    val onFailure: FailurePolicy = FailurePolicy.PAUSE,
    val retry: RetryPolicy = RetryPolicy(),
) {
  abstract fun subjects(input: I): List<Subject>

  abstract fun steps(): List<StepBinding<I>>

  /** Extra input checks; non-empty result is reported as invalid input. */
  open fun validateInput(input: I): List<String> = emptyList()

  /** Standing notes shown in the preflight dialog. */
  open val notes: List<String> = emptyList()

  protected fun <A, O> step(step: Step<A, O>, args: StepScope<I>.() -> A): StepBinding<I> =
      StepBinding(step) { input -> BoundStep(input, step, args) }

  internal val bindings: List<StepBinding<I>> by lazy { steps() }

  internal fun prepare(input: JsonObject): PreparedInput {
    val typed =
        try {
          EngineJson.decodeFromJsonElement(inputSerializer, input)
        } catch (e: SerializationException) {
          throw InvalidWorkflowInputException("Invalid input for '$id': ${e.message}")
        } catch (e: IllegalArgumentException) {
          throw InvalidWorkflowInputException("Invalid input for '$id': ${e.message}")
        }
    val problems = validateInput(typed)
    if (problems.isNotEmpty()) throw InvalidWorkflowInputException(problems.joinToString("; "))
    return PreparedInput(
        EngineJson.encodeToString(inputSerializer, typed),
        subjects(typed),
        bindings.map { it.factory(typed) },
    )
  }

  /** Binds the stored input JSON to this definition's steps (resume / rollback). */
  internal fun bind(inputJson: String): List<ErasedStep> {
    val typed = EngineJson.decodeFromString(inputSerializer, inputJson)
    return bindings.map { it.factory(typed) }
  }
}

internal class PreparedInput(
    val inputJson: String,
    val subjects: List<Subject>,
    val steps: List<ErasedStep>,
)

internal sealed interface ErasedCheck {
  data class WillRun(val summary: String?) : ErasedCheck

  data class NoOp(val reason: String, val outputJson: String?) : ErasedCheck

  data class Unknown(val reason: String) : ErasedCheck
}

internal class ErasedOutput(val json: String, val summary: String?)

/** Type-erased view of a step bound to a concrete workflow input. */
internal interface ErasedStep {
  val id: String
  val displayName: String
  val reversibility: Reversibility
  val skippable: Boolean
  val retry: RetryPolicy?
  val isPublic: Boolean
  val hasUnitOutput: Boolean

  suspend fun check(ctx: StepContext): ErasedCheck

  suspend fun validate(ctx: StepContext): Validation

  suspend fun run(ctx: StepContext): ErasedOutput

  /** [outputJson] null is only valid for steps whose output is Unit. */
  suspend fun rollback(ctx: StepContext, outputJson: String?)
}

internal class BoundStep<I : Any, A, O>(
    private val input: I,
    private val step: Step<A, O>,
    private val argsFn: StepScope<I>.() -> A,
) : ErasedStep {
  override val id get() = step.id
  override val displayName get() = step.displayName
  override val reversibility get() = step.reversibility
  override val skippable get() = step.skippable
  override val retry get() = step.retry
  override val isPublic get() = step is PublicStep<*, *>
  override val hasUnitOutput get() = step.outputSerializer.descriptor.serialName == "kotlin.Unit"

  private fun args(ctx: StepContext): A = StepScope(input, ctx).argsFn()

  override suspend fun check(ctx: StepContext): ErasedCheck =
      when (val c = step.check(ctx, args(ctx))) {
        is StepCheck.WillRun -> ErasedCheck.WillRun(c.summary)
        is StepCheck.Unknown -> ErasedCheck.Unknown(c.reason)
        is StepCheck.NoOp -> ErasedCheck.NoOp(c.reason, c.output?.let { encode(it) })
      }

  override suspend fun validate(ctx: StepContext) = step.validate(ctx, args(ctx))

  override suspend fun run(ctx: StepContext): ErasedOutput {
    val out = step.run(ctx, args(ctx))
    return ErasedOutput(encode(out), step.summarize(out))
  }

  @Suppress("UNCHECKED_CAST")
  override suspend fun rollback(ctx: StepContext, outputJson: String?) {
    val out: O =
        when {
          outputJson != null -> EngineJson.decodeFromString(step.outputSerializer, outputJson)
          hasUnitOutput -> Unit as O
          else -> throw PermanentStepException("No saved output for step '$id'")
        }
    step.rollback(ctx, args(ctx), out)
  }

  private fun encode(o: O) = EngineJson.encodeToString(step.outputSerializer, o)
}
