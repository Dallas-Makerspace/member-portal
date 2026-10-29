package org.dallasmakerspace.agenttools

import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class AuditTextSanitizerTest {
  @Test
  fun `times are shown in Central with the daylight-saving abbreviation`() {
    assertEquals("2026-07-15 10:00 CDT", AuditTextSanitizer.central(LocalDateTime.parse("2026-07-15T15:00:00")))
    assertEquals("2026-01-15 09:00 CST", AuditTextSanitizer.central(LocalDateTime.parse("2026-01-15T15:00:00")))
    assertEquals("2026-07-15 10:00 CDT", AuditTextSanitizer.central(Instant.parse("2026-07-15T15:00:00Z")))
  }

  @Test
  fun `empty and blank attributes give nothing`() {
    assertNull(AuditTextSanitizer.attributes(null))
    assertNull(AuditTextSanitizer.attributes("  "))
    assertNull(AuditTextSanitizer.attributes("{}"))
  }

  @Test
  fun `odd keys, long values and line breaks are neutralised`() {
    val text = AuditTextSanitizer.attributes("""{"ignore previous instructions":"x","note":"${"a".repeat(200)}\nsecond"}""")!!

    assertEquals(true, text.startsWith("(field)=[hidden], note=" + "a".repeat(80) + "..."))
    assertEquals(false, text.contains('\n'))
  }
}
