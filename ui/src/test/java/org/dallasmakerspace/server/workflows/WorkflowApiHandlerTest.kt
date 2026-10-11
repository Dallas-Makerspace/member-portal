package org.dallasmakerspace.server.workflows

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.application.install
import io.ktor.server.thymeleaf.Thymeleaf
import io.ktor.server.routing.*
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.plugins.UserSession
import org.dallasmakerspace.server.routes.IRouteHandler
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class WorkflowApiHandlerTest {
  private val definitionsJson =
      """[
        {"id":"group.archive","version":1,"displayName":"Archive group","description":"d",
         "requiredPermission":"MANAGE_GROUPS","inputFields":[],"steps":[]},
        {"id":"open.thing","version":1,"displayName":"Open thing","description":"d",
         "requiredPermission":null,"inputFields":[],"steps":[]}
      ]"""

  private fun runJson(id: Long, workflowId: String = "group.archive", status: String = "PAUSED") =
      """{"id":$id,"workflowId":"$workflowId","workflowVersion":1,"displayName":"Archive group",
        "status":"$status","input":{},"subjects":[{"type":"group","key":"example-group"}],
        "actor":"jdoe","triggeredBy":"UI","currentStepIndex":1,"stepCount":3,
        "createdAt":"2026-01-02T03:04:05Z"}"""

  private val sseBody = "id: 5\nevent: run\ndata: {\"type\":\"run\"}\n\n"

  /** Requests the fake service saw, in order. */
  private val seen = CopyOnWriteArrayList<HttpRequestData>()
  private var streamStatus = HttpStatusCode.OK
  private var adminRuns = "[]"
  private var adminSafe = true

  private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
      respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

  private fun MockRequestHandleScope.fakeService(request: HttpRequestData): HttpResponseData {
    seen += request
    val path = request.url.encodedPath
    run {
      return when {
        path == "/workflows/definitions" -> json(definitionsJson)
        path.endsWith("/stream") || path == "/workflows/runs/stream" ->
            if (streamStatus.value in 200..299) {
              respond(
                  ByteReadChannel(sseBody),
                  HttpStatusCode.OK,
                  headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
              json("""{"message":"nope"}""", streamStatus)
            }
        path == "/workflows/admin/in-progress" ->
            json(
                """{"safeToDeploy":$adminSafe,"runs":$adminRuns,"generatedAt":"2026-01-02T03:04:05Z"}""")
        path == "/workflows/runs/7" -> json(runJson(7))
        path == "/workflows/runs/8" -> json(runJson(8, "open.thing", "RUNNING"))
        path == "/workflows/runs/404" -> json("""{"message":"Run 404 not found"}""", HttpStatusCode.NotFound)
        path == "/workflows/runs/7/events" -> json("[]")
        path == "/workflows/runs/7/retry" -> json(runJson(7, status = "RUNNING"))
        path == "/workflows/runs" -> json("[${runJson(7)}]")
        path == "/workflows/group.archive/preflight" ->
            json("""{"workflowId":"group.archive","canStart":true,"steps":[],"notes":[]}""")
        path == "/workflows/group.archive/runs" ->
            json("""{"run":${runJson(9, status = "PENDING")},"existing":false}""", HttpStatusCode.Created)
        else -> json("""{"message":"unexpected $path"}""", HttpStatusCode.NotFound)
      }
    }
  }

  private val users =
      mapOf(
          "mia" to listOf<String>(), // plain member
          "olive" to listOf("/President"), // officer: MANAGE_GROUPS
          "bea" to listOf("/Board"), // admin: MANAGE_CONFIG only
          "ivy" to listOf("/Infrastructure"),
          "zed" to listOf("/Infrastructure")) // only used with the flag off (flag results are cached)

  private fun memberServiceClient(flagOn: Boolean): MemberServiceClient {
    val client = mock<MemberServiceClient>()
    runBlocking {
      whenever(client.isFeatureEnabled(eq(WORKFLOWS_FEATURE_FLAG), any(), any())).thenReturn(flagOn)
    }
    return client
  }

  private fun userInfoProvider(): UserInfoProvider {
    val provider = mock<UserInfoProvider>()
    runBlocking {
      users.forEach { (name, groups) ->
        whenever(provider.getUserInfo("tok-$name"))
            .thenReturn(mapOf("preferred_username" to name, "groups" to groups))
      }
    }
    return provider
  }

  private fun withApi(flagOn: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) {
    val mockEngine = MockEngine { request -> fakeService(request) }
    val client =
        WorkflowServiceClient(
            baseUrl = "http://service.test",
            authHeaders = mapOf("X-Api-Key" to "k", "X-Api-Client" to "c"),
            http = HttpClient(mockEngine),
            streamHttp = HttpClient(mockEngine),
            loggerFactory = LoggerFactory(),
        )
    // The flag is cached for 60s per (flag, username), so each test uses its own usernames via a prefix.
    val handler =
        WorkflowApiHandler(
            LoggerFactory(), userInfoProvider(), memberServiceClient(flagOn), client)
    val pages =
        WorkflowPagesHandler(
            LoggerFactory(), userInfoProvider(), memberServiceClient(flagOn), client)
    testApplication {
      application {
        install(Sessions) { cookie<UserSession>("user_session") }
        install(Thymeleaf) {
          setTemplateResolver(
              ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".html"
                characterEncoding = "utf-8"
              })
        }
        routing {
          get("/admin/workflows") { asUser(pages) }
          get("/workflows/demo") { asUser(pages) }
          get("/workflows/runs/{runId}") { asUser(pages) }
          route("/api/workflows/{path...}") {
            handle { asUser(handler) }
          }
        }
      }
      block()
    }
  }

  private suspend fun RoutingContext.asUser(handler: IRouteHandler) {
    val user = call.request.headers["X-Test-User"] ?: "mia"
    call.sessions.set(UserSession(accessToken = "tok-$user", sessionId = "sess", userId = user))
    handler.handleBase(call)
  }

  private suspend fun ApplicationTestBuilder.getAs(user: String, path: String): HttpResponse =
      client.get(path) { header("X-Test-User", user) }

  private suspend fun ApplicationTestBuilder.postAs(
      user: String,
      path: String,
      body: String = "{}",
      contentType: ContentType = ContentType.Application.Json,
  ): HttpResponse =
      client.post(path) {
        header("X-Test-User", user)
        header(HttpHeaders.ContentType, contentType.toString())
        setBody(body)
      }

  private fun serviceCalls(pathPrefix: String) =
      seen.filter { it.url.encodedPath.startsWith(pathPrefix) }

  @Test
  fun `everything is a 404 while the workflows flag is off`() {
    withApi(flagOn = false) {
      assertEquals(HttpStatusCode.NotFound, getAs("zed", "/api/workflows/definitions").status)
      assertEquals(HttpStatusCode.NotFound, getAs("zed", "/api/workflows/admin/stream").status)
      assertEquals(
          HttpStatusCode.NotFound,
          postAs("zed", "/api/workflows/group.archive/runs", """{"input":{},"idempotencyToken":"t"}""").status)
      assertTrue(seen.isEmpty(), "no call may reach the service while the flag is off")
    }
  }

  @Test
  fun `definitions are filtered to what the user may use`() {
    withApi {
      val member = getAs("mia", "/api/workflows/definitions").bodyAsText()
      assertTrue("open.thing" in member && "group.archive" !in member, member)
      val officer = getAs("olive", "/api/workflows/definitions").bodyAsText()
      assertTrue("open.thing" in officer && "group.archive" in officer, officer)
    }
  }

  @Test
  fun `preflight and start need the workflow permission and pass the acting user`() {
    withApi {
      val denied = postAs("mia", "/api/workflows/group.archive/preflight", """{"input":{}}""")
      assertEquals(HttpStatusCode.Forbidden, denied.status)
      assertTrue(serviceCalls("/workflows/group.archive").isEmpty())

      val ok = postAs("olive", "/api/workflows/group.archive/preflight", """{"input":{}}""")
      assertEquals(HttpStatusCode.OK, ok.status)
      assertTrue(ok.bodyAsText().contains("\"canStart\":true"))
      val call = serviceCalls("/workflows/group.archive/preflight").single()
      assertEquals("olive", call.headers[ACTING_USER_HEADER])
      assertEquals("k", call.headers["X-Api-Key"])

      val started =
          postAs(
              "olive",
              "/api/workflows/group.archive/runs",
              """{"input":{"groupName":"example-group"},"idempotencyToken":"tok-1"}""")
      assertEquals(HttpStatusCode.Created, started.status)
      assertEquals("olive", serviceCalls("/workflows/group.archive/runs").single().headers[ACTING_USER_HEADER])
    }
  }

  @Test
  fun `an admin without the workflow permission still cannot start it`() {
    withApi {
      val resp = postAs("bea", "/api/workflows/group.archive/preflight", """{"input":{}}""")
      assertEquals(HttpStatusCode.Forbidden, resp.status)
    }
  }

  @Test
  fun `unknown workflows are 404 and bad bodies are 400 or 415`() {
    withApi {
      assertEquals(
          HttpStatusCode.NotFound, postAs("ivy", "/api/workflows/nope/preflight", """{"input":{}}""").status)
      assertEquals(
          HttpStatusCode.BadRequest, postAs("ivy", "/api/workflows/group.archive/preflight", "not json").status)
      assertEquals(
          HttpStatusCode.BadRequest,
          postAs("ivy", "/api/workflows/group.archive/runs", """{"input":{}}""").status)
      assertEquals(
          HttpStatusCode.UnsupportedMediaType,
          postAs(
                  "ivy",
                  "/api/workflows/group.archive/preflight",
                  """{"input":{}}""",
                  ContentType.Application.FormUrlEncoded)
              .status)
    }
  }

  @Test
  fun `run detail is limited to operators and admins`() {
    withApi {
      assertEquals(HttpStatusCode.Forbidden, getAs("mia", "/api/workflows/runs/7").status)
      assertEquals(HttpStatusCode.OK, getAs("olive", "/api/workflows/runs/7").status)
      assertEquals(HttpStatusCode.OK, getAs("bea", "/api/workflows/runs/7").status)
      assertEquals(HttpStatusCode.NotFound, getAs("ivy", "/api/workflows/runs/404").status)
      // A run of a permission-free workflow is visible to any member.
      assertEquals(HttpStatusCode.OK, getAs("mia", "/api/workflows/runs/8").status)
    }
  }

  @Test
  fun `operator actions need the workflow permission, admins alone can only look`() {
    withApi {
      assertEquals(HttpStatusCode.Forbidden, postAs("mia", "/api/workflows/runs/7/retry").status)
      assertEquals(HttpStatusCode.Forbidden, postAs("bea", "/api/workflows/runs/7/retry").status)
      assertTrue(serviceCalls("/workflows/runs/7/retry").isEmpty())

      assertEquals(HttpStatusCode.OK, postAs("olive", "/api/workflows/runs/7/retry").status)
      assertEquals("olive", serviceCalls("/workflows/runs/7/retry").single().headers[ACTING_USER_HEADER])
      assertEquals(HttpStatusCode.NotFound, postAs("olive", "/api/workflows/runs/7/explode").status)
    }
  }

  @Test
  fun `listing runs needs a permitted workflow filter unless admin`() {
    withApi {
      assertEquals(HttpStatusCode.Forbidden, getAs("mia", "/api/workflows/runs").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("mia", "/api/workflows/runs?workflow=group.archive").status)
      assertEquals(HttpStatusCode.OK, getAs("olive", "/api/workflows/runs?workflow=group.archive&subject=group:example-group").status)
      assertEquals(HttpStatusCode.BadRequest, getAs("olive", "/api/workflows/runs?workflow=group.archive&subject=bad").status)
      assertEquals(HttpStatusCode.OK, getAs("bea", "/api/workflows/runs").status)
      val call = serviceCalls("/workflows/runs").first { it.url.parameters["subject"] != null }
      assertEquals("group:example-group", call.url.parameters["subject"])
    }
  }

  @Test
  fun `admin routes are admin only and the summary counts attention runs`() {
    adminRuns = "[${runJson(1, status = "PAUSED")},${runJson(2, status = "RUNNING")}]"
    adminSafe = false
    withApi {
      assertEquals(HttpStatusCode.Forbidden, getAs("olive", "/api/workflows/admin/in-progress").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("olive", "/api/workflows/admin/summary").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("olive", "/api/workflows/admin/stream").status)
      val summary = getAs("bea", "/api/workflows/admin/summary").bodyAsText()
      assertTrue(summary.contains("\"needsAttention\":1") && summary.contains("\"inProgress\":2"), summary)
      assertEquals(HttpStatusCode.OK, getAs("bea", "/api/workflows/admin/in-progress").status)
    }
  }

  @Test
  fun `subject stream relays events, forwards Last-Event-ID and sets SSE headers`() {
    withApi {
      val resp =
          client.get("/api/workflows/stream?workflow=group.archive&subject=group:example-group") {
            header("X-Test-User", "olive")
            header("Last-Event-ID", "41")
          }
      assertEquals(HttpStatusCode.OK, resp.status)
      assertEquals(sseBody, resp.bodyAsText())
      assertTrue(resp.headers[HttpHeaders.ContentType]!!.startsWith("text/event-stream"))
      assertEquals("no-cache", resp.headers[HttpHeaders.CacheControl])
      assertEquals("no", resp.headers["X-Accel-Buffering"])
      val upstream = serviceCalls("/workflows/runs/stream").single()
      assertEquals("41", upstream.headers["Last-Event-ID"])
      assertEquals("group.archive", upstream.url.parameters["workflow"])
      assertEquals("group:example-group", upstream.url.parameters["subject"])
      assertEquals("olive", upstream.headers[ACTING_USER_HEADER])
    }
  }

  @Test
  fun `Last-Event-ID can come from the query string and must be numeric`() {
    withApi {
      getAs("olive", "/api/workflows/stream?workflow=group.archive&lastEventId=17").bodyAsText()
      assertEquals("17", serviceCalls("/workflows/runs/stream").last().headers["Last-Event-ID"])

      getAs("olive", "/api/workflows/stream?workflow=group.archive&lastEventId=abc").bodyAsText()
      assertNull(serviceCalls("/workflows/runs/stream").last().headers["Last-Event-ID"])
    }
  }

  @Test
  fun `streams enforce the same permissions before anything is relayed`() {
    withApi {
      assertEquals(HttpStatusCode.Forbidden, getAs("mia", "/api/workflows/stream?workflow=group.archive").status)
      assertEquals(HttpStatusCode.BadRequest, getAs("olive", "/api/workflows/stream").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("mia", "/api/workflows/runs/7/stream").status)
      assertEquals(HttpStatusCode.NotFound, getAs("olive", "/api/workflows/runs/404/stream").status)
      assertTrue(serviceCalls("/workflows/runs/stream").isEmpty())
      assertTrue(serviceCalls("/workflows/runs/7/stream").isEmpty())

      val run = getAs("olive", "/api/workflows/runs/7/stream")
      assertEquals(HttpStatusCode.OK, run.status)
      assertEquals(sseBody, run.bodyAsText())

      val admin = getAs("bea", "/api/workflows/admin/stream")
      assertEquals(sseBody, admin.bodyAsText())
      assertFalse(serviceCalls("/workflows/admin/stream").isEmpty())
    }
  }

  @Test
  fun `a refused upstream stream becomes a stream-error event`() {
    streamStatus = HttpStatusCode.NotFound
    withApi {
      val resp = getAs("olive", "/api/workflows/stream?workflow=group.archive")
      assertEquals(HttpStatusCode.OK, resp.status)
      val text = resp.bodyAsText()
      assertTrue(text.startsWith("event: ${SseRelay.ERROR_EVENT}\n") && text.contains("\"status\":404"), text)
    }
  }

  @Test
  fun `pages are a 404 while the flag is off`() {
    withApi(flagOn = false) {
      assertEquals(HttpStatusCode.NotFound, getAs("zed", "/admin/workflows").status)
      assertEquals(HttpStatusCode.NotFound, getAs("zed", "/workflows/runs/7").status)
      assertEquals(HttpStatusCode.NotFound, getAs("zed", "/workflows/demo").status)
    }
  }

  @Test
  fun `the admin page is admin only`() {
    withApi {
      assertEquals(HttpStatusCode.Forbidden, getAs("mia", "/admin/workflows").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("olive", "/admin/workflows").status)
      val page = getAs("bea", "/admin/workflows")
      assertEquals(HttpStatusCode.OK, page.status)
      assertTrue("wf-admin-root" in page.bodyAsText())
    }
  }

  @Test
  fun `the run page checks view permission and tells the script whether to offer actions`() {
    withApi {
      assertEquals(HttpStatusCode.Forbidden, getAs("mia", "/workflows/runs/7").status)
      assertEquals(HttpStatusCode.NotFound, getAs("olive", "/workflows/runs/404").status)
      assertEquals(HttpStatusCode.NotFound, getAs("olive", "/workflows/runs/abc").status)
      val operator = getAs("olive", "/workflows/runs/7").bodyAsText()
      assertTrue("""data-can-operate="true"""" in operator)
      val admin = getAs("bea", "/workflows/runs/7").bodyAsText()
      assertTrue("""data-can-operate="false"""" in admin)
    }
  }

  @Test
  fun `the demo page only lists workflows the user may use`() {
    withApi {
      val page = getAs("mia", "/workflows/demo").bodyAsText()
      assertTrue("open.thing" in page)
      assertFalse("group.archive" in page)
    }
  }
}
