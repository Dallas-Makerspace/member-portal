package org.dallasmakerspace.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DirectoryOusTest {

  @Test
  fun acceptsGroupsDirectlyInEitherOu() {
    assertTrue(DirectoryOus.isInGroupOu("CN=Test Group,OU=Groups,DC=dms,DC=local"))
    assertTrue(DirectoryOus.isInGroupOu("CN=Test Group,OU=Groups,OU=Admin,DC=dms,DC=local"))
  }

  @Test
  fun acceptsGroupsInNestedSubOus() {
    assertTrue(DirectoryOus.isInGroupOu("CN=Test Group,OU=Shop,OU=Groups,DC=dms,DC=local"))
    assertTrue(DirectoryOus.isInGroupOu("CN=Test Group,OU=Sub,OU=Groups,OU=Admin,DC=dms,DC=local"))
  }

  @Test
  fun ignoresCaseAndSpacingAfterCommas() {
    assertTrue(DirectoryOus.isInGroupOu("cn=Test Group, ou=groups, dc=DMS, dc=Local"))
  }

  @Test
  fun rejectsGroupsInOtherContainers() {
    assertFalse(DirectoryOus.isInGroupOu("CN=Test Group,CN=Builtin,DC=dms,DC=local"))
    assertFalse(DirectoryOus.isInGroupOu("CN=Test Group,CN=Users,DC=dms,DC=local"))
    assertFalse(DirectoryOus.isInGroupOu("CN=Test Group,OU=Admin,DC=dms,DC=local"))
  }

  @Test
  fun rejectsTheOuItselfAndLookalikeNames() {
    assertFalse(DirectoryOus.isInGroupOu("OU=Groups,DC=dms,DC=local"))
    assertFalse(DirectoryOus.isInGroupOu("CN=Test Group,OU=MoreGroups,DC=dms,DC=local"))
    assertFalse(DirectoryOus.isInGroupOu("CN=Test Group,OU=Groups,DC=dms,DC=local,DC=extra"))
  }

  @Test
  fun rejectsMissingDn() {
    assertFalse(DirectoryOus.isInGroupOu(null))
    assertFalse(DirectoryOus.isInGroupOu(""))
    assertFalse(DirectoryOus.isInGroupOu("   "))
  }
}
