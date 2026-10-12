package org.dallasmakerspace.server.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import org.dallasmakerspace.models.DMSGroup

class ProfileGroupsTest {
  private fun group(name: String, dn: String) =
      DMSGroup(name = name, distinguishedName = dn, objectGuid = null)

  @Test
  fun keepsOnlyGroupsInTheGroupOusSortedByName() {
    val groups =
        listOf(
            group("Zeta", "CN=Zeta,OU=Groups,DC=dms,DC=local"),
            group("Builtin Admins", "CN=Builtin Admins,CN=Builtin,DC=dms,DC=local"),
            group("Alpha", "CN=Alpha,OU=Groups,OU=Admin,DC=dms,DC=local"),
            group("Domain Users", "CN=Domain Users,CN=Users,DC=dms,DC=local"),
        )

    assertEquals(listOf("Alpha", "Zeta"), profileDisplayGroups(groups).map { it.name })
  }

  @Test
  fun returnsNothingWhenNoGroupIsInTheGroupOus() {
    val groups = listOf(group("Domain Users", "CN=Domain Users,CN=Users,DC=dms,DC=local"))

    assertEquals(emptyList(), profileDisplayGroups(groups))
  }
}
