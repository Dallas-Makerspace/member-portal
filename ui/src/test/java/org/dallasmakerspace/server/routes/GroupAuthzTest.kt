package org.dallasmakerspace.server.routes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class GroupAuthzTest {

  @Test
  fun canManageGroupAllowsOfficerOnly() =
      assertTrue(canManageGroup(hasManageGroupsPermission = true, isGroupAdmin = false))

  @Test
  fun canManageGroupAllowsGroupAdminOnly() =
      assertTrue(canManageGroup(hasManageGroupsPermission = false, isGroupAdmin = true))

  @Test
  fun canManageGroupAllowsBoth() =
      assertTrue(canManageGroup(hasManageGroupsPermission = true, isGroupAdmin = true))

  @Test
  fun canManageGroupDeniesNeither() =
      assertFalse(canManageGroup(hasManageGroupsPermission = false, isGroupAdmin = false))
}
