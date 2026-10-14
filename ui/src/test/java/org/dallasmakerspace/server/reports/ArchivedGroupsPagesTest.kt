package org.dallasmakerspace.server.reports

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.thymeleaf.Thymeleaf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.models.ArchivedGroup
import org.dallasmakerspace.models.DMSGroup
import org.dallasmakerspace.server.auth.Authz
import org.dallasmakerspace.server.auth.Permission
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.plugins.UserSession
import org.dallasmakerspace.server.routes.CommitteeDetailHandler
import org.dallasmakerspace.server.routes.IRouteHandler
import org.dallasmakerspace.server.routes.ReportHandler
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

class ArchivedGroupsPagesTest {
  private val reportPath = "/reports/it/archived-groups"
  private val downloadUrl = "/api/workflows/runs/42/steps/1/artifacts/members.json/download"

  private val withArtifact =
      ArchivedGroup(
          id = 1,
          name = "Example Alpha",
          archivedAt = "2026-03-05T03:30:00Z",
          archivedBy = "asmith",
          reason = "Plain reason",
          runId = 42,
          artifactStepIndex = 1,
          artifactName = "members.json",
      )
  private val withoutArtifact =
      withArtifact.copy(id = 2, name = "Example Beta", archivedBy = "bdoe", runId = null)

  private val users =
      mapOf(
          "mia" to listOf<String>(), // plain member
          "bea" to listOf("/Board"), // MANAGE_CONFIG only
          "ivy" to listOf("/Infrastructure")) // holds DOWNLOAD_ARCHIVES

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

  private fun withApp(
      reports: ReportHandler? = null,
      committees: CommitteeDetailHandler? = null,
      block: suspend ApplicationTestBuilder.() -> Unit,
  ) {
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
          reports?.let { handler -> get("/reports/{path...}") { asUser(handler) } }
          committees?.let { handler -> get("/committees/{committee_slug}") { asUser(handler) } }
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

  private suspend fun ApplicationTestBuilder.getAs(user: String, path: String) =
      client.get(path) { header("X-Test-User", user) }

  private fun reportApp(
      client: MemberServiceClient,
      block: suspend ApplicationTestBuilder.() -> Unit,
  ) = withApp(reports = ReportHandler(LoggerFactory(), userInfoProvider(), client), block = block)

  private fun clientReturning(groups: List<ArchivedGroup>) =
      mock<MemberServiceClient>().also {
        runBlocking { whenever(it.getArchivedGroups(any(), anyOrNull())).thenReturn(groups) }
      }

  // --- report page ---

  @Test
  fun `infra sees the download column and the link only on rows with an artifact`() {
    reportApp(clientReturning(listOf(withArtifact, withoutArtifact))) {
      val html = getAs("ivy", reportPath).bodyAsText()

      assertTrue(">Download</th>" in html, html)
      assertTrue("href=\"$downloadUrl\"" in html, html)
      assertEquals(1, Regex("/artifacts/").findAll(html).count())
    }
  }

  @Test
  fun `a non infra member gets no download column and no download url`() {
    reportApp(clientReturning(listOf(withArtifact, withoutArtifact))) {
      val resp = getAs("mia", reportPath)
      val html = resp.bodyAsText()

      assertEquals(HttpStatusCode.OK, resp.status)
      assertTrue("Example Alpha" in html && "Example Beta" in html)
      assertFalse(">Download<" in html, html)
      assertFalse("/artifacts/" in html, html)
      assertFalse("members.json" in html, html)
      assertFalse("/steps/1/" in html, html)
    }
  }

  @Test
  fun `a MANAGE_CONFIG only user gets no download column and no download url`() {
    reportApp(clientReturning(listOf(withArtifact, withoutArtifact))) {
      val html = getAs("bea", reportPath).bodyAsText()

      assertTrue("Example Alpha" in html)
      assertFalse(">Download<" in html, html)
      assertFalse("/artifacts/" in html, html)
      assertFalse("members.json" in html, html)
    }
  }

  @Test
  fun `a DOWNLOAD_ARCHIVES holder sees the download column`() {
    assertTrue(
        Authz.fromUserInfo(mapOf("groups" to listOf("/Infrastructure")))
            .can(Permission.DOWNLOAD_ARCHIVES.name))
    reportApp(clientReturning(listOf(withArtifact))) {
      val html = getAs("ivy", reportPath).bodyAsText()

      assertTrue(">Download</th>" in html && "href=\"$downloadUrl\"" in html, html)
    }
  }

  @Test
  fun `rows show group, central time date, archived by and the reason in order`() {
    reportApp(clientReturning(listOf(withArtifact.copy(name = "Zed Group"), withoutArtifact))) {
      val html = getAs("mia", reportPath).bodyAsText()

      assertTrue("Mar 4, 2026 9:30 PM CST" in html, html)
      assertTrue("asmith" in html && "Plain reason" in html)
      assertTrue(html.indexOf("Zed Group") < html.indexOf("Example Beta"), "keeps the returned order")
      val headers = listOf(">Group<", ">Archived<", ">Archived by<", ">Reason<")
      assertEquals(headers.map { html.indexOf(it) }.sorted(), headers.map { html.indexOf(it) })
    }
  }

  @Test
  fun `a reason containing markup is escaped`() {
    val evil = withArtifact.copy(reason = "<script>alert(1)</script> & <b>bold</b>")
    reportApp(clientReturning(listOf(evil))) {
      val html = getAs("ivy", reportPath).bodyAsText()

      assertFalse("<script>alert(1)</script>" in html, html)
      assertFalse("<b>bold</b>" in html, html)
      assertTrue("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &lt;b&gt;bold&lt;/b&gt;" in html, html)
    }
  }

  @Test
  fun `an empty list shows the empty state and no table`() {
    reportApp(clientReturning(emptyList())) {
      val html = getAs("mia", reportPath).bodyAsText()

      assertTrue("No groups have been archived." in html, html)
      assertFalse("archived-groups-table" in html)
    }
  }

  @Test
  fun `no committee parameter asks the service for all groups`() {
    val client = clientReturning(listOf(withArtifact))
    reportApp(client) {
      val html = getAs("mia", reportPath).bodyAsText()

      assertFalse("archived-committee-filter" in html)
      verify(client).getArchivedGroups(eq(emptyList()), anyOrNull())
    }
  }

  @Test
  fun `a known committee filters by its prefixes and shows a clearable chip`() {
    val client = clientReturning(listOf(withArtifact))
    reportApp(client) {
      val html = getAs("mia", "$reportPath?committee=jewelry").bodyAsText()

      verify(client).getArchivedGroups(eq(listOf("Jewelry", "Lapidary")), anyOrNull())
      assertTrue("archived-committee-filter" in html, html)
      assertTrue("Committee: <span>Jewelry</span>" in html, html)
      assertTrue("href=\"/reports/it/archived-groups\"" in html && "Clear committee filter" in html)
    }
  }

  @Test
  fun `an unknown committee shows not found without calling the service or listing groups`() {
    val client = clientReturning(listOf(withArtifact))
    reportApp(client) {
      val resp = getAs("mia", "$reportPath?committee=%3Cb%3Enope%3C/b%3E")
      val html = resp.bodyAsText()

      assertEquals(HttpStatusCode.OK, resp.status)
      assertTrue("archived-committee-not-found" in html, html)
      assertFalse("<b>nope</b>" in html, "slug is escaped")
      assertFalse("Example Alpha" in html)
      verify(client, never()).getArchivedGroups(any(), anyOrNull())
    }
  }

  @Test
  fun `a service failure shows an error message instead of failing the page`() {
    val client = mock<MemberServiceClient>()
    runBlocking {
      whenever(client.getArchivedGroups(any(), anyOrNull())).thenAnswer { throw RuntimeException("down") }
    }
    reportApp(client) {
      val resp = getAs("mia", reportPath)

      assertEquals(HttpStatusCode.OK, resp.status)
      assertTrue("archived-load-failed" in resp.bodyAsText())
    }
  }

  @Test
  fun `the report is listed under IT Infrastructure for everyone`() {
    val itNode = ReportGraph.getRoot().children!!.single { it.urlSlug == "it" }
    val node = itNode.children!!.single { it.urlSlug == "archived-groups" }
    assertEquals("Archived Groups", node.name)
    assertTrue(node.visibleInNav)
  }

  // --- committee page ---

  private val committeeButton = "href=\"/reports/it/archived-groups?committee=jewelry\""

  private fun committeeService(archived: () -> List<ArchivedGroup>): MemberService {
    val service = mock<MemberService>()
    runBlocking {
      whenever(service.getAllGroups(anyOrNull())).thenReturn(emptyList<DMSGroup>())
      whenever(service.getMultipleGroups(any(), anyOrNull())).thenReturn(emptyList())
      whenever(service.getArchivedGroups(any(), anyOrNull())).thenAnswer { archived() }
    }
    return service
  }

  private fun committeeApp(
      service: MemberService,
      block: suspend ApplicationTestBuilder.() -> Unit,
  ) =
      withApp(
          committees = CommitteeDetailHandler(LoggerFactory(), userInfoProvider(), service, emptySet()),
          block = block)

  @Test
  fun `the committee page shows the archived groups button with a count`() {
    val service = committeeService { listOf(withArtifact, withoutArtifact) }
    committeeApp(service) {
      val html = getAs("mia", "/committees/jewelry").bodyAsText()

      assertTrue(committeeButton in html, html)
      assertTrue("Archived Groups (<span>2</span>)" in html, html)
      verify(service).getArchivedGroups(eq(listOf("Jewelry", "Lapidary")), anyOrNull())
    }
  }

  @Test
  fun `the committee page hides the button when nothing is archived`() {
    committeeApp(committeeService { emptyList() }) {
      val resp = getAs("mia", "/committees/jewelry")

      assertEquals(HttpStatusCode.OK, resp.status)
      assertFalse("archived-groups-button" in resp.bodyAsText())
    }
  }

  @Test
  fun `the committee page still renders without the button when the service call fails`() {
    committeeApp(committeeService { throw RuntimeException("down") }) {
      val resp = getAs("mia", "/committees/jewelry")
      val html = resp.bodyAsText()

      assertEquals(HttpStatusCode.OK, resp.status)
      assertTrue("Committee Groups" in html)
      assertFalse("archived-groups-button" in html)
    }
  }

  @Test
  fun `a committee without group prefixes makes no archived groups call`() {
    val service = committeeService { listOf(withArtifact) }
    committeeApp(service) {
      val resp = getAs("mia", "/committees/logistics")

      assertEquals(HttpStatusCode.OK, resp.status)
      assertFalse("archived-groups-button" in resp.bodyAsText())
      verify(service, never()).getArchivedGroups(any(), anyOrNull())
    }
  }
}
