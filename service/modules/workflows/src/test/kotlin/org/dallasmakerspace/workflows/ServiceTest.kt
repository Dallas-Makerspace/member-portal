package org.dallasmakerspace.workflows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.dallasmakerspace.models.workflows.PlanStepStatus
import org.dallasmakerspace.models.workflows.WorkflowOperatorAction
import org.dallasmakerspace.models.workflows.WorkflowRunStatus

class ServiceTest {
  private val log = mutableListOf<String>()

  private fun step(id: String, skippable: Boolean = false) =
      TestStep(id, log, Reversibility.REVERSIBLE, skippable)

  @Test
  fun preflightClassifiesEveryStepAndNeverRunsAnything() = blocking {
    val modes = mutableListOf<StepMode>()
    val willRun = step("will-run")
    willRun.checkFn = { ctx ->
      modes += ctx.mode
      StepCheck.WillRun("creates the thing")
    }
    val noOp = step("no-op")
    noOp.checkFn = { StepCheck.NoOp("already done", "known-output") }
    val usesKnown = step("uses-known-output")
    val blocked = step("blocked")
    blocked.validateFn = { Validation.Blocked(listOf("cannot proceed")) }
    val warned = step("warned")
    warned.validateFn = { Validation.Warnings(listOf("careful")) }
    val unknown = step("unknown")
    unknown.checkFn = { StepCheck.Unknown("cannot tell yet") }
    val usesMissing = step("uses-missing-output")
    val explodes = step("explodes")
    explodes.checkFn = { error("ad unreachable") }
    val wf =
        TestWorkflow(
            listOf(
                StepSpec(willRun),
                StepSpec(noOp),
                StepSpec(usesKnown, dependsOn = noOp),
                StepSpec(blocked),
                StepSpec(warned),
                StepSpec(unknown),
                StepSpec(usesMissing, dependsOn = willRun),
                StepSpec(explodes),
            ))
    val h = Harness(wf)

    val plan = h.service.preflight(wf.id, input(), "jdoe")

    assertEquals(
        listOf(
            PlanStepStatus.WILL_RUN,
            PlanStepStatus.NO_OP,
            PlanStepStatus.WILL_RUN,
            PlanStepStatus.BLOCKED,
            PlanStepStatus.WARNING,
            PlanStepStatus.UNKNOWN,
            PlanStepStatus.UNKNOWN,
            PlanStepStatus.UNKNOWN),
        plan.steps.map { it.status })
    assertFalse(plan.canStart)
    assertEquals(listOf("creates the thing"), plan.steps[0].messages)
    assertEquals(listOf("cannot proceed"), plan.steps[3].messages)
    assertEquals(listOf("careful"), plan.steps[4].messages)
    assertTrue(plan.steps[6].messages.single().contains("will-run"))
    assertTrue(plan.steps[7].messages.single().contains("ad unreachable"))
    assertEquals(listOf(StepMode.PREFLIGHT), modes)
    assertTrue(log.isEmpty(), "no run() during preflight")
    assertEquals(0, h.store.listRuns(RunFilter()).size, "no run created")
  }

  @Test
  fun preflightReportsActiveRunAndStartIsRefusedWhenBlocked() = blocking {
    val blocked = step("blocked")
    blocked.validateFn = { Validation.Blocked(listOf("nope")) }
    val h = Harness(TestWorkflow(listOf(StepSpec(blocked))))
    val result = h.service.start(h.workflow.id, input(), "jdoe", "UI", "t1")
    val plan = assertIs<StartResult.Blocked>(result).plan
    assertFalse(plan.canStart)
    assertTrue(h.store.listRuns(RunFilter()).isEmpty())

    val ok = Harness(TestWorkflow(listOf(StepSpec(step("a")))))
    val run = ok.start()
    val again = ok.service.preflight(ok.workflow.id, input(), "jdoe")
    assertFalse(again.canStart)
    assertEquals(run.id, again.activeRun?.id)
  }

  @Test
  fun startIsIdempotentPerTokenAndSubjectLockedForOthers() = blocking {
    val h = Harness(TestWorkflow(listOf(StepSpec(step("a")))))
    val first = assertIs<StartResult.Started>(h.service.start(h.workflow.id, input(), "jdoe", "UI", "tok-1"))

    val repeat = assertIs<StartResult.Existing>(h.service.start(h.workflow.id, input(), "jdoe", "UI", "tok-1"))
    assertEquals(first.run.id, repeat.run.id)

    val other = assertIs<StartResult.SubjectLocked>(h.service.start(h.workflow.id, input(), "asmith", "UI", "tok-2"))
    assertEquals(first.run.id, other.activeRun.id)

    val caseOnly = assertIs<StartResult.SubjectLocked>(h.service.start(h.workflow.id, input("ALPHA "), "asmith", "UI", "tok-3"))
    assertEquals(first.run.id, caseOnly.activeRun.id)

    val unrelated = assertIs<StartResult.Started>(h.service.start(h.workflow.id, input("beta"), "asmith", "UI", "tok-4"))
    assertTrue(unrelated.run.id != first.run.id)

    h.drain()
    assertNull(h.service.activeRunFor(Subject("thing", "alpha")))
    val after = assertIs<StartResult.Started>(h.service.start(h.workflow.id, input(), "asmith", "UI", "tok-5"))
    assertTrue(after.run.id != first.run.id)
    assertEquals(1, h.store.listRuns(RunFilter(workflowId = h.workflow.id, activeOnly = true, subject = Subject("thing", "alpha"))).size)
  }

  @Test
  fun unknownWorkflowAndInvalidInputAreRejected() = blocking {
    val h = Harness(TestWorkflow(listOf(StepSpec(step("a")))))
    assertFailsWith<UnknownWorkflowException> { h.service.preflight("nope", input(), "jdoe") }
    assertFailsWith<InvalidWorkflowInputException> {
      h.service.start(h.workflow.id, JsonObject(mapOf("other" to JsonPrimitive("x"))), "jdoe", "UI", "t")
    }
    assertFailsWith<InvalidWorkflowInputException> { h.service.preflight(h.workflow.id, input(" "), "jdoe") }
  }

  @Test
  fun runDtoCarriesIsoTimestampsParsedInputAndStoredInputIsCanonical() = blocking {
    val h = Harness(TestWorkflow(listOf(StepSpec(step("a")))))
    val extra = JsonObject(mapOf("name" to JsonPrimitive("alpha"), "ignored" to JsonPrimitive(1)))
    val run = assertIs<StartResult.Started>(h.service.start(h.workflow.id, extra, "jdoe", "UI", "t")).run
    assertEquals(JsonObject(mapOf("name" to JsonPrimitive("alpha"))), run.input)
    assertEquals("2030-01-01T00:00:00Z", run.createdAt)
    assertEquals("Test flow", run.displayName)
    assertEquals(listOf(Subject("thing", "alpha")), run.subjects)
    assertEquals("jdoe", run.actor)
    assertEquals("UI", run.triggeredBy)
    assertNull(run.lockedBy)
  }

  @Test
  fun operateRejectsActionsThatDoNotApplyToTheRunState() = blocking {
    val h = Harness(TestWorkflow(listOf(StepSpec(step("a")))))
    assertEquals(OperateResult.NotFound, h.operate(999, WorkflowOperatorAction.RETRY))
    val run = h.start()
    for (action in listOf(
        WorkflowOperatorAction.RETRY,
        WorkflowOperatorAction.SKIP,
        WorkflowOperatorAction.ROLLBACK)) {
      assertIs<OperateResult.NotAllowed>(h.operate(run.id, action), "$action on PENDING")
    }
    h.drain()
    for (action in WorkflowOperatorAction.entries) {
      assertIs<OperateResult.NotAllowed>(h.operate(run.id, action), "$action on SUCCEEDED")
    }
  }

  @Test
  fun operatorActionsAreAuditedAndPublished() = blocking {
    val a = step("a")
    a.failures += PermanentStepException("bad")
    val h = Harness(TestWorkflow(listOf(StepSpec(a))))
    val run = h.start()
    h.drain()
    h.bus.events.clear()
    h.operate(run.id, WorkflowOperatorAction.RETRY)

    val audit = h.service.listEvents(run.id).last()
    assertEquals("asmith", audit.actor)
    assertTrue(audit.message.contains("Retry requested"))
    assertTrue(h.bus.events.any { it.id == audit.id })
    assertEquals(WorkflowRunStatus.WAITING_RETRY, h.bus.runs.last().status)
  }

  @Test
  fun adminInProgressReportsSafeToDeploy() = blocking {
    val a = step("a")
    a.failures += PermanentStepException("bad")
    val h = Harness(TestWorkflow(listOf(StepSpec(a))))
    assertTrue(h.service.adminInProgress().safeToDeploy)
    val run = h.start()
    h.drain()
    val paused = h.service.adminInProgress()
    assertTrue(paused.safeToDeploy, "paused runs survive a restart")
    assertEquals(listOf(run.id), paused.runs.map { it.id })
    assertNotNull(paused.generatedAt)

    h.operate(run.id, WorkflowOperatorAction.ROLLBACK)
    assertFalse(h.service.adminInProgress().safeToDeploy, "ROLLING_BACK is in flight")
  }

  @Test
  fun definitionsListStepsAndPermission() {
    val priv = step("private-step")
    val wf = TestWorkflow(listOf(StepSpec(priv)))
    val def = WorkflowRegistry(setOf(wf)).definitions().single()
    assertEquals("test.flow", def.id)
    assertEquals("MANAGE_THINGS", def.requiredPermission)
    assertEquals(listOf("name"), def.inputFields.map { it.name })
    assertEquals(listOf("private-step"), def.steps.map { it.id })
    assertFalse(def.steps.single().isPublic)
  }

  @Test
  fun registryRejectsDuplicatesAndRepeatedStepIds() {
    val a = TestWorkflow(listOf(StepSpec(step("x"))))
    val b = TestWorkflow(listOf(StepSpec(step("y"))))
    assertFailsWith<IllegalArgumentException> { WorkflowRegistry(setOf(a, b)) }
    assertFailsWith<IllegalArgumentException> {
      WorkflowRegistry(setOf(TestWorkflow(listOf(StepSpec(step("x")), StepSpec(step("x"))))))
    }
  }
}
