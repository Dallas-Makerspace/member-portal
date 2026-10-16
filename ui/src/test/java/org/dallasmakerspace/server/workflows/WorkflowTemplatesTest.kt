package org.dallasmakerspace.server.workflows

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.thymeleaf.Thymeleaf
import io.ktor.server.thymeleaf.ThymeleafContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

class WorkflowTemplatesTest {
  private val pages = listOf("admin-workflows", "workflow-run", "workflow-demo")

  private fun resource(path: String): String =
      checkNotNull(javaClass.classLoader.getResource(path)) { "missing $path" }.readText()

  private fun render(template: String, model: Map<String, Any?>): String {
    var body = ""
    testApplication {
      application {
        install(Thymeleaf) {
          setTemplateResolver(
              ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".html"
                characterEncoding = "utf-8"
              })
        }
        routing { get("/page") { call.respond(ThymeleafContent(template, model)) } }
      }
      val resp = client.get("/page")
      assertEquals(HttpStatusCode.OK, resp.status)
      body = resp.bodyAsText()
    }
    return body
  }

  @Test
  fun `every workflow page includes the required scripts`() {
    pages.forEach { name ->
      val html = resource("templates/$name.html")
      assertTrue("bootstrap.bundle.min.js" in html, "$name: Bootstrap JS")
      assertTrue("/static/js/app.js" in html, "$name: app.js")
      assertTrue("/static/js/dms-page-loader.js" in html, "$name: page loader")
      assertTrue("/static/js/workflow-common.js" in html, "$name: common helpers")
    }
  }

  @Test
  fun `the run page carries the run id and operate flag for its script`() {
    val html = render("workflow-run", mapOf("runId" to 42L, "runTitle" to "Archive group", "canOperate" to true))
    assertTrue("""data-run-id="42"""" in html)
    assertTrue("""data-can-operate="true"""" in html)
    assertTrue("/static/js/workflow-run.js" in html)
  }

  @Test
  fun `the run page carries the download flags and the script gates links on them`() {
    val html =
        render(
            "workflow-run",
            mapOf(
                "runId" to 42L,
                "runTitle" to "Archive group",
                "canOperate" to false,
                "canDownload" to true,
                "canDownloadArchives" to false))
    assertTrue("""data-can-download="true"""" in html)
    assertTrue("""data-can-download-archives="false"""" in html)
    val js = resource("static/js/workflow-run.js")
    assertTrue("if (!canDownload) return null" in js)
    assertTrue("!a.infraOnly || canDownloadArchives" in js)
    assertTrue("'/artifacts/'" in js && "'/download'" in js)
    assertFalse("X-Amz" in js || "presigned" in js.lowercase())
  }

  @Test
  fun `the admin page has the table and banner hooks its script needs`() {
    val html = render("admin-workflows", mapOf("username" to "jdoe"))
    listOf("wf-admin-root", "wf-admin-banner", "wf-admin-rows", "wf-admin-conn", "workflow-admin.js").forEach {
      assertTrue(it in html, it)
    }
  }

  @Test
  fun `the demo page lists the definitions it was given`() {
    val html =
        render(
            "workflow-demo",
            mapOf(
                "definitions" to
                    listOf(mapOf("id" to "demo.flow", "displayName" to "Demo flow", "description" to "d"))))
    assertTrue("""data-demo-workflow="demo.flow"""" in html)
    assertTrue("Demo flow" in html)
  }

  @Test
  fun `the About menu has an Admin submenu with workflows only for admins`() {
    val on = render("admin-workflows", mapOf("workflowsAdminMenu" to true))
    assertTrue("""href="/admin/workflows"""" in on)
    assertTrue("Workflows in progress" in on)
    assertTrue("/api/workflows/admin/summary" in on)
    assertFalse(">Help" in on)

    val off = render("admin-workflows", mapOf("workflowsAdminMenu" to false))
    assertFalse("""href="/admin/workflows"""" in off)
    assertFalse("/api/workflows/admin/summary" in off)
    // Site Config moved under About > Admin and stays visible to everyone.
    assertTrue("""href="/admin/config"""" in off)
    assertTrue(">Admin" in off)
    assertTrue("https://github.com/Dallas-Makerspace" in off)
  }

  @Test
  fun `the launch fragment renders the data attributes the button script reads`() {
    val html = render("test-workflow-launch-host", emptyMap())
    assertTrue("""data-workflow-id="group.archive"""" in html)
    assertTrue("""data-subject="group:example-group"""" in html)
    assertTrue("""data-label="Archive group"""" in html)
    assertTrue("""data-btn-class="btn-outline-danger"""" in html)
    assertTrue("&quot;groupName&quot;" in html)
    assertTrue("/static/js/workflow-fields.js" in html)
    assertTrue(html.indexOf("workflow-fields.js") < html.indexOf("workflow-launch.js"))
    assertTrue(html.indexOf("workflow-common.js") < html.indexOf("workflow-fields.js"))
    assertTrue("/static/js/workflow-launch.js" in html)
  }

  @Test
  fun `the demo page loads the field helpers before the launch script`() {
    val html = resource("templates/workflow-demo.html")
    assertTrue(html.indexOf("workflow-fields.js") in 0 until html.indexOf("workflow-launch.js"))
  }
}
