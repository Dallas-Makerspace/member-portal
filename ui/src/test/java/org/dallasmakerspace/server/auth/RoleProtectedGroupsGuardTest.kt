package org.dallasmakerspace.server.auth

import org.dallasmakerspace.models.ProtectedGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Keeps the UI role mapping and the shared protected-group list from drifting: the archive
 * workflow refuses to archive a protected group, so any group name the role mapping depends on must
 * be in [ProtectedGroups].
 */
@RunWith(JUnit4::class)
class RoleProtectedGroupsGuardTest {

  private class RecordingGroups : AbstractList<String>() {
    val queried = mutableListOf<String>()

    override val size = 0

    override fun get(index: Int): String = throw IndexOutOfBoundsException()

    override fun contains(element: String): Boolean {
      queried += element
      return false
    }
  }

  private fun groupsTheRoleMappingLooksFor(): List<String> {
    val recorder = RecordingGroups()
    Role.fromKeycloakGroups(recorder)
    return recorder.queried.map { it.removePrefix("/") }.distinct()
  }

  @Test
  fun everyGroupTheRoleMappingUsesIsProtected() {
    val used = groupsTheRoleMappingLooksFor()
    assertTrue("role mapping should look at some groups", used.isNotEmpty())
    val unprotected = used.filterNot { ProtectedGroups.isProtected(it) }
    assertEquals(
        "Add these to ProtectedGroups.ROLE_GROUPS (common-models): $unprotected",
        emptyList<String>(),
        unprotected)
  }

  @Test
  fun theGuardSeesTheWholeMapping() {
    assertTrue(groupsTheRoleMappingLooksFor().containsAll(listOf("Infrastructure", "Board")))
  }
}
