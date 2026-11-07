package org.dallasmakerspace.agenttools

import kotlin.test.assertEquals
import org.junit.Test

class EntityLinksTest {
  @Test
  fun `member links use the profile path and fall back to the username`() {
    assertEquals("[Jane Doe](/profile/@jdoe)", memberLink("jdoe", "Jane Doe"))
    assertEquals("[jdoe](/profile/@jdoe)", memberLink("jdoe"))
  }

  @Test
  fun `group links survive markdown and match the portal's slug decoding`() {
    assertEquals(
        "[Example 102 (Lift Training)](/groups/Example+102+%28Lift+Training%29)",
        groupLink("Example 102 (Lift Training)"))
    assertEquals("[A (b) c](/groups/A+%5Bb%5D+c)", groupLink("A [b] c"))
  }
}
