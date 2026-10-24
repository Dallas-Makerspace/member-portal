package org.dallasmakerspace.config

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

/** Guards the rollout convention for flags that are new and not yet on for everyone. */
class NewFeatureFlagDefaultsTest {

  @Test
  fun `the committee follow flag starts as a pilot for the early access group, off for everyone else`() {
    val default = ConfigRegistry.COMMITTEE_FOLLOW_ENABLED.defaultValue

    assertFalse(default.enabled)
    assertEquals(setOf("mandarlimaye"), default.enabledUsernames)
    assertEquals(setOf("IT Early Access"), default.enabledGroupNames)
  }

  @Test
  fun `the bambu notifications flag starts as a pilot for the early access group, off for everyone else`() {
    val default = ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED.defaultValue

    assertFalse(default.enabled)
    assertEquals(setOf("mandarlimaye"), default.enabledUsernames)
    assertEquals(setOf("IT Early Access"), default.enabledGroupNames)
  }
}
