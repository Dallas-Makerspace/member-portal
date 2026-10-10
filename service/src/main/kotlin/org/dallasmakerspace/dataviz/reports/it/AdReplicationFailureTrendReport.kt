package org.dallasmakerspace.dataviz.reports.it

import java.time.Instant
import javax.inject.Inject
import org.dallasmakerspace.adreplication.AdReplicationHealthEsRepository
import org.dallasmakerspace.adreplication.adReplicationKibanaLink
import org.dallasmakerspace.adreplication.pivotTrend
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.api.DataVizReport
import org.dallasmakerspace.models.DataVizResponse

private const val DEFAULT_TREND_DAYS = 7

/**
 * Consecutive replication failure count over time, one line per source-DC to partner-DC pair,
 * hourly buckets (see `pivotTrend`). Shown alongside `AdReplicationLatencyTrendReport`: the
 * latency chart says *when/who* is affected, this says whether it's escalating (1 -> 5 -> 12,
 * worth investigating now) or self-healing (a single spike back to 0, a transient blip) - the
 * "should I lose sleep over this" signal from the report's design review.
 */
class AdReplicationFailureTrendReport
@Inject
constructor(
    private val esRepository: AdReplicationHealthEsRepository,
    private val appConfig: AppConfig,
) : DataVizReport() {
  override fun getName(): String = "ad-replication-failure-trend"

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val startTime = System.currentTimeMillis()
    val days = params["days"]?.firstOrNull()?.toIntOrNull() ?: DEFAULT_TREND_DAYS

    val samples = esRepository.fetchRecentSamples(days.toLong() * HOURS_PER_DAY)
    val (dataItems, dataFields) = pivotTrend(samples) { it.consecutiveReplicationFailures.toLong() }

    val timeTaken = System.currentTimeMillis() - startTime
    val metadata = buildMap {
      put("Generated at", Instant.now().toString())
      put("Time taken", "${timeTaken}ms")
      put("Window", "${days}d")
      put("Samples", "${samples.size}")
      put("Source", "Elasticsearch (member-portal-ad-replication-health)")
      adReplicationKibanaLink(appConfig, days)?.let { put("Kibana", it) }
    }

    return DataVizResponse(data = dataItems, dataFields = dataFields, metadata = metadata)
  }
}

private const val HOURS_PER_DAY = 24
