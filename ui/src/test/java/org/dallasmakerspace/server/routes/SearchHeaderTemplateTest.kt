package org.dallasmakerspace.server.routes

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templateresolver.StringTemplateResolver

/** Renders the real site header and the legacy Ask AI page to check the search dialog wiring. */
class SearchHeaderTemplateTest {
  private val engine = TemplateEngine().apply { setTemplateResolver(StringTemplateResolver()) }

  private fun resource(path: String): String =
      checkNotNull(javaClass.classLoader.getResource(path)).readText()

  private fun renderHeader(dialog: Boolean): String =
      engine.process(
          resource("templates/fragments/header.html"),
          Context().apply {
            setVariable("searchDialogEnabled", dialog)
            setVariable("current_username", "jdoe")
            setVariable("current_display_name", "Jane Doe")
          })

  private fun renderAskAi(vars: Map<String, Any?>): String =
      engine.process(
          resource("templates/ask-ai.html")
              .replace(Regex("""<th:block th:replace="~\{fragments/pwa-meta[^>]*></th:block>"""), "")
              .replace(Regex("""<header class="top-nav" th:replace="~\{fragments/header[^>]*></header>"""), ""),
          Context().apply { vars.forEach { (k, v) -> setVariable(k, v) } })

  @Test
  fun `with the flag on the header opens the search dialog instead of the old search box`() {
    val html = renderHeader(dialog = true)

    assertContains(html, """<dms-search-dialog username="jdoe">""")
    assertContains(html, "/static/js/search/dms-search-dialog.js")
    assertEquals(2, Regex("data-search-open").findAll(html).count())
    assertFalse(html.contains("<dms-search "))
    assertFalse(html.contains("dms-search-v6.js"))
    assertFalse(html.contains("toggleMobileSearch"))
  }

  @Test
  fun `with the flag off the header keeps the old search box`() {
    val html = renderHeader(dialog = false)

    assertTrue(html.contains("<dms-search max-results=\"7\">"))
    assertContains(html, "dms-search-v6.js")
    assertContains(html, "toggleMobileSearch")
    assertFalse(html.contains("dms-search-dialog"))
    assertFalse(html.contains("data-search-open"))
  }

  @Test
  fun `the header has nothing after the closing html tag`() {
    assertTrue(resource("templates/fragments/header.html").trimEnd().endsWith("</html>"))
  }

  @Test
  fun `the legacy ask ai page puts a question from search into the question box`() {
    val html = renderAskAi(mapOf("prefillQuestion" to "Where is the <laser> cutter?"))

    assertContains(html, "Where is the &lt;laser&gt; cutter?</textarea>")
  }

  @Test
  fun `an answered question wins over the search prefill`() {
    val html = renderAskAi(mapOf("question" to "How do I reserve a lathe?", "prefillQuestion" to "lathe"))

    assertContains(html, "How do I reserve a lathe?</textarea>")
  }
}
