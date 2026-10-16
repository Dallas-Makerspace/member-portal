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
         "requiredPermission":null,"inputFields":[],"steps":[]},
        {"id":"scoped.thing","version":1,"displayName":"Scoped thing","description":"d",
         "requiredPermission":null,"authorization":"GROUP_ADMIN","confirmTypingField":"groupName",
         "inputFields":[
           {"name":"groupName","label":"Group name"},
           {"name":"reason","label":"Reason","multiline":true,"minLength":10,"maxLength":200,
            "hint":"Everyone can read this."}],
         "steps":[]},
        {"id":"odd.thing","version":1,"displayName":"Odd thing","description":"d",
         "requiredPermission":null,"authorization":"FUTURE_MODE","inputFields":[],"steps":[]}
      ]"""

  private fun runJson(
      id: Long,
      workflowId: String = "group.archive",
      status: String = "PAUSED",
      actor: String = "jdoe",
      input: String = "{}",
  ) =
      """{"id":$id,"workflowId":"$workflowId","workflowVersion":1,"displayName":"Archive group",
        "status":"$status","input":$input,"subjects":[{"type":"group","key":"example-group"}],
        "actor":"$actor","triggeredBy":"UI","currentStepIndex":1,"stepCount":3,
        "createdAt":"2026-01-02T03:04:05Z"}"""

  // Runs of the per-item authorized workflow: 31 by jdoe for Alpha Group, 32 by ann for Beta Group,
  // 33 by jdoe with no group in its input.
  private val scopedRuns =
      mapOf(
          31L to runJson(31, "scoped.thing", actor = "jdoe", input = """{"groupName":"Alpha Group"}"""),
          32L to runJson(32, "scoped.thing", actor = "ann", input = """{"groupName":"Beta Group"}"""),
          33L to runJson(33, "scoped.thing", actor = "jdoe"))

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
        path == "/workflows/runs/25" -> json(runJson(25, "scoped.thing", "PAUSED"))
        Regex("/workflows/runs/(3[123])").matchEntire(path) != null ->
            json(scopedRuns.getValue(path.substringAfterLast('/').toLong()))
        Regex("/workflows/runs/3[123]/events").matches(path) -> json("[]")
        path == "/workflows/runs/25/retry" -> json(runJson(25, "scoped.thing", "RUNNING"))
        path == "/workflows/scoped.thing/preflight" || path == "/workflows/scoped.thing/runs" ->
            when (request.headers[ACTING_USER_HEADER]) {
              "mia" -> json("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden)
              "bea" -> json("""{"message":"Group is locked by run 5"}""", HttpStatusCode.Conflict)
              else -> json("""{"workflowId":"scoped.thing","canStart":true,"steps":[],"notes":[]}""")
            }
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
        path == "/workflows/runs" ->
            if (request.url.parameters["workflow"] == "scoped.thing") {
              json("[${scopedRuns.getValue(31)},${scopedRuns.getValue(32)}]")
            } else {
              json("[${runJson(7)},${scopedRuns.getValue(31)},${scopedRuns.getValue(32)}]")
            }
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
          "zed" to listOf("/Infrastructure"), // only used with the flag off (flag results are cached)
          "gus" to listOf<String>(), // plain member who administers Alpha Group
          "ann" to listOf<String>(), // plain member, actor of run 32
          "jdoe" to listOf<String>(), // plain member, actor of runs 31 and 33
          "fay" to listOf("/President")) // officer with the archive flag off

  private fun memberServiceClient(flagOn: Boolean): MemberServiceClient {
    val client = mock<MemberServiceClient>()
    runBlocking {
      whenever(client.isFeatureEnabled(eq(WORKFLOWS_FEATURE_FLAG), any(), any())).thenReturn(flagOn)
      whenever(client.isFeatureEnabled(eq(ARCHIVE_FEATURE_FLAG), any(), any())).thenReturn(flagOn)
      whenever(client.isFeatureEnabled(eq(ARCHIVE_FEATURE_FLAG), eq("fay"), any())).thenReturn(false)
      whenever(client.isGroupAdmin(any(), any(), any())).thenReturn(false)
      whenever(client.isGroupAdmin(eq("gus"), eq("Alpha+Group"), any())).thenReturn(true)
      whenever(client.isGroupAdmin(eq("gus"), eq("alpha+group"), any())).thenReturn(true)
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
      assertTrue("scoped.thing" in member, member)
      val officer = getAs("olive", "/api/workflows/definitions").bodyAsText()
      assertTrue("open.thing" in officer && "group.archive" in officer, officer)
    }
  }

  @Test
  fun `definitions relay the field metadata and authorization to the browser`() {
    withApi {
      val body = getAs("mia", "/api/workflows/definitions").bodyAsText()
      listOf(
              """"authorization":"GROUP_ADMIN"""",
              """"confirmTypingField":"groupName"""",
              """"required":true""",
              """"multiline":true""",
              """"minLength":10""",
              """"maxLength":200""",
              """"hint":"Everyone can read this."""")
          .forEach { assertTrue(it in body, "$it missing from $body") }
      // A definition with a mode the UI does not know is not offered.
      assertTrue("odd.thing" !in body, body)
    }
  }

  @Test
  fun `a per-item authorized workflow passes service 403 and 409 answers through`() {
    withApi {
      val denied = postAs("mia", "/api/workflows/scoped.thing/preflight", """{"input":{"groupName":"x"}}""")
      assertEquals(HttpStatusCode.Forbidden, denied.status)
      assertTrue("Forbidden" in denied.bodyAsText())
      assertEquals(1, serviceCalls("/workflows/scoped.thing/preflight").size)

      val deniedStart =
          postAs("mia", "/api/workflows/scoped.thing/runs", """{"input":{},"idempotencyToken":"t"}""")
      assertEquals(HttpStatusCode.Forbidden, deniedStart.status)

      val locked = postAs("bea", "/api/workflows/scoped.thing/runs", """{"input":{},"idempotencyToken":"t"}""")
      assertEquals(HttpStatusCode.Conflict, locked.status)
      assertTrue("locked" in locked.bodyAsText())

      val ok = postAs("olive", "/api/workflows/scoped.thing/preflight", """{"input":{}}""")
      assertEquals(HttpStatusCode.OK, ok.status)
      assertEquals("olive", serviceCalls("/workflows/scoped.thing/preflight").last().headers[ACTING_USER_HEADER])
    }
  }

  @Test
  fun `a workflow with an unknown authorization mode is refused before reaching the service`() {
    withApi {
      val resp = postAs("ivy", "/api/workflows/odd.thing/preflight", """{"input":{}}""")
      assertEquals(HttpStatusCode.Forbidden, resp.status)
      assertTrue(serviceCalls("/workflows/odd.thing").isEmpty())
    }
  }

  @Test
  fun `group archive is unusable for a user without the archive flag`() {
    withApi {
      val defs = getAs("fay", "/api/workflows/definitions").bodyAsText()
      assertTrue("group.archive" !in defs, defs)
      assertTrue("open.thing" in defs, defs)
      assertEquals(
          HttpStatusCode.Forbidden,
          postAs("fay", "/api/workflows/group.archive/preflight", """{"input":{}}""").status)
      assertEquals(
          HttpStatusCode.Forbidden,
          postAs("fay", "/api/workflows/group.archive/runs", """{"input":{},"idempotencyToken":"t"}""").status)
      assertTrue(serviceCalls("/workflows/group.archive").isEmpty())
      // Another officer with the flag is unaffected.
      assertEquals(
          HttpStatusCode.OK,
          postAs("olive", "/api/workflows/group.archive/preflight", """{"input":{}}""").status)
    }
  }

  @Test
  fun `a plain member cannot list, view, stream or download another user's per-item run`() {
    withApi {
      listOf("/api/workflows/runs/31", "/api/workflows/runs/32", "/api/workflows/runs/31/events",
              "/api/workflows/runs/31/stream", "/api/workflows/runs?workflow=scoped.thing",
              "/api/workflows/runs?workflow=scoped.thing&subject=group:alpha group",
              "/api/workflows/stream?workflow=scoped.thing",
              "/api/workflows/stream?workflow=scoped.thing&subject=group:alpha group",
              "/api/workflows/runs/31/steps/0/artifacts/member-list/download")
          .forEach { assertEquals(HttpStatusCode.Forbidden, getAs("mia", it).status, it) }
      assertTrue(serviceCalls("/workflows/runs/stream").isEmpty())
      assertTrue(serviceCalls("/workflows/runs/31/stream").isEmpty())
    }
  }

  @Test
  fun `the board's admin rights do not open per-item authorized runs`() {
    withApi {
      assertEquals(HttpStatusCode.Forbidden, getAs("bea", "/api/workflows/runs/31").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("bea", "/api/workflows/runs?workflow=scoped.thing").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("bea", "/api/workflows/stream?workflow=scoped.thing").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("bea", "/api/workflows/runs/31/stream").status)
    }
  }

  @Test
  fun `the actor sees their own per-item run and no other`() {
    withApi {
      assertEquals(HttpStatusCode.OK, getAs("jdoe", "/api/workflows/runs/31").status)
      assertEquals(HttpStatusCode.OK, getAs("jdoe", "/api/workflows/runs/33").status)
      assertEquals(HttpStatusCode.OK, getAs("jdoe", "/api/workflows/runs/31/events").status)
      assertEquals(sseBody, getAs("jdoe", "/api/workflows/runs/31/stream").bodyAsText())
      assertEquals(HttpStatusCode.Forbidden, getAs("jdoe", "/api/workflows/runs/32").status)
      assertEquals(HttpStatusCode.OK, getAs("ann", "/api/workflows/runs/32").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("ann", "/api/workflows/runs/31").status)
      // Being an actor does not open the unfiltered lists.
      assertEquals(HttpStatusCode.Forbidden, getAs("ann", "/api/workflows/runs?workflow=scoped.thing").status)
    }
  }

  @Test
  fun `a group admin sees runs for their group only, and status for that one subject`() {
    withApi {
      assertEquals(HttpStatusCode.OK, getAs("gus", "/api/workflows/runs/31").status)
      assertEquals(HttpStatusCode.OK, getAs("gus", "/api/workflows/runs/31/events").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("gus", "/api/workflows/runs/32").status)
      // No group in the input: cannot be determined, so closed.
      assertEquals(HttpStatusCode.Forbidden, getAs("gus", "/api/workflows/runs/33").status)
      assertEquals(
          HttpStatusCode.OK,
          getAs("gus", "/api/workflows/runs?workflow=scoped.thing&subject=group:alpha group").status)
      assertEquals(
          HttpStatusCode.Forbidden,
          getAs("gus", "/api/workflows/runs?workflow=scoped.thing&subject=group:beta group").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("gus", "/api/workflows/runs?workflow=scoped.thing").status)
      assertEquals(
          HttpStatusCode.Forbidden,
          getAs("gus", "/api/workflows/runs?workflow=scoped.thing&subject=team:alpha group").status)
      assertEquals(
          HttpStatusCode.OK,
          getAs("gus", "/api/workflows/stream?workflow=scoped.thing&subject=group:alpha group").status)
      assertEquals(
          HttpStatusCode.Forbidden,
          getAs("gus", "/api/workflows/stream?workflow=scoped.thing&subject=group:beta group").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("gus", "/api/workflows/stream?workflow=scoped.thing").status)
      assertEquals(sseBody, getAs("gus", "/api/workflows/runs/31/stream").bodyAsText())
      assertEquals(HttpStatusCode.Forbidden, getAs("gus", "/api/workflows/runs/32/stream").status)
    }
  }

  @Test
  fun `infra sees every per-item run and the unfiltered lists`() {
    withApi {
      listOf("/api/workflows/runs/31", "/api/workflows/runs/32", "/api/workflows/runs/33",
              "/api/workflows/runs/32/events", "/api/workflows/runs?workflow=scoped.thing",
              "/api/workflows/stream?workflow=scoped.thing", "/api/workflows/runs/32/stream")
          .forEach { assertEquals(HttpStatusCode.OK, getAs("ivy", it).status, it) }
    }
  }

  @Test
  fun `the unfiltered admin lists hide other users' per-item runs from the board`() {
    adminRuns =
        "[${runJson(1, status = "PAUSED")},${runJson(41, "scoped.thing", actor = "ann")}," +
            "${runJson(42, "scoped.thing", actor = "bea")}]"
    withApi {
      val board = getAs("bea", "/api/workflows/admin/in-progress").bodyAsText()
      assertTrue("\"id\":1," in board && "\"id\":42," in board && "\"id\":41," !in board, board)
      val summary = getAs("bea", "/api/workflows/admin/summary").bodyAsText()
      assertTrue("\"inProgress\":2" in summary, summary)
      val list = getAs("bea", "/api/workflows/runs").bodyAsText()
      assertTrue("\"id\":7," in list && "\"id\":31," !in list && "\"id\":32," !in list, list)

      val infra = getAs("ivy", "/api/workflows/admin/in-progress").bodyAsText()
      assertTrue("\"id\":41," in infra, infra)
      assertTrue("\"inProgress\":3" in getAs("ivy", "/api/workflows/admin/summary").bodyAsText())
      val infraList = getAs("ivy", "/api/workflows/runs").bodyAsText()
      assertTrue("\"id\":31," in infraList && "\"id\":32," in infraList, infraList)
    }
  }

  @Test
  fun `admin stream messages drop runs the viewer may not see`() {
    val hidden: (org.dallasmakerspace.models.workflows.WorkflowRunDto) -> Boolean = { it.workflowId == "scoped.thing" }
    val data =
        """{"type":"admin","admin":{"safeToDeploy":true,"generatedAt":"2026-01-02T03:04:05Z",
          "runs":[${runJson(1)},${runJson(41, "scoped.thing")}]}}"""
    val out = WorkflowApiHandler.filterStreamMessage(data, hidden)
    assertTrue("\"id\":1," in out && "\"id\":41," !in out && "\"safeToDeploy\":true" in out, out)
    val single = WorkflowApiHandler.filterStreamMessage("""{"type":"run","run":${runJson(41, "scoped.thing")}}""", hidden)
    assertFalse("scoped.thing" in single, single)
    assertEquals("""{"type":"filtered"}""", WorkflowApiHandler.filterStreamMessage("not json", hidden))
  }

  @Test
  fun `a per-item run page is limited to admins of its group, its actor and infra`() {
    withApi {
      assertEquals(HttpStatusCode.Forbidden, getAs("mia", "/workflows/runs/31").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("bea", "/workflows/runs/31").status)
      assertEquals(HttpStatusCode.OK, getAs("gus", "/workflows/runs/31").status)
      assertEquals(HttpStatusCode.OK, getAs("jdoe", "/workflows/runs/31").status)
      assertEquals(HttpStatusCode.OK, getAs("ivy", "/workflows/runs/31").status)
      assertEquals(HttpStatusCode.Forbidden, getAs("gus", "/workflows/runs/32").status)
    }
  }

  @Test
  fun `operating a per-item authorized run is infra only`() {
    withApi {
      assertEquals(HttpStatusCode.Forbidden, postAs("mia", "/api/workflows/runs/25/retry").status)
      assertEquals(HttpStatusCode.Forbidden, postAs("bea", "/api/workflows/runs/25/retry").status)
      assertTrue(serviceCalls("/workflows/runs/25/retry").isEmpty())
      assertEquals(HttpStatusCode.OK, postAs("ivy", "/api/workflows/runs/25/retry").status)
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
