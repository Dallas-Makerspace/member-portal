package org.dallasmakerspace.agenttools.activity

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.dallasmakerspace.agent.AgentAccess
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

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
  fun `the offer is decided once per few minutes, for members with and without access`() = blocking<Unit> {
    val agentAccess = mock<AgentAccess> { onBlocking { groupsOf("chair") } doReturn listOf("Infrastructure"); onBlocking { groupsOf("plain") } doReturn emptyList() }
    var now = 0L
    val access = activityAccess(emptyList(), agentAccess = agentAccess).also { it.clock = { now } }

    repeat(3) {
      assertTrue(access.isOffered("chair"))
      assertFalse(access.isOffered("plain"))
    }
    verify(agentAccess, times(1)).groupsOf("chair")
    verify(agentAccess, times(1)).groupsOf("plain")

    now = ActivityAccess.OFFER_TTL_MILLIS
    assertTrue(access.isOffered("chair"))
    verify(agentAccess, times(2)).groupsOf("chair")
  }

  @Test
  fun `every tool call is checked against current groups, not the cached offer`() = blocking<Unit> {
    var groups = listOf("Infrastructure")
    val agentAccess = mock<AgentAccess> { onBlocking { groupsOf(any()) } doAnswer { groups } }
    val access = activityAccess(emptyList(), agentAccess = agentAccess)

    assertTrue(access.isOffered("jdoe"))
    groups = emptyList()

    assertTrue(access.isOffered("jdoe"))
    assertEquals(emptyList(), access.areasOf("jdoe"))
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
