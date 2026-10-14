package org.dallasmakerspace.workflows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import org.dallasmakerspace.models.workflows.WorkflowArtifactDto
import org.dallasmakerspace.models.workflows.WorkflowOperatorAction
import org.dallasmakerspace.models.workflows.WorkflowRunDto
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowStepStatus

class ArtifactTest {
  private val log = mutableListOf<String>()

  private fun ref(
      name: String = "report",
      key: String = "exports/secret-key.json",
      infraOnly: Boolean = false,
  ) = ArtifactRef(name, "Report ($name)", "application/json", "SOME_NAMESPACE", key, infraOnly)

  private fun step(id: String) = TestStep(id, log)

  /** Step whose args come from the run input, to prove artifacts() sees the re-bound args. */
  private class NamedStep : Step<String, String> {
    override val id = "named"
    override val reversibility = Reversibility.REVERSIBLE
    override val outputSerializer = String.serializer()

    override suspend fun check(ctx: StepContext, args: String) = StepCheck.WillRun()

    override suspend fun run(ctx: StepContext, args: String) = "out"

    override suspend fun rollback(ctx: StepContext, args: String, output: String) = Unit

    override fun artifacts(args: String, output: String) =
        listOf(ArtifactRef("data", "Data", "text/plain", "NS", "runs/$args/$output.txt", true))
  }

  private class NamedWorkflow(private val named: NamedStep) :
      Workflow<TestInput>(
          id = "named.flow",
          version = 1,
          permission = "MANAGE_THINGS",
          inputSerializer = TestInput.serializer(),
      ) {
    override fun subjects(input: TestInput) = listOf(Subject("thing", input.name))

    override fun steps() = listOf(step(named) { input.name })
  }

  @Test
  fun declaredArtifactIsResolvedFromPersistedOutputAndRunInput() = blocking {
    val h = Harness(NamedWorkflow(NamedStep()))
    val run = h.start("alpha")
    h.drain()

    val resolved = h.service.resolveArtifact(run.id, 0, "data")

    assertNotNull(resolved)
    assertEquals("runs/alpha/out.txt", resolved.key)
    assertEquals("NS", resolved.namespace)
    assertEquals("text/plain", resolved.contentType)
    assertTrue(resolved.infraOnly)
    assertEquals("named.flow", resolved.workflowId)
    assertEquals("MANAGE_THINGS", resolved.requiredPermission)
  }

  @Test
  fun runDetailListsArtifactsWithoutTheStorageKey() = blocking {
    val s = step("s1")
    s.artifactsFn = { listOf(ref("report", key = "private/path/$it.json", infraOnly = true)) }
    val h = Harness(TestWorkflow(listOf(StepSpec(s))))
    val run = h.start()
    h.drain()

    val dto = h.run(run.id)

    assertEquals(
        listOf(WorkflowArtifactDto("report", "Report (report)", "application/json", true)),
        dto.steps.single().artifacts)
    val json = Json.encodeToString(WorkflowRunDto.serializer(), dto)
    assertFalse("private/path" in json, "a storage key leaked into the run DTO")
    assertFalse("SOME_NAMESPACE" in json, "a namespace leaked into the run DTO")
    assertTrue(h.bus.runs.isNotEmpty())
    h.bus.runs.forEach {
      assertFalse("private/path" in Json.encodeToString(WorkflowRunDto.serializer(), it))
    }
  }

  @Test
  fun artifactDtoCarriesNoLocationFields() {
    assertEquals(
        listOf("name", "label", "contentType", "infraOnly"),
        WorkflowArtifactDto.serializer().descriptor.elementNames.toList())
  }

  @Test
  fun undeclaredOrUnknownArtifactsResolveToNull() = blocking {
    val withArtifact = step("a")
    withArtifact.artifactsFn = { listOf(ref("report")) }
    val without = step("b")
    val h = Harness(TestWorkflow(listOf(StepSpec(withArtifact), StepSpec(without))))
    val run = h.start()
    h.drain()

    assertNotNull(h.service.resolveArtifact(run.id, 0, "report"))
    assertNull(h.service.resolveArtifact(run.id, 0, "other"))
    assertNull(h.service.resolveArtifact(run.id, 1, "report"), "step 1 declares nothing")
    assertEquals(emptyList(), h.run(run.id).steps[1].artifacts)
  }

  @Test
  fun wrongRunOrStepIndexResolvesToNull() = blocking {
    val s = step("a")
    s.artifactsFn = { listOf(ref("report")) }
    val h = Harness(TestWorkflow(listOf(StepSpec(s))))
    val run = h.start()
    h.drain()

    assertNull(h.service.resolveArtifact(run.id + 99, 0, "report"))
    assertNull(h.service.resolveArtifact(run.id, 1, "report"))
    assertNull(h.service.resolveArtifact(run.id, -1, "report"))
    assertNull(h.service.resolveArtifact(run.id, Int.MAX_VALUE, "report"))
  }

  @Test
  fun stepsThatHaveNotSucceededExposeNoArtifacts() = blocking {
    val first = step("first")
    first.artifactsFn = { listOf(ref("report")) }
    val failing = step("failing")
    failing.failures.add(PermanentStepException("boom"))
    failing.artifactsFn = { listOf(ref("report")) }
    val pending = step("pending")
    pending.artifactsFn = { listOf(ref("report")) }
    val h = Harness(TestWorkflow(listOf(StepSpec(first), StepSpec(failing), StepSpec(pending))))
    val run = h.start()
    h.drain()

    val dto = h.run(run.id)
    assertEquals(WorkflowRunStatus.PAUSED, dto.status)
    assertEquals(WorkflowStepStatus.SUCCEEDED, dto.steps[0].status)
    assertNotNull(h.service.resolveArtifact(run.id, 0, "report"))
    assertNull(h.service.resolveArtifact(run.id, 1, "report"), "failed step")
    assertNull(h.service.resolveArtifact(run.id, 2, "report"), "pending step")
    assertEquals(emptyList(), dto.steps[1].artifacts)
    assertEquals(emptyList(), dto.steps[2].artifacts)
  }

  @Test
  fun rolledBackStepsNoLongerExposeArtifacts() = blocking {
    val first = step("first")
    first.artifactsFn = { listOf(ref("report")) }
    val failing = step("failing")
    failing.failures.add(PermanentStepException("boom"))
    val h = Harness(TestWorkflow(listOf(StepSpec(first), StepSpec(failing))))
    val run = h.start()
    h.drain()
    h.operate(run.id, WorkflowOperatorAction.ROLLBACK)
    h.drain()

    assertEquals(WorkflowStepStatus.ROLLED_BACK, h.run(run.id).steps[0].status)
    assertNull(h.service.resolveArtifact(run.id, 0, "report"))
  }

  @Test
  fun skippedStepWithOutputStillExposesItsArtifact() = blocking {
    val s = step("already-done")
    s.checkFn = { StepCheck.NoOp("exists", "existing-out") }
    s.artifactsFn = { listOf(ref("report", key = "k/$it")) }
    val h = Harness(TestWorkflow(listOf(StepSpec(s))))
    val run = h.start()
    h.drain()

    assertEquals(WorkflowStepStatus.SKIPPED, h.run(run.id).steps.single().status)
    assertEquals("k/existing-out", h.service.resolveArtifact(run.id, 0, "report")?.key)
  }

  @Test
  fun malformedDuplicateAndThrowingDeclarationsAreIgnored() = blocking {
    val s = step("messy")
    s.artifactsFn = {
      listOf(
          ref("Bad Name"),
          ref("blank-key", key = " "),
          ref("good", key = "first"),
          ref("good", key = "second"))
    }
    val thrower = step("thrower")
    thrower.artifactsFn = { error("boom") }
    val h = Harness(TestWorkflow(listOf(StepSpec(s), StepSpec(thrower))))
    val run = h.start()
    h.drain()

    val dto = h.run(run.id)
    assertEquals(listOf("good"), dto.steps[0].artifacts.map { it.name })
    assertEquals("first", h.service.resolveArtifact(run.id, 0, "good")?.key)
    assertNull(h.service.resolveArtifact(run.id, 0, "Bad Name"))
    assertEquals(emptyList(), dto.steps[1].artifacts)
    assertNull(h.service.resolveArtifact(run.id, 1, "report"))
  }

  @Test
  fun downloadRequestIsAuditedWithStepAndActorAndPublished() = blocking {
    val s = step("a")
    s.artifactsFn = { listOf(ref("report")) }
    val h = Harness(TestWorkflow(listOf(StepSpec(s))))
    val run = h.start()
    h.drain()
    val before = h.bus.events.size

    val event = h.service.recordArtifactDownload(run.id, 0, "report", "asmith")

    assertEquals(0, event.stepIndex)
    assertEquals("asmith", event.actor)
    assertEquals("INFO", event.level)
    assertEquals("Artifact report download requested by asmith", event.message)
    assertEquals(before + 1, h.bus.events.size)
    assertEquals(event, h.bus.events.last())
    assertTrue(h.service.listEvents(run.id).any { it.id == event.id })
  }
}
