package org.dallasmakerspace.members

import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction

class GroupGuidBackfillStoreTest {
  private val store = GroupGuidBackfillStore()
  private val guid1 = "11111111-1111-1111-1111-111111111111"
  private val guid2 = "22222222-2222-2222-2222-222222222222"

  @BeforeTest
  fun setUp() = MembersGroupsTestDb.reset()

  @Test
  fun `lists rows with their guids`() = runBlocking {
    val id = MembersGroupsTestDb.insert("Team A", "CN=Team A", guid1)
    assertEquals(listOf(GroupRowRef(id, "Team A", "CN=Team A", guid1)), store.listRows())
  }

  @Test
  fun `sets the guid only where it is still null`() = runBlocking {
    val empty = MembersGroupsTestDb.insert("Team A", "CN=Team A")
    val filled = MembersGroupsTestDb.insert("Team B", "CN=Team B", guid1)
    val result = store.setGuidsIfNull(listOf(empty to guid2, filled to guid2))
    assertEquals(GuidWriteResult(1, 1, emptyList()), result)
    assertEquals(Triple("Team A", "CN=Team A", guid2), MembersGroupsTestDb.row(empty))
    assertEquals(Triple("Team B", "CN=Team B", guid1), MembersGroupsTestDb.row(filled))
  }

  @Test
  fun `a second run changes nothing`() = runBlocking {
    val id = MembersGroupsTestDb.insert("Team A", "CN=Team A")
    store.setGuidsIfNull(listOf(id to guid1))
    assertEquals(GuidWriteResult(0, 1, emptyList()), store.setGuidsIfNull(listOf(id to guid1)))
  }

  @Test
  fun `a guid already used by another row is reported as failed and the rest still apply`() =
      runBlocking {
        MembersGroupsTestDb.insert("Team A", "CN=Team A", guid1)
        val clash = MembersGroupsTestDb.insert("Team B", "CN=Team B")
        val fine = MembersGroupsTestDb.insert("Team C", "CN=Team C")
        val result = store.setGuidsIfNull(listOf(clash to guid1, fine to guid2))
        assertEquals(GuidWriteResult(1, 0, listOf(clash)), result)
        assertEquals(guid2, MembersGroupsTestDb.row(fine)!!.third)
      }

  @Test
  fun `backup table is created once and holds a copy`() = runBlocking {
    MembersGroupsTestDb.insert("Team A", "CN=Team A")
    val date = LocalDate.of(2030, 1, 2)
    assertEquals("groups_backup_20300102", store.createBackupTable(date))
    MembersGroupsTestDb.insert("Team B", "CN=Team B")
    store.createBackupTable(date)
    val backedUp =
        transaction(MembersGroupsTestDb.db) {
          exec("SELECT COUNT(*) FROM groups_backup_20300102") {
            it.next()
            it.getInt(1)
          }
        }
    assertEquals(1, backedUp)
  }
}
