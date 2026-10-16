package org.dallasmakerspace.models

import org.junit.Assert.assertEquals
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
  fun mainGroupOuIsTheFirstGroupOu() {
    assertEquals(DirectoryOus.MAIN_GROUP_OU, DirectoryOus.GROUP_OUS.first())
  }

  @Test
  fun mainGroupOuAcceptsDirectChildrenAndSubOus() {
    assertTrue(DirectoryOus.isInMainGroupOu("CN=Test Group,OU=Groups,DC=dms,DC=local"))
    assertTrue(DirectoryOus.isInMainGroupOu("CN=Test Group,OU=Shop,OU=Groups,DC=dms,DC=local"))
    assertTrue(DirectoryOus.isInMainGroupOu("cn=Test Group, ou=groups, dc=DMS, dc=Local"))
  }

  @Test
  fun mainGroupOuRejectsTheAdminGroupOuAndOthers() {
    assertFalse(DirectoryOus.isInMainGroupOu("CN=Test Group,OU=Groups,OU=Admin,DC=dms,DC=local"))
    assertFalse(
        DirectoryOus.isInMainGroupOu("CN=Test Group,OU=Sub,OU=Groups,OU=Admin,DC=dms,DC=local"))
    assertFalse(DirectoryOus.isInMainGroupOu("CN=Test Group,CN=Users,DC=dms,DC=local"))
    assertFalse(DirectoryOus.isInMainGroupOu("OU=Groups,DC=dms,DC=local"))
    assertFalse(DirectoryOus.isInMainGroupOu("CN=Test Group,OU=MoreGroups,DC=dms,DC=local"))
    assertFalse(DirectoryOus.isInMainGroupOu(null))
    assertFalse(DirectoryOus.isInMainGroupOu(" "))
  }

  @Test
  fun escapedCommasInTheCnCannotFakeTheOu() {
    assertFalse(DirectoryOus.isInMainGroupOu("CN=x\\,OU=Groups,DC=dms,DC=local"))
    assertFalse(DirectoryOus.isInGroupOu("CN=x\\,OU=Groups,DC=dms,DC=local"))
    assertFalse(DirectoryOus.isInMainGroupOu("CN=x\\,OU=Groups\\,OU=Admin,DC=dms,DC=local"))
    assertTrue(DirectoryOus.isInMainGroupOu("CN=Shop\\, Wood,OU=Groups,DC=dms,DC=local"))
  }

  @Test
  fun mainGroupOuOrChildAcceptsTheOuItselfAndSubOus() {
    assertTrue(DirectoryOus.isMainGroupOuOrChild("OU=Groups,DC=dms,DC=local"))
    assertTrue(DirectoryOus.isMainGroupOuOrChild("ou=Shop, ou=groups, dc=dms, dc=local"))
    assertFalse(DirectoryOus.isMainGroupOuOrChild("OU=Groups,OU=Admin,DC=dms,DC=local"))
    assertFalse(DirectoryOus.isMainGroupOuOrChild("DC=dms,DC=local"))
    assertFalse(DirectoryOus.isMainGroupOuOrChild(null))
  }

  @Test
  fun rejectsMissingDn() {
    assertFalse(DirectoryOus.isInGroupOu(null))
    assertFalse(DirectoryOus.isInGroupOu(""))
    assertFalse(DirectoryOus.isInGroupOu("   "))
  }

  @Test
  fun groupOuRankOrdersByGroupOuAndIsEscapeAware() {
    assertEquals(0, DirectoryOus.groupOuRank("CN=A,OU=Groups,DC=dms,DC=local"))
    assertEquals(0, DirectoryOus.groupOuRank("CN=A,OU=Sub,OU=Groups,DC=dms,DC=local"))
    assertEquals(1, DirectoryOus.groupOuRank("cn=a, ou=sub, ou=groups, ou=admin, dc=DMS, dc=Local"))
    assertEquals(Int.MAX_VALUE, DirectoryOus.groupOuRank("CN=A,CN=Users,DC=dms,DC=local"))
    assertEquals(Int.MAX_VALUE, DirectoryOus.groupOuRank(null))
    assertEquals(Int.MAX_VALUE, DirectoryOus.groupOuRank("OU=Groups,DC=dms,DC=local"))
    assertEquals(Int.MAX_VALUE, DirectoryOus.groupOuRank("CN=A\\,OU=Groups,DC=dms,DC=local"))
  }

  @Test
  fun sameDnIgnoresCaseAndSpacingButNotStructure() {
    assertTrue(
        DirectoryOus.sameDn("CN=A,OU=Groups,DC=dms,DC=local", "cn=a, ou=groups, dc=DMS, dc=Local"))
    assertFalse(DirectoryOus.sameDn("CN=A,OU=Groups,DC=dms,DC=local", "CN=A,OU=Sub,OU=Groups,DC=dms,DC=local"))
    assertFalse(
        DirectoryOus.sameDn(
            "CN=A\\,B,OU=Groups,DC=dms,DC=local", "CN=A,B,OU=Groups,DC=dms,DC=local"))
    assertFalse(DirectoryOus.sameDn(null, null))
    assertFalse(DirectoryOus.sameDn("", ""))
  }
}
