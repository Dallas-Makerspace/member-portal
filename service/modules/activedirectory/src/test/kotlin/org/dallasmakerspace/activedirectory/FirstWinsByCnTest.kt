package org.dallasmakerspace.activedirectory

import com.unboundid.ldap.sdk.Attribute
import com.unboundid.ldap.sdk.SearchResultEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class FirstWinsByCnTest {
  private fun entry(dn: String, cn: String?) =
      SearchResultEntry(dn, if (cn == null) emptyList() else listOf(Attribute("cn", cn)))

  @Test
  fun keepsEntriesFromAllOusInOrder() {
    val result =
        firstWinsByCn(
            listOf(
                listOf(entry("CN=A,OU=Groups,DC=x", "A")),
                listOf(entry("CN=B,OU=Groups,OU=Admin,DC=x", "B"))))

    assertEquals(listOf("CN=A,OU=Groups,DC=x", "CN=B,OU=Groups,OU=Admin,DC=x"), result.map { it.dn })
  }

  @Test
  fun earlierOuWinsOnDuplicateNameIgnoringCase() {
    val duplicates = mutableListOf<String>()

    val result =
        firstWinsByCn(
            listOf(
                listOf(entry("CN=Shop,OU=Groups,DC=x", "Shop")),
                listOf(entry("CN=shop,OU=Groups,OU=Admin,DC=x", "shop"))),
            onDuplicate = { duplicates += it })

    assertEquals(listOf("CN=Shop,OU=Groups,DC=x"), result.map { it.dn })
    assertEquals(listOf("shop"), duplicates)
  }

  @Test
  fun keepsEntriesWithoutCnAndHandlesEmptyOus() {
    val result =
        firstWinsByCn(listOf(emptyList(), listOf(entry("CN=NoCn,OU=Groups,DC=x", null))))

    assertEquals(1, result.size)
  }
}
