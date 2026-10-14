package org.dallasmakerspace.server.reports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.models.ArchivedGroup
import org.dallasmakerspace.models.Committees

class ArchivedGroupsReportTest {
  private fun group(
      name: String = "Example Group",
      archivedAt: String = "2026-03-05T03:30:00Z",
      runId: Long? = 42,
      step: Int? = 1,
      artifact: String? = "members.json",
  ) =
      ArchivedGroup(
          id = 1,
          name = name,
          archivedAt = archivedAt,
          archivedBy = "jdoe",
          reason = "No longer used",
          runId = runId,
          artifactStepIndex = step,
          artifactName = artifact,
      )

  @Test
  fun `archived time is shown in Central Time across the date line and DST`() {
    assertEquals("Mar 4, 2026 9:30 PM CST", ArchivedGroupsReport.formatArchivedAt("2026-03-05T03:30:00Z"))
    assertEquals("Jun 30, 2026 11:15 PM CDT", ArchivedGroupsReport.formatArchivedAt("2026-07-01T04:15:00Z"))
    assertEquals("Mar 4, 2026 9:30 PM CST", ArchivedGroupsReport.formatArchivedAt("2026-03-05T03:30:00+00:00"))
  }

  @Test
  fun `an unparseable time is shown as given`() {
    assertEquals("yesterday", ArchivedGroupsReport.formatArchivedAt("yesterday"))
  }

  @Test
  fun `download url needs all three artifact fields and encodes the name`() {
    assertEquals(
        "/api/workflows/runs/42/steps/1/artifacts/member%20list.json/download",
        ArchivedGroupsReport.downloadUrl(group(artifact = "member list.json")))
    assertNull(ArchivedGroupsReport.downloadUrl(group(runId = null)))
    assertNull(ArchivedGroupsReport.downloadUrl(group(step = null)))
    assertNull(ArchivedGroupsReport.downloadUrl(group(artifact = null)))
  }

  @Test
  fun `no committee asks for every archived group`() = runBlocking<Unit> {
    var asked: List<String>? = null
    val model =
        ArchivedGroupsReport.reportModel(null, canDownload = false, fetch = { asked = it; listOf(group()) })

    assertEquals(emptyList(), asked)
    assertEquals(1, (model["archivedRows"] as List<*>).size)
    assertFalse("archivedCommittee" in model || "archivedCommitteeSlug" in model)
  }

  @Test
  fun `a known committee filters by its group prefixes`() = runBlocking<Unit> {
    var asked: List<String>? = null
    val model =
        ArchivedGroupsReport.reportModel("jewelry", canDownload = false, fetch = { asked = it; emptyList() })

    assertEquals(listOf("Jewelry", "Lapidary"), asked)
    assertEquals(Committees.findBySlug("jewelry"), model["archivedCommittee"])
  }

  @Test
  fun `an unknown committee makes no service call`() = runBlocking<Unit> {
    var called = false
    val model =
        ArchivedGroupsReport.reportModel("no-such", canDownload = true, fetch = { called = true; emptyList() })

    assertFalse(called)
    assertEquals("no-such", model["archivedCommitteeSlug"])
    assertEquals(emptyList<ArchivedGroupRow>(), model["archivedRows"])
  }

  @Test
  fun `a blank committee parameter means no filter`() = runBlocking<Unit> {
    val model = ArchivedGroupsReport.reportModel("", canDownload = false, fetch = { emptyList() })

    assertFalse("archivedCommitteeSlug" in model)
  }

  @Test
  fun `a committee without group prefixes shows nothing instead of everything`() = runBlocking<Unit> {
    var called = false
    val model =
        ArchivedGroupsReport.reportModel("logistics", canDownload = false, fetch = { called = true; listOf(group()) })

    assertFalse(called)
    assertEquals(emptyList<ArchivedGroupRow>(), model["archivedRows"])
  }

  @Test
  fun `download urls only reach infra rows that have an artifact`() = runBlocking<Unit> {
    val groups = listOf(group(), group(name = "No Artifact", runId = null))

    val infra = ArchivedGroupsReport.reportModel(null, true, { groups })
    val member = ArchivedGroupsReport.reportModel(null, false, { groups })

    @Suppress("UNCHECKED_CAST")
    val infraRows = infra["archivedRows"] as List<ArchivedGroupRow>
    @Suppress("UNCHECKED_CAST")
    val memberRows = member["archivedRows"] as List<ArchivedGroupRow>
    assertEquals(true, infra["archivedShowDownload"])
    assertTrue(infraRows[0].downloadUrl != null)
    assertNull(infraRows[1].downloadUrl)
    assertEquals(false, member["archivedShowDownload"])
    assertTrue(memberRows.all { it.downloadUrl == null })
  }

  @Test
  fun `a failing fetch is reported and yields no rows`() = runBlocking<Unit> {
    var failure: Exception? = null
    val model =
        ArchivedGroupsReport.reportModel(
            null, false, fetch = { throw IllegalStateException("down") }, onFailure = { failure = it })

    assertEquals(true, model["archivedLoadFailed"])
    assertEquals("down", failure?.message)
    assertEquals(emptyList<ArchivedGroupRow>(), model["archivedRows"])
  }

  @Test
  fun `committee count covers the success, failure, timeout and no prefix cases`() = runBlocking<Unit> {
    val jewelry = Committees.findBySlug("jewelry")!!
    val warnings = mutableListOf<String>()

    assertEquals(2, ArchivedGroupsReport.committeeCount(jewelry, { listOf(group(), group()) }, { warnings += it }))
    assertEquals(0, ArchivedGroupsReport.committeeCount(jewelry, { emptyList() }, { warnings += it }))
    assertTrue(warnings.isEmpty())

    assertEquals(0, ArchivedGroupsReport.committeeCount(jewelry, { error("boom") }, { warnings += it }))
    assertEquals(
        0,
        ArchivedGroupsReport.committeeCount(
            jewelry, { delay(5_000); listOf(group()) }, { warnings += it }, timeoutMs = 50))
    assertEquals(listOf("boom", "timed out"), warnings)

    var called = false
    assertEquals(
        0,
        ArchivedGroupsReport.committeeCount(
            Committees.findBySlug("logistics")!!, { called = true; listOf(group()) }, { warnings += it }))
    assertFalse(called)
  }
}
