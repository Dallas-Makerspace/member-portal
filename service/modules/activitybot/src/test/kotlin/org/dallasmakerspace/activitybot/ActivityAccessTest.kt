package org.dallasmakerspace.activitybot

import kotlin.test.assertEquals
import org.junit.Test

class ActivityAccessTest {
  private val auto = Areas.ALL.single { it.key == "auto" }

  @Test
  fun `the configured groups can ask about every area`() = blocking {
    assertEquals(Areas.ALL, activityAccess(listOf("Infrastructure")).areasFor(listOf("Infrastructure")))
    assertEquals(Areas.ALL, activityAccess(listOf("president")).areasOf("jdoe"))
  }

  @Test
  fun `a committee chair can ask only about their committee's areas`() = blocking {
    assertEquals(listOf(auto), activityAccess(listOf("Automotive Committee Chair")).areasOf("jdoe"))
    assertEquals(emptyList(), activityAccess(listOf("Woodshop Committee Chair")).areasOf("jdoe"))
  }

  @Test
  fun `an ordinary member can ask about nothing`() = blocking {
    assertEquals(emptyList(), activityAccess(listOf("Automotive 102 (Lift Training)")).areasOf("jdoe"))
  }

  @Test
  fun `a blank list leaves only the chairs`() = blocking {
    val access = activityAccess(listOf("Infrastructure"), configured = " , ")

    assertEquals(emptyList(), access.areasOf("jdoe"))
  }

  @Test
  fun `the area belongs to the Automotive committee and its tool exists`() {
    assertEquals("Automotive Committee Chair", auto.chairGroup)
    assertEquals(listOf("auto-lift-1"), auto.tools.map { it.interlockTag })
  }
}
