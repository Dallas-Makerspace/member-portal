package org.dallasmakerspace.workflows.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URL
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.workflows.WorkflowArtifactUrlResponse
import org.dallasmakerspace.models.workflows.WorkflowEventDto
import org.dallasmakerspace.models.workflows.WorkflowRunDto
import org.dallasmakerspace.models.workflows.WorkflowStepStatus
import org.dallasmakerspace.models.workflows.WorkflowStreamMessage
import org.dallasmakerspace.storage.IObjectStore
import org.dallasmakerspace.storage.InMemoryObjectStore
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreException
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace
import org.dallasmakerspace.storage.StoredObject
import org.dallasmakerspace.workflows.ArtifactLinks
import org.dallasmakerspace.workflows.ArtifactRef
import org.dallasmakerspace.workflows.CreateRunResult
import org.dallasmakerspace.workflows.DefaultWorkflowService
import org.dallasmakerspace.workflows.EngineSettings
import org.dallasmakerspace.workflows.InMemoryWorkflowStore
import org.dallasmakerspace.workflows.NewRun
import org.dallasmakerspace.workflows.ObjectStoreArtifactLinks
import org.dallasmakerspace.workflows.Reversibility
import org.dallasmakerspace.workflows.Step
import org.dallasmakerspace.workflows.StepCheck
import org.dallasmakerspace.workflows.StepContext
import org.dallasmakerspace.workflows.StreamFilter
import org.dallasmakerspace.workflows.Subject
import org.dallasmakerspace.workflows.Workflow
import org.dallasmakerspace.workflows.WorkflowEventBus
import org.dallasmakerspace.workflows.WorkflowRegistry
import org.dallasmakerspace.workflows.WorkflowStore
import org.dallasmakerspace.workflows.WorkflowWakeSignal
import org.junit.Test

/** The artifact URL route against the real service, store and mock object store. */
class WorkflowArtifactRoutesTest {
  private companion object {
    const val KEY = "group-exports/1/test-group.json"
    const val NAME = "member-list"
  }

  @kotlinx.serialization.Serializable data class In(val name: String)

  private class ReportStep(private val namespace: String, private val key: String) :
      Step<Unit, String> {
    override val id = "report"
    override val reversibility = Reversibility.REVERSIBLE
    override val outputSerializer = String.serializer()

    override suspend fun check(ctx: StepContext, args: Unit) = StepCheck.WillRun()

    override suspend fun run(ctx: StepContext, args: Unit) = key

    override suspend fun rollback(ctx: StepContext, args: Unit, output: String) = Unit

    override fun artifacts(args: Unit, output: String) =
        listOf(ArtifactRef(NAME, "Member list", "application/json", namespace, output))
  }

  private class ReportWorkflow(step: ReportStep) :
      Workflow<In>(
          id = "report.flow",
          version = 1,
          permission = "MANAGE_GROUPS",
          inputSerializer = In.serializer()) {
    private val s = step

    override fun subjects(input: In) = listOf(Subject("group", input.name))

    override fun steps() = listOf(step(s) { Unit })
  }

  private class RecordingBus : WorkflowEventBus {
    val events = CopyOnWriteArrayList<WorkflowEventDto>()

    override fun publishRun(run: WorkflowRunDto) = Unit

    override fun publishEvent(event: WorkflowEventDto) {
      events.add(event)
    }

    override fun subscribe(filter: StreamFilter): Flow<WorkflowStreamMessage> = emptyFlow()
  }

  /** Delegating store whose audit append can be made to fail. */
  private class FlakyAuditStore(private val d: WorkflowStore) : WorkflowStore by d {
    var failAudit = false

    override suspend fun appendEvent(
        runId: Long,
        stepIndex: Int?,
        level: String,
        message: String,
        actor: String?,
    ) = if (failAudit) error("audit store down") else d.appendEvent(runId, stepIndex, level, message, actor)
  }

  private val objects = InMemoryObjectStore()
  private val exports = ObjectStoreFactory(objects).forNamespace(StorageNamespace.WORKFLOW_EXPORTS)
  private val store = FlakyAuditStore(InMemoryWorkflowStore())
  private val bus = RecordingBus()
  private val json = Json { ignoreUnknownKeys = true }

  private fun service(namespace: String = StorageNamespace.WORKFLOW_EXPORTS.name) =
      DefaultWorkflowService(
          store,
          WorkflowRegistry(setOf(ReportWorkflow(ReportStep(namespace, KEY)))),
          bus,
          EngineSettings(),
          WorkflowWakeSignal(),
          LoggerFactory())

  /** A run whose only step SUCCEEDED with [KEY] as its persisted output. */
  private fun succeededRun(stepStatus: WorkflowStepStatus = WorkflowStepStatus.SUCCEEDED): Long =
      runBlocking {
        val created =
            store.createRun(
                NewRun(
                    "report.flow",
                    1,
                    """{"name":"Test Group"}""",
                    "jdoe",
                    "TEST",
                    listOf("report"),
                    listOf(Subject("group", "Test Group")),
                    "tok"))
        val run = (created as CreateRunResult.Created).run
        val claimed = checkNotNull(store.claimNext("test-instance", 30))
        store.markStepStarted(claimed.fence, 0)
        store.finishStep(
            claimed.fence, 0, stepStatus, outputJson = json.encodeToString(String.serializer(), KEY))
        run.id
      }

  private fun withApp(
      links: ArtifactLinks? = ObjectStoreArtifactLinks(ObjectStoreFactory(objects)),
      svc: DefaultWorkflowService = service(),
      block: suspend ApplicationTestBuilder.() -> Unit,
  ) = testApplication {
    application {
      install(Authentication) {
        bearer("test") {
          authenticate { credential ->
            when (credential.token) {
              "ui" -> ApiKeyAuthProvider.ApiKeyPrincipal("k", "ui", setOf(Permission.RUN_WORKFLOWS))
              "other" ->
                  ApiKeyAuthProvider.ApiKeyPrincipal(
                      "k", "other", Permission.entries.toSet() - Permission.RUN_WORKFLOWS)
              else -> null
            }
          }
        }
      }
      routing {
        authenticate("test") { workflowRoutes(svc, bus, Job(), 100, artifactLinks = links) }
      }
    }
    block()
  }

  private suspend fun ApplicationTestBuilder.url(
      runId: Long,
      index: String = "0",
      name: String = NAME,
      query: String = "",
      actor: String? = "asmith",
      token: String? = "ui",
  ): HttpResponse =
      client.get("/workflows/runs/$runId/steps/$index/artifacts/$name/url$query") {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        actor?.let { header(ACTING_USER_HEADER, it) }
      }

  private suspend fun auditEvents(runId: Long) =
      store.listEvents(runId).filter { "download requested" in it.message }

  private fun putExport() = runBlocking {
    exports.put(ObjectKey(KEY), "{}".toByteArray(), "application/json")
  }

  @Test
  fun `returns a short-lived url for the persisted key and audits the click`() = withApp {
    putExport()
    val runId = succeededRun()

    val response = url(runId)

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    val body = json.decodeFromString(WorkflowArtifactUrlResponse.serializer(), response.bodyAsText())
    assertEquals(300, body.expiresInSeconds)
    assertTrue(body.url.endsWith("/workflow-exports/$KEY"), body.url)
    val audit = auditEvents(runId).single()
    assertEquals(0, audit.stepIndex)
    assertEquals("asmith", audit.actor)
    assertEquals("INFO", audit.level)
    assertEquals("Artifact $NAME download requested by asmith", audit.message)
    assertEquals(listOf(audit.id), bus.events.map { it.id })
    assertFalse(audit.message.contains(KEY) || audit.message.contains("http"))
  }

  @Test
  fun `never takes the key or namespace from the client`() = withApp {
    putExport()
    exports.put(ObjectKey("other/evil.json"), "{}".toByteArray(), "application/json")
    val runId = succeededRun()

    val response =
        url(runId, query = "?key=other/evil.json&namespace=PROFILE_PHOTOS&url=http://evil.invalid")

    assertEquals(HttpStatusCode.OK, response.status)
    val body = json.decodeFromString(WorkflowArtifactUrlResponse.serializer(), response.bodyAsText())
    assertTrue(body.url.endsWith("/workflow-exports/$KEY"), body.url)
    assertFalse("evil" in body.url)
  }

  @Test
  fun `answers 404 for unknown run, step or artifact`() = withApp {
    putExport()
    val runId = succeededRun()

    assertEquals(HttpStatusCode.NotFound, url(runId + 50).status)
    assertEquals(HttpStatusCode.NotFound, url(runId, index = "3").status)
    assertEquals(HttpStatusCode.NotFound, url(runId, index = "-1").status)
    assertEquals(HttpStatusCode.NotFound, url(runId, name = "nope").status)
    assertEquals(HttpStatusCode.BadRequest, url(runId, index = "x").status)
    assertTrue(auditEvents(runId).isEmpty(), "failed lookups are not download audits")
    assertTrue(bus.events.isEmpty())
  }

  @Test
  fun `answers 404 for a step that did not succeed`() = withApp {
    putExport()
    val runId = succeededRun(WorkflowStepStatus.FAILED)

    assertEquals(HttpStatusCode.NotFound, url(runId).status)
  }

  @Test
  fun `answers 410 when the object is gone and writes no audit event`() = withApp {
    val runId = succeededRun()

    val response = url(runId)

    assertEquals(HttpStatusCode.Gone, response.status)
    assertFalse("http" in response.bodyAsText())
    assertTrue(auditEvents(runId).isEmpty())
    assertTrue(bus.events.isEmpty())
  }

  @Test
  fun `answers 404 for an artifact declared in an unknown namespace`() =
      withApp(svc = service(namespace = "NOT_A_NAMESPACE")) {
        putExport()
        val runId = succeededRun()

        assertEquals(HttpStatusCode.NotFound, url(runId).status)
        assertTrue(auditEvents(runId).isEmpty())
      }

  @Test
  fun `answers 502 when the object store cannot be reached`() {
    val broken =
        object : IObjectStore by objects {
          override suspend fun head(key: ObjectKey): StoredObject? = throw ObjectStoreException("down")
        }
    withApp(links = ObjectStoreArtifactLinks(ObjectStoreFactory(broken))) {
      val runId = succeededRun()

      assertEquals(HttpStatusCode.BadGateway, url(runId).status)
      assertTrue(auditEvents(runId).isEmpty())
    }
  }

  @Test
  fun `fails closed with no url when the audit event cannot be written`() = withApp {
    putExport()
    val runId = succeededRun()
    store.failAudit = true

    val response = url(runId)

    assertEquals(HttpStatusCode.InternalServerError, response.status)
    assertFalse("workflow-exports" in response.bodyAsText())
  }

  @Test
  fun `requires authentication, RUN_WORKFLOWS and the acting user header`() = withApp {
    putExport()
    val runId = succeededRun()

    assertEquals(HttpStatusCode.Unauthorized, url(runId, token = null).status)
    assertEquals(HttpStatusCode.Forbidden, url(runId, token = "other").status)
    assertEquals(HttpStatusCode.BadRequest, url(runId, actor = null).status)
    assertTrue(auditEvents(runId).isEmpty())
  }

  @Test
  fun `answers 503 when downloads are not configured`() = withApp(links = null) {
    putExport()
    val runId = succeededRun()

    assertEquals(HttpStatusCode.ServiceUnavailable, url(runId).status)
  }

  @Test
  fun `the link points inside the declared namespace prefix`() = runBlocking<Unit> {
    val links = ObjectStoreArtifactLinks(ObjectStoreFactory(objects))

    val signed: URL = links.presignedGet("PROFILE_PHOTOS", "a/b.jpg", Duration.ofSeconds(60))

    assertTrue(signed.toString().endsWith("/profile-photos/a/b.jpg"))
    assertNotNull(signed)
  }
}
