package org.dallasmakerspace.server.memberservice

import org.dallasmakerspace.models.CommunicationPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class MemberSettingsHandlerTest {

  @Test
  fun historyRowShowsContactPreferenceLabel() {
    val row =
        settingsHistoryRow(
            mapOf("setting_key" to "communication_preference", "setting_value" to "DISCOURSE"))

    assertEquals("Communication preference", row["label"])
    assertEquals("Talk", row["display_value"])
    assertEquals(false, row["is_cleared"])
  }

  @Test
  fun historyRowShowsClearedContactPreference() {
    val row =
        settingsHistoryRow(
            mapOf("setting_key" to "communication_preference", "setting_value" to null))

    assertEquals("No preference", row["display_value"])
    assertEquals(true, row["is_cleared"])
  }

  @Test
  fun historyRowShowsDeliberateNoContactMethod() {
    val row =
        settingsHistoryRow(
            mapOf("setting_key" to "communication_preference", "setting_value" to "NONE"))

    assertEquals("None", row["display_value"])
    assertEquals(false, row["is_cleared"])
  }

  @Test
  fun contactOptionsCoverEveryPreferenceWithNoneLast() {
    val options = contactOptions(null, null, null, null)

    assertEquals(CommunicationPreference.entries.map { it.name }, options.map { it["value"] })
    assertEquals("NONE", options.last()["value"])
  }

  @Test
  fun contactOptionsShowLinkStateOnlyForTalkAndDiscord() {
    val byValue =
        contactOptions(
                talkLinked = true,
                talkUsername = "jdoe",
                discordLinked = false,
                discordUsername = null)
            .associateBy { it["value"] }

    assertEquals("linked", byValue.getValue("DISCOURSE")["link_state"])
    assertEquals("@jdoe", byValue.getValue("DISCOURSE")["link_detail"])
    assertEquals("unlinked", byValue.getValue("DISCORD")["link_state"])
    assertNull(byValue.getValue("DISCORD")["link_detail"])
    assertNull(byValue.getValue("EMAIL")["link_state"])
    assertNull(byValue.getValue("SMS")["link_state"])
  }

  @Test
  fun contactOptionsOmitLinkStateWhenMemberNotLoaded() {
    val byValue = contactOptions(null, null, null, null).associateBy { it["value"] }

    assertNull(byValue.getValue("DISCOURSE")["link_state"])
    assertNull(byValue.getValue("DISCORD")["link_state"])
  }

  @Test
  fun historyRowShowsSocialLinkUrl() {
    val row =
        settingsHistoryRow(mapOf("setting_key" to "social_instagram", "setting_value" to "jdoe"))

    assertEquals("Instagram", row["label"])
    assertEquals("https://www.instagram.com/jdoe", row["display_value"])
    assertEquals("jdoe", row["setting_value"])
  }

  @Test
  fun historyRowShowsRemovedSocialLink() {
    val row = settingsHistoryRow(mapOf("setting_key" to "social_youtube", "setting_value" to ""))

    assertEquals("YouTube", row["label"])
    assertEquals("Removed", row["display_value"])
    assertEquals(true, row["is_cleared"])
  }

  @Test
  fun updatedToastNamesTheChange() {
    assertEquals("Notification preferences updated.", settingsUpdatedToast("notifications"))
    assertEquals(
        "Talk account unlinked.", settingsUpdatedToast(MemberSettingsHandler.TALK_UNLINKED))
    assertEquals(
        "Discord account unlinked.", settingsUpdatedToast(MemberSettingsHandler.DISCORD_UNLINKED))
    assertNull(settingsUpdatedToast("something-else"))
    assertNull(settingsUpdatedToast(null))
  }
}
