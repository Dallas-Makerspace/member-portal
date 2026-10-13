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
import org.dallasmakerspace.members.DnUpdate
import org.dallasmakerspace.members.GroupRowRef
import org.dallasmakerspace.members.GuidWriteResult
import org.dallasmakerspace.members.IGroupGuidBackfillStore
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class GroupGuidBackfillJobTest {
  private fun guid(n: Int) = "00000000-0000-0000-0000-%012d".format(n)

  private class FakeStore(var rows: List<GroupRowRef>) : IGroupGuidBackfillStore {
    val events = mutableListOf<String>()
    val batches = mutableListOf<List<Pair<Int, String>>>()
    val dnBatches = mutableListOf<List<Pair<Int, DnUpdate>>>()

    override suspend fun listRows() = rows

    override suspend fun createBackupTable(date: LocalDate): String {
      events.add("backup")
      return "groups_backup_20300102"
    }

    override suspend fun setGuidsIfNull(assignments: List<Pair<Int, String>>): GuidWriteResult {
      events.add("write")
      batches.add(assignments)
      val byId = assignments.toMap()
      rows =
          rows.map { r ->
            val guid = byId[r.id]
            if (guid != null && r.objectGuid == null) r.copy(objectGuid = guid) else r
          }
      return GuidWriteResult(assignments.size, 0, emptyList())
    }

    override suspend fun setDnsForGuid(updates: List<Pair<Int, DnUpdate>>): GuidWriteResult {
      events.add("dnWrite")
      dnBatches.add(updates)
      val byId = updates.toMap()
      rows =
          rows.map { r ->
            val change = byId[r.id]
            if (change != null && r.objectGuid == change.guid) r.copy(dn = change.dn) else r
          }
      return GuidWriteResult(updates.size, 0, emptyList())
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
    assertEquals(
        listOf("backup", "write", "write", "write", "dnWrite", "dnWrite", "dnWrite"), store.events)
    assertEquals(listOf(2, 2, 1), store.batches.map { it.size })
    assertEquals((1..5).map { it to guid(it) }, store.batches.flatten())
    assertTrue(report.contains("mode=REAL_RUN"))
    assertTrue(report.contains("backup=groups_backup_20300102"))
    assertTrue(report.contains("updated=5"))
    assertTrue(report.contains("ALREADY_SET=1"))
  }

  private fun sameDnRow(id: Int, name: String, guid: String? = null) =
      GroupRowRef(id, name, adDn(name), guid)

  @Test
  fun `dry run reports dn updates and writes nothing`() = runBlocking {
    val store =
        FakeStore(
            listOf(row(1, "Team 1"), sameDnRow(2, "Team 2", guid(2)), row(3, "Team 3", guid(3))))
    val ads = listOf(adGroup("Team 1", 1), adGroup("Team 2", 2), adGroup("Team 3", 3))
    val report = job(store, *ads.toTypedArray()).run(GroupGuidBackfillJobParams())
    assertTrue(report.contains("DN_UPDATE=2"))
    assertTrue(report.contains("DN_UNCHANGED=1"))
    assertTrue(report.contains("DN_CONFLICT=0 ids=[]"))
    assertTrue(store.events.isEmpty())
  }

  @Test
  fun `real run writes dns only for rows that need them`() = runBlocking {
    val store =
        FakeStore(
            listOf(row(1, "Team 1"), sameDnRow(2, "Team 2", guid(2)), row(3, "Team 3", guid(3))))
    val ads = listOf(adGroup("Team 1", 1), adGroup("Team 2", 2), adGroup("Team 3", 3))
    val report = job(store, *ads.toTypedArray()).run(GroupGuidBackfillJobParams(false, 1))
    assertEquals(listOf("backup", "write", "dnWrite", "dnWrite"), store.events)
    assertEquals(listOf(1, 3), store.dnBatches.flatten().map { it.first })
    assertEquals(listOf(adDn("Team 1"), adDn("Team 2"), adDn("Team 3")), store.rows.map { it.dn })
    assertTrue(report.contains("dnUpdated=2 dnUnchanged=0 dnFailed=0"))
  }

  @Test
  fun `a second real run changes nothing`() = runBlocking {
    val store = FakeStore(listOf(row(1, "Team 1"), row(2, "Team 2", guid(2))))
    val j = job(store, adGroup("Team 1", 1), adGroup("Team 2", 2))
    j.run(GroupGuidBackfillJobParams(dryRun = false))
    val eventsAfterFirst = store.events.size
    val report = j.run(GroupGuidBackfillJobParams(dryRun = false))
    assertTrue(report.contains("MATCHED=0"))
    assertTrue(report.contains("DN_UPDATE=0"))
    assertTrue(report.contains("DN_UNCHANGED=2"))
    assertEquals(listOf("backup"), store.events.drop(eventsAfterFirst))
    assertEquals(1, store.batches.size)
    assertEquals(1, store.dnBatches.size)
  }

  @Test
  fun `a dn held by another row is a conflict that does not block the run`() = runBlocking {
    val holder = GroupRowRef(9, "Unrelated Row", adDn("Team 1"), guid(99))
    val store = FakeStore(listOf(row(1, "Team 1", guid(1)), row(2, "Team 2", guid(2)), holder))
    val report =
        job(store, adGroup("Team 1", 1), adGroup("Team 2", 2))
            .run(GroupGuidBackfillJobParams(dryRun = false))
    assertTrue(report.contains("DN_CONFLICT=1 ids=[1]"))
    assertFalse(report.contains("REFUSED"))
    assertEquals(listOf(2), store.dnBatches.flatten().map { it.first })
    assertEquals(row(1, "Team 1", guid(1)), store.rows.first { it.id == 1 })
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
