package org.dallasmakerspace.server.routes

import io.ktor.http.parametersOf
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class SocialLinksTest {

  @Test
  fun displayLinksKeepsNetworkOrderAndSkipsInvalidValues() {
    val links =
        SocialLinks.displayLinks(
            mapOf(
                "social_instagram" to "jdoe",
                "social_linkedin" to "jane-doe",
                "social_facebook" to "groups",
                "communication_preference" to "EMAIL"))

    assertEquals(listOf("LinkedIn", "Instagram"), links.map { it["name"] })
    assertEquals("https://www.linkedin.com/in/jane-doe", links[0]["url"])
    assertEquals("/static/images/instagram.svg", links[1]["icon"])
  }

  @Test
  fun formFieldsPrefillCurrentLinks() {
    val fields = SocialLinks.formFields(mapOf("social_youtube" to "@jdoe"))

    assertEquals(
        listOf("social_linkedin", "social_youtube", "social_facebook", "social_instagram"),
        fields.map { it["key"] })
    assertEquals("https://www.youtube.com/@jdoe", fields[1]["value"])
    assertEquals("", fields[0]["value"])
  }

  @Test
  fun formFieldsHaveHintsAndOfficialHelpLinks() {
    val fields = SocialLinks.formFields(emptyMap())

    assertTrue(fields.all { it.getValue("hint").isNotBlank() })
    assertEquals(
        listOf("www.linkedin.com", "support.google.com", "www.facebook.com", null),
        fields.map { field -> field.getValue("help_url").ifEmpty { null }?.let { URI(it).host } })
  }

  @Test
  fun parseFormStoresHandlesAndClearsBlankFields() {
    val updates =
        SocialLinks.parseForm(
            parametersOf(
                "form" to listOf("socials"),
                "social_linkedin" to listOf("https://www.linkedin.com/in/jdoe/"),
                "social_youtube" to listOf("  "),
                "social_instagram" to listOf("instagram.com/jdoe")))

    assertEquals(
        mapOf("social_linkedin" to "jdoe", "social_youtube" to "", "social_instagram" to "jdoe"),
        updates)
  }

  @Test
  fun parseFormRejectsAnyInvalidLink() {
    assertNull(
        SocialLinks.parseForm(
            parametersOf(
                "social_linkedin" to listOf("https://www.linkedin.com/in/jdoe"),
                "social_facebook" to listOf("https://www.example.com/jdoe"))))
  }

  @Test
  fun parseFormIgnoresMissingFields() {
    assertEquals(emptyMap<String, String>(), SocialLinks.parseForm(parametersOf()))
  }
}
