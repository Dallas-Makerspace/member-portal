package org.dallasmakerspace.bambuddy.notifications

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class BambuJobNamesTest {

  @Test
  fun `normalizes basename, extensions, underscores, whitespace and case`() {
    assertEquals("cable clip v2", normalizeJobName("Cable_Clip_v2"))
    assertEquals("cable clip v2", normalizeJobName("  Cable  Clip   v2 "))
    assertEquals("cable clip v2", normalizeJobName("/data/prints/Cable_Clip_v2.gcode.3mf"))
    assertEquals("cable clip v2", normalizeJobName("C:\\prints\\Cable_Clip_v2.GCODE"))
    assertEquals("cable clip v2", normalizeJobName("Cable_Clip_v2.3mf"))
  }

  @Test
  fun `blank names normalize to null and never match`() {
    assertNull(normalizeJobName(null))
    assertNull(normalizeJobName("  "))
    assertNull(normalizeJobName(".3mf"))
    assertFalse(sameJobName(null, null))
    assertFalse(sameJobName("", ""))
  }

  @Test
  fun `webhook and queue forms of the same job match, different jobs do not`() {
    assertTrue(sameJobName("Cable Clip v2", "Cable_Clip_v2"))
    assertTrue(sameJobName("Calibration Cube", "calibration_cube.gcode.3mf"))
    assertFalse(sameJobName("Cable Clip v2", "Cable_Clip_v3"))
  }
}
