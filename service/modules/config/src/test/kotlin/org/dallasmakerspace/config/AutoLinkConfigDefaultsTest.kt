package org.dallasmakerspace.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoLinkConfigDefaultsTest {
  @Test
  fun `the kill switch is a registered boolean that defaults on`() {
    val key = ConfigRegistry.DISCOURSE_AUTO_LINK_ENABLED

    assertEquals("discourse.auto-link.enabled", key.key)
    assertEquals(ConfigValueType.BooleanType, key.type)
    assertTrue(key.defaultValue)
    assertTrue(ConfigRegistry.ALL.any { it.key == key.key })
  }

  @Test
  fun `the nightly cap is a registered int that defaults to 20`() {
    val key = ConfigRegistry.DISCOURSE_AUTO_LINK_NIGHTLY_CAP

    assertEquals("discourse.auto-link.nightly-cap", key.key)
    assertEquals(ConfigValueType.IntType, key.type)
    assertEquals(20, key.defaultValue)
    assertTrue(ConfigRegistry.ALL.any { it.key == key.key })
  }
}
