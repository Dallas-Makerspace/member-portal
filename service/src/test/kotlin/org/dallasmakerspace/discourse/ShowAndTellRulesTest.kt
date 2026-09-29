package org.dallasmakerspace.discourse

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ShowAndTellRulesTest {

  @Test
  fun `project topics are the monthly Show and Tell topics`() {
    assertTrue(ShowAndTellRules.isProjectTopic("Show and Tell September 2026"))
    assertFalse(ShowAndTellRules.isProjectTopic("Welcome to the category"))
  }

  @Test
  fun `the intro post is not a project`() {
    assertFalse(ShowAndTellRules.isProjectPost(1))
    assertTrue(ShowAndTellRules.isProjectPost(2))
  }

  @Test
  fun `image extraction prefers the large source and skips small images`() {
    val cooked =
        """<p><img src="/emoji.png" width="20"> <img src="/small.png" data-large-src="/big.png" width="600"></p>"""

    assertEquals("https://talk.dallasmakerspace.org/big.png", ShowAndTellRules.extractLargeImageUrl(cooked))
  }

  @Test
  fun `image extraction accepts images without a width attribute`() {
    assertEquals(
        "https://cdn.example.com/a.jpg",
        ShowAndTellRules.extractLargeImageUrl("""<img src="https://cdn.example.com/a.jpg">"""))
  }

  @Test
  fun `image extraction resolves protocol-relative urls`() {
    assertEquals(
        "https://cdn.example.com/a.jpg",
        ShowAndTellRules.extractLargeImageUrl("""<img src="//cdn.example.com/a.jpg" width="400">"""))
  }

  @Test
  fun `image extraction returns null when there is no qualifying image`() {
    assertNull(ShowAndTellRules.extractLargeImageUrl("<p>just text</p>"))
    assertNull(ShowAndTellRules.extractLargeImageUrl("""<img src="/a.png" width="100">"""))
  }

  @Test
  fun `post urls point at the topic and post number`() {
    assertEquals(
        "https://talk.dallasmakerspace.org/t/show-and-tell-september/123/4",
        ShowAndTellRules.postUrl("show-and-tell-september", 123, 4))
  }
}
