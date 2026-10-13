package org.dallasmakerspace.workflows.groups

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
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
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.GroupNotFoundException
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.DirectoryOus
import org.dallasmakerspace.models.workflows.PlanStepStatus
import org.dallasmakerspace.models.workflows.WorkflowReversibility
import org.dallasmakerspace.models.workflows.WorkflowRunDto
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowStepStatus
import org.dallasmakerspace.models.workflows.WorkflowSubject
import org.dallasmakerspace.storage.IObjectStore
import org.dallasmakerspace.storage.InMemoryObjectStore
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreException
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace
import org.dallasmakerspace.storage.StoredObject
import org.dallasmakerspace.workflows.DefaultWorkflowExecutor
import org.dallasmakerspace.workflows.DefaultWorkflowService
import org.dallasmakerspace.workflows.EngineSettings
import org.dallasmakerspace.workflows.Fence
import org.dallasmakerspace.workflows.InMemoryWorkflowStore
import org.dallasmakerspace.workflows.InProcessWorkflowEventBus
import org.dallasmakerspace.workflows.NewEvent
import org.dallasmakerspace.workflows.RunFilter
import org.dallasmakerspace.workflows.StartResult
import org.dallasmakerspace.workflows.StreamFilter
import org.dallasmakerspace.workflows.WorkflowRegistry
import org.dallasmakerspace.workflows.WorkflowStore
import org.dallasmakerspace.workflows.WorkflowWakeSignal
import org.dallasmakerspace.workflows.groupLockMessage
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

private val IN_GROUP_OU = "CN=Test Group,${DirectoryOus.GROUP_OUS.first()}"

private fun fakeGroup(
    name: String,
    dn: String = IN_GROUP_OU,
    members: List<ADUser> = listOf(fakeUser("asmith"), fakeUser("bsmith"), fakeUser("csmith")),
    incomplete: Boolean = false,
    source: Int? = null,
) =
    ADGroup(
        cn = name,
        description = null,
        distinguishedName = dn,
        objectGuid = "11111111-2222-3333-4444-555555555555",
        members = members,
        membersListIncomplete = incomplete,
        sourceMemberCount = source,
    )

/** Directory behavior by call number (1-based) and requested name. */
private typealias Behavior = (Int, String) -> ADGroup

private val defaultBehavior: Behavior = { _, name ->
  when {
    name.equals("Test Group", ignoreCase = true) -> fakeGroup("Test Group")
    name.equals("Outside Group", ignoreCase = true) ->
        fakeGroup(
            "Outside Group",
            dn = "CN=Outside Group,OU=Elsewhere,DC=example,DC=invalid",
            members = emptyList())
    name.equals("Big Group", ignoreCase = true) ->
        fakeGroup("Big Group", incomplete = true, source = 10_005)
    else -> throw GroupNotFoundException(name)
  }
}

private class PutCountingStore(private val d: IObjectStore) : IObjectStore by d {
  val puts = AtomicInteger()

  override suspend fun put(
      key: ObjectKey,
      bytes: ByteArray,
      contentType: String,
      metadata: Map<String, String>
  ): StoredObject {
    puts.incrementAndGet()
    return d.put(key, bytes, contentType, metadata)
  }
}

private class DownStore : IObjectStore by InMemoryObjectStore() {
  override suspend fun head(key: ObjectKey): StoredObject? = throw ObjectStoreException("down")

  override suspend fun put(
      key: ObjectKey,
      bytes: ByteArray,
      contentType: String,
      metadata: Map<String, String>
  ): StoredObject = throw ObjectStoreException("down")
}

/** Throws once from finishStep(SUCCEEDED), as if the process died right after run() returned. */
private class CrashOnFinishStore(private val d: WorkflowStore) : WorkflowStore by d {
  @Volatile var crashNext = false

  override suspend fun finishStep(
      fence: Fence,
      stepIndex: Int,
      status: WorkflowStepStatus,
      outputJson: String?,
      outputSummary: String?,
      error: String?,
      events: List<NewEvent>,
  ) {
    if (crashNext && status == WorkflowStepStatus.SUCCEEDED) {
      crashNext = false
      throw IllegalStateException("simulated crash")
    }
    d.finishStep(fence, stepIndex, status, outputJson, outputSummary, error, events)
  }
}

private class Engine(
    exportStore: IObjectStore,
    leaseSeconds: Int = 30,
    behavior: Behavior = defaultBehavior,
) {
  val calls = AtomicInteger()
  val directory: IActiveDirectoryService = mock {
    on { getGroup(any()) } doAnswer { call ->
      behavior(calls.incrementAndGet(), call.getArgument(0))
    }
  }
  val workflow =
      GroupExportMembersWorkflow(
          ExportGroupMembersStep(directory, ObjectStoreFactory(exportStore)))
  val registry = WorkflowRegistry(setOf(workflow))
  val store = CrashOnFinishStore(InMemoryWorkflowStore())
  val bus = InProcessWorkflowEventBus()
  val wake = WorkflowWakeSignal()
  val settings = EngineSettings().apply {
    instanceId = "inst-test"
    this.leaseSeconds = leaseSeconds
  }
  val service = DefaultWorkflowService(store, registry, bus, settings, wake, LoggerFactory())
  val executor = DefaultWorkflowExecutor(store, registry, bus, settings, wake, LoggerFactory())

  fun input(name: String = "Test Group") = JsonObject(mapOf("groupName" to JsonPrimitive(name)))

  /** Subscribes (before returning) to the next run that settles; event-driven, no polling. */
  fun CoroutineScope.settled(): Deferred<WorkflowRunDto> =
      async(start = CoroutineStart.UNDISPATCHED) {
        bus.subscribe(StreamFilter(workflowId = workflow.id))
            .first {
              val status = it.run?.status
              status != null &&
                  (status.isTerminal ||
                      status == WorkflowRunStatus.PAUSED ||
                      status == WorkflowRunStatus.ROLLBACK_FAILED)
            }
            .run!!
      }

  suspend fun CoroutineScope.startAndAwait(name: String, token: String): WorkflowRunDto {
    val settled = settled()
    val started = service.start(workflow.id, input(name), "jdoe", "TEST", token)
    val run = assertIs<StartResult.Started>(started).run
    val done = withTimeout(30.seconds) { settled.await() }
    assertEquals(run.id, done.id)
    return service.getRun(run.id)!!
  }
}

class GroupExportMembersWorkflowTest {
  private val objectStore = InMemoryObjectStore()
  private val exports =
      ObjectStoreFactory(objectStore).forNamespace(StorageNamespace.WORKFLOW_EXPORTS)

  private fun withExecutor(engine: Engine, block: suspend CoroutineScope.() -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    engine.executor.start(scope)
    try {
      runBlocking { block() }
    } finally {
      runBlocking { engine.executor.shutdown(2_000) }
      scope.cancel()
    }
  }

  // definition and preflight

  @Test
  fun `definition is public read-only auto-rollback and requires manage-groups`() {
    val engine = Engine(objectStore)
    val def = engine.registry.definitions().single()

    assertEquals("group.export-members", def.id)
    assertEquals(1, def.version)
    assertEquals("MANAGE_GROUPS", def.requiredPermission)
    assertEquals(listOf("groupName"), def.inputFields.map { it.name })
    val step = def.steps.single()
    assertEquals("group.export-members", step.id)
    assertTrue(step.isPublic)
    assertEquals(WorkflowReversibility.REVERSIBLE, step.reversibility)
    assertEquals(
        org.dallasmakerspace.workflows.FailurePolicy.AUTO_ROLLBACK, engine.workflow.onFailure)
    assertTrue(engine.workflow.notes.any { it.contains("member names") })
  }

  @Test
  fun `subject is the group and normalizes case and spacing`() {
    val subject = Engine(objectStore).workflow.subjects(GroupExportMembersInput("  Test Group "))

    assertEquals(WorkflowSubject("group", "test group"), normalizeSubject(subject.single()))
  }

  @Test
  fun `a blank group name is rejected as invalid input`() = runBlocking<Unit> {
    val engine = Engine(objectStore)

    val thrown =
        runCatching { engine.service.preflight(engine.workflow.id, engine.input("  "), "jdoe") }
            .exceptionOrNull()

    assertNotNull(thrown)
    assertTrue(thrown.message!!.contains("groupName"))
  }

  @Test
  fun `preflight plans a run for a valid group and changes nothing`() = runBlocking<Unit> {
    val engine = Engine(objectStore)

    val plan = engine.service.preflight(engine.workflow.id, engine.input(), "jdoe")

    assertTrue(plan.canStart)
    assertEquals(PlanStepStatus.WILL_RUN, plan.steps.single().status)
    assertTrue(plan.notes.isNotEmpty())
    assertTrue(exports.list(ObjectKey("group-exports")).isEmpty())
    assertTrue(engine.service.listRuns(RunFilter()).isEmpty())
  }

  @Test
  fun `preflight blocks a missing group and a group outside the group OUs`() = runBlocking<Unit> {
    val engine = Engine(objectStore)

    val missing = engine.service.preflight(engine.workflow.id, engine.input("Nope"), "jdoe")
    val outside =
        engine.service.preflight(engine.workflow.id, engine.input("Outside Group"), "jdoe")

    assertFalse(missing.canStart)
    assertEquals(PlanStepStatus.BLOCKED, missing.steps.single().status)
    assertFalse(outside.canStart)
    assertEquals(PlanStepStatus.BLOCKED, outside.steps.single().status)
  }

  @Test
  fun `preflight still validates the group when the object store is down`() = runBlocking<Unit> {
    val engine = Engine(DownStore())

    val missing = engine.service.preflight(engine.workflow.id, engine.input("Nope"), "jdoe")
    val outside =
        engine.service.preflight(engine.workflow.id, engine.input("Outside Group"), "jdoe")
    val fine = engine.service.preflight(engine.workflow.id, engine.input(), "jdoe")

    assertEquals(PlanStepStatus.BLOCKED, missing.steps.single().status)
    assertEquals(PlanStepStatus.BLOCKED, outside.steps.single().status)
    assertTrue(fine.canStart)
    assertEquals(PlanStepStatus.WILL_RUN, fine.steps.single().status)
  }

  @Test
  fun `preflight warns about a truncated group but allows the run`() = runBlocking<Unit> {
    val engine = Engine(objectStore)

    val plan = engine.service.preflight(engine.workflow.id, engine.input("Big Group"), "jdoe")

    assertTrue(plan.canStart)
    assertEquals(PlanStepStatus.WARNING, plan.steps.single().status)
  }

  @Test
  fun `start refuses a blocked group`() = runBlocking<Unit> {
    val engine = Engine(objectStore)

    val result =
        engine.service.start(
            engine.workflow.id, engine.input("Outside Group"), "jdoe", "TEST", "tok-blocked")

    assertIs<StartResult.Blocked>(result)
  }

  // end to end

  @Test
  fun `a run succeeds end to end and a second start with a new token also succeeds`() {
    val engine = Engine(objectStore)
    withExecutor(engine) {
      val first = with(engine) { startAndAwait("Test Group", "tok-1") }

      assertEquals(WorkflowRunStatus.SUCCEEDED, first.status)
      val step = first.steps.single()
      assertEquals(WorkflowStepStatus.SUCCEEDED, step.status)
      assertEquals("3 members exported", step.outputSummary)

      val record = engine.store.getSteps(first.id).single()
      val output = Json.parseToJsonElement(assertNotNull(record.outputJson)).jsonObject
      assertEquals(
          setOf(
              "key", "memberCount", "sha256", "complete", "sourceMemberCount", "nestedGroupCount"),
          output.keys)
      assertEquals(
          "group-exports/${first.id}/test-group.json", output["key"]!!.jsonPrimitive.content)
      assertFalse(record.outputJson!!.contains("smith"))
      assertFalse(first.toString().contains("smith"))

      val second = with(engine) { startAndAwait("Test Group", "tok-2") }

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
  }

  @Test
  fun `the same token returns the existing run instead of starting another`() {
    val engine = Engine(objectStore)
    withExecutor(engine) {
      val run = with(engine) { startAndAwait("Test Group", "tok-same") }

      val again =
          engine.service.start(engine.workflow.id, engine.input(), "jdoe", "TEST", "tok-same")

      assertEquals(run.id, assertIs<StartResult.Existing>(again).run.id)
    }
  }

  @Test
  fun `the subject lock is held while the run executes and released once it succeeds`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val engine =
        Engine(objectStore) { call, name ->
          if (call >= 2) {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
          }
          defaultBehavior(call, name)
        }
    withExecutor(engine) {
      val settled = with(engine) { settled() }
      val started =
          engine.service.start(engine.workflow.id, engine.input(), "jdoe", "TEST", "tok-lock")
      val run = assertIs<StartResult.Started>(started).run
      assertTrue(entered.await(30, TimeUnit.SECONDS), "run never reached the directory read")

      val active = engine.service.activeRunFor(WorkflowSubject("group", "Test Group"))
      assertEquals(run.id, assertNotNull(active).id)
      assertTrue(active.status == WorkflowRunStatus.RUNNING)
      assertNotNull(engine.service.groupLockMessage("Test Group"))
      assertNotNull(engine.service.groupLockMessage("  TEST group "))

      release.countDown()
      val done = withTimeout(30.seconds) { settled.await() }

      assertEquals(WorkflowRunStatus.SUCCEEDED, done.status)
      assertNull(engine.service.activeRunFor(WorkflowSubject("group", "Test Group")))
      assertNull(engine.service.groupLockMessage("Test Group"))
    }
  }

  @Test
  fun `a permanent failure rolls the run back and releases the subject lock`() {
    val engine =
        Engine(objectStore) { call, name ->
          if (call >= 2) throw GroupNotFoundException(name)
          defaultBehavior(call, name)
        }
    withExecutor(engine) {
      val run = with(engine) { startAndAwait("Test Group", "tok-fail") }

      assertEquals(WorkflowRunStatus.ROLLED_BACK, run.status)
      assertEquals(WorkflowStepStatus.ROLLED_BACK, run.steps.single().status)
      assertNull(engine.service.activeRunFor(WorkflowSubject("group", "Test Group")))
      assertNull(engine.service.groupLockMessage("Test Group"))
      assertTrue(exports.list(ObjectKey("group-exports")).isEmpty())
    }
  }

  @Test
  fun `a crash after the upload but before the result is saved resumes via NoOp`() {
    val puts = PutCountingStore(objectStore)
    val engine = Engine(puts, leaseSeconds = 1)
    withExecutor(engine) {
      engine.store.crashNext = true

      val run = with(engine) { startAndAwait("Test Group", "tok-crash") }

      assertEquals(WorkflowRunStatus.SUCCEEDED, run.status)
      assertEquals(1, puts.puts.get(), "the resumed attempt must not upload again")
      val record = engine.store.getSteps(run.id).single()
      assertEquals(WorkflowStepStatus.SUCCEEDED, record.status)
      assertTrue(record.attempts >= 1)
      val output = Json.parseToJsonElement(assertNotNull(record.outputJson)).jsonObject
      val stored = assertNotNull(exports.head(ObjectKey(output["key"]!!.jsonPrimitive.content)))
      assertEquals(stored.metadata["sha256"], output["sha256"]!!.jsonPrimitive.content)
      val keys = exports.list(ObjectKey("group-exports")).map { it.key.value }
      assertEquals(listOf(stored.key.value), keys)
    }
  }
}
