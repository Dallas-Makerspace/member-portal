package org.dallasmakerspace.activedirectory

import com.unboundid.ldap.sdk.Attribute
import com.unboundid.ldap.sdk.SearchResultEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreferredByGroupOuTest {
  private val first = "OU=Groups,DC=dms,DC=local"
  private val second = "OU=Groups,OU=Admin,DC=dms,DC=local"

  private fun entry(cn: String, parent: String) =
      SearchResultEntry("CN=$cn,$parent", listOf(Attribute("cn", cn)))

  @Test
  fun `first group ou ranks before the second and others rank last`() {
    assertEquals(0, groupOuRank("CN=A,$first"))
    assertEquals(1, groupOuRank("CN=A,$second"))
    assertEquals(0, groupOuRank("CN=A,OU=Sub,$first"))
    assertEquals(1, groupOuRank("cn=a, ou=sub, ou=groups, ou=admin, dc=DMS, dc=Local"))
    assertEquals(Int.MAX_VALUE, groupOuRank("CN=A,CN=Users,DC=dms,DC=local"))
  }

  @Test
  fun `earliest group ou wins regardless of result order`() {
    val admin = entry("Shop", second)
    val main = entry("Shop", first)
    assertEquals(main.dn, preferredByGroupOu(listOf(admin, main))?.dn)
    assertEquals(main.dn, preferredByGroupOu(listOf(main, admin))?.dn)
  }

  @Test
  fun `entries outside the group ous lose to ones inside, else the first wins`() {
    val users = entry("Shop", "CN=Users,DC=dms,DC=local")
    val admin = entry("Shop", second)
    assertEquals(admin.dn, preferredByGroupOu(listOf(users, admin))?.dn)
    val other = entry("Shop", "OU=Elsewhere,DC=dms,DC=local")
    assertEquals(users.dn, preferredByGroupOu(listOf(users, other))?.dn)
  }

  @Test
  fun `empty input gives null`() {
    assertNull(preferredByGroupOu(emptyList()))
  }

  @Test
  fun `preferredPerCn keeps one entry per name in first-seen order`() {
    val entries =
        listOf(
            entry("Shop", second),
            entry("Lab", first),
            entry("shop", first),
            entry("Lab", second))
    val result = preferredPerCn(entries)
    assertEquals(listOf("CN=shop,$first", "CN=Lab,$first"), result.map { it.dn })
  }
}

class OncePerNameTest {
  @Test
  fun `emits once per distinct name ignoring case`() {
    val emitted = mutableListOf<String>()
    val once = OncePerName { emitted += it }
    once.log("Shop")
    once.log("shop")
    once.log("Shop")
    once.log("Lab")
    assertEquals(listOf("Shop", "Lab"), emitted)
  }
}
