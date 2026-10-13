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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

class GroupPageLaunchTest {
  private fun render(model: Map<String, Any?>): String {
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
        routing { get("/page") { call.respond(ThymeleafContent("group", model)) } }
      }
      val resp = client.get("/page")
      assertEquals(HttpStatusCode.OK, resp.status)
      body = resp.bodyAsText()
    }
    return body
  }

  private fun groupModel(name: String = "Test Group", enabled: Boolean? = null) =
      buildMap<String, Any?> {
        put("name", name)
        put("slug", "Test+Group")
        put("selfMembershipAction", "")
        put("memberUsernamesJson", "[]")
        put("workflowSubject", GroupWorkflowLaunch.subject(name))
        put("exportMembersInput", GroupWorkflowLaunch.exportMembersInput(name))
        if (enabled != null) put("workflowsEnabled", enabled)
      }

  @Test
  fun `the export button and its scripts render when workflows are enabled`() {
    val html = render(groupModel(enabled = true))

    assertTrue("""data-workflow-id="group.export-members"""" in html)
    assertTrue("""data-subject="group:test group"""" in html)
    assertTrue("""data-label="Export members"""" in html)
    assertTrue("""data-input="{&quot;groupName&quot;:&quot;Test Group&quot;}"""" in html)
    assertTrue("/static/js/workflow-common.js" in html)
    assertTrue("/static/js/workflow-launch.js" in html)
    assertTrue("/static/styles/workflows.css" in html)
  }

  @Test
  fun `nothing workflow related renders when the flag is off`() {
    val html = render(groupModel(enabled = false))

    assertNoWorkflowMarkup(html)
  }

  @Test
  fun `nothing workflow related renders when the flag is missing`() {
    val html = render(groupModel(enabled = null))

    assertNoWorkflowMarkup(html)
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

  private fun assertNoWorkflowMarkup(html: String) {
    assertFalse("wf-launch" in html)
    assertFalse("group.export-members" in html)
    assertFalse("workflow-launch.js" in html)
    assertFalse("workflow-common.js" in html)
    assertFalse("workflows.css" in html)
    assertTrue("Test Group" in html)
  }
}
