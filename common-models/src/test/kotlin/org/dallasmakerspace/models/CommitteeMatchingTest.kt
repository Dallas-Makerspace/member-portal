package org.dallasmakerspace.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitteeMatchingTest {

  private fun names(group: String) = Committees.forGroup(group).map { it.name }

  @Test
  fun matchesByPrefixIgnoringCase() {
    assertEquals(listOf("Laser"), names("Laser Basics"))
    assertEquals(listOf("Laser"), names("LASER advanced"))
  }

  @Test
  fun committeeWithSeveralPrefixesMatchesAnyOfThem() {
    assertEquals(listOf("Jewelry"), names("Lapidary Cabbing"))
    assertEquals(listOf("Woodshop"), names("WS Table Saw"))
    assertEquals(listOf("Woodshop"), names("Woodshop Basics"))
  }

  @Test
  fun shortPrefixesMatchAnyGroupThatStartsWithThem() {
    assertEquals(listOf("3D Fab"), names("3D Printing Intro"))
    // Known limit: "CA" is a plain prefix, so any group starting with it matches.
    assertEquals(listOf("Creative Arts"), names("Calendar Admins"))
  }

  @Test
  fun unrelatedAndBlankGroupsMatchNothing() {
    assertTrue(names("Board of Directors").isEmpty())
    assertTrue(Committees.forGroup("").isEmpty())
    assertTrue(Committees.forGroup("   ").isEmpty())
  }

  @Test
  fun committeesWithoutPrefixesNeverMatch() {
    val vector = Committees.findByName("VECTOR")!!
    assertTrue(vector.groupPrefixes.isEmpty())
    assertFalse(vector.ownsGroup("VECTOR Basics"))
    assertFalse(vector.ownsGroup(""))
  }

  @Test
  fun inactiveCommitteesAreSkipped() {
    val inactive = Committee(id = 999, name = "Gone", chairGroupName = null, groupPrefixes = listOf("Gone"), isActive = false)
    assertTrue(inactive.ownsGroup("Gone Basics"))
    assertTrue(names("Gone Basics").isEmpty())
  }
}
