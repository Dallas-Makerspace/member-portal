package org.dallasmakerspace.adreplication

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.dallasmakerspace.models.DataType

private fun sample(
    timestamp: String,
    pairKey: String,
    latencyMinutes: Long? = null,
    consecutiveReplicationFailures: Int = 0,
) =
    StoredReplicationSample(
        timestamp = Instant.parse(timestamp),
        server = pairKey.substringBefore("->"),
        partner = pairKey.substringAfter("->"),
        partition = "DC=dms,DC=local",
        pairKey = pairKey,
        lastReplicationSuccess = null,
        consecutiveReplicationFailures = consecutiveReplicationFailures,
        lastReplicationResult = 0,
        latencyMinutes = latencyMinutes,
    )

class AdReplicationTrendPivotTest {

  @Test
  fun `pivotTrend produces one Hour STRING field plus one NUMBER field per pair`() {
    val samples =
        listOf(sample("2026-09-19T17:00:00Z", "DC1->DC2", latencyMinutes = 5))

    val (_, fields) = pivotTrend(samples) { it.latencyMinutes }

    assertEquals("Hour", fields[0].name)
    assertEquals(DataType.STRING, fields[0].type)
    assertEquals(listOf("DC1->DC2"), fields.drop(1).map { it.name })
    assertEquals(DataType.NUMBER, fields[1].type)
  }

  @Test
  fun `pivotTrend buckets timestamps to the hour in Chicago time, not UTC`() {
    // 17:55 UTC on 2026-09-19 is 12:55 CDT (UTC-5) - should truncate to the 12:00 Chicago bucket,
    // not a 17:00 UTC bucket.
    val samples = listOf(sample("2026-09-19T17:55:00Z", "DC1->DC2", latencyMinutes = 5))

    val (items, _) = pivotTrend(samples) { it.latencyMinutes }

    assertEquals(1, items.size)
    assertEquals(JsonPrimitive("09/19 12:00"), items.single().values["Hour"])
  }

  @Test
  fun `pivotTrend aggregates same bucket-pair with MAX, not average or last-write`() {
    // A real blip (14) must survive even though most samples in the hour are healthy (2) - MAX,
    // not averaging, is what makes this chart useful for catching brief spikes.
    val samples =
        listOf(
            sample("2026-09-19T17:00:00Z", "DC1->DC2", latencyMinutes = 2),
            sample("2026-09-19T17:15:00Z", "DC1->DC2", latencyMinutes = 14),
            sample("2026-09-19T17:30:00Z", "DC1->DC2", latencyMinutes = 3),
        )

    val (items, _) = pivotTrend(samples) { it.latencyMinutes }

    assertEquals(1, items.size)
    assertEquals(JsonPrimitive(14L), items.single().values["DC1->DC2"])
  }

  @Test
  fun `pivotTrend gives each DC pair its own independent column`() {
    val samples =
        listOf(
            sample("2026-09-19T17:00:00Z", "DC1->DC2", latencyMinutes = 5),
            sample("2026-09-19T17:00:00Z", "DC1->DC3", latencyMinutes = 9),
        )

    val (items, fields) = pivotTrend(samples) { it.latencyMinutes }

    assertEquals(setOf("Hour", "DC1->DC2", "DC1->DC3"), fields.map { it.name }.toSet())
    assertEquals(JsonPrimitive(5L), items.single().values["DC1->DC2"])
    assertEquals(JsonPrimitive(9L), items.single().values["DC1->DC3"])
  }

  @Test
  fun `pivotTrend renders a missing bucket-pair combo as a gap (JsonNull), not zero`() {
    // DC1->DC3 has no sample in this hour - a real "unreachable this hour" signal, meaningfully
    // different from "0 failures" or "0 minutes latency".
    val samples =
        listOf(
            sample("2026-09-19T17:00:00Z", "DC1->DC2", latencyMinutes = 5),
            sample("2026-09-19T18:00:00Z", "DC1->DC3", latencyMinutes = 5),
        )

    val (items, _) = pivotTrend(samples) { it.latencyMinutes }

    val firstHour = items.first { it.values["Hour"] == JsonPrimitive("09/19 12:00") }
    assertEquals(JsonNull, firstHour.values["DC1->DC3"])
  }

  @Test
  fun `pivotTrend orders buckets chronologically regardless of input order`() {
    val samples =
        listOf(
            sample("2026-09-19T19:00:00Z", "DC1->DC2", latencyMinutes = 3),
            sample("2026-09-19T17:00:00Z", "DC1->DC2", latencyMinutes = 1),
            sample("2026-09-19T18:00:00Z", "DC1->DC2", latencyMinutes = 2),
        )

    val (items, _) = pivotTrend(samples) { it.latencyMinutes }

    assertEquals(
        listOf("09/19 12:00", "09/19 13:00", "09/19 14:00"),
        items.map { (it.values["Hour"] as JsonPrimitive).content })
  }

  @Test
  fun `pivotTrend works for the failure-count selector too`() {
    val samples = listOf(sample("2026-09-19T17:00:00Z", "DC1->DC2", consecutiveReplicationFailures = 4))

    val (items, _) = pivotTrend(samples) { it.consecutiveReplicationFailures.toLong() }

    assertEquals(JsonPrimitive(4L), items.single().values["DC1->DC2"])
  }

  @Test
  fun `pivotTrend returns no items for an empty sample list`() {
    val (items, fields) = pivotTrend(emptyList()) { it.latencyMinutes }

    assertEquals(emptyList(), items)
    assertEquals(listOf("Hour"), fields.map { it.name })
  }
}
