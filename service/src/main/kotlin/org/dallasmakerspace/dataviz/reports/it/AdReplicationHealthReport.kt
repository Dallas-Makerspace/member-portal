package org.dallasmakerspace.dataviz.reports.it

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.dallasmakerspace.adreplication.AdReplicationHealthEsRepository
import org.dallasmakerspace.adreplication.StoredReplicationSample
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse

// Latency past which an otherwise-succeeding link is flagged stale rather than OK - AD's own
// default tombstone-lifetime-based alerting (repadmin /replsummary) uses much longer windows, but
// this is meant to catch the "one DC quietly fell behind" pattern described in the report request
// well before it becomes a lingering-object problem.
private const val STALE_LATENCY_MINUTES = 60L

// How far back to look for "the latest sample per pair" - generous relative to the cron job's
// expected 15-30 min cadence so one missed/slow run doesn't make a healthy DC look unreachable.
private const val SNAPSHOT_LOOKBACK_HOURS = 3L

private val CHICAGO_ZONE = ZoneId.of("America/Chicago")
private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.US)

// The naming-context DN AD reports (e.g. "CN=Schema,CN=Configuration,DC=dms,DC=local") is
// unreadable and, worse, wide enough by itself to force the whole table into horizontal scroll -
// collapse it to the one meaningful word.
private fun friendlyPartitionName(partitionDn: String): String =
    when {
      partitionDn.startsWith("CN=Schema", ignoreCase = true) -> "Schema"
      partitionDn.startsWith("CN=Configuration", ignoreCase = true) -> "Configuration"
      partitionDn.isBlank() -> ""
      else -> "Domain"
    }

/**
 * Current replication health snapshot across the configured domain controllers
 * (`app.ad-replication.dc-hostnames`) - the most recent sample per (source DC, partner DC,
 * partition) tuple in Elasticsearch, written periodically by `AdReplicationHealthCronJob`. See
 * `AdReplicationLatencyTrendReport`/`AdReplicationFailureTrendReport` for the trend charts on the
 * same report page.
 */
class AdReplicationHealthReport
@Inject
constructor(
    private val esRepository: AdReplicationHealthEsRepository,
    private val appConfig: AppConfig,
) : DataVizReport() {
  override fun getName(): String = "ad-replication-health"

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val startTime = System.currentTimeMillis()
    val dcHostnames =
        appConfig
            .requireStringProperty("app.ad-replication.dc-hostnames")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // Newest first, so the first sample seen per (server, partner, partition) key is the latest.
    val recentSamples = esRepository.fetchRecentSamples(SNAPSHOT_LOOKBACK_HOURS)
    val latestPerTuple =
        recentSamples.distinctBy { Triple(it.server, it.partner, it.partition) }

    // Case-insensitive: WinRM/AD reports each DC's own configured hostname casing (e.g.
    // "DC1.dms.local"), which need not match how it was typed into app.ad-replication.dc-hostnames.
    val reportingDcs = latestPerTuple.map { it.server.lowercase() }.toSet()
    val unreachableDcs = dcHostnames.filterNot { it.lowercase() in reportingDcs }

    val dataItems =
        latestPerTuple.map { it.toDataItem() } + unreachableDcs.map { unreachableRow(it) }

    val timeTaken = System.currentTimeMillis() - startTime
    val metadata =
        mapOf(
            "Generated at" to Instant.now().toString(),
            "Time taken" to "${timeTaken}ms",
            "Domain controllers configured" to "${dcHostnames.size}",
            "Domain controllers reporting" to "${reportingDcs.size}",
            "Snapshot lookback" to "${SNAPSHOT_LOOKBACK_HOURS}h",
            "Source" to "Elasticsearch (member-portal-ad-replication-health)",
        )

    return DataVizResponse(data = dataItems, dataFields = dataFields(), metadata = metadata)
  }

  private fun unreachableRow(hostname: String) =
      DataItem(
          values =
              mapOf(
                  "Source DC" to JsonPrimitive(hostname),
                  "Partner DC" to JsonPrimitive(""),
                  "Partition" to JsonPrimitive(""),
                  "Last Success" to JsonPrimitive("N/A"),
                  "Latency (min)" to JsonPrimitive(-1),
                  "Consecutive Failures" to JsonPrimitive(-1),
                  "Status" to statusBadge("secondary", "UNREACHABLE"),
              ))

  private fun StoredReplicationSample.toDataItem(): DataItem {
    val latencyMinutes = lastReplicationSuccess?.let { Duration.between(it, Instant.now()).toMinutes() }
    val status =
        when {
          consecutiveReplicationFailures > 0 ->
              statusBadge("danger", "FAILING (${consecutiveReplicationFailures})")
          lastReplicationSuccess == null -> statusBadge("danger", "NEVER SYNCED")
          latencyMinutes != null && latencyMinutes > STALE_LATENCY_MINUTES ->
              statusBadge("warning", "STALE (${latencyMinutes}m)")
          else -> statusBadge("success", "OK")
        }
    return DataItem(
        values =
            mapOf(
                "Source DC" to JsonPrimitive(server),
                "Partner DC" to JsonPrimitive(partner),
                "Partition" to JsonPrimitive(friendlyPartitionName(partition)),
                "Last Success" to
                    (lastReplicationSuccess?.let { JsonPrimitive(TIMESTAMP_FORMAT.format(it.atZone(CHICAGO_ZONE))) }
                        ?: JsonPrimitive("Never")),
                "Latency (min)" to (latencyMinutes?.let { JsonPrimitive(it) } ?: JsonPrimitive(-1)),
                "Consecutive Failures" to JsonPrimitive(consecutiveReplicationFailures),
                "Status" to status,
            ))
  }

  private fun statusBadge(variant: String, text: String): JsonElement = buildJsonObject {
    put("variant", JsonPrimitive(variant))
    put("text", JsonPrimitive(text))
  }

  private fun dataFields() =
      listOf(
          DataField(name = "Source DC", type = DataType.STRING, label = "Source DC"),
          DataField(name = "Partner DC", type = DataType.STRING, label = "Partner DC"),
          DataField(name = "Partition", type = DataType.STRING, label = "Partition"),
          // STRING, not RELATIVE_DATE - dms-relative-date.js hard-errors on an empty/non-ISO
          // timestamp, which "Never"/"N/A" rows would trigger on every page load.
          DataField(name = "Last Success", type = DataType.STRING, label = "Last Success"),
          DataField(name = "Latency (min)", type = DataType.NUMBER, label = "Latency (min)"),
          DataField(name = "Consecutive Failures", type = DataType.NUMBER, label = "Consecutive Failures"),
          DataField(name = "Status", type = DataType.BADGE, label = "Status"),
      )
}
