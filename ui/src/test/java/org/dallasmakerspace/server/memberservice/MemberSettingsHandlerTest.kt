package org.dallasmakerspace.server.memberservice

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
