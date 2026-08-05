package org.dallasmakerspace.server.models

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AvatarUrlTest {

  @Test
  fun normalizesSiteRelativeDiscoursePath() {
    assertEquals(
        "https://talk.dallasmakerspace.org/user_avatar/talk.dallasmakerspace.org/jdoe/90/1_2.png",
        normalizeAvatarUrl("/user_avatar/talk.dallasmakerspace.org/jdoe/{size}/1_2.png", size = 90))
  }

  @Test
  fun normalizesProtocolRelativeUrl() {
    assertEquals(
        "https://cdn.example.com/avatar.png", normalizeAvatarUrl("//cdn.example.com/avatar.png"))
  }

  @Test
  fun passesThroughAbsoluteHttpsUrl() {
    val url = "https://cdn.example.com/user_avatar/x/{size}/1.png"
    assertEquals(
        "https://cdn.example.com/user_avatar/x/90/1.png", normalizeAvatarUrl(url, size = 90))
  }

  @Test
  fun passesThroughAbsoluteHttpUrl() {
    assertEquals(
        "http://cdn.example.com/avatar.png",
        normalizeAvatarUrl("http://cdn.example.com/avatar.png"))
  }

  @Test
  fun substitutesDefaultSizeWhenNotSpecified() {
    assertEquals(
        "https://talk.dallasmakerspace.org/x/144/1.png", normalizeAvatarUrl("/x/{size}/1.png"))
  }

  @Test
  fun substitutesExplicitSize() {
    assertEquals(
        "https://talk.dallasmakerspace.org/x/90/1.png",
        normalizeAvatarUrl("/x/{size}/1.png", size = 90))
  }

  @Test
  fun returnsEmptyStringForNull() {
    assertEquals("", normalizeAvatarUrl(null))
  }

  @Test
  fun returnsEmptyStringForBlank() {
    assertEquals("", normalizeAvatarUrl(""))
  }
}
