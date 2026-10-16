package org.dallasmakerspace.workflows

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.models.workflows.WorkflowDefinitionDto
import org.dallasmakerspace.models.workflows.WorkflowReversibility
import org.dallasmakerspace.models.workflows.WorkflowStepDefinitionDto

/** All registered workflows; rejects invalid definitions at construction so bad ones fail at startup. */
@Singleton
class WorkflowRegistry @Inject constructor(workflows: Set<@JvmSuppressWildcards Workflow<*>>) {
  private val byId: Map<String, Workflow<*>>

  init {
    val ids = workflows.groupBy { it.id }
    ids.forEach { (id, list) -> require(list.size == 1) { "Duplicate workflow id '$id'" } }
    workflows.forEach(::validate)
    byId = workflows.associateBy { it.id }
  }

  fun get(id: String): Workflow<*>? = byId[id]

  fun all(): List<Workflow<*>> = byId.values.sortedBy { it.id }

  fun definitions(): List<WorkflowDefinitionDto> = all().map(::toDto)

  private fun validate(wf: Workflow<*>) {
    val steps = wf.bindings.map { it.step }
    require(steps.isNotEmpty()) { "Workflow '${wf.id}' has no steps" }
    val dup = steps.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(dup.isEmpty()) { "Workflow '${wf.id}' repeats step ids $dup" }
    wf.confirmTypingField?.let { field ->
      require(wf.inputFields.any { it.name == field }) {
        "Workflow '${wf.id}' confirmTypingField '$field' is not an input field"
      }
    }
    if (wf.onFailure == FailurePolicy.AUTO_ROLLBACK) {
      val unsafe = steps.filter { it.reversibility != WorkflowReversibility.REVERSIBLE }
      require(unsafe.isEmpty()) {
        "Workflow '${wf.id}' uses AUTO_ROLLBACK but has non-REVERSIBLE steps ${unsafe.map { it.id }}"
      }
    }
  }

  private fun toDto(wf: Workflow<*>) =
      WorkflowDefinitionDto(
          id = wf.id,
          version = wf.version,
          displayName = wf.displayName,
          description = wf.description,
          requiredPermission = wf.permission,
          inputFields = wf.inputFields,
          authorization = wf.authorization,
          confirmTypingField = wf.confirmTypingField,
          steps =
              wf.bindings.map {
                WorkflowStepDefinitionDto(
                    id = it.step.id,
                    displayName = it.step.displayName,
                    reversibility = it.step.reversibility,
                    skippable = it.step.skippable,
                    isPublic = it.step is PublicStep<*, *>,
                )
              },
      )
}

internal fun Workflow<*>.stepInfo(stepId: String): Step<*, *>? =
    bindings.firstOrNull { it.step.id == stepId }?.step
