package org.dallasmakerspace.members

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADException
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GroupHistoryRepositoryGuidTest {
  private val guid = "11111111-1111-1111-1111-111111111111"
  private val otherGuid = "22222222-2222-2222-2222-222222222222"
  private val ad: IActiveDirectoryService = mock()
  private lateinit var repo: GroupHistoryRepository

  private fun dnOf(name: String) = "CN=$name,OU=Test,DC=example,DC=test"

  @BeforeTest
  fun setUp() {
    MembersGroupsTestDb.reset()
    val resolver =
        GroupGuidResolver(
            ad, 500, 60_000, 30_000, System::currentTimeMillis,
            CoroutineScope(SupervisorJob() + Dispatchers.IO))
    repo = GroupHistoryRepository(resolver)
  }

  private fun adReturns(name: String, objectGuid: String?) {
    whenever(ad.getGroup(any()))
        .thenReturn(ADGroup(name, null, dnOf(name), objectGuid, emptyList(), false))
  }

  @Test
  fun `creates a row with the guid when none exists`() = runBlocking {
    val id = repo.resolveGroup("Team A", dnOf("Team A"), guid)
    assertEquals(Triple("Team A", dnOf("Team A"), guid), MembersGroupsTestDb.row(id))
  }

  @Test
  fun `row found by name without a guid gets the guid filled in`() = runBlocking {
    val existing = MembersGroupsTestDb.insert("Team A", placeholderGroupDn("Team A"))
    val id = repo.resolveGroup("team a", dnOf("Team A"), guid)
    assertEquals(existing, id)
    assertEquals(Triple("Team A", placeholderGroupDn("Team A"), guid), MembersGroupsTestDb.row(id))
  }

  @Test
  fun `row found by guid with a stale name and dn is renamed in place`() = runBlocking {
    val existing = MembersGroupsTestDb.insert("Old Name", dnOf("Old Name"), guid)
    val id = repo.resolveGroup("New Name", dnOf("New Name"), guid)
    assertEquals(existing, id)
    assertEquals(Triple("New Name", dnOf("New Name"), guid), MembersGroupsTestDb.row(id))
    assertEquals(1, MembersGroupsTestDb.count())
  }

  @Test
  fun `rename is skipped when the new name belongs to another row`() = runBlocking {
    val renamed = MembersGroupsTestDb.insert("Old Name", dnOf("Old Name"), guid)
    val other = MembersGroupsTestDb.insert("New Name", dnOf("New Name"))
    val id = repo.resolveGroup("New Name", dnOf("New Name"), guid)
    assertEquals(renamed, id)
    assertNotEquals(other, id)
    assertEquals("Old Name", MembersGroupsTestDb.row(renamed)!!.first)
    assertEquals(2, MembersGroupsTestDb.count())
  }

  @Test
  fun `name row holding a different guid is returned unchanged`() = runBlocking {
    val existing = MembersGroupsTestDb.insert("Team A", dnOf("Team A"), otherGuid)
    val id = repo.resolveGroup("Team A", dnOf("Team A"), guid)
    assertEquals(existing, id)
    assertEquals(otherGuid, MembersGroupsTestDb.row(id)!!.third)
    assertEquals(1, MembersGroupsTestDb.count())
  }

  @Test
  fun `without a guid it behaves like the old name-only lookup`() = runBlocking {
    val first = repo.resolveGroup("Team A", null, null)
    val second = repo.resolveGroup("Team A", null, null)
    assertEquals(first, second)
    assertEquals(Triple("Team A", placeholderGroupDn("Team A"), null), MembersGroupsTestDb.row(first))
  }

  @Test
  fun `an unparseable guid is treated as absent`() = runBlocking {
    val id = repo.resolveGroup("Team A", null, "not-a-guid")
    assertNull(MembersGroupsTestDb.row(id)!!.third)
  }

  @Test
  fun `getOrCreateGroup resolves the guid through AD`() = runBlocking {
    adReturns("Team A", guid)
    val id = repo.getOrCreateGroup("Team A")
    assertEquals(Triple("Team A", dnOf("Team A"), guid), MembersGroupsTestDb.row(id))
  }

  @Test
  fun `getOrCreateGroup fills the guid on an existing name-only row`() = runBlocking {
    val existing = MembersGroupsTestDb.insert("Team A", placeholderGroupDn("Team A"))
    adReturns("Team A", guid)
    assertEquals(existing, repo.getOrCreateGroup("Team A"))
    assertEquals(guid, MembersGroupsTestDb.row(existing)!!.third)
  }

  @Test
  fun `getOrCreateGroup falls back to name only when AD fails`() = runBlocking {
    whenever(ad.getGroup(any())).thenAnswer { throw ADException("down") }
    val id = repo.getOrCreateGroup("Team A")
    assertEquals(Triple("Team A", placeholderGroupDn("Team A"), null), MembersGroupsTestDb.row(id))
    assertEquals(id, repo.getOrCreateGroup("Team A"))
  }

  @Test
  fun `getOrCreateGroup is not held up by a slow AD`() = runBlocking {
    whenever(ad.getGroup(any())).thenAnswer {
      Thread.sleep(1_500)
      ADGroup("Team A", null, dnOf("Team A"), guid, emptyList(), false)
    }
    val started = System.currentTimeMillis()
    val id = repo.getOrCreateGroup("Team A")
    assert(System.currentTimeMillis() - started < 1_200)
    assertNull(MembersGroupsTestDb.row(id)!!.third)
  }
}
