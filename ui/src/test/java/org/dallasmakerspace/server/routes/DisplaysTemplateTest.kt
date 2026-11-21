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
      managed: Boolean = true,
      state: String? = null,
  ) =
      mapOf<String, Any?>(
          "id" to 1,
          "name" to name,
          "committeeName" to if (managed) "Test Shop" else null,
          "committeeSlug" to if (managed) "test-shop" else null,
          "managed" to managed,
          "state" to (state ?: if (!enabled) "DISABLED" else when (online) { true -> "ONLINE"; false -> "OFFLINE"; null -> "UNSEEN" }),
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
          setVariable("online", displays.count { it["state"] == "ONLINE" })
          setVariable("offline", displays.count { it["state"] == "OFFLINE" })
          setVariable("notSetUp", displays.count { it["managed"] == false })
          setVariable("setUp", displays.count { it["managed"] == true })
          setVariable("attention", displays.count { it["state"] in setOf("OFFLINE", "UNSEEN", "DISABLED") })
          setVariable("dashboards", displays.count { it["showingDashboard"] == true })
        }
    return engine.process(template, context)
  }

  private val sample =
      listOf(
          tv("Shop floor TV", true, "The committee dashboard", true, listOf("Tool Status", "Classes")),
          tv("Lobby TV", false, "Offline"),
          tv("Classroom TV", null, "Not seen yet"),
          tv("Spare TV", true, "Showing YouTube", enabled = false),
          tv("Classroom Display With A Rather Long Name For Wrapping", true, "Showing Google Photos", true, listOf("Tool Status", "Upcoming Classes", "Lathe Room")),
          tv("Hallway Display", null, "Not set up for a committee", managed = false, state = "ON_NETWORK"))

  @Test
  fun `each TV shows its status, what it is showing and its dashboard pages`() {
    val html = render(sample)
    File("build/displays-sample.html").apply { parentFile.mkdirs() }.writeText(render(sample, infra = true))

    assertTrue(html.contains("Shop floor TV"))
    assertTrue(html.contains("The committee dashboard"))
    assertTrue(html.contains(">Tool Status<") && html.contains(">Classes<"))
    assertEquals(2, Regex("disp-badge-ok").findAll(html).count())
    assertTrue(html.contains("Offline") && html.contains("Not seen yet") && html.contains("Disabled"))
    assertTrue(html.contains("Showing YouTube"))
  }

  @Test
  fun `a TV nobody has set up is listed with a note instead of a committee`() {
    val html = render(sample)

    assertTrue(html.contains("Hallway Display"))
    assertTrue(html.contains("On the network"))
    assertTrue(html.contains("No committee yet"))
    assertEquals(1, Regex("data-managed=\"false\"").findAll(html).count())
    assertEquals(5, Regex("data-managed=\"true\"").findAll(html).count())
  }

  @Test
  fun `a TV's address is never shown, even to infrastructure members`() {
    assertFalse(render(sample, infra = false).contains("tv-1.example.internal"))
    assertFalse(render(sample, infra = true).contains("tv-1.example.internal"))
  }

  @Test
  fun `rows carry what the filters need`() {
    val html = render(sample)

    assertTrue(html.contains("data-search=\"shop floor tv test shop test_tv\""))
    assertEquals(3, Regex("data-problem=\"true\"").findAll(html).count())
    assertEquals(3, Regex("data-problem=\"false\"").findAll(html).count())
  }

  @Test
  fun `no TVs and an unreachable service are told apart`() {
    assertTrue(render(emptyList()).contains("No TVs have been found yet"))
    val down = render(emptyList(), unavailable = true)
    assertTrue(down.contains("could not be loaded"))
    assertFalse(down.contains("disp-table"))
  }
}
