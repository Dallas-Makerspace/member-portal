package org.dallasmakerspace.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class SocialNetworkTest {

  private fun assertParses(network: SocialNetwork, input: String, expected: String) =
      assertEquals(input, expected, network.parse(input))

  private fun assertRejects(network: SocialNetwork, input: String) =
      assertNull(input, network.parse(input))

  @Test
  fun parsesLinkedInProfileUrls() {
    val n = SocialNetwork.LINKEDIN
    assertParses(n, "https://www.linkedin.com/in/jdoe", "jdoe")
    assertParses(n, "linkedin.com/in/jdoe/", "jdoe")
    assertParses(n, "http://uk.linkedin.com/in/jane-doe-123?trk=profile", "jane-doe-123")
    assertParses(n, "https://www.linkedin.com/in/j%C3%A9r%C3%B4me", "j%C3%A9r%C3%B4me")
    assertParses(n, "  https://www.linkedin.com/in/jdoe  ", "jdoe")
  }

  @Test
  fun rejectsNonProfileLinkedInUrls() {
    val n = SocialNetwork.LINKEDIN
    assertRejects(n, "https://www.linkedin.com/company/example-co")
    assertRejects(n, "https://www.linkedin.com/in/ab")
    assertRejects(n, "https://notlinkedin.com/in/jdoe")
    assertRejects(n, "https://www.linkedin.com.example.com/in/jdoe")
    assertRejects(n, "jdoe")
    assertRejects(n, "")
  }

  @Test
  fun parsesYouTubeChannelUrls() {
    val n = SocialNetwork.YOUTUBE
    assertParses(n, "https://www.youtube.com/@jdoe", "@jdoe")
    assertParses(n, "youtube.com/@jdoe/videos", "@jdoe")
    assertParses(
        n,
        "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv",
        "channel/UCabcdefghijklmnopqrstuv")
    assertParses(n, "https://m.youtube.com/c/JaneDoe", "c/JaneDoe")
    assertParses(n, "https://www.youtube.com/user/jdoe?sub_confirmation=1", "user/jdoe")
  }

  @Test
  fun rejectsNonChannelYouTubeUrls() {
    val n = SocialNetwork.YOUTUBE
    assertRejects(n, "https://www.youtube.com/watch?v=abc123")
    assertRejects(n, "https://youtu.be/abc123")
    assertRejects(n, "https://www.youtube.com/channel/UCshort")
    assertRejects(n, "https://www.youtube.com/@ab")
  }

  @Test
  fun parsesFacebookProfileUrls() {
    val n = SocialNetwork.FACEBOOK
    assertParses(n, "https://www.facebook.com/jane.doe", "jane.doe")
    assertParses(
        n,
        "https://m.facebook.com/profile.php?id=123456789012345&ref=bookmarks",
        "profile.php?id=123456789012345")
    assertParses(n, "https://www.facebook.com/share/abc12XYZ/", "share/abc12XYZ")
  }

  @Test
  fun rejectsNonProfileFacebookUrls() {
    val n = SocialNetwork.FACEBOOK
    assertRejects(n, "https://www.facebook.com/groups/12345")
    assertRejects(n, "https://www.facebook.com/profile.php")
    assertRejects(n, "https://www.facebook.com/events/12345")
    assertRejects(n, "https://www.facebook.com/jdoe")
  }

  @Test
  fun parsesInstagramProfileUrls() {
    val n = SocialNetwork.INSTAGRAM
    assertParses(n, "https://www.instagram.com/jdoe", "jdoe")
    assertParses(n, "instagram.com/jane_doe.art/?hl=en", "jane_doe.art")
  }

  @Test
  fun rejectsNonProfileInstagramUrls() {
    val n = SocialNetwork.INSTAGRAM
    assertRejects(n, "https://www.instagram.com/p/AbC123/")
    assertRejects(n, "https://www.instagram.com/explore")
    assertRejects(n, "https://www.example.com/jdoe")
  }

  @Test
  fun profileUrlRoundTrips() {
    mapOf(
            SocialNetwork.LINKEDIN to "jane-doe",
            SocialNetwork.YOUTUBE to "channel/UCabcdefghijklmnopqrstuv",
            SocialNetwork.FACEBOOK to "profile.php?id=123456789012345",
            SocialNetwork.INSTAGRAM to "jane_doe.art",
        )
        .forEach { (network, value) ->
          assertEquals(value, network.parse(network.profileUrl(value)))
        }
  }

  @Test
  fun matchesCaseInsensitively() {
    assertEquals("JDoe", SocialNetwork.LINKEDIN.parse("HTTPS://WWW.LINKEDIN.COM/IN/JDoe"))
  }

  @Test
  fun rejectsOverlongInput() {
    assertNull(SocialNetwork.LINKEDIN.parse("https://www.linkedin.com/in/jdoe?" + "x".repeat(500)))
  }

  @Test
  fun validatesStoredValues() {
    assertTrue(SocialNetwork.INSTAGRAM.isValidValue("jdoe"))
    assertFalse(SocialNetwork.INSTAGRAM.isValidValue("https://www.instagram.com/jdoe"))
    assertTrue(SocialNetwork.FACEBOOK.isValidValue("profile.php?id=123456789012345"))
    assertFalse(SocialNetwork.FACEBOOK.isValidValue("groups"))
  }

  @Test
  fun findsNetworkBySettingKey() {
    assertEquals(SocialNetwork.YOUTUBE, SocialNetwork.fromSettingKey("social_youtube"))
    assertNull(SocialNetwork.fromSettingKey("communication_preference"))
  }
}
