package org.dallasmakerspace.bambuddy.notifications

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

class BambuTtlTest {

  @Test
  fun `revision 3 worked examples`() {
    assertEquals(80, BambuTtl.ttlMinutes(20)) // 20m + 1h grace (10% clamped up)
    assertEquals(360, BambuTtl.ttlMinutes(300)) // 5h + 1h (10% = 30m, clamped up)
    assertEquals(660, BambuTtl.ttlMinutes(600)) // 10h + 1h (10% = 60m, exactly at floor)
    assertEquals(1320, BambuTtl.ttlMinutes(1200)) // 20h + 2h
    assertEquals(2580, BambuTtl.ttlMinutes(2400)) // 40h + 3h (10% = 4h, capped)
  }

  @Test
  fun `grace floor and cap boundaries`() {
    assertEquals(61, BambuTtl.ttlMinutes(1)) // shortest job still gets the 60m floor
    assertEquals(659, BambuTtl.ttlMinutes(599)) // ceil(59.9) = 60 -> floor
    assertEquals(660, BambuTtl.ttlMinutes(600))
    assertEquals(662, BambuTtl.ttlMinutes(601)) // ceil(60.1) = 61
    assertEquals(1979, BambuTtl.ttlMinutes(1799)) // ceil(179.9) = 180 -> cap reached exactly
    assertEquals(1980, BambuTtl.ttlMinutes(1800))
    assertEquals(1981, BambuTtl.ttlMinutes(1801)) // ceil(180.1) = 181, clamped to 180
    assertEquals(10180, BambuTtl.ttlMinutes(10_000)) // very long jobs keep the 180m cap
  }

  @Test
  fun `non-positive remaining is rejected - a watch never arms with a non-positive TTL`() {
    assertFailsWith<IllegalArgumentException> { BambuTtl.ttlMinutes(0) }
    assertFailsWith<IllegalArgumentException> { BambuTtl.ttlMinutes(-30) }
  }

  @Test
  fun `estimate minutes comes from slicer seconds, rounded up`() {
    assertNull(BambuTtl.estimateMinutes(null))
    assertNull(BambuTtl.estimateMinutes(0))
    assertNull(BambuTtl.estimateMinutes(-90))
    assertEquals(1, BambuTtl.estimateMinutes(59))
    assertEquals(1, BambuTtl.estimateMinutes(60))
    assertEquals(2, BambuTtl.estimateMinutes(61))
    assertEquals(60, BambuTtl.estimateMinutes(3600))
  }
}
