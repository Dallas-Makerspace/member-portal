package org.dallasmakerspace.dataviz.reports.it

import java.time.Instant
import javax.inject.Inject
import org.dallasmakerspace.adreplication.AdReplicationHealthEsRepository
import org.dallasmakerspace.adreplication.pivotTrend
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.models.DataVizResponse

private const val DEFAULT_TREND_DAYS = 7

/**
 * Replication latency (minutes since last successful sync) over time, one line per source-DC to
 * partner-DC pair, hourly buckets (see `pivotTrend`). Shown alongside the live snapshot table and
 * `AdReplicationFailureTrendReport` on the same report page - recommended per the report's design
 * review as the primary early-warning signal for a DC silently falling behind, since a sustained
 * band of elevated latency reads very differently from a single blip.
 */
class AdReplicationLatencyTrendReport
@Inject
constructor(
    private val esRepository: AdReplicationHealthEsRepository,
) : DataVizReport() {
  override fun getName(): String = "ad-replication-latency-trend"

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val startTime = System.currentTimeMillis()
    val days = params["days"]?.firstOrNull()?.toIntOrNull() ?: DEFAULT_TREND_DAYS

    val samples = esRepository.fetchRecentSamples(days.toLong() * HOURS_PER_DAY)
    val (dataItems, dataFields) = pivotTrend(samples) { it.latencyMinutes }

    val timeTaken = System.currentTimeMillis() - startTime
    val metadata =
        mapOf(
            "Generated at" to Instant.now().toString(),
            "Time taken" to "${timeTaken}ms",
            "Window" to "${days}d",
            "Samples" to "${samples.size}",
            "Source" to "Elasticsearch (member-portal-ad-replication-health)",
        )

    return DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)
  }
}

private const val HOURS_PER_DAY = 24
