package org.dallasmakerspace.cron

data class GroupGuidBackfillJobParams(
    val dryRun: Boolean = true,
    val batchSize: Int = GroupGuidBackfillJob.DEFAULT_BATCH_SIZE,
) : CronJobParams {
  override fun toString() = "[dryRun=$dryRun, batchSize=$batchSize]"
}
