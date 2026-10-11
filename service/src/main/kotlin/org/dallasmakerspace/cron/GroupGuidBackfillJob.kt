package org.dallasmakerspace.cron

import dagger.Reusable
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.AdGroupRef
import org.dallasmakerspace.members.GroupGuidClassification
import org.dallasmakerspace.members.IGroupGuidBackfillStore
import org.dallasmakerspace.members.classifyGroupGuids

/**
 * Fills groups.object_guid from AD (stage 2 of the objectGUID migration). Dry-run by default. The
 * report holds counts and row ids only, never group names or DNs.
 */
@Reusable
class GroupGuidBackfillJob
internal constructor(
    loggerFactory: LoggerFactory,
    private val activeDirectoryService: IActiveDirectoryService,
    private val store: IGroupGuidBackfillStore,
    private val today: () -> LocalDate,
) : CronJob<GroupGuidBackfillJobParams>(GroupGuidBackfillJobParams::class, loggerFactory) {

  @Inject
  constructor(
      loggerFactory: LoggerFactory,
      activeDirectoryService: IActiveDirectoryService,
      store: IGroupGuidBackfillStore,
  ) : this(loggerFactory, activeDirectoryService, store, LocalDate::now)

  override suspend fun run(params: GroupGuidBackfillJobParams): String {
    log.info("Running group GUID backfill; params: $params")
    val rows = store.listRows()
    val adGroups =
        withContext(Dispatchers.IO) { activeDirectoryService.getAllGroups() }
            .map { AdGroupRef(it.objectGuid, it.distinguishedName, it.cn) }
    val result = classifyGroupGuids(rows, adGroups)

    val report = StringBuilder()
    report.appendLine("mode=${if (params.dryRun) "DRY_RUN" else "REAL_RUN"}")
    report.appendLine("rows=${rows.size} adGroups=${adGroups.size}")
    appendClassification(report, result)

    if (!params.dryRun) {
      if (result.ambiguous.isNotEmpty() || result.unusableGuid.isNotEmpty()) {
        report.appendLine("REFUSED: AMBIGUOUS or UNUSABLE_GUID entries must be resolved by hand")
      } else {
        report.append(applyUpdates(result, params.batchSize.coerceIn(1, MAX_BATCH_SIZE)))
      }
    }
    return report.toString().also { log.info(it) }
  }

  private suspend fun applyUpdates(result: GroupGuidClassification, batchSize: Int): String {
    val backup = store.createBackupTable(today())
    var updated = 0
    var unchanged = 0
    val failed = mutableListOf<Int>()
    for (batch in result.matched.entries.map { it.key to it.value }.chunked(batchSize)) {
      val written = store.setGuidsIfNull(batch)
      updated += written.updated
      unchanged += written.unchanged
      failed += written.failedIds
    }
    return "backup=$backup\nupdated=$updated unchanged=$unchanged failed=${failed.size}" +
        " failedIds=$failed\n"
  }

  private fun appendClassification(report: StringBuilder, result: GroupGuidClassification) {
    report.appendLine("MATCHED=${result.matched.size}")
    report.appendLine("ALREADY_SET=${result.alreadySet.size}")
    report.appendLine(
        "ROW_WITHOUT_AD_GROUP=${result.rowsWithoutAdGroup.size} " +
            "ids=${result.rowsWithoutAdGroup.take(MAX_LISTED_IDS)}")
    report.appendLine("AD_GROUP_WITHOUT_ROW=${result.adGroupsWithoutRow}")
    val reasons = result.ambiguous.values.groupingBy { it }.eachCount()
    report.appendLine(
        "AMBIGUOUS=${result.ambiguous.size} ids=${result.ambiguous.keys.take(MAX_LISTED_IDS)} " +
            "reasons=$reasons")
    report.appendLine(
        "UNUSABLE_GUID=${result.unusableGuid.size} ids=${result.unusableGuid.take(MAX_LISTED_IDS)}")
  }

  companion object {
    const val DEFAULT_BATCH_SIZE = 50
    private const val MAX_BATCH_SIZE = 500
    private const val MAX_LISTED_IDS = 500
  }
}
