package org.dallasmakerspace.server.routes

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templateresolver.StringTemplateResolver

/** Renders the real TVs / Displays page (without the site header) with made-up TVs. */
class DisplaysTemplateTest {
  private val engine = TemplateEngine().apply { setTemplateResolver(StringTemplateResolver()) }

  private val template: String =
      checkNotNull(javaClass.classLoader.getResource("templates/displays.html")).readText()
          .replace(Regex("""<th:block th:replace="~\{fragments/pwa-meta[^>]*></th:block>"""), "")
          .replace(Regex("""<header class="top-nav" th:replace="~\{fragments/header[^>]*></header>"""), "")

  private fun tv(
      name: String,
      online: Boolean?,
      showing: String,
      dashboard: Boolean = false,
      pages: List<String> = emptyList(),
      enabled: Boolean = true,
  ) =
      mapOf<String, Any?>(
          "id" to 1,
          "name" to name,
          "committeeName" to "Test Shop",
          "committeeSlug" to "test-shop",
          "host" to "tv-1.example.internal",
          "deviceName" to "Test_TV",
          "enabled" to enabled,
          "online" to online,
          "lastSeen" to if (online == null) null else "Today, 8:15 AM",
          "showing" to showing,
          "showingDashboard" to dashboard,
          "dashboardPages" to pages,
          "schedule" to "On until 2:00 AM",
          "inHours" to true,
          "reclaimAfterMinutes" to 15)

  private fun render(
      displays: List<Map<String, Any?>>,
      infra: Boolean = false,
      unavailable: Boolean = false,
  ): String {
    val context =
        Context().apply {
          setVariable("displays", displays)
          setVariable("unavailable", unavailable)
          setVariable("isInfraUser", infra)
          setVariable("total", displays.size)
          setVariable("online", displays.count { it["online"] == true })
          setVariable("offline", displays.count { it["online"] == false })
          setVariable("dashboards", displays.count { it["showingDashboard"] == true })
        }
    return engine.process(template, context)
  }

  private val sample =
      listOf(
          tv("Shop floor TV", true, "The committee dashboard", true, listOf("Tool Status", "Classes")),
          tv("Lobby TV", false, "Offline"),
          tv("Classroom TV", null, "Not seen yet"),
          tv("Spare TV", true, "Showing YouTube", enabled = false))

  @Test
  fun `each TV shows its status, what it is showing and its dashboard pages`() {
    val html = render(sample)
    File("build/displays-sample.html").apply { parentFile.mkdirs() }.writeText(render(sample, infra = true))

    assertTrue(html.contains("Shop floor TV"))
    assertTrue(html.contains("The committee dashboard"))
    assertTrue(html.contains(">Tool Status<") && html.contains(">Classes<"))
    assertEquals(1, Regex("disp-badge-ok").findAll(html).count())
    assertTrue(html.contains("Offline") && html.contains("Not seen yet") && html.contains("Disabled"))
    assertTrue(html.contains("Showing YouTube"))
  }

  @Test
  fun `the address column is only for infrastructure members`() {
    assertFalse(render(sample, infra = false).contains("tv-1.example.internal"))
    assertTrue(render(sample, infra = true).contains("tv-1.example.internal"))
  }

  @Test
  fun `rows carry what the filters need`() {
    val html = render(sample)

    assertTrue(html.contains("data-search=\"shop floor tv test shop\""))
    assertEquals(3, Regex("data-problem=\"true\"").findAll(html).count())
    assertEquals(1, Regex("data-problem=\"false\"").findAll(html).count())
  }

  @Test
  fun `no TVs and an unreachable service are told apart`() {
    assertTrue(render(emptyList()).contains("No TVs have been set up yet"))
    val down = render(emptyList(), unavailable = true)
    assertTrue(down.contains("could not be loaded"))
    assertFalse(down.contains("disp-table"))
  }
}
