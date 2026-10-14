package org.dallasmakerspace.activedirectory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class ActiveDirectoryServiceGroupFieldsTest {
  private val dn = "CN=Test Group,OU=Groups,DC=example,DC=test"

  private fun service(extra: Map<String, Any?>): ActiveDirectoryService {
    val client: IActiveDirectoryClient = mock {
      on { getGroup(any()) } doReturn
          (mapOf<String, Any?>(
              "cn" to "Test Group",
              "distinguishedName" to dn,
              "member" to emptyArray<String>(),
          ) + extra)
    }
    return ActiveDirectoryService(client)
  }

  @Test
  fun `getGroup returns managedBy apart from administrators and the parent groups`() {
    val owner = "CN=Owner,OU=Members,DC=example,DC=test"
    val parents =
        arrayOf(
            "CN=Parent A,OU=Groups,DC=example,DC=test",
            "CN=Parent B,OU=Groups,DC=example,DC=test")
    val admins = arrayOf(owner, "CN=Domain Admins,CN=Users,DC=example,DC=test")
    val group =
        service(mapOf("managedBy" to owner, "memberOf" to parents, "administrators" to admins))
            .getGroup("Test Group")

    assertEquals(owner, group.managedBy)
    assertEquals(parents.toList(), group.parentGroups)
    assertEquals(2, group.administrators.size)
  }

  @Test
  fun `getGroup defaults managedBy to null and parentGroups to empty`() {
    val group = service(emptyMap()).getGroup("Test Group")

    assertNull(group.managedBy)
    assertEquals(emptyList(), group.parentGroups)
  }

  @Test
  fun `a blank managedBy counts as unset`() {
    assertNull(service(mapOf("managedBy" to " ")).getGroup("Test Group").managedBy)
  }

  @Test
  fun `attributeStrings handles arrays, collections, single values and null`() {
    assertEquals(listOf("a", "b"), attributeStrings(arrayOf("a", "b")))
    assertEquals(listOf("a", "b"), attributeStrings(listOf("a", null, "b")))
    assertEquals(listOf("a"), attributeStrings("a"))
    assertEquals(emptyList(), attributeStrings(null))
  }
}
