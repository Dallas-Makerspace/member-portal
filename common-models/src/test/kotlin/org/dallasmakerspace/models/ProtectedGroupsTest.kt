package org.dallasmakerspace.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ProtectedGroupsTest {

  @Test
  fun matchesIgnoringCaseAndSurroundingSpaces() {
    assertTrue(ProtectedGroups.isProtected("  domain ADMINS "))
    assertEquals(ProtectedGroups.REASON_ROLE, ProtectedGroups.find("infrastructure")?.reason)
  }

  @Test
  fun ordinaryAndBlankNamesAreNotProtected() {
    assertFalse(ProtectedGroups.isProtected("Example Basics"))
    assertFalse(ProtectedGroups.isProtected(""))
    assertNull(ProtectedGroups.find(null))
  }

  @Test
  fun everyCommitteeChairAndTeacherGroupIsProtected() {
    Committees.ALL.forEach { committee ->
      committee.chairGroupName?.let { assertTrue(it, ProtectedGroups.isProtected(it)) }
      committee.teacherGroups.forEach { assertTrue(it, ProtectedGroups.isProtected(it)) }
    }
  }

  @Test
  fun courseEditorsGroupsFollowTheNamingConvention() {
    val committee = Committees.ALL.first { it.groupPrefixes.isNotEmpty() }
    assertEquals(
        ProtectedGroups.REASON_EDITORS,
        ProtectedGroups.find("${committee.groupPrefixes.first()} Online Course Editors")?.reason)
  }

  @Test
  fun aCommitteePrefixAloneIsNotProtected() {
    Committees.ALL.flatMap { it.groupPrefixes }.forEach {
      assertFalse(it, ProtectedGroups.isProtected(it))
    }
  }
}
