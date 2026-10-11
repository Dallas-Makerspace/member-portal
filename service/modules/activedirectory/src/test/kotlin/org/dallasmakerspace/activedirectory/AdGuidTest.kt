package org.dallasmakerspace.activedirectory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdGuidTest {
  private val canonical = "33221100-5544-7766-8899-aabbccddeeff"

  @Test
  fun `fromBytes swaps the first three fields`() {
    val bytes = ByteArray(16) { (if (it < 8) it * 0x11 else 0x88 + (it - 8) * 0x11).toByte() }
    assertEquals(canonical, AdGuid.fromBytes(bytes))
  }

  @Test
  fun `fromBytes rejects null and wrong length`() {
    assertNull(AdGuid.fromBytes(null))
    assertNull(AdGuid.fromBytes(ByteArray(15)))
    assertNull(AdGuid.fromBytes(ByteArray(17)))
  }

  @Test
  fun `normalize lowercases and trims canonical input`() {
    assertEquals(canonical, AdGuid.normalize("  33221100-5544-7766-8899-AABBCCDDEEFF "))
  }

  @Test
  fun `normalize accepts braces and bare hex`() {
    assertEquals(canonical, AdGuid.normalize("{33221100-5544-7766-8899-aabbccddeeff}"))
    assertEquals(canonical, AdGuid.normalize("33221100554477668899AABBCCDDEEFF"))
  }

  @Test
  fun `normalize rejects garbage`() {
    assertNull(AdGuid.normalize(null))
    assertNull(AdGuid.normalize(""))
    assertNull(AdGuid.normalize("mock-guid"))
    assertNull(AdGuid.normalize("33221100-5544-7766-8899-aabbccddeef"))
    assertNull(AdGuid.normalize("33221100-5544-7766-8899-aabbccddeegg"))
    assertNull(AdGuid.normalize("���"))
  }

  @Test
  fun `fromBytes output is stable under normalize`() {
    val guid = AdGuid.fromBytes(ByteArray(16) { it.toByte() })
    assertEquals(guid, AdGuid.normalize(guid))
  }
}
