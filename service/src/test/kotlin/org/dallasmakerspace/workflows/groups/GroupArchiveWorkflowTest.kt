package org.dallasmakerspace.workflows.groups

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.dallasmakerspace.activedirectory.GroupDeletionRefusedException
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.ARCHIVE_ARTIFACT_NAME
import org.dallasmakerspace.members.ARCHIVE_ARTIFACT_STEP_INDEX
import org.dallasmakerspace.models.workflows.PlanStepStatus
import org.dallasmakerspace.models.workflows.WorkflowOperatorAction
import org.dallasmakerspace.models.workflows.WorkflowReversibility
import org.dallasmakerspace.models.workflows.WorkflowRunDto
import org.dallasmakerspace.models.workflows.WorkflowRunStatus
import org.dallasmakerspace.models.workflows.WorkflowStepStatus
import org.dallasmakerspace.models.workflows.WorkflowSubject
import org.dallasmakerspace.storage.IObjectStore
import org.dallasmakerspace.storage.InMemoryObjectStore
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace
import org.dallasmakerspace.storage.StoredObject
import org.dallasmakerspace.workflows.AuthorizationDecision
import org.dallasmakerspace.workflows.DefaultWorkflowExecutor
import org.dallasmakerspace.workflows.DefaultWorkflowService
import org.dallasmakerspace.workflows.EngineSettings
import org.dallasmakerspace.workflows.FailurePolicy
import org.dallasmakerspace.workflows.InMemoryWorkflowStore
import org.dallasmakerspace.workflows.InProcessWorkflowEventBus
import org.dallasmakerspace.workflows.InvalidWorkflowInputException
import org.dallasmakerspace.workflows.OperateResult
import org.dallasmakerspace.workflows.StartResult
import org.dallasmakerspace.workflows.StreamFilter
import org.dallasmakerspace.workflows.WorkflowForbiddenException
import org.dallasmakerspace.workflows.WorkflowAuthorization
import org.dallasmakerspace.workflows.WorkflowRegistry
import org.dallasmakerspace.workflows.WorkflowStartAuthorizer
import org.dallasmakerspace.workflows.WorkflowWakeSignal
import org.dallasmakerspace.workflows.groups.references.GroupReference
import org.dallasmakerspace.workflows.groups.references.GroupReferenceChecker
import org.dallasmakerspace.workflows.groups.references.GroupReferenceFinder
import org.dallasmakerspace.workflows.groups.references.GroupReferenceKinds
import org.dallasmakerspace.workflows.groups.references.ProtectedGroupReferenceFinder
import org.dallasmakerspace.workflows.normalizeSubject
import org.junit.Test

private const val GROUP = "Test Group"
private const val REASON = "Retired after the project ended"

/** Runs a hook after every successful put (to change the directory between export and delete). */
private class HookedStore(private val d: IObjectStore, var afterPut: () -> Unit = {}) :
    IObjectStore by d {
  override suspend fun put(
      key: ObjectKey,
      bytes: ByteArray,
      contentType: String,
      metadata: Map<String, String>
  ): StoredObject = d.put(key, bytes, contentType, metadata).also { afterPut() }
}

private class AllowAuthorizer : WorkflowStartAuthorizer {
  val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<JsonObject, String>>()
  @Volatile var decision: AuthorizationDecision = AuthorizationDecision.Allowed

  override suspend fun authorize(
      workflowId: String,
      input: JsonObject,
      actor: String,
  ): AuthorizationDecision {
    seen += input to actor
    return decision
  }
}

private class MutableFinder : GroupReferenceFinder {
  @Volatile var hits: List<GroupReference> = emptyList()

  override val system = "Calendar"

  override suspend fun find(groupName: String) = hits
}

private class ArchiveEngine(
    val directory: FakeDirectory = FakeDirectory(),
    val archive: RecordingArchive = RecordingArchive(),
    val history: RecordingGroupHistory = RecordingGroupHistory(),
    finders: Set<GroupReferenceFinder> = setOf(ProtectedGroupReferenceFinder()),
) {
  val backing = InMemoryObjectStore()
  val objects = HookedStore(backing)
  val exports = ObjectStoreFactory(backing).forNamespace(StorageNamespace.WORKFLOW_EXPORTS)
  private val factory = ObjectStoreFactory(objects)
  private val checker = GroupReferenceChecker(finders)
  val workflow =
      GroupArchiveWorkflow(
          ExportGroupMembersStep(directory, factory),
          MarkGroupDisabledStep(directory, history, archive),
          DeleteAdGroupStep(directory, factory, archive, checker),
          activeDirectory = directory,
          references = checker,
          archive = archive,
      )
  val registry = WorkflowRegistry(setOf(workflow))
  val store = InMemoryWorkflowStore()
  val bus = InProcessWorkflowEventBus()
  val wake = WorkflowWakeSignal()
  val settings = EngineSettings().apply { instanceId = "inst-test" }
  val authorizer = AllowAuthorizer()
  val service =
      DefaultWorkflowService(
          store,
          registry,
          bus,
          settings,
          wake,
          LoggerFactory(),
          mapOf(WorkflowAuthorization.GROUP_ADMIN to authorizer))
  val executor = DefaultWorkflowExecutor(store, registry, bus, settings, wake, LoggerFactory())

  init {
    directory.add(FakeGroup(GROUP))
  }

  fun input(name: String = GROUP, reason: String = REASON) =
      JsonObject(mapOf("groupName" to JsonPrimitive(name), "reason" to JsonPrimitive(reason)))

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

  suspend fun CoroutineScope.startAndAwait(
      token: String,
      name: String = GROUP,
      reason: String = REASON,
  ): WorkflowRunDto {
    val settled = settled()
    val started = service.start(workflow.id, input(name, reason), "jdoe", "TEST", token)
    val run = assertIs<StartResult.Started>(started).run
    val done = withTimeout(30.seconds) { settled.await() }
    assertEquals(run.id, done.id)
    return service.getRun(run.id)!!
  }

  suspend fun CoroutineScope.operateAndAwait(
      runId: Long,
      action: WorkflowOperatorAction,
  ): WorkflowRunDto {
    val settled = settled()
    assertIs<OperateResult.Ok>(service.operate(runId, action, "asmith"))
    withTimeout(30.seconds) { settled.await() }
    return service.getRun(runId)!!
  }

  suspend fun plan(name: String = GROUP, reason: String = REASON) =
      service.preflight(workflow.id, input(name, reason), "jdoe")

  fun statuses(run: WorkflowRunDto) = run.steps.map { it.status }

  fun archiveKeys() = runBlocking { exports.list(ObjectKey("group-archives")).map { it.key.value } }
}

class GroupArchiveWorkflowTest {

  private fun withExecutor(engine: ArchiveEngine, block: suspend CoroutineScope.() -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    engine.executor.start(scope)
    try {
      runBlocking { block() }
    } finally {
      runBlocking { engine.executor.shutdown(2_000) }
      scope.cancel()
    }
  }

  // definition

  @Test
  fun `definition names the authorization, the confirmation field and the safe failure policy`() {
    val engine = ArchiveEngine()
    val def = engine.registry.definitions().single()

    assertEquals("group.archive", def.id)
    assertEquals(1, def.version)
    assertEquals("Archive group", def.displayName)
    assertNull(def.requiredPermission)
    assertEquals("GROUP_ADMIN", def.authorization)
    assertEquals("groupName", def.confirmTypingField)
    assertEquals(FailurePolicy.PAUSE, engine.workflow.onFailure)
    assertEquals(3, engine.workflow.retry.maxAttempts)
    assertEquals(
        listOf(
            "group.check-archivable",
            "group.export-members",
            "group.mark-disabled",
            "group.delete-ad-group",
            "group.finalize-archive"),
        def.steps.map { it.id })
    assertEquals(
        listOf(false, true, true, true, false), def.steps.map { it.isPublic })
    assertEquals(
        listOf(
            WorkflowReversibility.REVERSIBLE,
            WorkflowReversibility.REVERSIBLE,
            WorkflowReversibility.REVERSIBLE,
            WorkflowReversibility.BEST_EFFORT,
            WorkflowReversibility.IRREVERSIBLE),
        def.steps.map { it.reversibility })
  }

  @Test
  fun `input fields describe the multiline bounded reason`() {
    val def = ArchiveEngine().registry.definitions().single()

    val (group, reason) = def.inputFields
    assertEquals("groupName", group.name)
    assertFalse(group.multiline)
    assertEquals("reason", reason.name)
    assertTrue(reason.multiline)
    assertEquals(10, reason.minLength)
    assertEquals(200, reason.maxLength)
    assertEquals(
        "Visible to all members in the Archived Groups report. Do not include personal details.",
        reason.hint)
  }

  @Test
  fun `the artifact lookup used by the report points at the export step`() {
    val steps = ArchiveEngine().registry.definitions().single().steps
    assertEquals("group.export-members", steps[ARCHIVE_ARTIFACT_STEP_INDEX].id)
    assertEquals(ExportGroupMembersStep.MEMBER_LIST_ARTIFACT, ARCHIVE_ARTIFACT_NAME)
  }

  @Test
  fun `notes warn about the export, the delete and the limits of a rollback`() {
    val notes = ArchiveEngine().workflow.notes.joinToString(" ")

    assertTrue(notes.contains("object store"))
    assertTrue(notes.contains("deleted from Active Directory"))
    assertTrue(notes.contains("lose any access"))
    assertTrue(notes.contains("new identity"))
    assertTrue(notes.contains("cannot be restored"))
  }

  @Test
  fun `the subject is the trimmed group and normalizes case`() {
    val subject = ArchiveEngine().workflow.subjects(GroupArchiveInput("  Test Group ", REASON))

    assertEquals(WorkflowSubject("group", "test group"), normalizeSubject(subject.single()))
  }

  // input validation

  @Test
  fun `reason length is bounded to 10 through 200 characters after trimming`() {
    val wf = ArchiveEngine().workflow

    fun problems(reason: String, group: String = GROUP) =
        wf.validateInput(GroupArchiveInput(group, reason))

    assertTrue(problems("x".repeat(10)).isEmpty())
    assertTrue(problems("x".repeat(200)).isEmpty())
    assertTrue(problems("  " + "x".repeat(10) + "  ").isEmpty())
    assertTrue(problems("x".repeat(9)).single().contains("reason"))
    assertTrue(problems("x".repeat(201)).single().contains("reason"))
    assertTrue(problems("   short   ").single().contains("reason"))
    assertTrue(problems("").single().contains("reason"))
    assertTrue(problems(REASON, group = "  ").single().contains("groupName"))
    assertEquals(2, problems("", group = "").size)
  }

  @Test
  fun `the group name is bounded and free of control characters`() {
    val wf = ArchiveEngine().workflow

    fun problems(group: String) = wf.validateInput(GroupArchiveInput(group, REASON))

    assertTrue(problems("g".repeat(256)).isEmpty())
    assertTrue(problems("  Test Group  ").isEmpty())
    assertTrue(problems("g".repeat(257)).single().contains("256"))
    assertTrue(problems("").single().contains("groupName"))
    assertTrue(problems("   ").single().contains("groupName"))
    listOf("a\nb", "a\rb", "a\u0000b", "a\tb", "a\u001bb").forEach {
      assertTrue(problems(it).single().contains("control"), it)
    }
  }

  @Test
  fun `an out of range reason or missing group is rejected before anything is evaluated`() =
      runBlocking<Unit> {
        val engine = ArchiveEngine()

        assertFailsWith<InvalidWorkflowInputException> { engine.plan(reason = "too short") }
        assertFailsWith<InvalidWorkflowInputException> { engine.plan(name = " ") }
        assertFailsWith<InvalidWorkflowInputException> {
          engine.service.start(
              engine.workflow.id, engine.input(reason = "x".repeat(201)), "jdoe", "TEST", "t")
        }
        assertTrue(engine.directory.calls.isEmpty())
      }

  // authorization

  @Test
  fun `preflight and start consult the authorizer with the raw input and the actor`() =
      runBlocking<Unit> {
        val engine = ArchiveEngine()

        engine.plan()
        engine.service.start(engine.workflow.id, engine.input(), "jdoe", "TEST", "tok-auth")

        assertEquals(2, engine.authorizer.seen.size)
        assertEquals(engine.input() to "jdoe", engine.authorizer.seen.first())
      }

  @Test
  fun `a denied actor is forbidden on preflight and start and nothing is touched`() =
      runBlocking<Unit> {
        val engine = ArchiveEngine()
        engine.authorizer.decision = AuthorizationDecision.Denied("not for you")

        val thrown = assertFailsWith<WorkflowForbiddenException> { engine.plan() }
        val started =
            engine.service.start(engine.workflow.id, engine.input(), "jdoe", "TEST", "tok-no")

        assertEquals("not for you", thrown.reason)
        assertEquals("not for you", assertIs<StartResult.Forbidden>(started).reason)
        assertTrue(engine.directory.calls.isEmpty())
        assertTrue(engine.archive.calls.isEmpty())
        assertTrue(engine.service.listRuns(org.dallasmakerspace.workflows.RunFilter()).isEmpty())
      }

  @Test
  fun `without a registered authorizer the workflow fails closed`() = runBlocking<Unit> {
    val engine = ArchiveEngine()
    val bare =
        DefaultWorkflowService(
            engine.store,
            engine.registry,
            engine.bus,
            engine.settings,
            engine.wake,
            LoggerFactory())

    assertFailsWith<WorkflowForbiddenException> {
      bare.preflight(engine.workflow.id, engine.input(), "jdoe")
    }
    assertIs<StartResult.Forbidden>(
        bare.start(engine.workflow.id, engine.input(), "jdoe", "TEST", "tok-bare"))
  }

  // preflight

  @Test
  fun `preflight of a healthy group can start, warns, and changes nothing`() = runBlocking<Unit> {
    val engine = ArchiveEngine()

    val plan = engine.plan()

    assertTrue(plan.canStart)
    assertEquals(
        listOf(
            PlanStepStatus.WARNING,
            PlanStepStatus.WILL_RUN,
            PlanStepStatus.WILL_RUN,
            PlanStepStatus.WILL_RUN,
            PlanStepStatus.WILL_RUN),
        plan.steps.map { it.status })
    assertTrue(plan.steps[0].messages.any { it.contains("outside the portal") })
    assertEquals(engine.workflow.notes, plan.notes)
    assertTrue(engine.directory.writes().isEmpty(), "preflight wrote to the directory")
    assertTrue(engine.archive.calls.isEmpty(), "preflight wrote to the portal")
    assertTrue(engine.history.resolved.isEmpty())
    assertTrue(engine.archiveKeys().isEmpty())
    assertTrue(engine.service.listRuns(org.dallasmakerspace.workflows.RunFilter()).isEmpty())
  }

  @Test
  fun `preflight warns when the group is itself a member of other groups`() = runBlocking<Unit> {
    val engine = ArchiveEngine()
    engine.directory.find(GROUP)!!.parents = listOf("CN=Parent,$MAIN_OU", "CN=Other,$MAIN_OU")

    val plan = engine.plan()

    assertTrue(plan.canStart)
    assertTrue(
        plan.steps[0].messages.any {
          it.contains("member of 2 other group(s)") &&
              it.contains("restores them only for groups under the main group location")
        })
  }

  @Test
  fun `a blocking reference blocks the plan and is listed without member data`() =
      runBlocking<Unit> {
        val engine =
            ArchiveEngine(
                finders =
                    setOf(
                        StaticFinder(
                            "Calendar",
                            listOf(
                                GroupReference(
                                    "Calendar",
                                    GroupReferenceKinds.PREREQUISITE,
                                    2,
                                    "2 class prerequisites use this group")))))

        val plan = engine.plan()

        assertFalse(plan.canStart)
        assertEquals(PlanStepStatus.BLOCKED, plan.steps[0].status)
        assertEquals(
            listOf("Calendar: 2 class prerequisites use this group"), plan.steps[0].messages)
        assertFalse(plan.steps[0].messages.joinToString().contains("smith"))
      }

  @Test
  fun `a failed reference lookup also blocks`() = runBlocking<Unit> {
    val failing =
        object : GroupReferenceFinder {
          override val system = "Moodle"

          override suspend fun find(groupName: String): List<GroupReference> =
              throw java.io.IOException("down")
        }
    val engine = ArchiveEngine(finders = setOf(failing))

    val plan = engine.plan()

    assertFalse(plan.canStart)
    assertTrue(plan.steps[0].messages.single().startsWith("Moodle: could not check Moodle"))
  }

  @Test
  fun `a protected group is blocked once, with the reason`() = runBlocking<Unit> {
    val engine = ArchiveEngine()
    engine.directory.add(FakeGroup("Infrastructure"))

    val plan = engine.plan(name = "Infrastructure")

    assertFalse(plan.canStart)
    assertEquals(PlanStepStatus.BLOCKED, plan.steps[0].status)
    assertEquals(1, plan.steps[0].messages.size)
    assertTrue(plan.steps[0].messages.single().startsWith("Protected group: "))
  }

  @Test
  fun `a group with nested groups is blocked`() = runBlocking<Unit> {
    val engine = ArchiveEngine()
    engine.directory.find(GROUP)!!.nested = listOf("CN=Inner,$MAIN_OU")

    val plan = engine.plan()

    assertFalse(plan.canStart)
    assertTrue(plan.steps[0].messages.single().contains("1 nested group(s)"))
  }

  @Test
  fun `a group in the admin OU is blocked`() = runBlocking<Unit> {
    val engine = ArchiveEngine()
    engine.directory.add(FakeGroup("Admin Group", dn = "CN=Admin Group,$ADMIN_OU"))

    val plan = engine.plan(name = "Admin Group")

    assertFalse(plan.canStart)
    assertEquals(PlanStepStatus.BLOCKED, plan.steps[0].status)
    assertTrue(plan.steps[0].messages.single().contains("outside the main group location"))
  }

  @Test
  fun `a missing group is blocked`() = runBlocking<Unit> {
    val engine = ArchiveEngine()

    val plan = engine.plan(name = "Nope")

    assertFalse(plan.canStart)
    assertTrue(plan.steps[0].messages.single().contains("was not found"))
  }

  @Test
  fun `an incomplete membership blocks the export step`() = runBlocking<Unit> {
    val engine = ArchiveEngine()
    engine.directory.find(GROUP)!!.incomplete = true
    val truncated = engine.plan()
    engine.directory.find(GROUP)!!.incomplete = false
    engine.directory.find(GROUP)!!.unresolved = 2
    val unresolved = engine.plan()

    assertFalse(truncated.canStart)
    assertEquals(PlanStepStatus.BLOCKED, truncated.steps[1].status)
    assertFalse(unresolved.canStart)
    assertEquals(PlanStepStatus.BLOCKED, unresolved.steps[1].status)
    assertTrue(unresolved.steps[1].messages.single().contains("2 members could not be resolved"))
  }

  @Test
  fun `start refuses a blocked plan`() = runBlocking<Unit> {
    val engine = ArchiveEngine()
    engine.directory.find(GROUP)!!.nested = listOf("CN=Inner,$MAIN_OU")

    val result =
        engine.service.start(engine.workflow.id, engine.input(), "jdoe", "TEST", "tok-blocked")

    assertIs<StartResult.Blocked>(result)
    assertTrue(engine.directory.writes().isEmpty())
  }

  // end to end

  @Test
  fun `a run archives the group and records who, why and the private artifact`() {
    val engine = ArchiveEngine()
    withExecutor(engine) {
      val run = with(engine) { startAndAwait("tok-1", reason = "  $REASON  ") }

      assertEquals(WorkflowRunStatus.SUCCEEDED, run.status)
      assertEquals(List(5) { WorkflowStepStatus.SUCCEEDED }, engine.statuses(run))
      assertNull(engine.directory.find(GROUP), "the directory group should be gone")

      val archived = engine.archive.listArchived(emptyList()).single()
      assertEquals(GROUP, archived.name)
      assertEquals("jdoe", archived.archivedBy)
      assertEquals(REASON, archived.reason)
      assertEquals(run.id, archived.runId)
      assertEquals(ARCHIVE_ARTIFACT_STEP_INDEX, archived.artifactStepIndex)
      assertEquals(ARCHIVE_ARTIFACT_NAME, archived.artifactName)
      assertEquals("ARCHIVED", engine.archive.statusOf(GROUP))
      assertEquals(
          listOf(GROUP, "jdoe", REASON, run.id, "11111111-2222-3333-4444-555555555555"),
          engine.archive.finalized.single())

      val artifact = run.steps[ARCHIVE_ARTIFACT_STEP_INDEX].artifacts.single()
      assertEquals(ARCHIVE_ARTIFACT_NAME, artifact.name)
      assertTrue(artifact.infraOnly)
      val resolved =
          assertNotNull(
              engine.service.resolveArtifact(
                  run.id, ARCHIVE_ARTIFACT_STEP_INDEX, ARCHIVE_ARTIFACT_NAME))
      assertTrue(resolved.infraOnly)
      assertEquals("WORKFLOW_EXPORTS", resolved.namespace)
      assertEquals("group-archives/${run.id}/test-group.json", resolved.key)
      assertEquals(listOf(resolved.key), engine.archiveKeys())
      assertTrue(engine.exports.list(ObjectKey("group-exports")).isEmpty())
      assertEquals(
          Triple(GROUP, "CN=$GROUP,$MAIN_OU", "11111111-2222-3333-4444-555555555555"),
          engine.history.resolved.single())
    }
  }

  @Test
  fun `only the declared steps touch the directory`() {
    val engine = ArchiveEngine()
    withExecutor(engine) {
      val run = with(engine) { startAndAwait("tok-calls") }

      assertEquals(WorkflowRunStatus.SUCCEEDED, run.status)
      assertEquals(listOf("deleteGroup:$GROUP"), engine.directory.writes())
      assertTrue(
          engine.directory.calls.all {
            it == "deleteGroup:$GROUP" ||
                it == "getGroup:$GROUP" ||
                it == "getGroupStrict:$GROUP" ||
                it.startsWith("findGroupByGuid:")
          },
          engine.directory.calls.toString())
    }
  }

  @Test
  fun `persisted step outputs hold no member data`() {
    val engine = ArchiveEngine()
    withExecutor(engine) {
      val run = with(engine) { startAndAwait("tok-pii") }

      val outputs = engine.store.getSteps(run.id).mapNotNull { it.outputJson }.joinToString()
      assertTrue(outputs.contains("membersSha256"))
      listOf("asmith", "bsmith", "csmith", "Display", "example.invalid").forEach {
        assertFalse(outputs.contains(it), "output leaks '$it'")
      }
      assertFalse(run.toString().contains("smith"))
    }
  }

  @Test
  fun `the same token returns the existing run while the subject is locked`() {
    val engine = ArchiveEngine()
    engine.directory.failDelete = GroupDeletionRefusedException(GROUP, "refused")
    withExecutor(engine) {
      val run = with(engine) { startAndAwait("tok-same") }
      assertEquals(WorkflowRunStatus.PAUSED, run.status)

      val again =
          engine.service.start(engine.workflow.id, engine.input(), "jdoe", "TEST", "tok-same")
      val other =
          engine.service.start(engine.workflow.id, engine.input(), "jdoe", "TEST", "tok-other")

      assertEquals(run.id, assertIs<StartResult.Existing>(again).run.id)
      assertEquals(run.id, assertIs<StartResult.SubjectLocked>(other).activeRun.id)
    }
  }

  // snapshot staleness

  @Test
  fun `a membership change between the export and the delete blocks the delete`() {
    val engine = ArchiveEngine()
    engine.objects.afterPut = { engine.directory.find(GROUP)!!.members += "dsmith" }
    withExecutor(engine) {
      val run = with(engine) { startAndAwait("tok-stale") }

      assertEquals(WorkflowRunStatus.PAUSED, run.status)
      assertEquals(WorkflowStepStatus.FAILED, run.steps[3].status)
      assertTrue(run.steps[3].error!!.contains("members changed since the snapshot"))
      assertNotNull(engine.directory.find(GROUP), "the group must not have been deleted")
      assertFalse(engine.directory.calls.contains("deleteGroup:$GROUP"))
      assertTrue(engine.archive.finalized.isEmpty())
      assertEquals("DISABLED", engine.archive.statusOf(GROUP))
    }
  }

  @Test
  fun `a reference that appears while the run is paused blocks the retry`() {
    val late = MutableFinder()
    val engine = ArchiveEngine(finders = setOf(late))
    engine.directory.failDelete = GroupDeletionRefusedException(GROUP, "refused")
    withExecutor(engine) {
      val paused = with(engine) { startAndAwait("tok-late") }
      assertEquals(WorkflowRunStatus.PAUSED, paused.status)
      late.hits =
          listOf(GroupReference("Calendar", GroupReferenceKinds.PREREQUISITE, 1, "1 prerequisite"))

      val retried = with(engine) { operateAndAwait(paused.id, WorkflowOperatorAction.RETRY) }

      assertEquals(WorkflowRunStatus.PAUSED, retried.status)
      assertTrue(retried.steps[3].error!!.contains("Calendar: 1 prerequisite"))
      assertNotNull(engine.directory.find(GROUP))
      assertEquals(1, engine.directory.deleteGuids.size, "no second delete attempt")
    }
  }

  @Test
  fun `a group replaced under the same name while the run is paused is left alone`() {
    val engine = ArchiveEngine()
    engine.directory.failDelete = GroupDeletionRefusedException(GROUP, "refused")
    withExecutor(engine) {
      val paused = with(engine) { startAndAwait("tok-swap") }
      engine.directory.groups.remove(GROUP.lowercase())
      engine.directory.add(
          FakeGroup(GROUP, guid = "cccccccc-0000-0000-0000-000000000003"))

      val retried = with(engine) { operateAndAwait(paused.id, WorkflowOperatorAction.RETRY) }

      // the snapshotted group is gone (by GUID), so the delete is a no-op and the stranger stays
      assertEquals(WorkflowRunStatus.SUCCEEDED, retried.status)
      assertEquals(WorkflowStepStatus.SUCCEEDED, retried.steps[3].status)
      assertEquals("cccccccc-0000-0000-0000-000000000003", engine.directory.find(GROUP)!!.guid)
      assertEquals(1, engine.directory.deleteGuids.size)
    }
  }

  // failure, pause and rollback

  @Test
  fun `a delete failure pauses the run and a rollback restores the portal state`() {
    val engine = ArchiveEngine()
    engine.directory.failDelete = GroupDeletionRefusedException(GROUP, "CN=x,$ADMIN_OU")
    withExecutor(engine) {
      val paused = with(engine) { startAndAwait("tok-pause") }

      assertEquals(WorkflowRunStatus.PAUSED, paused.status)
      assertEquals(WorkflowStepStatus.FAILED, paused.steps[3].status)
      assertEquals("DISABLED", engine.archive.statusOf(GROUP))
      assertNotNull(engine.service.activeRunFor(WorkflowSubject("group", GROUP)))

      val rolled = with(engine) { operateAndAwait(paused.id, WorkflowOperatorAction.ROLLBACK) }

      assertEquals(WorkflowRunStatus.ROLLED_BACK, rolled.status)
      assertEquals("ACTIVE", engine.archive.statusOf(GROUP))
      assertNotNull(engine.directory.find(GROUP))
      assertFalse(engine.directory.calls.any { it.startsWith("createGroup") })
      assertTrue(engine.archive.finalized.isEmpty())
      assertNull(engine.service.activeRunFor(WorkflowSubject("group", GROUP)))
      assertEquals(1, engine.archiveKeys().size, "the snapshot is kept on rollback")
    }
  }

  @Test
  fun `a rollback after the group was deleted recreates it with its members and a new identity`() {
    val engine = ArchiveEngine()
    val original = engine.directory.find(GROUP)!!
    original.parents = listOf("CN=Parent,$MAIN_OU")
    engine.directory.failAfterDelete = GroupDeletionRefusedException(GROUP, "reply lost")
    withExecutor(engine) {
      val paused = with(engine) { startAndAwait("tok-lost") }

      assertEquals(WorkflowRunStatus.PAUSED, paused.status)
      assertNull(engine.directory.find(GROUP), "the delete did land")

      val rolled = with(engine) { operateAndAwait(paused.id, WorkflowOperatorAction.ROLLBACK) }

      assertEquals(WorkflowRunStatus.ROLLED_BACK, rolled.status)
      val recreated = assertNotNull(engine.directory.find(GROUP))
      assertEquals("CN=$GROUP,$MAIN_OU", recreated.dn)
      assertEquals("A fake group", recreated.description)
      assertEquals("CN=Fake Owner,OU=Users,DC=example,DC=invalid", recreated.managedBy)
      assertEquals(setOf("asmith", "bsmith", "csmith"), recreated.members)
      assertNotEquals("11111111-2222-3333-4444-555555555555", recreated.guid)
      assertEquals(listOf("CN=Parent,$MAIN_OU"), recreated.parents)
      assertEquals(GROUP to recreated.guid, engine.archive.guidUpdates.single())
      assertEquals("ACTIVE", engine.archive.statusOf(GROUP))
      assertTrue(engine.archive.finalized.isEmpty())
      assertEquals(WorkflowStepStatus.ROLLED_BACK, rolled.steps[3].status)
      assertEquals(WorkflowStepStatus.ROLLED_BACK, rolled.steps[2].status)
    }
  }

  @Test
  fun `a failure while recording the archive can be rolled back through the recreated group`() {
    val engine = ArchiveEngine()
    engine.archive.finalizeSucceeds = false
    withExecutor(engine) {
      val paused = with(engine) { startAndAwait("tok-final") }

      assertEquals(WorkflowRunStatus.PAUSED, paused.status)
      assertEquals(WorkflowStepStatus.FAILED, paused.steps[4].status)
      assertNull(engine.directory.find(GROUP))

      val rolled = with(engine) { operateAndAwait(paused.id, WorkflowOperatorAction.ROLLBACK) }

      assertEquals(WorkflowRunStatus.ROLLED_BACK, rolled.status)
      assertEquals(setOf("asmith", "bsmith", "csmith"), engine.directory.find(GROUP)!!.members)
      assertEquals("ACTIVE", engine.archive.statusOf(GROUP))
    }
  }
}
