package org.dallasmakerspace.cron

import dagger.Reusable
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.dallasmakerspace.adreplication.AdReplicationHealthEsRepository
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.core.logging.AppInMemoryLogger
import org.dallasmakerspace.winrm.IWinRmClient

/**
 * Periodically samples AD replication health from every configured domain controller (via WinRM -
 * see [IWinRmClient.getReplicationPartnerStatus]) and writes the results to Elasticsearch (see
 * [AdReplicationHealthEsRepository]), rather than a DB table: this is monitoring telemetry, not
 * member-portal domain data.
 *
 * Triggered externally on a schedule via `GET /cron/ad-replication-health` (see other jobs in
 * this package and the cron routes block in `Routing.kt`) - this class does no scheduling of its
 * own.
 */
@Reusable
class AdReplicationHealthCronJob
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val winRmClient: IWinRmClient,
    private val esRepository: AdReplicationHealthEsRepository,
    private val appConfig: AppConfig,
) : CronJob<AdReplicationHealthCronJobParams>(AdReplicationHealthCronJobParams::class, loggerFactory) {

  override suspend fun run(params: AdReplicationHealthCronJobParams): String {
    val startTime = System.currentTimeMillis()
    val sampledAt = Instant.now()

    val dcHostnames =
        appConfig
            .requireStringProperty("app.ad-replication.dc-hostnames")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    val resultsByDc =
        coroutineScope {
          dcHostnames
              .map { hostname -> hostname to async { winRmClient.getReplicationPartnerStatus(hostname) } }
              .map { (hostname, deferred) -> hostname to deferred.await() }
        }

    val allSamples = resultsByDc.mapNotNull { (_, statuses) -> statuses }.flatten()
    val unreachableDcs = resultsByDc.filter { (_, statuses) -> statuses == null }.map { (hostname, _) -> hostname }

    val writtenCount = esRepository.writeSamples(sampledAt, allSamples)

    val timeTaken = System.currentTimeMillis() - startTime
    log.info(
        "AdReplicationHealthCronJob complete in ${timeTaken}ms: " +
            "dcsQueried=${dcHostnames.size} " +
            "dcsUnreachable=${unreachableDcs.size} " +
            "samplesCollected=${allSamples.size} " +
            "samplesWritten=$writtenCount")
    if (unreachableDcs.isNotEmpty()) {
      log.warn("Unreachable DCs this run: ${unreachableDcs.joinToString(", ")}")
    }

    val logBuffer = (log as? AppInMemoryLogger)?.getLog() ?: ""
    (log as? AppInMemoryLogger)?.clear()
    return logBuffer
  }
}
