package org.dallasmakerspace.notifications.templates

import kotlin.test.assertTrue
import org.junit.Test

class ThumbnailLayoutTest {

  private val url = "https://img.example.org/a.png"

  @Test
  fun `default crops to the fixed thumbnail box`() {
    val html = thumbnailColumnsHtml(url, "alt", "https://x.example.org", "<p>body</p>")

    assertTrue(html.contains("class=\"dms-thumb\"") && html.contains("height=\"147\""), html)
    assertTrue(html.contains("object-fit:cover"), html)
  }

  @Test
  fun `keeping the aspect ratio sets no fixed height or crop`() {
    val html =
        thumbnailColumnsHtml(url, "alt", "https://x.example.org", "<p>body</p>", keepAspectRatio = true)

    assertTrue(html.contains("class=\"dms-thumb-auto\"") && html.contains("height:auto"), html)
    assertTrue(!html.contains("object-fit") && !html.contains("height=\""), html)
  }

  @Test
  fun `no image renders only the text column`() {
    val html = thumbnailColumnsHtml("", "alt", "https://x.example.org", "<p>body</p>")

    assertTrue(!html.contains("<img") && html.contains("<p>body</p>"), html)
  }
}
