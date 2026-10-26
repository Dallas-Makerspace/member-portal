package org.dallasmakerspace.cron

import dagger.Reusable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.discourse.DiscourseAutoLinkService

// File-level because @Reusable doesn't guarantee a single job instance.
private val running = AtomicBoolean(false)

/**
 * Nightly Talk auto-link (called by Jenkins). Dry run unless dryRun=false; the report holds
 * usernames and counts, never emails.
 */
@Reusable
class DiscourseAutoLinkCronJob
@Inject
constructor(
    loggerFactory: LoggerFactory,
    private val autoLinkService: DiscourseAutoLinkService,
) : CronJob<DiscourseAutoLinkCronJobParams>(DiscourseAutoLinkCronJobParams::class, loggerFactory) {

  override suspend fun run(params: DiscourseAutoLinkCronJobParams): String {
    if (!running.compareAndSet(false, true)) return "already running"
    try {
      log.info("Running Talk auto-link; params: $params")
      return autoLinkService.runNightly(params.dryRun).also { log.info(it) }
    } finally {
      running.set(false)
    }
  }
}

data class DiscourseAutoLinkCronJobParams(val dryRun: Boolean = true) : CronJobParams {
  override fun toString() = "[dryRun=$dryRun]"
}
