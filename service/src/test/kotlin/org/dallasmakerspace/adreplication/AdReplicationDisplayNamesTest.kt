package org.dallasmakerspace.adreplication

import kotlin.test.Test
import kotlin.test.assertEquals

class AdReplicationDisplayNamesTest {

  @Test
  fun `shortDcName strips a DNS domain suffix and uppercases`() {
    assertEquals("DC1", shortDcName("dc1.dms.local"))
  }

  @Test
  fun `shortDcName is a no-op (aside from uppercasing) when there is no domain suffix`() {
    assertEquals("DC2", shortDcName("DC2"))
    assertEquals("DC3", shortDcName("dc3"))
  }

  @Test
  fun `shortDcName only strips the first label, not multi-level suffixes`() {
    assertEquals("DC1", shortDcName("dc1.infra.dms.local"))
  }
}
