package org.dallasmakerspace.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveFlagTest {
  @Test
  fun `the archive flag is a registered feature flag that defaults off`() {
    val key = ConfigRegistry.GROUPS_ARCHIVE_ENABLED

    assertEquals("groups.archive.enabled", key.key)
    assertEquals("Groups", key.category)
    assertEquals(ConfigValueType.FeatureFlagType, key.type)
    assertFalse(key.defaultValue.enabled)
    assertTrue(key.description.contains("piloted"))
    assertTrue(ConfigRegistry.ALL.contains(key) || ConfigRegistry.ALL.any { it.key == key.key })
  }
}
