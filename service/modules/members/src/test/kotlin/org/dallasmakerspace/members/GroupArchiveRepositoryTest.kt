package org.dallasmakerspace.members

import java.time.LocalDateTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADException
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.transactions.transaction
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GroupArchiveRepositoryTest {
  private val guid = "11111111-1111-1111-1111-111111111111"
  private val otherGuid = "22222222-2222-2222-2222-222222222222"
  private val ad: IActiveDirectoryService = mock()
  private var clock = LocalDateTime.of(2026, 10, 13, 15, 30, 0)
  private lateinit var archive: GroupArchiveRepository
  private lateinit var history: GroupHistoryRepository

  private fun dnOf(name: String) = "CN=$name,OU=Test,DC=example,DC=test"

  @BeforeTest
  fun setUp() {
    MembersGroupsTestDb.reset()
    archive = GroupArchiveRepository(now = { clock })
    val resolver =
        GroupGuidResolver(
            ad, 500, 60_000, 30_000, System::currentTimeMillis,
            CoroutineScope(SupervisorJob() + Dispatchers.IO))
    history = GroupHistoryRepository(resolver)
  }

  private fun insertGroup(name: String, status: String = "ACTIVE", guid: String? = null) =
      MembersGroupsTestDb.insert(name, dnOf(name), guid, status)

  private fun insertArchived(name: String, at: String, runId: Long?): Int {
    val rowId = insertGroup(name)
    clock = LocalDateTime.parse(at)
    runBlocking { archive.finalizeArchived(name, "asmith", "retired $name", runId) }
    return rowId
  }

  @Test
  fun `markDisabled moves ACTIVE to DISABLED and is idempotent`() = runBlocking {
    val id = insertGroup("Team A")
    assertTrue(archive.markDisabled("Team A"))
    assertEquals("DISABLED", MembersGroupsTestDb.status(id))
    assertTrue(archive.markDisabled("team a"))
    assertEquals("DISABLED", MembersGroupsTestDb.status(id))
  }

  @Test
  fun `markDisabled is false when the group has no row`() = runBlocking {
    assertFalse(archive.markDisabled("Nope"))
  }

  @Test
  fun `markActive rolls DISABLED back to ACTIVE and is idempotent`() = runBlocking {
    val id = insertGroup("Team A", "DISABLED")
    assertTrue(archive.markActive("Team A"))
    assertEquals("ACTIVE", MembersGroupsTestDb.status(id))
    assertTrue(archive.markActive("Team A"))
    assertFalse(archive.markActive("Nope"))
  }

  @Test
  fun `markDisabled and markActive never touch an ARCHIVED row`() = runBlocking {
    val id = insertGroup("Team A", "ARCHIVED")
    assertFalse(archive.markDisabled("Team A"))
    assertFalse(archive.markActive("Team A"))
    assertEquals("ARCHIVED", MembersGroupsTestDb.status(id))
  }

  @Test
  fun `finalizeArchived records who, why, which run and when`() = runBlocking {
    val id = insertGroup("Team A", "DISABLED")
    assertTrue(archive.finalizeArchived("Team A", "asmith", "no longer needed", 42L))
    assertEquals("ARCHIVED", MembersGroupsTestDb.status(id))
    assertEquals(Triple("asmith", "no longer needed", 42L), MembersGroupsTestDb.archiveMeta(id))
    val listed = archive.listArchived(emptyList()).single()
    assertEquals("2026-10-13T15:30:00Z", listed.archivedAt)
  }

  @Test
  fun `finalizeArchived also works straight from ACTIVE`() = runBlocking {
    val id = insertGroup("Team A")
    assertTrue(archive.finalizeArchived("Team A", "asmith", "r", null))
    assertEquals("ARCHIVED", MembersGroupsTestDb.status(id))
    assertEquals(Triple("asmith", "r", null), MembersGroupsTestDb.archiveMeta(id))
  }

  @Test
  fun `finalizeArchived is idempotent for the same run and false for another run`() = runBlocking {
    val id = insertGroup("Team A", "DISABLED")
    assertTrue(archive.finalizeArchived("Team A", "asmith", "first", 7L))
    clock = clock.plusHours(1)
    assertTrue(archive.finalizeArchived("Team A", "bsmith", "second", 7L))
    assertEquals(Triple("asmith", "first", 7L), MembersGroupsTestDb.archiveMeta(id))
    assertFalse(archive.finalizeArchived("Team A", "bsmith", "other run", 8L))
    assertFalse(archive.finalizeArchived("Team A", "bsmith", "no run", null))
    assertEquals(Triple("asmith", "first", 7L), MembersGroupsTestDb.archiveMeta(id))
  }

  @Test
  fun `finalizeArchived is false when the group has no row`() = runBlocking {
    assertFalse(archive.finalizeArchived("Nope", "asmith", "r", 1L))
  }

  @Test
  fun `finalizeArchived stores a guid only when the row has none`() = runBlocking {
    val blank = insertGroup("Team A")
    val held = insertGroup("Team B", guid = guid)
    archive.finalizeArchived("Team A", "asmith", "r", 1L, otherGuid.uppercase())
    archive.finalizeArchived("Team B", "asmith", "r", 2L, otherGuid)
    assertEquals(otherGuid, MembersGroupsTestDb.row(blank)!!.third)
    assertEquals(guid, MembersGroupsTestDb.row(held)!!.third)
  }

  @Test
  fun `finalizeArchived skips a guid that another row already holds`() = runBlocking {
    insertGroup("Team B", guid = guid)
    val blank = insertGroup("Team A")
    assertTrue(archive.finalizeArchived("Team A", "asmith", "r", 1L, guid))
    assertNull(MembersGroupsTestDb.row(blank)!!.third)
  }

  @Test
  fun `statusOf is null without a row and ARCHIVED with only an archived one`() = runBlocking {
    assertNull(archive.statusOf("Team A"))
    insertGroup("Team A", "ARCHIVED")
    assertEquals("ARCHIVED", archive.statusOf("Team A"))
  }

  @Test
  fun `statusOf reports the live status when both exist`() = runBlocking {
    transaction(MembersGroupsTestDb.db) {
      exec("INSERT INTO `groups` (name, dn, status) VALUES ('Team A', 'dn-old', 'ARCHIVED')")
      exec("INSERT INTO `groups` (name, dn, status) VALUES ('Team A', 'dn-new', 'DISABLED')")
    }
    assertEquals("DISABLED", archive.statusOf("Team A"))
  }

  @Test
  fun `a new group can take the name and dn of an archived group`() = runBlocking {
    val old = insertGroup("Team A", "ARCHIVED")
    val fresh = history.resolveGroup("Team A", dnOf("Team A"), guid)
    assertTrue(fresh != old)
    assertEquals(Triple("Team A", dnOf("Team A"), guid), MembersGroupsTestDb.row(fresh))
    assertEquals("ARCHIVED", MembersGroupsTestDb.status(old))
    assertEquals("ACTIVE", MembersGroupsTestDb.status(fresh))
    assertEquals(2, MembersGroupsTestDb.count())
  }

  @Test
  fun `resolveGroup by name alone also ignores an archived row`() = runBlocking {
    val old = insertGroup("Team A", "ARCHIVED", guid = guid)
    val fresh = history.resolveGroup("Team A", null, null)
    assertTrue(fresh != old)
    assertEquals(fresh, history.resolveGroup("Team A", null, null))
    assertEquals(2, MembersGroupsTestDb.count())
  }

  @Test
  fun `an archived row is never revived or renamed from a name match`() = runBlocking {
    val old = insertGroup("Team A", "ARCHIVED")
    history.resolveGroup("Team A", dnOf("Team A"), guid)
    assertEquals("ARCHIVED", MembersGroupsTestDb.status(old))
    assertNull(MembersGroupsTestDb.row(old)!!.third)
  }

  @Test
  fun `getOrCreateGroup gives a fresh row for an archived name`() = runBlocking {
    val old = insertGroup("Team A", "ARCHIVED")
    whenever(ad.getGroup(any())).thenAnswer { throw ADException("down") }
    val fresh = history.getOrCreateGroup("Team A")
    assertTrue(fresh != old)
    assertEquals("ACTIVE", MembersGroupsTestDb.status(fresh))
    assertEquals(fresh, history.getOrCreateGroup("Team A"))
  }

  @Test
  fun `a guid match on an archived row is reported and nothing is written`() = runBlocking {
    val old = insertGroup("Old Name", "ARCHIVED", guid = guid)
    val id = history.resolveGroup("New Name", dnOf("New Name"), guid)
    assertEquals(old, id)
    assertEquals(Triple("Old Name", dnOf("Old Name"), guid), MembersGroupsTestDb.row(old))
    assertEquals(1, MembersGroupsTestDb.count())
  }

  @Test
  fun `names stay unique among live rows`() {
    insertGroup("Team A")
    assertFailsWith<ExposedSQLException> {
      transaction(MembersGroupsTestDb.db) {
        exec("INSERT INTO `groups` (name, dn) VALUES ('TEAM A', 'other-dn')")
      }
    }
    assertFailsWith<ExposedSQLException> {
      transaction(MembersGroupsTestDb.db) {
        exec(
            "INSERT INTO `groups` (name, dn, status) " +
                "VALUES ('other', '${dnOf("Team A")}', 'DISABLED')")
      }
    }
  }

  @Test
  fun `archiving frees the name and a second archive of the same name is allowed`() = runBlocking {
    insertGroup("Team A")
    assertTrue(archive.finalizeArchived("Team A", "asmith", "r1", 1L))
    insertGroup("Team A")
    assertTrue(archive.finalizeArchived("Team A", "asmith", "r2", 2L))
    assertEquals(listOf(2L, 1L), archive.listArchived(emptyList()).map { it.runId })
  }

  @Test
  fun `listArchived is newest first and only lists archived rows`() = runBlocking {
    insertGroup("Live Group")
    insertGroup("Disabled Group", "DISABLED")
    insertArchived("Shop Saws", "2026-01-05T10:00:00", 1L)
    insertArchived("Shop Lathe", "2026-03-05T10:00:00", 2L)
    insertArchived("Lab Scope", "2026-02-05T10:00:00", null)

    val listed = archive.listArchived(emptyList())

    assertEquals(listOf("Shop Lathe", "Lab Scope", "Shop Saws"), listed.map { it.name })
  }

  @Test
  fun `listArchived filters by case-insensitive name prefix, any of several`() = runBlocking {
    insertArchived("Shop Saws", "2026-01-05T10:00:00", 1L)
    insertArchived("shop Lathe", "2026-03-05T10:00:00", 2L)
    insertArchived("Lab Scope", "2026-02-05T10:00:00", 3L)
    insertArchived("Garden", "2026-02-06T10:00:00", 4L)

    assertEquals(
        listOf("shop Lathe", "Shop Saws"), archive.listArchived(listOf("SHOP")).map { it.name })
    assertEquals(
        listOf("shop Lathe", "Lab Scope", "Shop Saws"),
        archive.listArchived(listOf("Shop", "lab")).map { it.name })
    assertEquals(emptyList(), archive.listArchived(listOf("Zzz")))
    assertEquals(4, archive.listArchived(listOf(" ", "")).size)
    assertEquals(4, archive.listArchived(emptyList()).size)
  }

  @Test
  fun `listArchived maps the row and the artifact constants`() = runBlocking {
    val withRun = insertArchived("Shop Saws", "2026-01-05T10:00:00", 9L)
    insertArchived("Lab Scope", "2026-01-04T10:00:00", null)

    val (shop, lab) = archive.listArchived(emptyList())

    assertEquals(withRun, shop.id)
    assertEquals("Shop Saws", shop.name)
    assertEquals("2026-01-05T10:00:00Z", shop.archivedAt)
    assertEquals("asmith", shop.archivedBy)
    assertEquals("retired Shop Saws", shop.reason)
    assertEquals(9L, shop.runId)
    assertEquals(ARCHIVE_ARTIFACT_STEP_INDEX, shop.artifactStepIndex)
    assertEquals("member-list", shop.artifactName)
    assertNull(lab.runId)
    assertNull(lab.artifactStepIndex)
    assertNull(lab.artifactName)
  }

  @Test
  fun `updateObjectGuid replaces the GUID of a live row and is idempotent`() = runBlocking {
    val id = insertGroup("Team A", "DISABLED", guid)

    assertTrue(archive.updateObjectGuid("team a", otherGuid.uppercase()))
    assertEquals(otherGuid, MembersGroupsTestDb.row(id)!!.third)
    assertTrue(archive.updateObjectGuid("Team A", otherGuid))
    assertEquals(otherGuid, MembersGroupsTestDb.row(id)!!.third)
  }

  @Test
  fun `objectGuidOf reads the live row only`() = runBlocking {
    insertGroup("Team A", "DISABLED", guid)
    insertGroup("Team B", "ARCHIVED", otherGuid)
    insertGroup("Team C")

    assertEquals(guid, archive.objectGuidOf("team a"))
    assertNull(archive.objectGuidOf("Team B"))
    assertNull(archive.objectGuidOf("Team C"))
    assertNull(archive.objectGuidOf("Nope"))
  }

  @Test
  fun `updateObjectGuid ignores archived rows, missing groups and malformed GUIDs`() = runBlocking {
    val archived = insertGroup("Team A", "ARCHIVED", guid)
    val live = insertGroup("Team B", "ACTIVE", otherGuid)

    assertFalse(archive.updateObjectGuid("Team A", otherGuid))
    assertFalse(archive.updateObjectGuid("Nope", otherGuid))
    assertFalse(archive.updateObjectGuid("Team B", "not-a-guid"))
    assertEquals(guid, MembersGroupsTestDb.row(archived)!!.third)
    assertEquals(otherGuid, MembersGroupsTestDb.row(live)!!.third)
  }

  @Test
  fun `matchesAnyPrefix treats no usable prefix as everything`() {
    assertTrue(matchesAnyPrefix("Anything", emptyList()))
    assertTrue(matchesAnyPrefix("Anything", listOf("  ")))
    assertTrue(matchesAnyPrefix("Shop Saws", listOf("x", " shop")))
    assertFalse(matchesAnyPrefix("Shop Saws", listOf("saws")))
  }

  @Test
  fun `getGroupId ignores archived rows`() = runBlocking {
    val repo = GroupSettingsRepository(LoggerFactory(), mock())
    insertGroup("Team A", "ARCHIVED")
    assertNull(repo.getGroupId("Team A"))
    val live = insertGroup("Team A")
    assertEquals(live, repo.getGroupId("Team A"))
  }

  @Test
  fun `backfill listRows skips archived rows`() = runBlocking {
    val live = insertGroup("Team A")
    insertGroup("Team B", "ARCHIVED")
    assertEquals(listOf(live), GroupGuidBackfillStore().listRows().map { it.id })
  }
}
