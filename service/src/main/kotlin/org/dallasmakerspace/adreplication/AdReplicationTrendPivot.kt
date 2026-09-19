package org.dallasmakerspace.adreplication

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType

private val CHICAGO_ZONE = ZoneId.of("America/Chicago")
private val HOUR_LABEL_FORMAT = DateTimeFormatter.ofPattern("MM/dd HH:00", Locale.US)

/**
 * Pivots raw [samples] into the wide, one-row-per-hour-bucket / one-column-per-DC-pair shape
 * `dms-line-chart-visualizer.js` expects (see `CalTrendsEvents` for the same wide-format
 * convention used elsewhere in this codebase): one STRING "Hour" label field plus one NUMBER
 * field per pair, each field becoming its own line/series.
 *
 * Buckets are hourly, in Chicago time (per this repo's timestamp-handling convention), and
 * [valueSelector] is aggregated with MAX per bucket/pair - not average - so a real, if brief,
 * spike (the "one DC quietly fell behind" pattern this report exists to catch) isn't smoothed
 * away by averaging with healthy samples in the same hour. A missing bucket/pair renders as a
 * gap (JsonNull), not zero, since "no sample" and "zero failures" are meaningfully different.
 */
fun pivotTrend(
    samples: List<StoredReplicationSample>,
    valueSelector: (StoredReplicationSample) -> Long?,
): Pair<List<DataItem>, List<DataField>> {
  data class HourBucket(val epochHour: Long, val label: String)

  fun bucketOf(sample: StoredReplicationSample): HourBucket {
    val truncated = sample.timestamp.atZone(CHICAGO_ZONE).truncatedTo(ChronoUnit.HOURS)
    return HourBucket(truncated.toEpochSecond() / SECONDS_PER_HOUR, HOUR_LABEL_FORMAT.format(truncated))
  }

  val pairKeys = samples.map { it.pairKey }.distinct().sorted()
  val valueByBucketAndPair =
      samples
          .groupBy { bucketOf(it) to it.pairKey }
          .mapValues { (_, group) -> group.mapNotNull(valueSelector).maxOrNull() }
  val orderedBuckets = samples.map { bucketOf(it) }.distinct().sortedBy { it.epochHour }

  val dataItems =
      orderedBuckets.map { bucket ->
        val values = mutableMapOf<String, JsonElement>("Hour" to JsonPrimitive(bucket.label))
        pairKeys.forEach { pairKey ->
          val value = valueByBucketAndPair[bucket to pairKey]
          values[pairKey] = value?.let { JsonPrimitive(it) } ?: JsonNull
        }
        DataItem(values = values)
      }
  // name stays the full pairKey (the lookup key into `values`, and the grouping identity), but
  // label - what the chart legend actually shows - is shortened, so a change here can never
  // desync a series from its data.
  val dataFields =
      listOf(DataField(name = "Hour", type = DataType.STRING, label = "Hour")) +
          pairKeys.map { pairKey ->
            val label = pairKey.split("->").joinToString("->") { shortDcName(it) }
            DataField(name = pairKey, type = DataType.NUMBER, label = label)
          }

  return dataItems to dataFields
}

private const val SECONDS_PER_HOUR = 3600L
