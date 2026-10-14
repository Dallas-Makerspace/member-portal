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
import org.mockito.Mockito.mockingDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private const val PRESIGNED =
    "https://objects.example.invalid/bucket/workflow-exports/x.json?X-Amz-Signature=sig-secret"

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

  /** Run with one SUCCEEDED step offering a public and an infra-only file. */
  private fun artifactRunJson(id: Long, workflowId: String) =
      runJson(id, workflowId, "SUCCEEDED").trimEnd().removeSuffix("}") +
          ""","steps":[{"index":0,"stepId":"group.export-members","displayName":"Export",
        "status":"SUCCEEDED","attempts":1,"reversibility":"REVERSIBLE","artifacts":[
          {"name":"member-list","label":"Member list (JSON)","contentType":"application/json","infraOnly":false},
          {"name":"archive-copy","label":"Archive copy","contentType":"application/json","infraOnly":true}]}]}"""

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
        path == "/workflows/runs/21" -> json(artifactRunJson(21, "group.archive"))
        path == "/workflows/runs/22" -> json(artifactRunJson(22, "open.thing"))
        path == "/workflows/runs/23" -> json(artifactRunJson(23, "group.archive"))
        path == "/workflows/runs/24" -> json(artifactRunJson(24, "group.archive"))
        path.startsWith("/workflows/runs/23/steps/") ->
            json("""{"message":"gone"}""", HttpStatusCode.Gone)
        path.startsWith("/workflows/runs/24/steps/") ->
            json("""{"message":"boom"}""", HttpStatusCode.InternalServerError)
        path.matches(Regex("/workflows/runs/2[12]/steps/0/artifacts/[a-z-]+/url")) ->
            json("""{"url":"$PRESIGNED","expiresInSeconds":300}""")
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

  private fun withApi(
      flagOn: Boolean = true,
      loggerFactory: LoggerFactory = LoggerFactory(),
      block: suspend ApplicationTestBuilder.() -> Unit,
  ) {
    val mockEngine = MockEngine { request -> fakeService(request) }
    val client =
        WorkflowServiceClient(
            baseUrl = "http://service.test",
            authHeaders = mapOf("X-Api-Key" to "k", "X-Api-Client" to "c"),
            http = HttpClient(mockEngine),
            streamHttp = HttpClient(mockEngine),
            loggerFactory = loggerFactory,
        )
    // The flag is cached for 60s per (flag, username), so each test uses its own usernames via a prefix.
    val handler =
        WorkflowApiHandler(
            loggerFactory, userInfoProvider(), memberServiceClient(flagOn), client)
    val pages =
        WorkflowPagesHandler(
            loggerFactory, userInfoProvider(), memberServiceClient(flagOn), client)
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

  private suspend fun ApplicationTestBuilder.downloadAs(user: String, path: String): HttpResponse =
      createClient { followRedirects = false }.get(path) { header("X-Test-User", user) }

  private fun artifactPath(run: Int, name: String, step: Int = 0) =
      "/api/workflows/runs/$run/steps/$step/artifacts/$name/download"

  private fun urlCalls() =
      serviceCalls("/workflows/runs").filter { it.url.encodedPath.endsWith("/url") }

  @Test
  fun `a permitted download redirects to a fresh presigned url without caching`() {
    withApi {
      val resp = downloadAs("olive", artifactPath(21, "member-list"))

      assertEquals(HttpStatusCode.Found, resp.status)
      assertEquals(PRESIGNED, resp.headers[HttpHeaders.Location])
      assertEquals("no-store", resp.headers[HttpHeaders.CacheControl])
      assertFalse(PRESIGNED in resp.bodyAsText())
      val call = urlCalls().single()
      assertEquals("/workflows/runs/21/steps/0/artifacts/member-list/url", call.url.encodedPath)
      assertEquals("olive", call.headers[ACTING_USER_HEADER])
    }
  }

  @Test
  fun `downloads need the workflow permission`() {
    withApi {
      assertEquals(HttpStatusCode.Forbidden, downloadAs("mia", artifactPath(21, "member-list")).status)
      assertEquals(HttpStatusCode.Forbidden, downloadAs("bea", artifactPath(21, "member-list")).status)
      assertTrue(urlCalls().isEmpty(), "the service must not be asked for a link")
    }
  }

  @Test
  fun `infra-only files need DOWNLOAD_ARCHIVES, not just MANAGE_CONFIG`() {
    withApi {
      // officer: has the workflow permission but not the archive permission
      assertEquals(HttpStatusCode.Forbidden, downloadAs("olive", artifactPath(21, "archive-copy")).status)
      // Board: MANAGE_CONFIG only; the open workflow removes the workflow-permission reason
      assertEquals(HttpStatusCode.Forbidden, downloadAs("bea", artifactPath(22, "archive-copy")).status)
      assertTrue(urlCalls().isEmpty())
      assertEquals(HttpStatusCode.Found, downloadAs("bea", artifactPath(22, "member-list")).status)
      assertEquals(HttpStatusCode.Found, downloadAs("ivy", artifactPath(21, "archive-copy")).status)
      assertEquals(HttpStatusCode.Found, downloadAs("ivy", artifactPath(22, "archive-copy")).status)
    }
  }

  @Test
  fun `unknown runs steps and artifacts are 404 and never ask the service for a link`() {
    withApi {
      assertEquals(HttpStatusCode.NotFound, downloadAs("ivy", artifactPath(404, "member-list")).status)
      assertEquals(HttpStatusCode.NotFound, downloadAs("ivy", artifactPath(21, "other")).status)
      assertEquals(
          HttpStatusCode.NotFound, downloadAs("ivy", artifactPath(21, "member-list", step = 5)).status)
      assertEquals(HttpStatusCode.NotFound, downloadAs("ivy", artifactPath(21, "Bad_Name")).status)
      assertEquals(
          HttpStatusCode.NotFound, downloadAs("ivy", artifactPath(21, "member-list", step = -1)).status)
      assertTrue(urlCalls().isEmpty())
    }
  }

  @Test
  fun `a gone file is 410 and a service failure is 502, with no redirect`() {
    withApi {
      val gone = downloadAs("olive", artifactPath(23, "member-list"))
      assertEquals(HttpStatusCode.Gone, gone.status)
      assertNull(gone.headers[HttpHeaders.Location])
      val failed = downloadAs("olive", artifactPath(24, "member-list"))
      assertEquals(HttpStatusCode.BadGateway, failed.status)
      assertNull(failed.headers[HttpHeaders.Location])
    }
  }

  @Test
  fun `downloads are a 404 while the flag is off`() {
    withApi(flagOn = false) {
      assertEquals(HttpStatusCode.NotFound, downloadAs("zed", artifactPath(21, "member-list")).status)
      assertTrue(seen.isEmpty())
    }
  }

  @Test
  fun `the presigned url is never logged`() {
    val logger = mock<io.ktor.util.logging.Logger>()
    val factory = mock<LoggerFactory> { on { create(any()) } doReturn logger }
    withApi(loggerFactory = factory) {
      assertEquals(HttpStatusCode.Found, downloadAs("olive", artifactPath(21, "member-list")).status)
      assertEquals(HttpStatusCode.Forbidden, downloadAs("olive", artifactPath(21, "archive-copy")).status)
    }
    val logged =
        mockingDetails(logger).invocations.joinToString("\n") { inv ->
          java.util.Arrays.deepToString(inv.arguments)
        }
    assertFalse("sig-secret" in logged, logged)
    assertFalse(PRESIGNED in logged)
  }

  @Test
  fun `the run page tells the script which downloads to offer`() {
    withApi {
      val officer = getAs("olive", "/workflows/runs/21").bodyAsText()
      assertTrue("""data-can-download="true"""" in officer)
      assertTrue("""data-can-download-archives="false"""" in officer)
      val board = getAs("bea", "/workflows/runs/21").bodyAsText()
      assertTrue("""data-can-download="false"""" in board)
      assertTrue("""data-can-download-archives="false"""" in board)
      val boardOpen = getAs("bea", "/workflows/runs/22").bodyAsText()
      assertTrue("""data-can-download="true"""" in boardOpen)
      assertTrue("""data-can-download-archives="false"""" in boardOpen)
      val infra = getAs("ivy", "/workflows/runs/21").bodyAsText()
      assertTrue("""data-can-download="true"""" in infra)
      assertTrue("""data-can-download-archives="true"""" in infra)
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
