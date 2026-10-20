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
  fun unlinkedNoteIsNotDescribingTheRadioUntilSelected() {
    val html = render()

    assertFalse(radio(html, "DISCORD").contains("_warn"))
    assertTrue(html.contains("id=\"contact_DISCORD_warn\""))
  }

  @Test
  fun bannerReminderCopyShowsOnlyToTheMemberThemselves() {
    assertTrue(render().contains("also clears the reminder"))
    assertFalse(render(isSelf = false).contains("clears the reminder"))
    assertFalse(render(saved = "EMAIL").contains("clears the reminder"))
  }

  @Test
  fun unlinkedNoteDoesNotTalkAboutSavingBecauseChoicesSaveThemselves() {
    for (saved in listOf("", "DISCORD")) {
      val html = render(saved = saved)
      val text = html.replace(Regex("\\s+"), " ")

      assertTrue(text.contains("Members won't see this until you link your"))
      assertFalse(text.contains("You can save this now"))
    }
    assertTrue(radio(render(saved = "DISCORD"), "DISCORD").contains("checked"))
  }

  @Test
  fun linkButtonWaitsForPendingAutosave() {
    assertTrue(Regex("<a[^>]*data-autosave-wait[^>]*link-discord").containsMatchIn(render()))
  }

  @Test
  fun contactPreferenceAutosavesWithoutASaveButton() {
    val html = render()
    val withoutNoscript = html.replace(Regex("(?s)<noscript>.*?</noscript>"), "")

    assertTrue(html.contains("data-autosave"))
    assertFalse(withoutNoscript.contains("id=\"saveBtn\""))
    assertFalse(Regex(">\\s*Save\\s*<").containsMatchIn(withoutNoscript))
  }

  private fun renderFull(saved: String = "", withHistory: Boolean = true): String {
    val context =
        Context().apply {
          setVariables(
              mapOf(
                  "preferred_username" to "jdoe",
                  "is_self" to "true",
                  "authz" to Authz(emptySet()),
                  "contact_options" to contactOptions(true, "jdoe-talk", true, "jdoe-chat"),
                  "communication_preference" to saved,
                  "member_loaded" to true,
                  "discourse_linked" to true,
                  "discord_linked" to true,
                  "discourse_username" to "jdoe-talk",
                  "discord_username" to "jdoe-chat",
                  "notification_preferences_enabled" to true,
                  "preference_topics" to
                      listOf(
                          mapOf(
                              "topic" to "CLASS_UPDATES",
                              "topicDisplayName" to "Class updates",
                              "enabled" to true,
                              "digestSupported" to true,
                              "effectiveAggregation" to "IMMEDIATE",
                              "effectiveAggregationDisplay" to "Immediately")),
                  "aggregation_options" to mapOf("IMMEDIATE" to "Immediately").entries.toList(),
                  "digest_schedule" to "",
                  "suppressed" to false,
                  "suggest_daily_digest" to false,
                  "suggestion_threshold" to 5,
                  "settings_history" to
                      if (withHistory) {
                        listOf(
                            mapOf(
                                "updated_at" to "2026-01-01T00:00:00Z",
                                "label" to "Contact preference",
                                "display_value" to "Email",
                                "is_cleared" to false,
                                "updated_by" to "jdoe"))
                      } else {
                        emptyList()
                      },
              ))
        }
    return engine.process("settings", context)
  }

  private fun sectionBodyClass(html: String, id: String): String =
      Regex("<div class=\"([^\"]*)\" id=\"$id-body\"").find(html)!!.groupValues[1]

  @Test
  fun everySectionIsACollapsibleCardWithItsOwnDeepLinkId() {
    val html = renderFull()
    val ids =
        Regex("<section[^>]*id=\"([^\"]+)\"[^>]*data-settings-section")
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()

    assertEquals(
        listOf(
            "contact-preference",
            "linked-accounts",
            "notification-preferences",
            "settings-history"),
        ids)
    ids.forEach { id ->
      assertTrue(html.contains("aria-controls=\"$id-body\""), "toggle for $id")
      assertTrue(html.contains("data-bs-target=\"#$id-body\""), "target for $id")
      sectionBodyClass(html, id)
    }
  }

  @Test
  fun onlyTheContactCardWithAPendingChoiceStartsOpen() {
    val unset = renderFull(saved = "")
    assertEquals("collapse show", sectionBodyClass(unset, "contact-preference"))
    assertTrue(unset.contains("aria-expanded=\"true\""))
    listOf("linked-accounts", "notification-preferences", "settings-history").forEach {
      assertEquals("collapse", sectionBodyClass(unset, it))
    }

    assertEquals("collapse", sectionBodyClass(renderFull(saved = "EMAIL"), "contact-preference"))
  }

  @Test
  fun absentSectionsAreNotRendered() {
    val html = renderFull(withHistory = false)

    assertFalse(html.contains("id=\"settings-history\""))
    assertTrue(html.contains("id=\"contact-preference\""))
  }

  @Test
  fun notificationRowsAutosaveIndividuallyWithoutASaveButton() {
    val html = renderFull()
    val withoutNoscript = html.replace(Regex("(?s)<noscript>.*?</noscript>"), "")

    assertTrue(html.contains("data-autosave-scope=\"tr\""))
    assertFalse(withoutNoscript.contains("Save preferences"))
    assertTrue(html.contains("name=\"form\" value=\"notifications\""))
  }

  @Test
  fun noneCardCanBeSelected() {
    assertTrue(radio(render(saved = "NONE"), "NONE").contains("checked"))
  }

  @Test
  fun linkStateIsUnavailableWhenMemberDidNotLoad() {
    val html = render(talkLinked = null, discordLinked = null)

    assertTrue(html.contains("Link status unavailable"))
    assertFalse(html.contains("contact-option-warning"))
  }
}
