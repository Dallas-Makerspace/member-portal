package org.dallasmakerspace.server.memberservice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.dallasmakerspace.server.auth.Authz
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

/** Renders the real settings page to check the contact preference cards. */
class SettingsTemplateTest {

  private val engine =
      TemplateEngine().apply {
        setTemplateResolver(
            ClassLoaderTemplateResolver().apply {
              prefix = "templates/"
              suffix = ".html"
              characterEncoding = "UTF-8"
            })
      }

  private fun render(
      saved: String = "",
      isSelf: Boolean = true,
      talkLinked: Boolean? = true,
      discordLinked: Boolean? = false,
  ): String {
    val context =
        Context().apply {
          setVariables(
              mapOf(
                  "preferred_username" to "jdoe",
                  "is_self" to isSelf.toString(),
                  "authz" to Authz(emptySet()),
                  "contact_options" to
                      contactOptions(talkLinked, "jdoe-talk", discordLinked, null),
                  "communication_preference" to saved,
                  "member_loaded" to (talkLinked != null),
                  "notification_preferences_enabled" to false,
                  "settings_history" to emptyList<Map<String, Any?>>(),
              ))
        }
    return engine.process("settings", context)
  }

  private fun radio(html: String, value: String): String =
      Regex("<input[^>]*value=\"$value\"[^>]*>").find(html)!!.value

  @Test
  fun noCardIsSelectedWhenNothingIsSaved() {
    val html = render()

    assertEquals(5, Regex("type=\"radio\"").findAll(html).count())
    assertFalse(Regex("<input[^>]*type=\"radio\"[^>]*checked").containsMatchIn(html))
    assertFalse(html.contains("No preference"))
  }

  @Test
  fun savedCardIsSelected() {
    val html = render(saved = "SMS")

    assertTrue(radio(html, "SMS").contains("checked"))
    assertFalse(radio(html, "EMAIL").contains("checked"))
  }

  @Test
  fun linkStateShowsOnTalkAndDiscordOnly() {
    val html = render()

    assertTrue(html.contains("Linked as @jdoe-talk"))
    assertTrue(html.contains("Not linked yet"))
    assertTrue(radio(html, "DISCORD").contains("data-link-state=\"unlinked\""))
    assertFalse(radio(html, "EMAIL").contains("data-link-state"))
  }

  @Test
  fun unlinkedWarningOffersLinkToSelf() {
    val html = render()

    assertTrue(html.contains("href=\"/link-discord?pref=DISCORD\""))
    assertFalse(html.contains("link-discourse?pref="))
  }

  @Test
  fun unlinkedWarningHasNoLinkWhenAdminViewsAnotherMember() {
    val html = render(isSelf = false)

    assertTrue(html.contains("This member hasn't linked"))
    assertFalse(html.contains("link-discord?pref="))
  }

  @Test
  fun linkStateIsUnavailableWhenMemberDidNotLoad() {
    val html = render(talkLinked = null, discordLinked = null)

    assertTrue(html.contains("Link status unavailable"))
    assertFalse(html.contains("contact-option-warning"))
  }
}
