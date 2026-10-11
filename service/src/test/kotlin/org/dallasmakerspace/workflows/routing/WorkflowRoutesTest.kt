package org.dallasmakerspace.workflows.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.models.workflows.*
import org.dallasmakerspace.workflows.*
import org.junit.Test

class WorkflowRoutesTest {

  private val service = FakeWorkflowService()
  private val bus = InProcessWorkflowEventBus()
  private val json = Json { ignoreUnknownKeys = true }

  private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
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
      routing { authenticate("test") { workflowRoutes(service, bus, Job(), 100) } }
    }
    block()
  }

  private suspend fun ApplicationTestBuilder.get(
      path: String,
      token: String? = "ui",
  ): HttpResponse =
      client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

  private suspend fun ApplicationTestBuilder.post(
      path: String,
      body: String = "{}",
      actor: String? = "jdoe",
      token: String? = "ui",
  ): HttpResponse =
      client.post(path) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        actor?.let { header(ACTING_USER_HEADER, it) }
        contentType(ContentType.Application.Json)
        setBody(body)
      }

  private val startBody = """{"input":{"groupName":"Test Group"},"idempotencyToken":"tok-1"}"""

  @Test
  fun `requires authentication and RUN_WORKFLOWS`() = withApp {
    assertEquals(HttpStatusCode.Unauthorized, get("/workflows/definitions", token = null).status)
    assertEquals(HttpStatusCode.Forbidden, get("/workflows/definitions", token = "other").status)
    assertEquals(HttpStatusCode.Forbidden, get("/workflows/runs", token = "other").status)
    assertEquals(HttpStatusCode.Forbidden, get("/workflows/admin/in-progress", token = "other").status)
    assertEquals(HttpStatusCode.Forbidden, get("/workflows/runs/stream", token = "other").status)
    assertEquals(HttpStatusCode.Forbidden, get("/workflows/admin/stream", token = "other").status)
    assertEquals(HttpStatusCode.Forbidden, post("/workflows/test.flow/runs", startBody, token = "other").status)
    assertEquals(HttpStatusCode.Forbidden, post("/workflows/runs/1/retry", token = "other").status)
    assertEquals(HttpStatusCode.OK, get("/workflows/definitions").status)
  }

  @Test
  fun `only the UI role among the service roles holds RUN_WORKFLOWS`() {
    val holders =
        org.dallasmakerspace.auth.Role.entries
            .filter { Permission.RUN_WORKFLOWS in it.permissions }
            .map { it.name }
    assertEquals(listOf("UI_SUPERUSER", "INFRA"), holders)
  }

  @Test
  fun `definitions are returned as json`() = withApp {
    service.definitions =
        listOf(
            WorkflowDefinitionDto(
                "test.flow", 1, "Test flow", "Does things", "MANAGE_GROUPS", emptyList(), emptyList()))
    val response = get("/workflows/definitions")
    assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
    val parsed =
        json.decodeFromString(ListSerializer(WorkflowDefinitionDto.serializer()), response.bodyAsText())
    assertEquals("MANAGE_GROUPS", parsed.single().requiredPermission)
  }

  @Test
  fun `preflight returns the plan and requires the acting user`() = withApp {
    service.preflightResult = { emptyPlan(canStart = false) }
    val body = """{"input":{"groupName":"Test Group"}}"""
    assertEquals(HttpStatusCode.BadRequest, post("/workflows/test.flow/preflight", body, actor = null).status)
    val ok = post("/workflows/test.flow/preflight", body)
    assertEquals(HttpStatusCode.OK, ok.status)
    assertFalse(
        json.decodeFromString(WorkflowPreflightResponse.serializer(), ok.bodyAsText()).canStart)
    assertEquals(HttpStatusCode.BadRequest, post("/workflows/test.flow/preflight", "not json").status)
  }

  @Test
  fun `preflight maps unknown workflow to 404 and invalid input to 400`() = withApp {
    service.preflightResult = { throw UnknownWorkflowException("nope") }
    assertEquals(HttpStatusCode.NotFound, post("/workflows/nope/preflight", """{"input":{}}""").status)
    service.preflightResult = { throw InvalidWorkflowInputException("groupName is required") }
    val response = post("/workflows/test.flow/preflight", """{"input":{}}""")
    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(
        "groupName is required",
        json.decodeFromString(WorkflowErrorResponse.serializer(), response.bodyAsText()).message)
  }

  @Test
  fun `start returns 201 for a new run and passes actor and token to the service`() = withApp {
    service.startResult = { StartResult.Started(testRun(7)) }
    val response = post("/workflows/test.flow/runs", startBody)
    assertEquals(HttpStatusCode.Created, response.status)
    val parsed = json.decodeFromString(WorkflowStartResponse.serializer(), response.bodyAsText())
    assertEquals(7, parsed.run.id)
    assertFalse(parsed.existing)
    val call = service.startCalls.single()
    assertEquals(listOf("test.flow", "jdoe", "UI", "tok-1"), listOf(call[0], call[2], call[3], call[4]))
    assertEquals("Test Group", (call[1] as kotlinx.serialization.json.JsonObject)["groupName"]?.jsonPrimitive?.content)
  }

  @Test
  fun `start with a repeated token returns 200 and existing true`() = withApp {
    service.startResult = { StartResult.Existing(testRun(7)) }
    val response = post("/workflows/test.flow/runs", startBody)
    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(json.decodeFromString(WorkflowStartResponse.serializer(), response.bodyAsText()).existing)
  }

  @Test
  fun `start on a locked subject returns 409 with the active run`() = withApp {
    service.startResult = { StartResult.SubjectLocked(testRun(3, WorkflowRunStatus.PAUSED)) }
    val response = post("/workflows/test.flow/runs", startBody)
    assertEquals(HttpStatusCode.Conflict, response.status)
    val parsed = json.decodeFromString(WorkflowErrorResponse.serializer(), response.bodyAsText())
    assertEquals(3, parsed.activeRun?.id)
    assertTrue(parsed.message.isNotBlank())
  }

  @Test
  fun `start that is blocked returns 422 with the plan`() = withApp {
    service.startResult = { StartResult.Blocked(emptyPlan(canStart = false)) }
    val response = post("/workflows/test.flow/runs", startBody)
    assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    val plan = json.decodeFromString(WorkflowPreflightResponse.serializer(), response.bodyAsText())
    assertFalse(plan.canStart)
  }

  @Test
  fun `start validates workflow, input, token and acting user`() = withApp {
    service.startResult = { throw UnknownWorkflowException("nope") }
    assertEquals(HttpStatusCode.NotFound, post("/workflows/nope/runs", startBody).status)
    service.startResult = { throw InvalidWorkflowInputException("bad") }
    assertEquals(HttpStatusCode.BadRequest, post("/workflows/test.flow/runs", startBody).status)
    assertEquals(
        HttpStatusCode.BadRequest,
        post("/workflows/test.flow/runs", """{"input":{},"idempotencyToken":"  "}""").status)
    assertEquals(HttpStatusCode.BadRequest, post("/workflows/test.flow/runs", startBody, actor = "").status)
    assertEquals(HttpStatusCode.BadRequest, post("/workflows/test.flow/runs", """{"input":{}}""").status)
  }

  @Test
  fun `run list parses filters`() = withApp {
    service.runs[1] = testRun(1)
    val response =
        get(
            "/workflows/runs?workflow=group.archive&subject=group:Test%20Group:x" +
                "&status=paused,RUNNING&actor=jdoe&active=true&limit=9999")
    assertEquals(HttpStatusCode.OK, response.status)
    val filter = assertNotNull(service.lastListFilter)
    assertEquals("group.archive", filter.workflowId)
    assertEquals(WorkflowSubject("group", "Test Group:x"), filter.subject)
    assertEquals(setOf(WorkflowRunStatus.PAUSED, WorkflowRunStatus.RUNNING), filter.statuses)
    assertEquals("jdoe", filter.actor)
    assertTrue(filter.activeOnly)
    assertEquals(500, filter.limit)
    assertEquals(
        1, json.decodeFromString(ListSerializer(WorkflowRunDto.serializer()), response.bodyAsText()).size)
  }

  @Test
  fun `run list defaults and bad parameters`() = withApp {
    get("/workflows/runs")
    val filter = assertNotNull(service.lastListFilter)
    assertNull(filter.workflowId)
    assertNull(filter.subject)
    assertNull(filter.statuses)
    assertFalse(filter.activeOnly)
    assertEquals(100, filter.limit)
    assertEquals(HttpStatusCode.BadRequest, get("/workflows/runs?status=bogus").status)
    assertEquals(HttpStatusCode.BadRequest, get("/workflows/runs?subject=nocolon").status)
    assertEquals(HttpStatusCode.BadRequest, get("/workflows/runs?limit=abc").status)
  }

  @Test
  fun `run detail includes steps and 404s for unknown runs`() = withApp {
    service.runs[4] = testRun(4, steps = listOf(testStep(0, WorkflowStepStatus.SUCCEEDED)))
    val response = get("/workflows/runs/4")
    assertEquals(HttpStatusCode.OK, response.status)
    val run = json.decodeFromString(WorkflowRunDto.serializer(), response.bodyAsText())
    assertEquals(1, run.steps.size)
    assertEquals(HttpStatusCode.NotFound, get("/workflows/runs/99").status)
    assertEquals(HttpStatusCode.BadRequest, get("/workflows/runs/abc").status)
  }

  @Test
  fun `events honour the after parameter`() = withApp {
    service.runs[4] = testRun(4)
    service.events += listOf(testEvent(1, 4), testEvent(2, 4), testEvent(3, 4), testEvent(4, 5))
    val all =
        json.decodeFromString(
            ListSerializer(WorkflowEventDto.serializer()), get("/workflows/runs/4/events").bodyAsText())
    assertEquals(listOf(1L, 2L, 3L), all.map { it.id })
    val after =
        json.decodeFromString(
            ListSerializer(WorkflowEventDto.serializer()),
            get("/workflows/runs/4/events?after=2").bodyAsText())
    assertEquals(listOf(3L), after.map { it.id })
    assertEquals(HttpStatusCode.NotFound, get("/workflows/runs/99/events").status)
    assertEquals(HttpStatusCode.BadRequest, get("/workflows/runs/4/events?after=x").status)
  }

  @Test
  fun `operator actions return the run, 404 and 409`() = withApp {
    service.runs[4] = testRun(4, WorkflowRunStatus.PAUSED)
    WorkflowOperatorAction.entries.forEach { action ->
      val response = post("/workflows/runs/4/${action.name.lowercase()}", "")
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(4, json.decodeFromString(WorkflowRunDto.serializer(), response.bodyAsText()).id)
    }
    assertEquals(
        WorkflowOperatorAction.entries.toList(), service.operateCalls.map { it.second })
    assertTrue(service.operateCalls.all { it.first == 4L && it.third == "jdoe" })

    assertEquals(HttpStatusCode.NotFound, post("/workflows/runs/99/retry", "").status)

    service.operateResult = { OperateResult.NotAllowed("Run is not paused") }
    val conflict = post("/workflows/runs/4/skip", "")
    assertEquals(HttpStatusCode.Conflict, conflict.status)
    assertEquals(
        "Run is not paused",
        json.decodeFromString(WorkflowErrorResponse.serializer(), conflict.bodyAsText()).message)

    assertEquals(HttpStatusCode.NotFound, post("/workflows/runs/4/explode", "").status)
    assertEquals(HttpStatusCode.BadRequest, post("/workflows/runs/4/retry", "", actor = null).status)
    assertEquals(HttpStatusCode.BadRequest, post("/workflows/runs/abc/retry", "").status)
  }

  @Test
  fun `admin in-progress returns the dto`() = withApp {
    service.admin = WorkflowAdminInProgressDto(false, listOf(testRun(2)), "2026-01-02T03:04:05Z")
    val parsed =
        json.decodeFromString(
            WorkflowAdminInProgressDto.serializer(), get("/workflows/admin/in-progress").bodyAsText())
    assertFalse(parsed.safeToDeploy)
    assertEquals(2, parsed.runs.single().id)
  }
}
