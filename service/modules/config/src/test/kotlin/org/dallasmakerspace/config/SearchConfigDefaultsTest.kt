package org.dallasmakerspace.config

import kotlin.test.assertTrue
import org.junit.Test

class SearchConfigDefaultsTest {

  @Test
  fun `every default related-terms line groups at least two terms`() {
    val lines = ConfigRegistry.SEARCH_RELATED_TERMS.defaultValue.lines().filter { it.isNotBlank() }

    assertTrue(lines.isNotEmpty())
    lines.forEach { line ->
      assertTrue(line.split(',').count { it.isNotBlank() } >= 2, "too few terms in: $line")
    }
  }

  @Test
  fun `related terms can be edited in Site Config`() {
    assertTrue(ConfigRegistry.SEARCH_RELATED_TERMS in ConfigRegistry.ALL)
  }
}
