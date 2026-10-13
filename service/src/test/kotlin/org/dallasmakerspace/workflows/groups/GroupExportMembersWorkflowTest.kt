package org.dallasmakerspace.workflows.groups

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.activedirectory.ADException
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.DirectoryOus
import org.dallasmakerspace.models.workflows.PlanStepStatus
import org.dallasmakerspace.models.workflows.WorkflowRunDto
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowStepStatus
import org.dallasmakerspace.models.workflows.WorkflowSubject
import org.dallasmakerspace.storage.InMemoryObjectStore
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace
import org.dallasmakerspace.workflows.DefaultWorkflowExecutor
import org.dallasmakerspace.workflows.DefaultWorkflowService
import org.dallasmakerspace.workflows.EngineSettings
import org.dallasmakerspace.workflows.InMemoryWorkflowStore
import org.dallasmakerspace.workflows.InProcessWorkflowEventBus
import org.dallasmakerspace.workflows.StartResult
import org.dallasmakerspace.workflows.StreamFilter
import org.dallasmakerspace.workflows.WorkflowRegistry
import org.dallasmakerspace.workflows.WorkflowWakeSignal
import org.dallasmakerspace.workflows.normalizeSubject
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

private fun fakeUser(name: String) =
    ADUser(
        sAMAccountName = name,
        mail = "$name@example.invalid",
        givenName = "Given",
        sn = "Family",
        telephoneNumber = null,
        employeeID = null,
        displayName = "Display $name",
        objectGuid = "guid-$name",
        whenCreated = null,
        enabled = true,
        groups = emptyList(),
    )

class GroupExportMembersWorkflowTest {
  private val objectStore = InMemoryObjectStore()
  private val exports =
      ObjectStoreFactory(objectStore).forNamespace(StorageNamespace.WORKFLOW_EXPORTS)
  private val inGroupOu = "CN=Test Group,${DirectoryOus.GROUP_OUS.first()}"

  private val directory: IActiveDirectoryService =
      mock {
        on { getGroup(any()) } doAnswer { call ->
          val name = call.getArgument<String>(0)
          when {
            name.equals("Test Group", ignoreCase = true) ->
                ADGroup(
                    cn = "Test Group",
                    description = null,
                    distinguishedName = inGroupOu,
                    objectGuid = "11111111-2222-3333-4444-555555555555",
                    members = listOf(fakeUser("asmith"), fakeUser("bsmith"), fakeUser("csmith")),
                    membersListIncomplete = false,
                )
            name.equals("Outside Group", ignoreCase = true) ->
                ADGroup(
                    cn = "Outside Group",
                    description = null,
                    distinguishedName = "CN=Outside Group,OU=Elsewhere,DC=example,DC=invalid",
                    objectGuid = null,
                    members = emptyList(),
                    membersListIncomplete = false,
                )
            else -> throw ADException("Group $name not found in AD")
          }
        }
      }

  private val workflow =
      GroupExportMembersWorkflow(
          ExportGroupMembersStep(directory, ObjectStoreFactory(objectStore)))
  private val registry = WorkflowRegistry(setOf(workflow))
  private val store = InMemoryWorkflowStore()
  private val bus = InProcessWorkflowEventBus()
  private val wake = WorkflowWakeSignal()
  private val settings = EngineSettings().apply { instanceId = "inst-test" }
  private val service =
      DefaultWorkflowService(store, registry, bus, settings, wake, LoggerFactory())
  private val executor =
      DefaultWorkflowExecutor(store, registry, bus, settings, wake, LoggerFactory())

  private fun input(name: String = "Test Group") =
      JsonObject(mapOf("groupName" to JsonPrimitive(name)))

  /** Starts [token]'s run and waits for it to settle by listening to the bus (no polling). */
  private suspend fun startAndAwait(
      name: String,
      token: String,
  ): WorkflowRunDto {
    val settled =
        CoroutineScope(Dispatchers.Default).async(start = CoroutineStart.UNDISPATCHED) {
          bus.subscribe(StreamFilter(workflowId = workflow.id))
              .first {
                val status = it.run?.status
                status != null && (status.isTerminal || status == WorkflowRunStatus.PAUSED)
              }
              .run!!
        }
    val started = service.start(workflow.id, input(name), "jdoe", "TEST", token)
    val run = assertIs<StartResult.Started>(started).run
    val done = withTimeout(30.seconds) { settled.await() }
    assertEquals(run.id, done.id)
    return service.getRun(run.id)!!
  }

  private fun withExecutor(block: suspend () -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    executor.start(scope)
    try {
      runBlocking { block() }
    } finally {
      runBlocking { executor.shutdown(2_000) }
      scope.cancel()
    }
  }

  @Test
  fun `definition is public read-only and requires manage-groups`() {
    val def = registry.definitions().single()

    assertEquals("group.export-members", def.id)
    assertEquals(1, def.version)
    assertEquals("MANAGE_GROUPS", def.requiredPermission)
    assertEquals(listOf("groupName"), def.inputFields.map { it.name })
    val step = def.steps.single()
    assertEquals("group.export-members", step.id)
    assertTrue(step.isPublic)
    assertEquals(
        org.dallasmakerspace.models.workflows.WorkflowReversibility.REVERSIBLE, step.reversibility)
    assertTrue(workflow.notes.any { it.contains("member names") })
  }

  @Test
  fun `subject is the group and normalizes case and spacing`() {
    val subject = workflow.subjects(GroupExportMembersInput("  Test Group "))

    assertEquals(
        WorkflowSubject("group", "test group"), normalizeSubject(subject.single()))
  }

  @Test
  fun `a blank group name is rejected as invalid input`() = runBlocking<Unit> {
    val thrown =
        runCatching { service.preflight(workflow.id, input("  "), "jdoe") }.exceptionOrNull()

    assertNotNull(thrown)
    assertTrue(thrown.message!!.contains("groupName"))
  }

  @Test
  fun `preflight plans a run for a valid group and changes nothing`() = runBlocking<Unit> {
    val plan = service.preflight(workflow.id, input(), "jdoe")

    assertTrue(plan.canStart)
    assertEquals(PlanStepStatus.WILL_RUN, plan.steps.single().status)
    assertTrue(plan.notes.isNotEmpty())
    assertTrue(exports.list(ObjectKey("group-exports")).isEmpty())
    assertTrue(service.listRuns(org.dallasmakerspace.workflows.RunFilter()).isEmpty())
  }

  @Test
  fun `preflight blocks a missing group and a group outside the group OUs`() = runBlocking<Unit> {
    val missing = service.preflight(workflow.id, input("Nope"), "jdoe")
    val outside = service.preflight(workflow.id, input("Outside Group"), "jdoe")

    assertFalse(missing.canStart)
    assertEquals(PlanStepStatus.BLOCKED, missing.steps.single().status)
    assertFalse(outside.canStart)
    assertEquals(PlanStepStatus.BLOCKED, outside.steps.single().status)
  }

  @Test
  fun `start refuses a blocked group`() = runBlocking<Unit> {
    val result = service.start(workflow.id, input("Outside Group"), "jdoe", "TEST", "tok-blocked")

    assertIs<StartResult.Blocked>(result)
  }

  @Test
  fun `a run succeeds end to end and a second start with a new token also succeeds`() =
      withExecutor {
        val first = startAndAwait("Test Group", "tok-1")

        assertEquals(WorkflowRunStatus.SUCCEEDED, first.status)
        val step = first.steps.single()
        assertEquals(WorkflowStepStatus.SUCCEEDED, step.status)
        assertEquals("3 members exported", step.outputSummary)

        val record = store.getSteps(first.id).single()
        val output = Json.parseToJsonElement(assertNotNull(record.outputJson)).jsonObject
        assertEquals(setOf("key", "memberCount", "sha256"), output.keys)
        assertEquals(
            "group-exports/${first.id}/test-group.json", output["key"]!!.jsonPrimitive.content)
        assertFalse(record.outputJson!!.contains("smith"))
        assertFalse(first.toString().contains("smith"))

        val second = startAndAwait("Test Group", "tok-2")

        assertEquals(WorkflowRunStatus.SUCCEEDED, second.status)
        assertTrue(first.id != second.id)
        val keys = exports.list(ObjectKey("group-exports")).map { it.key.value }.sorted()
        assertEquals(
            listOf(
                "group-exports/${first.id}/test-group.json",
                "group-exports/${second.id}/test-group.json",
            ),
            keys)
      }

  @Test
  fun `the same token returns the existing run instead of starting another`() = withExecutor {
    val run = startAndAwait("Test Group", "tok-same")

    val again = service.start(workflow.id, input(), "jdoe", "TEST", "tok-same")

    assertEquals(run.id, assertIs<StartResult.Existing>(again).run.id)
  }

  @Test
  fun `the subject lock is released once the run succeeds`() = withExecutor {
    startAndAwait("Test Group", "tok-lock")

    assertEquals(null, service.activeRunFor(WorkflowSubject("group", "Test Group")))
  }
}
