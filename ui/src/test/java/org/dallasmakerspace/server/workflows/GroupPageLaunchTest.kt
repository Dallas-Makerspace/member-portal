package org.dallasmakerspace.server.workflows

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respond
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
import io.ktor.server.thymeleaf.ThymeleafContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.models.DMSGroup
import org.dallasmakerspace.models.DirectoryOus
import org.dallasmakerspace.server.auth.Authz
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.plugins.UserSession
import org.dallasmakerspace.server.plugins.configureCommonModel
import org.dallasmakerspace.server.routes.GroupsHandler
import org.dallasmakerspace.server.routes.IRouteHandler
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

class GroupPageLaunchTest {
  private fun installThymeleaf(app: io.ktor.server.application.Application) {
    app.install(Thymeleaf) {
      setTemplateResolver(
          ClassLoaderTemplateResolver().apply {
            prefix = "templates/"
            suffix = ".html"
            characterEncoding = "utf-8"
          })
    }
  }

  private fun render(model: Map<String, Any?>): String {
    var body = ""
    testApplication {
      application {
        installThymeleaf(this)
        routing { get("/page") { call.respond(ThymeleafContent("group", model)) } }
      }
      val resp = client.get("/page")
      assertEquals(HttpStatusCode.OK, resp.status)
      body = resp.bodyAsText()
    }
    return body
  }

  private fun groupModel(
      name: String = "Test Group",
      enabled: Boolean? = null,
      canArchive: Boolean? = true,
  ) =
      buildMap<String, Any?> {
        put("name", name)
        put("slug", "Test+Group")
        put("selfMembershipAction", "")
        put("memberUsernamesJson", "[]")
        if (canArchive != null) {
          put("canArchiveGroup", canArchive)
          put("workflowSubject", GroupWorkflowLaunch.subject(name))
          put("archiveInput", GroupWorkflowLaunch.archiveInput(name))
        }
        if (enabled != null) put("workflowsEnabled", enabled)
      }

  // --- template: button markup, driven by the model flags ---

  @Test
  fun `the archive button and its scripts render when workflows are on and the viewer may archive`() {
    val html = render(groupModel(enabled = true))

    assertTrue("""data-workflow-id="group.archive"""" in html)
    assertTrue("""data-subject="group:test group"""" in html)
    assertTrue("""data-label="Archive group"""" in html)
    assertTrue("""data-input="{&quot;groupName&quot;:&quot;Test Group&quot;}"""" in html)
    assertTrue("btn-outline-danger" in html)
    assertTrue("/static/js/workflow-common.js" in html)
    assertTrue("/static/js/workflow-fields.js" in html)
    assertTrue("/static/js/workflow-launch.js" in html)
    assertTrue("/static/styles/workflows.css" in html)
  }

  @Test
  fun `the export button is gone`() {
    listOf(true, false).forEach { enabled ->
      val html = render(groupModel(enabled = enabled))
      assertFalse("group.export-members" in html)
      assertFalse("Export members" in html)
    }
  }

  @Test
  fun `nothing workflow related renders when the viewer may not archive`() {
    assertNoWorkflowMarkup(render(groupModel(enabled = true, canArchive = false)))
    assertNoWorkflowMarkup(render(groupModel(enabled = true, canArchive = null)))
  }

  @Test
  fun `nothing workflow related renders when the flag is off or missing`() {
    assertNoWorkflowMarkup(render(groupModel(enabled = false)))
    assertNoWorkflowMarkup(render(groupModel(enabled = null)))
  }

  @Test
  fun `the page loader and Bootstrap scripts stay on the page either way`() {
    listOf(true, false).forEach { enabled ->
      val html = render(groupModel(enabled = enabled))
      assertTrue("/static/js/dms-page-loader.js" in html)
      assertTrue("bootstrap.bundle.min.js" in html)
      assertTrue("/static/js/app.js" in html)
    }
  }

  @Test
  fun `a group name with quotes stays valid JSON inside the attribute`() {
    val name = """Quote "Q" & Co"""
    val html = render(groupModel(name = name, enabled = true))

    val raw = Regex("""data-input="([^"]*)"""").find(html)!!.groupValues[1]
    val decoded = raw.replace("&quot;", "\"").replace("&amp;", "&")
    val parsed = Json.parseToJsonElement(decoded).jsonObject
    assertEquals(name, parsed["groupName"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a group name containing markup is escaped in the rendered attributes`() {
    val name = "<script>alert(1)</script>"
    val html = render(groupModel(name = name, enabled = true))

    assertFalse(name in html, "raw markup from the group name reached the page")
    val button = Regex("""<span[^>]*class="wf-launch"[^>]*>""").find(html)!!.value
    assertFalse("<script" in button)
    assertTrue("data-input=\"{&quot;groupName&quot;:&quot;&lt;script&gt;" in button, button)
    assertTrue("data-subject=\"group:&lt;script&gt;" in button, button)
  }

  @Test
  fun `the subject is trimmed and lowercased like the service normalizes it`() {
    assertEquals("group:test group", GroupWorkflowLaunch.subject("  Test Group "))
    assertEquals("group:a:b", GroupWorkflowLaunch.subject("A:B"))
  }

  // --- canArchive rule ---

  private fun authzFor(vararg groups: String) = Authz.fromUserInfo(mapOf("groups" to groups.toList()))

  private val mainDn = "CN=Test Group,${DirectoryOus.MAIN_GROUP_OU}"
  private val adminOuDn = "CN=Test Group,${DirectoryOus.GROUP_OUS[1]}"

  @Test
  fun `a listed admin or an infra member may archive a main OU group, nobody else`() {
    val member = authzFor()
    val infra = authzFor("/Infrastructure")
    assertTrue(GroupWorkflowLaunch.canArchive(mainDn, isGroupAdmin = true, authz = member))
    assertTrue(GroupWorkflowLaunch.canArchive(mainDn, isGroupAdmin = false, authz = infra))
    assertFalse(GroupWorkflowLaunch.canArchive(mainDn, isGroupAdmin = false, authz = member))
    // MANAGE_GROUPS (officers) and MANAGE_CONFIG (board) are not enough on their own.
    assertFalse(GroupWorkflowLaunch.canArchive(mainDn, false, authzFor("/President")))
    assertFalse(GroupWorkflowLaunch.canArchive(mainDn, false, authzFor("/Board")))
  }

  @Test
  fun `admin OU groups and unknown DNs are never archivable`() {
    val infra = authzFor("/Infrastructure")
    assertFalse(GroupWorkflowLaunch.canArchive(adminOuDn, true, infra))
    assertFalse(GroupWorkflowLaunch.canArchive("", true, infra))
    assertFalse(GroupWorkflowLaunch.canArchive(null, true, infra))
    assertFalse(GroupWorkflowLaunch.canArchive(DirectoryOus.MAIN_GROUP_OU, true, infra))
  }

  // --- GroupsHandler end to end (mocked backends): which viewers get the button ---

  private val users =
      mapOf(
          "adm" to listOf<String>(), // listed admin (isGroupAdmin = true)
          "inf" to listOf("/Infrastructure"),
          "mem" to listOf<String>(), // plain member
          "err" to listOf<String>()) // admin lookup throws

  /** The flag is cached 60s per (flag, username), so each scenario uses its own suffix. */
  private fun withGroupPage(
      suffix: String,
      flagOn: Boolean = true,
      archiveFlagOn: Boolean = true,
      block: suspend ApplicationTestBuilder.() -> Unit,
  ) {
    val provider = mock<UserInfoProvider>()
    val client = mock<MemberServiceClient>()
    val service = mock<MemberService>()
    runBlocking {
      users.forEach { (name, groups) ->
        whenever(provider.getUserInfo("tok-$name$suffix"))
            .thenReturn(mapOf("preferred_username" to "$name$suffix", "groups" to groups))
      }
      whenever(client.isFeatureEnabled(any(), any(), anyOrNull())).thenAnswer {
        when (it.getArgument<String>(0)) {
          WORKFLOWS_FEATURE_FLAG -> flagOn
          ARCHIVE_FEATURE_FLAG -> archiveFlagOn
          else -> false
        }
      }
      whenever(client.getFeatureFlag(any(), anyOrNull(), any())).thenReturn(false)
      whenever(client.getSelfMembershipStatus(any(), any(), anyOrNull())).thenReturn(null)
      whenever(client.isGroupAdmin(any(), any(), anyOrNull())).thenAnswer {
        val user = it.getArgument<String>(0)
        if (user.startsWith("err")) error("lookup failed")
        user.startsWith("adm")
      }
      whenever(service.hasPrerequisiteClasses(any(), anyOrNull())).thenReturn(false)
      whenever(service.getGroup(any(), anyOrNull())).thenAnswer {
        val slug = it.getArgument<String>(0)
        DMSGroup(
            name = "Test Group",
            distinguishedName = if (slug == "Ops+Group") adminOuDn else mainDn,
            objectGuid = null)
      }
    }
    val handler = GroupsHandler(LoggerFactory(), provider, service, client)
    testApplication {
      application {
        install(Sessions) { cookie<UserSession>("user_session") }
        installThymeleaf(this)
        configureCommonModel(client)
        routing { get("/groups/{group_slug}") { asUser(handler, suffix) } }
      }
      block()
    }
  }

  private suspend fun RoutingContext.asUser(handler: IRouteHandler, suffix: String) {
    val user = call.request.headers["X-Test-User"] ?: "mem"
    call.sessions.set(UserSession(accessToken = "tok-$user$suffix", sessionId = "sess", userId = "$user$suffix"))
    handler.handleBase(call)
  }

  private suspend fun ApplicationTestBuilder.pageAs(user: String, slug: String = "Test+Group"): String {
    val resp = client.get("/groups/$slug") { header("X-Test-User", user) }
    assertEquals(HttpStatusCode.OK, resp.status)
    return resp.bodyAsText()
  }

  private fun assertArchiveButton(html: String) {
    assertTrue("""data-workflow-id="group.archive"""" in html, "archive button missing")
    assertTrue("workflow-launch.js" in html)
    assertFalse("group.export-members" in html)
  }

  @Test
  fun `a listed group admin sees the archive button when the flag is on`() {
    withGroupPage("-a1") { assertArchiveButton(pageAs("adm")) }
  }

  @Test
  fun `an infra member sees the archive button without being listed`() {
    withGroupPage("-a2") { assertArchiveButton(pageAs("inf")) }
  }

  @Test
  fun `the button is hidden when the flag is off even for admins`() {
    withGroupPage("-off", flagOn = false) {
      assertNoWorkflowMarkup(pageAs("adm"))
      assertNoWorkflowMarkup(pageAs("inf"))
    }
  }

  @Test
  fun `the button is hidden without the dedicated archive flag even for admins and infra`() {
    withGroupPage("-noarch", archiveFlagOn = false) {
      assertNoWorkflowMarkup(pageAs("adm"))
      assertNoWorkflowMarkup(pageAs("inf"))
    }
  }

  @Test
  fun `the archive flag alone is not enough without the workflows flag`() {
    withGroupPage("-nowf", flagOn = false, archiveFlagOn = true) {
      assertNoWorkflowMarkup(pageAs("inf"))
    }
  }

  @Test
  fun `the button is hidden for a plain member`() {
    withGroupPage("-a3") { assertNoWorkflowMarkup(pageAs("mem")) }
  }

  @Test
  fun `the button is hidden for admin OU groups even for infra`() {
    withGroupPage("-a4") {
      assertNoWorkflowMarkup(pageAs("inf", "Ops+Group"), "Ops Group")
      assertNoWorkflowMarkup(pageAs("adm", "Ops+Group"), "Ops Group")
    }
  }

  @Test
  fun `a failing admin lookup hides the button and does not break the page`() {
    withGroupPage("-a5") { assertNoWorkflowMarkup(pageAs("err")) }
  }

  private fun assertNoWorkflowMarkup(html: String, groupName: String = "Test Group") {
    assertFalse("wf-launch" in html)
    assertFalse("group.archive" in html)
    assertFalse("group.export-members" in html)
    assertFalse("workflow-launch.js" in html)
    assertFalse("workflow-common.js" in html)
    assertFalse("workflows.css" in html)
    assertTrue(groupName in html)
  }
}
