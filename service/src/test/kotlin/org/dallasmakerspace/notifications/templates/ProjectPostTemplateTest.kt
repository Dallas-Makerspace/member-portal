package org.dallasmakerspace.notifications.templates

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ProjectPostTemplateTest {

  private val template = ProjectPostTemplate()

  private fun data() =
      ProjectPostData(
          memberName = "Jane Doe",
          memberUsername = "jdoe",
          topicTitle = "Show and Tell September",
          imageUrl = "https://talk.example.com/uploads/big.png",
          excerpt = "My first lathe project")

  @Test
  fun `subject names the member`() {
    assertEquals("New project from Jane Doe", template.renderSubject(data()))
  }

  @Test
  fun `html shows the linked image, excerpt and profile link`() {
    val html = template.renderHtml(data())

    assertTrue(
        Regex("<a href=\"https://members.dallasmakerspace.org/profile/@jdoe\"[^>]*><img [^>]*src=\"https://talk.example.com/uploads/big.png\"")
            .containsMatchIn(html))
    assertTrue(html.contains("My first lathe project"))
    assertTrue(html.contains("<a href=\"https://members.dallasmakerspace.org/profile/@jdoe\">@jdoe</a>"))
    assertTrue(html.contains("You are receiving this because you follow"))
  }

  @Test
  fun `image is a profile-style thumbnail`() {
    val html = template.renderHtml(data())

    assertTrue(html.contains("width=\"218\" height=\"147\""))
    assertTrue(html.contains("object-fit:cover"))
    assertTrue(html.contains("border-radius:12px"))
  }

  @Test
  fun `image and text are side-by-side columns that can stack`() {
    val html = template.renderHtml(data())

    assertEquals(2, Regex("class=\"dms-col\"").findAll(html).count())
    assertTrue(html.contains("display:inline-block"))
    assertTrue(html.contains("<!--[if mso]>"))
    assertTrue(html.indexOf("dms-thumb") < html.indexOf("My first lathe project"))
  }

  @Test
  fun `without an image the text uses the full width`() {
    val html = template.renderHtml(data().copy(imageUrl = ""))

    assertEquals(1, Regex("class=\"dms-col\"").findAll(html).count())
    assertTrue(!html.contains("dms-thumb") && !html.contains("[if mso]"))
  }

  @Test
  fun `html escapes member-provided text`() {
    val html =
        template.renderHtml(
            data()
                .copy(
                    topicTitle = "<script>alert(1)</script>",
                    excerpt = "<img src=x onerror=alert(1)>",
                    memberName = "Jane \"J\" Doe"))

    assertTrue(!html.contains("<script>"))
    assertTrue(!html.contains("<img src=x"))
    assertTrue(html.contains("&lt;script&gt;"))
    assertTrue(html.contains("Jane &quot;J&quot; Doe"))
  }

  @Test
  fun `html omits the image and excerpt when absent`() {
    val html = template.renderHtml(data().copy(imageUrl = "", excerpt = ""))

    assertTrue(!html.contains("<img"))
    assertTrue(html.contains("See their projects"))
  }

  @Test
  fun `text is plain and links to the profile`() {
    val text = template.renderText(data())

    assertTrue(text.contains("Jane Doe (@jdoe) shared a project in Show and Tell September"))
    assertTrue(text.contains("See their projects at https://members.dallasmakerspace.org/profile/@jdoe"))
    assertTrue(!text.contains("<"))
  }

  @Test
  fun `htmlExcerpt strips tags, decodes entities and truncates`() {
    assertEquals("Hello & welcome", htmlExcerpt("<p>Hello &amp;   <b>welcome</b></p>"))
    assertEquals("abc…", htmlExcerpt("<p>abcdef</p>", maxLength = 3))
    assertEquals("", htmlExcerpt("<img src=\"x\">"))
  }
}
