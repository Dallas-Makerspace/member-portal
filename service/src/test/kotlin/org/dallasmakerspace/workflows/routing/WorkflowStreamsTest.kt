package org.dallasmakerspace.workflows.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.models.workflows.*
import org.dallasmakerspace.workflows.*
import org.junit.Test

class WorkflowStreamsTest {

  private val service = FakeWorkflowService()
  private val bus = InProcessWorkflowEventBus()
  private val streamsClosed = Job()
  private val json = Json { ignoreUnknownKeys = true }

  private data class Frame(
      val event: String?,
      val id: String?,
      val data: String?,
      val comment: Boolean,
  )

  private suspend fun ByteReadChannel.readFrame(): Frame? = withTimeout(5_000) {
    var event: String? = null
    var id: String? = null
    var data: String? = null
    var comment = false
    var any = false
    while (true) {
      val line = readLine() ?: return@withTimeout null
      if (line.isEmpty()) {
        if (any) return@withTimeout Frame(event, id, data, comment)
        continue
      }
      any = true
      when {
        line.startsWith(":") -> comment = true
        line.startsWith("event: ") -> event = line.removePrefix("event: ")
        line.startsWith("id: ") -> id = line.removePrefix("id: ")
        line.startsWith("data: ") -> data = line.removePrefix("data: ")
      }
    }
    @Suppress("UNREACHABLE_CODE") null
  }

  private fun Frame.message() =
      json.decodeFromString(WorkflowStreamMessage.serializer(), assertNotNull(data))

  private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application {
      install(Authentication) {
        bearer("test") {
          authenticate {
            ApiKeyAuthProvider.ApiKeyPrincipal("k", "ui", setOf(Permission.RUN_WORKFLOWS))
          }
        }
      }
      routing { authenticate("test") { workflowRoutes(service, bus, streamsClosed, 100) } }
    }
    block()
  }

  private suspend fun ApplicationTestBuilder.stream(
      path: String,
      lastEventId: String? = null,
      block: suspend (HttpResponse, ByteReadChannel) -> Unit,
  ) {
    client
        .prepareGet(path) {
          header(HttpHeaders.Authorization, "Bearer ui")
          lastEventId?.let { header("Last-Event-ID", it) }
        }
        .execute { response -> block(response, response.bodyAsChannel()) }
  }

  @Test
  fun `run stream sends snapshot with steps then replays events after Last-Event-ID`() = withApp {
    service.runs[4] = testRun(4, steps = listOf(testStep(0, WorkflowStepStatus.SUCCEEDED)))
    service.events += listOf(testEvent(1, 4), testEvent(2, 4), testEvent(3, 4))
    stream("/workflows/runs/4/stream", lastEventId = "1") { response, ch ->
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
      assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
      assertEquals("no", response.headers["X-Accel-Buffering"])

      val snapshot = assertNotNull(ch.readFrame())
      assertEquals("snapshot", snapshot.event)
      assertNull(snapshot.id)
      assertEquals(1, snapshot.message().run?.steps?.size)

      val e2 = assertNotNull(ch.readFrame())
      assertEquals(listOf("event", "2"), listOf(e2.event, e2.id))
      assertEquals(2, e2.message().event?.id)
      val e3 = assertNotNull(ch.readFrame())
      assertEquals(listOf("event", "3"), listOf(e3.event, e3.id))
    }
  }

  @Test
  fun `run stream without Last-Event-ID replays every event, after query overrides`() = withApp {
    service.runs[4] = testRun(4)
    service.events += listOf(testEvent(1, 4), testEvent(2, 4))
    stream("/workflows/runs/4/stream") { _, ch ->
      assertEquals("snapshot", ch.readFrame()?.event)
      assertEquals("1", ch.readFrame()?.id)
      assertEquals("2", ch.readFrame()?.id)
    }
    stream("/workflows/runs/4/stream?after=1") { _, ch ->
      assertEquals("snapshot", ch.readFrame()?.event)
      assertEquals("2", ch.readFrame()?.id)
    }
  }

  @Test
  fun `run stream pushes live run and event messages for its run only`() = withApp {
    service.runs[4] = testRun(4)
    service.events += testEvent(1, 4)
    stream("/workflows/runs/4/stream", lastEventId = "1") { _, ch ->
      assertEquals("snapshot", ch.readFrame()?.event)

      bus.publishRun(testRun(5))
      bus.publishEvent(testEvent(9, 5))
      bus.publishEvent(testEvent(1, 4))
      bus.publishEvent(testEvent(2, 4))
      val event = assertNotNull(ch.readFrame())
      assertEquals(listOf("event", "2"), listOf(event.event, event.id))

      service.runs[4] =
          testRun(
              4, WorkflowRunStatus.PAUSED, steps = listOf(testStep(0, WorkflowStepStatus.FAILED)))
      bus.publishRun(testRun(4, WorkflowRunStatus.PAUSED))
      val run = assertNotNull(ch.readFrame())
      assertEquals("run", run.event)
      assertNull(run.id)
      val message = run.message()
      assertEquals(WorkflowRunStatus.PAUSED, message.run?.status)
      assertEquals(WorkflowStepStatus.FAILED, message.run?.steps?.single()?.status)
    }
  }

  @Test
  fun `run stream subscribes before reading the snapshot so nothing is lost`() = withApp {
    service.runs[4] = testRun(4)
    var reads = 0
    service.duringSnapshot = {
      // 1st read is the existence check, 2nd is the snapshot
      if (++reads == 2) bus.publishEvent(testEvent(5, 4))
    }
    stream("/workflows/runs/4/stream") { _, ch ->
      assertEquals("snapshot", ch.readFrame()?.event)
      val event = assertNotNull(ch.readFrame())
      assertEquals(listOf("event", "5"), listOf(event.event, event.id))
    }
  }

  @Test
  fun `run stream sends heartbeat comments when idle`() = withApp {
    service.runs[4] = testRun(4)
    stream("/workflows/runs/4/stream") { _, ch ->
      assertEquals("snapshot", ch.readFrame()?.event)
      assertTrue(assertNotNull(ch.readFrame()).comment)
      assertTrue(assertNotNull(ch.readFrame()).comment)
    }
  }

  @Test
  fun `heartbeats never query the service`() = withApp {
    service.runs[4] = testRun(4)
    stream("/workflows/admin/stream") { _, ch ->
      assertEquals("snapshot", ch.readFrame()?.event)
      val reads = service.adminCalls
      repeat(3) { assertTrue(assertNotNull(ch.readFrame()).comment) }
      assertEquals(reads, service.adminCalls)
    }
    stream("/workflows/runs/stream?workflow=test.flow") { _, ch ->
      assertEquals("snapshot", ch.readFrame()?.event)
      service.lastListFilter = null
      repeat(3) { assertTrue(assertNotNull(ch.readFrame()).comment) }
      assertNull(service.lastListFilter)
    }
    var runReads = 0
    stream("/workflows/runs/4/stream") { _, ch ->
      assertEquals("snapshot", ch.readFrame()?.event)
      service.duringSnapshot = { runReads++ }
      repeat(3) { assertTrue(assertNotNull(ch.readFrame()).comment) }
      assertEquals(0, runReads)
    }
  }

  @Test
  fun `run stream for an unknown run is a json 404`() = withApp {
    stream("/workflows/runs/99/stream") { response, _ ->
      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
    }
  }

  @Test
  fun `streams end cleanly when the app is shutting down`() = withApp {
    service.runs[4] = testRun(4)
    service.admin = WorkflowAdminInProgressDto(true, emptyList(), "2026-01-02T03:04:05Z")
    stream("/workflows/runs/4/stream") { _, ch ->
      assertEquals("snapshot", ch.readFrame()?.event)
      streamsClosed.cancel()
      assertNull(ch.readFrame())
    }
    stream("/workflows/admin/stream") { _, ch ->
      assertEquals("snapshot", ch.readFrame()?.event)
      assertNull(ch.readFrame())
    }
  }

  @Test
  fun `run list stream sends a snapshot then only matching run changes`() = withApp {
    service.runs[1] = testRun(1)
    stream("/workflows/runs/stream?subject=group:Test%20Group&workflow=test.flow") { response, ch ->
      assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
      val snapshot = assertNotNull(ch.readFrame())
      assertEquals("snapshot", snapshot.event)
      assertEquals(listOf(1L), snapshot.message().runs?.map { it.id })
      val filter = assertNotNull(service.lastListFilter)
      assertEquals(WorkflowSubject("group", "Test Group"), filter.subject)
      assertEquals("test.flow", filter.workflowId)
      assertEquals(50, filter.limit)

      bus.publishRun(testRun(2, subjects = listOf(WorkflowSubject("group", "Other"))))
      bus.publishEvent(testEvent(1, 3))
      bus.publishRun(testRun(3, WorkflowRunStatus.SUCCEEDED))
      val run = assertNotNull(ch.readFrame())
      assertEquals("run", run.event)
      assertEquals(3, run.message().run?.id)
    }
  }

  @Test
  fun `run list stream rejects a malformed subject`() = withApp {
    val response =
        client.get("/workflows/runs/stream?subject=nocolon") {
          header(HttpHeaders.Authorization, "Bearer ui")
        }
    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertTrue(response.bodyAsText().contains("subject"))
  }

  @Test
  fun `admin stream sends a snapshot then a coalesced admin message per run change`() = withApp {
    service.admin = WorkflowAdminInProgressDto(true, listOf(testRun(1)), "t0")
    stream("/workflows/admin/stream") { _, ch ->
      val snapshot = assertNotNull(ch.readFrame())
      assertEquals("snapshot", snapshot.event)
      assertEquals("t0", snapshot.message().admin?.generatedAt)
      assertEquals(1, snapshot.message().runs?.size)

      service.admin = WorkflowAdminInProgressDto(false, listOf(testRun(2)), "t1")
      bus.publishEvent(testEvent(1, 2))
      bus.publishRun(testRun(2))
      bus.publishRun(testRun(2))
      val admin = assertNotNull(ch.readFrame())
      assertEquals("admin", admin.event)
      assertEquals(false, admin.message().admin?.safeToDeploy)
    }
  }
}
