package org.dallasmakerspace.cron

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADGroup
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.GroupRowRef
import org.dallasmakerspace.members.GuidWriteResult
import org.dallasmakerspace.members.IGroupGuidBackfillStore
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class GroupGuidBackfillJobTest {
  private fun guid(n: Int) = "00000000-0000-0000-0000-%012d".format(n)

  private class FakeStore(val rows: List<GroupRowRef>) : IGroupGuidBackfillStore {
    val events = mutableListOf<String>()
    val batches = mutableListOf<List<Pair<Int, String>>>()

    override suspend fun listRows() = rows

    override suspend fun createBackupTable(date: LocalDate): String {
      events.add("backup")
      return "groups_backup_20300102"
    }

    override suspend fun setGuidsIfNull(assignments: List<Pair<Int, String>>): GuidWriteResult {
      events.add("write")
      batches.add(assignments)
      return GuidWriteResult(assignments.size, 0, emptyList())
    }
  }

  private fun adDn(name: String) = "CN=$name,OU=Test,DC=example,DC=test"

  private fun adGroup(name: String, n: Int) =
      ADGroup(name, null, adDn(name), guid(n), emptyList(), false)

  private fun job(store: IGroupGuidBackfillStore, vararg groups: ADGroup): GroupGuidBackfillJob {
    val loggerFactory: LoggerFactory = mock()
    val logger: Logger = mock()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(logger)
    whenever(loggerFactory.createInMemoryLogger(any<Class<*>>())).thenReturn(logger)
    val ad: IActiveDirectoryService = mock()
    whenever(ad.getAllGroups()).thenReturn(groups.toList())
    return GroupGuidBackfillJob(loggerFactory, ad, store) { LocalDate.of(2030, 1, 2) }
  }

  private fun row(id: Int, name: String, guid: String? = null) =
      GroupRowRef(id, name, "CN=$name,OU=Groups,DC=dms,DC=local", guid)

  @Test
  fun `dry run is the default and performs no writes`() = runBlocking {
    val store = FakeStore(listOf(row(1, "Zebra Crew"), row(2, "Orphan Club")))
    val report = job(store, adGroup("Zebra Crew", 1)).run(GroupGuidBackfillJobParams())
    assertTrue(GroupGuidBackfillJobParams().dryRun)
    assertTrue(store.events.isEmpty())
    assertTrue(report.contains("mode=DRY_RUN"))
    assertTrue(report.contains("MATCHED=1"))
    assertTrue(report.contains("ROW_WITHOUT_AD_GROUP=1 ids=[2]"))
    assertTrue(report.contains("AMBIGUOUS=0"))
  }

  @Test
  fun `report shows counts and row ids but no group names or dns`() = runBlocking {
    val store =
        FakeStore(
            listOf(
                row(1, "Zebra Crew"),
                row(2, "Orphan Club"),
                row(3, "Twin Peaks"),
                row(4, "Twin Peaks Copy").copy(dn = adDn("Twin Peaks"))))
    val report =
        job(store, adGroup("Zebra Crew", 1), adGroup("Twin Peaks", 3), adGroup("Extra Team", 9))
            .run(GroupGuidBackfillJobParams())
    assertTrue(report.contains("AMBIGUOUS=2 ids=[3, 4]"))
    assertTrue(report.contains("AD_GROUP_WITHOUT_ROW=1"))
    listOf("Zebra", "Orphan", "Twin", "Extra", "CN=", "DC=").forEach {
      assertFalse(report.contains(it), "report leaks $it")
    }
  }

  @Test
  fun `real run is refused when anything is ambiguous`() = runBlocking {
    val twin = row(2, "Zebra Crew Copy").copy(dn = adDn("Zebra Crew"))
    val store = FakeStore(listOf(row(1, "Zebra Crew"), twin))
    val report =
        job(store, adGroup("Zebra Crew", 1)).run(GroupGuidBackfillJobParams(dryRun = false))
    assertTrue(report.contains("REFUSED"))
    assertTrue(store.events.isEmpty())
  }

  @Test
  fun `real run backs up first, then updates in batches`() = runBlocking {
    val rows =
        (1..5).map { row(it, "Team $it") } + row(6, "Gone Team") + row(7, "Done Team", guid(7))
    val ads = (1..5).map { adGroup("Team $it", it) } + adGroup("Done Team", 7)
    val store = FakeStore(rows)
    val report =
        job(store, *ads.toTypedArray()).run(GroupGuidBackfillJobParams(false, batchSize = 2))
    assertEquals(listOf("backup", "write", "write", "write"), store.events)
    assertEquals(listOf(2, 2, 1), store.batches.map { it.size })
    assertEquals((1..5).map { it to guid(it) }, store.batches.flatten())
    assertTrue(report.contains("mode=REAL_RUN"))
    assertTrue(report.contains("backup=groups_backup_20300102"))
    assertTrue(report.contains("updated=5"))
    assertTrue(report.contains("ALREADY_SET=1"))
  }

  @Test
  fun `real run refuses when a matched ad group has no usable guid`() = runBlocking {
    val store = FakeStore(listOf(row(1, "Team 1")))
    val bad = ADGroup("Team 1", null, adDn("Team 1"), null, emptyList(), false)
    val report = job(store, bad).run(GroupGuidBackfillJobParams(dryRun = false))
    assertTrue(report.contains("UNUSABLE_GUID=1"))
    assertTrue(report.contains("REFUSED"))
    assertTrue(store.events.isEmpty())
  }

  @Test
  fun `nothing to do still produces a report`() = runBlocking {
    val store = FakeStore(emptyList())
    val report = job(store).run(GroupGuidBackfillJobParams(dryRun = false))
    assertTrue(report.contains("MATCHED=0"))
    assertEquals(listOf("backup"), store.events)
  }
}
