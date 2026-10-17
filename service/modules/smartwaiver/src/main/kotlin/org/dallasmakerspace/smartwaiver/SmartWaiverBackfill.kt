package org.dallasmakerspace.smartwaiver

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory

private val CHICAGO: ZoneId = ZoneId.of("America/Chicago")
private val FIRST_WAIVER_DAY: LocalDate = LocalDate.of(2014, 1, 1)
private const val STAGE_DISCOVER = "discover"
private const val STAGE_ENRICH = "enrich"
private const val STAGE_CHECKINS = "checkins"
private const val RUN = "run"
private const val ENRICH_BATCH = 50
private const val LOG_EVERY_UNITS = 50
private const val MAX_CONSECUTIVE_FAILURES = 10
private const val MAX_RECENT_ERRORS = 200
private const val CALLS_PER_WAIVER_GUESS = 2.0
private const val ENRICH_CALLS_PER_WAIVER = 1.8 // one waiver call, plus photos for most
private const val CHECKINS_PER_PAGE = 100
private const val PRIOR_WEIGHT = 10.0
private const val SEARCH_RESULTS_PER_PAGE = 100.0
private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_HOUR = 3_600L
private const val MS_PER_SECOND = 1000L
private const val ONE_MINUTE_MS = 60_000L
private const val FIFTEEN_MINUTES_MS = 15 * ONE_MINUTE_MS
private const val KEEP_ALIVE_MS = 15_000L
private const val SLOW_MODE_FACTOR = 2L
private const val DEFAULT_DAILY_BUDGET = 25_000
private const val DEFAULT_INTERVAL_MS = 3_000L

enum class RunState {
  IDLE,
  RUNNING,
  WAITING,
  PAUSED,
  DONE,
  FAILED,
}

/** One line of the run's plan, shown before the work starts. */
@Serializable
data class PlanStep(
    val title: String,
    val units: Int?,
    val unit: String,
    val etaSeconds: Long?,
    val approximate: Boolean = false,
)

/** One snapshot of the backfill, sent over the status and stream routes. */
@Serializable
data class BackfillProgress(
    val state: RunState = RunState.IDLE,
    /** The trial size this run was started with, or null for a full run. */
    val limit: Int? = null,
    val plan: List<PlanStep> = emptyList(),
    val stageIndex: Int = 0,
    val stageCount: Int = 0,
    val stageTitle: String? = null,
    val stageUnit: String? = null,
    /** True when [stageTotal] is an estimate (the check-in history has no exact count). */
    val stageApprox: Boolean = false,
    /** What the job is doing right now, in plain words. */
    val activity: String? = null,
    val stage: String? = null,
    val stageDone: Int = 0,
    val stageTotal: Int? = null,
    val discovered: Int = 0,
    val mirrored: Int = 0,
    val failed: Int = 0,
    val photosStored: Int = 0,
    val checkinsAdded: Int = 0,
    val requestsToday: Int = 0,
    val dailyBudget: Int = DEFAULT_DAILY_BUDGET,
    /** How fast the job calls SmartWaiver, in calls per minute. */
    val paceCallsPerMinute: Double = 0.0,
    val requestsPerMinute1: Double = 0.0,
    val requestsPerMinute15: Double = 0.0,
    val rateLimitHits: Long = 0,
    val slowMode: Boolean = false,
    val waitingUntil: String? = null,
    /** Epoch seconds the run wakes up at, while WAITING; lets a client show a countdown. */
    val waitingUntilEpoch: Long? = null,
    val etaStageSeconds: Long? = null,
    val etaOverallSeconds: Long? = null,
    val lastError: String? = null,
    val startedAt: String? = null,
    val updatedAt: String? = null,
    val message: String? = null,
)

/** What the work is expected to take, from SmartWaiver's own counts; used for the plan and the ETA. */
internal data class Estimate(
    val discoverUnits: Int = 0,
    val discoverCalls: Double = 0.0,
    val enrichUnits: Int = 0,
    val checkinPages: Int = 0,
    val expectedWaivers: Int? = null,
)

enum class StartResult {
  STARTED,
  ALREADY_RUNNING,
}

/**
 * Seconds until [remainingCalls] API calls are done at one call per [intervalSeconds], with at most
 * [budget] calls per day: today's leftover first, then a wait until midnight, then whole days.
 */
internal fun etaSeconds(
    remainingCalls: Long,
    requestsToday: Int,
    budget: Int,
    intervalSeconds: Double,
    secondsUntilMidnight: Long,
): Long {
  if (remainingCalls <= 0) return 0
  val capacityToday = maxOf(0, budget - requestsToday).toLong()
  if (remainingCalls <= capacityToday) return (remainingCalls * intervalSeconds).toLong()
  val left = remainingCalls - capacityToday
  val fullDays = (left - 1) / budget
  val lastDayCalls = left - fullDays * budget
  return secondsUntilMidnight + fullDays * SECONDS_PER_DAY + (lastDayCalls * intervalSeconds).toLong()
}

/**
 * Mirrors the whole SmartWaiver history in the background: Discover (find every waiver), Enrich
 * (fetch each one in full plus its photos) and Check-ins. It runs inside the service, resumes from
 * its saved cursors after a restart, and is deliberately slow: a daily request budget and a fixed
 * pace keep it a polite API client. When the day's budget is spent it sleeps on one timer until
 * midnight Chicago time; nothing polls.
 */
@Singleton
class SmartWaiverBackfill
internal constructor(
    private val client: ISmartwaiverApiClient,
    private val repository: SmartWaiverMirrorRepository,
    private val mirrorService: SmartWaiverMirrorService,
    private val checkinSync: SmartWaiverCheckinSync,
    loggerFactory: LoggerFactory,
    private val dailyBudget: Int,
    private val intervalMs: Long,
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
  @Inject
  constructor(
      client: ISmartwaiverApiClient,
      repository: SmartWaiverMirrorRepository,
      mirrorService: SmartWaiverMirrorService,
      checkinSync: SmartWaiverCheckinSync,
      loggerFactory: LoggerFactory,
      appConfig: AppConfig,
  ) : this(
      client,
      repository,
      mirrorService,
      checkinSync,
      loggerFactory,
      appConfig.getLongProperty("app.smartwaiver.backfill.daily-request-budget", DEFAULT_DAILY_BUDGET.toLong()).toInt(),
      appConfig.getLongProperty("app.smartwaiver.backfill.request-interval-ms", DEFAULT_INTERVAL_MS),
  )

  private val log = loggerFactory.create(javaClass)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lock = Any()
  private var job: Job? = null
  private val paused = MutableStateFlow(false)
  private val updates =
      MutableSharedFlow<BackfillProgress>(replay = 1, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  private val json = Json { encodeDefaults = true }

  @Volatile private var progress = BackfillProgress(dailyBudget = dailyBudget)
  private val recentErrors = ArrayDeque<String>()
  private val samples = ArrayDeque<Pair<Long, Long>>() // epoch ms to total API requests

  // Run-wide counters, updated only from the single run coroutine.
  private var requestsToday = 0
  private var budgetDay: LocalDate? = null
  private var lastRequestTotal = 0L
  private var lastRateLimitHits = 0L
  private var slowMode = false
  private var startedAt: LocalDateTime? = null
  private var consecutiveFailures = 0
  private var unitsThisStage = 0
  private var callsThisStage = 0L
  private var stageStartedMs = 0L
  private var estimate = Estimate()
  private var currentStage: String? = null

  fun status(): BackfillProgress = progress

  fun recentErrors(): List<String> = synchronized(lock) { recentErrors.toList() }

  /** Server-sent-event frames: every progress change, plus a comment every 15 s to keep idle connections open. */
  fun frames(): Flow<String> = channelFlow {
    val keepAlive = launch {
      while (true) {
        delay(KEEP_ALIVE_MS)
        send(": keep-alive\n\n")
      }
    }
    try {
      updates.asSharedFlow().collect { send("data: ${json.encodeToString(it)}\n\n") }
    } finally {
      keepAlive.cancel()
    }
  }

  fun pause() {
    paused.value = true
  }

  fun resume() {
    paused.value = false
  }

  /**
   * Starts the run, or continues from the saved cursors; [restart] clears them and begins again.
   * With [limit], only that many already-stored waivers are mirrored in full (no discovery, no
   * check-ins) and the stages are left unfinished, so a later full run carries on from there.
   */
  fun start(restart: Boolean = false, limit: Int? = null): StartResult {
    synchronized(lock) {
      if (job?.isActive == true) return StartResult.ALREADY_RUNNING
      log.info(
          "SmartWaiver backfill starting (restart=$restart, limit=$limit, budget $dailyBudget " +
              "calls/day, one call per ${intervalMs}ms)")
      paused.value = false
      // Replace the previous run's snapshot now, so a client that attaches right after start()
      // doesn't see that run's DONE and leave.
      progress =
          BackfillProgress(
              dailyBudget = dailyBudget, state = RunState.RUNNING, limit = limit, activity = "Starting")
      publish()
      job = scope.launch { runAll(restart, limit) }
    }
    return StartResult.STARTED
  }

  /**
   * Ends the run now, keeping the cursors. It is saved as IDLE so a restart of the service does not
   * pick it up again; call [start] to continue from where it stopped.
   */
  suspend fun stop() {
    val running = synchronized(lock) { job.also { job = null } }
    running?.cancelAndJoin()
    paused.value = false
    progress = progress.copy(state = RunState.IDLE, waitingUntil = null)
    publish()
    repository.saveState(
        BackfillState(
            RUN, RunState.IDLE.name, null, progress.mirrored, null, requestsToday, budgetDay, progress.lastError,
            startedAt, now(), null))
  }

  /** After a restart of the service: carry on if the previous run was cut off. */
  @Suppress("TooGenericExceptionCaught")
  suspend fun resumeIfInterrupted() {
    try {
      val state = repository.getState(RUN) ?: return
      if (state.status == RunState.RUNNING.name || state.status == RunState.WAITING.name) {
        log.info("SmartWaiver backfill was running when the service stopped; resuming")
        start()
      }
    } catch (e: Exception) {
      log.warn("Could not check for an interrupted SmartWaiver backfill: ${e.message}")
    }
  }

  // ---- the run ----

  @Suppress("TooGenericExceptionCaught")
  private suspend fun runAll(restart: Boolean, limit: Int?) {
    try {
      begin(restart, limit)
      makePlan(limit)
      if (limit != null) {
        enrich(limit)
        progress = progress.copy(message = "Trial of up to $limit waivers finished; start again without a limit for the rest")
        finish(RunState.DONE, null)
        return
      }
      if (!stageDone(STAGE_DISCOVER)) discover()
      if (!stageDone(STAGE_ENRICH)) enrich()
      if (!stageDone(STAGE_CHECKINS)) checkins()
      finish(RunState.DONE, null)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.error("SmartWaiver backfill stopped: ${e.message}", e)
      finish(RunState.FAILED, e.message ?: e.javaClass.simpleName)
    }
  }

  private suspend fun begin(restart: Boolean, limit: Int?) {
    val now = now()
    val saved = repository.getState(RUN)
    if (restart) {
      listOf(STAGE_DISCOVER, STAGE_ENRICH, STAGE_CHECKINS).forEach { saveStage(it, "IDLE", null, 0, null) }
    }
    startedAt = if (restart || saved?.startedAt == null) now else saved.startedAt
    budgetDay = saved?.budgetDay
    requestsToday = if (restart) 0 else saved?.requestsToday ?: 0
    rollBudgetDay()
    val stats = client.stats()
    lastRequestTotal = stats.requests
    lastRateLimitHits = stats.rateLimitHits
    slowMode = false
    consecutiveFailures = 0
    progress =
        BackfillProgress(
            dailyBudget = dailyBudget,
            paceCallsPerMinute = ONE_MINUTE_MS.toDouble() / intervalMs,
            startedAt = startedAt.toString(),
            limit = limit,
            activity = "Planning the run")
    setState(RunState.RUNNING, null, null)
  }

  /**
   * Works out what is left to do and how long it should take, so the run starts with an overview
   * and one overall estimate. SmartWaiver's yearly counts (about 13 searches) give the number of
   * waivers; check-ins are assumed to be about one per waiver. If the counts can't be read the plan
   * simply has no numbers for the steps that need them.
   */
  @Suppress("TooGenericExceptionCaught")
  private suspend fun makePlan(limit: Int?) {
    val interval = effectiveIntervalMs() / MS_PER_SECOND.toDouble()
    val eta = { calls: Double ->
      etaSeconds(calls.toLong(), requestsToday, dailyBudget, interval, secondsUntilMidnight())
    }
    if (limit != null) {
      val units = minOf(limit, repository.countNeedingMirror())
      estimate = Estimate(enrichUnits = units)
      progress =
          progress.copy(
              stageCount = 1,
              plan =
                  listOf(
                      PlanStep(
                          "Copy up to $limit waivers with their photos (trial)",
                          units,
                          "waivers",
                          eta(units * ENRICH_CALLS_PER_WAIVER))),
              etaOverallSeconds = eta(units * ENRICH_CALLS_PER_WAIVER))
      publish()
      return
    }
    progress = progress.copy(activity = "Counting waivers on SmartWaiver to plan the run")
    publish()
    val expected =
        try {
          (FIRST_WAIVER_DAY.year..today().year).sumOf { year ->
            client.countWaivers(LocalDate.of(year, 1, 1), minOf(LocalDate.of(year, 12, 31), today()))
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          log.warn("Could not count waivers on SmartWaiver for the plan: ${e.message}")
          null
        }
    val stored = repository.countWaivers()
    val discoverState = repository.getState(STAGE_DISCOVER)
    val months = monthsBetween(FIRST_WAIVER_DAY, today())
    val discoverLeft =
        if (discoverState?.status == RunState.DONE.name) 0
        else months - (discoverState?.doneCount ?: 0).coerceAtMost(months)
    val discoverCalls =
        discoverLeft +
            Math.ceil((expected ?: 0) * discoverLeft.toDouble() / months / SEARCH_RESULTS_PER_PAGE)
    val toEnrich =
        repository.countNeedingMirror() +
            if (discoverLeft > 0 && expected != null) maxOf(0, expected - stored) else 0
    val enrichDone = repository.getState(STAGE_ENRICH)?.status == RunState.DONE.name
    val checkinsDone = repository.getState(STAGE_CHECKINS)?.status == RunState.DONE.name
    val pages =
        if (checkinsDone) 0 else Math.ceil((expected ?: stored) / CHECKINS_PER_PAGE.toDouble()).toInt()
    estimate =
        Estimate(
            discoverUnits = discoverLeft,
            discoverCalls = discoverCalls,
            enrichUnits = if (enrichDone) 0 else toEnrich,
            checkinPages = pages,
            expectedWaivers = expected)
    val c1 = estimate.discoverCalls
    val c2 = c1 + estimate.enrichUnits * ENRICH_CALLS_PER_WAIVER
    val c3 = c2 + estimate.checkinPages
    val steps =
        listOf(
            PlanStep("Find every waiver on SmartWaiver", estimate.discoverUnits, "monthly searches", eta(c1)),
            PlanStep(
                "Copy each waiver's details and photos",
                estimate.enrichUnits,
                "waivers",
                eta(c2) - eta(c1),
                approximate = true),
            PlanStep(
                "Copy the check-in history",
                estimate.checkinPages,
                "pages of $CHECKINS_PER_PAGE",
                eta(c3) - eta(c2),
                approximate = true),
        )
    progress = progress.copy(stageCount = steps.size, plan = steps, etaOverallSeconds = eta(c3), activity = null)
    publish()
    log.info(
        "SmartWaiver backfill plan: ${estimate.discoverUnits} searches, ${estimate.enrichUnits} waivers, " +
            "about ${estimate.checkinPages} check-in pages; roughly ${eta(c3) / SECONDS_PER_HOUR} hours")
  }

  private suspend fun stageDone(name: String) = repository.getState(name)?.status == RunState.DONE.name

  private suspend fun discover() {
    val today = today()
    val saved = repository.getState(STAGE_DISCOVER)
    var start = saved?.cursorValue?.let { LocalDate.parse(it) } ?: FIRST_WAIVER_DAY
    val months = monthsBetween(FIRST_WAIVER_DAY, today)
    var done = saved?.doneCount ?: 0
    beginStage(STAGE_DISCOVER, done, months)
    while (!start.isAfter(today)) {
      gateBeforeUnit()
      val end = minOf(start.plusMonths(1).minusDays(1), today)
      progress = progress.copy(activity = "Searching SmartWaiver for waivers signed $start to $end")
      publish()
      val before = client.stats().requests
      val summaries = client.getWaiverDetails(start, end)
      val added = repository.insertMissingSummaries(summaries)
      log.info("Discover $start to $end: ${summaries.size} on SmartWaiver, $added new (window ${done + 1}/$months)")
      start = end.plusDays(1)
      done++
      progress = progress.copy(discovered = progress.discovered + added)
      saveStage(STAGE_DISCOVER, "RUNNING", start.toString(), done, months)
      unitDone(done, months, client.stats().requests - before, remainingUnits = months - done)
    }
    saveStage(STAGE_DISCOVER, RunState.DONE.name, start.toString(), months, months)
    log.info("SmartWaiver discover finished: ${progress.discovered} new waivers")
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun enrich(maxUnits: Int? = null) {
    val saved = repository.getState(STAGE_ENRICH)
    var cursor = saved?.cursorValue?.toLongOrNull() ?: 0L
    var done = saved?.doneCount ?: 0
    val total = done + repository.countNeedingMirror()
    // A trial counts only its own waivers; the saved counters keep the run-wide totals.
    val shownTotal = if (maxUnits != null) minOf(maxUnits, total - done) else total
    beginStage(STAGE_ENRICH, if (maxUnits != null) 0 else done, shownTotal)
    var failedThisPass = 0
    var processed = 0
    var capped = false
    batches@ while (true) {
      val batch = repository.waiversNeedingMirror(cursor, ENRICH_BATCH)
      if (batch.isEmpty()) break
      for (ref in batch) {
        if (maxUnits != null && processed >= maxUnits) {
          capped = true
          break@batches
        }
        processed++
        gateBeforeUnit()
        val shownDone = if (maxUnits != null) processed - 1 else done
        progress = progress.copy(activity = "Copying waiver ${shownDone + 1} of $shownTotal")
        publish()
        val before = client.stats().requests
        try {
          val outcome = mirrorService.mirrorWaiver(ref.waiverId)
          consecutiveFailures = 0
          progress = progress.copy(mirrored = progress.mirrored + 1, photosStored = progress.photosStored + outcome.photos)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          failedThisPass++
          recordFailure(ref.waiverId, e)
        }
        cursor = ref.rowId
        done++
        saveStage(STAGE_ENRICH, "RUNNING", cursor.toString(), done, total)
        val after = if (maxUnits != null) processed else done
        unitDone(after, shownTotal, client.stats().requests - before, remainingUnits = shownTotal - after)
        if (done % LOG_EVERY_UNITS == 0) logProgress()
      }
    }
    if (capped) {
      log.info("SmartWaiver enrich trial stopped after $processed waivers (${progress.mirrored} mirrored, $failedThisPass failed)")
      return // stage stays RUNNING with its cursor, so a full run continues from here
    }
    val note = if (failedThisPass > 0) "$failedThisPass waivers could not be mirrored; see the errors list" else null
    saveStage(STAGE_ENRICH, RunState.DONE.name, cursor.toString(), done, total, note)
    log.info("SmartWaiver enrich finished: ${progress.mirrored} mirrored, $failedThisPass failed")
  }

  private suspend fun checkins() {
    val saved = repository.getState(STAGE_CHECKINS)
    val from = saved?.cursorValue?.let { LocalDateTime.parse(it) } ?: FIRST_WAIVER_DAY.atStartOfDay()
    var pages = saved?.doneCount ?: 0
    // The history has no exact count, so the page total is an estimate that grows if it is too low.
    var shownTotal = maxOf(estimate.checkinPages, pages + 1)
    beginStage(STAGE_CHECKINS, pages, shownTotal)
    progress = progress.copy(stageApprox = true, activity = "Reading check-ins from ${from.toLocalDate()}")
    publish()
    val now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).withNano(0)
    checkinSync.syncRange(from, now) { added, cursor ->
      pages++
      if (pages >= shownTotal) shownTotal = pages + 1
      progress =
          progress.copy(
              checkinsAdded = progress.checkinsAdded + added,
              activity = "Reading check-ins from ${cursor.toLocalDate()}")
      saveStage(STAGE_CHECKINS, "RUNNING", cursor.toString(), pages, null)
      unitDone(pages, shownTotal, 1, remainingUnits = shownTotal - pages)
      gateBeforeUnit()
    }
    saveStage(STAGE_CHECKINS, RunState.DONE.name, now.toString(), pages, null)
    log.info("SmartWaiver check-ins finished: ${progress.checkinsAdded} new")
  }

  // ---- pacing, budget, pause ----

  private fun beginStage(name: String, done: Int, total: Int?) {
    unitsThisStage = 0
    callsThisStage = 0
    stageStartedMs = clock.millis()
    currentStage = name
    val trial = progress.limit != null
    val (index, title, unit) =
        when (name) {
          STAGE_DISCOVER -> Triple(1, "Finding every waiver on SmartWaiver", "monthly searches")
          STAGE_ENRICH ->
              Triple(if (trial) 1 else 2, "Copying each waiver's details and photos", "waivers")
          else -> Triple(3, "Copying the check-in history", "pages of $CHECKINS_PER_PAGE")
        }
    progress =
        progress.copy(
            stage = name,
            stageIndex = index,
            stageTitle = title,
            stageUnit = unit,
            stageApprox = false,
            stageDone = done,
            stageTotal = total)
    publish()
  }

  /** Waits out a pause, and sleeps to the next Chicago midnight when today's budget is used up. */
  private suspend fun gateBeforeUnit() {
    if (paused.value) {
      setState(RunState.PAUSED, null, null)
      persistRun()
      paused.first { !it }
      setState(RunState.RUNNING, null, null)
    }
    rollBudgetDay()
    if (requestsToday >= dailyBudget) {
      val wake = nextChicagoMidnight()
      log.info("SmartWaiver backfill used today's budget of $dailyBudget calls; sleeping until $wake")
      setState(RunState.WAITING, wake.toLocalDateTime().toString() + " America/Chicago", null)
      progress = progress.copy(waitingUntilEpoch = wake.toEpochSecond())
      publish()
      persistRun()
      sleep(maxOf(0L, wake.toInstant().toEpochMilli() - clock.millis()))
      rollBudgetDay()
      setState(RunState.RUNNING, null, null)
    }
  }

  /** Books a finished unit's calls against the budget, then paces: one call per interval, doubled after a 429. */
  private suspend fun unitDone(done: Int, total: Int?, calls: Long, remainingUnits: Int) {
    val stats = client.stats()
    val used = (stats.requests - lastRequestTotal).coerceAtLeast(0)
    lastRequestTotal = stats.requests
    requestsToday += used.toInt()
    if (stats.rateLimitHits > lastRateLimitHits) {
      if (!slowMode) log.warn("SmartWaiver rate limit hit; backfill pace halves until the next Chicago day")
      slowMode = true
      lastRateLimitHits = stats.rateLimitHits
    }
    unitsThisStage++
    callsThisStage += calls
    rollBudgetDay()
    recordSample(stats.requests)
    // Blend the planned calls per unit with what has been measured, so the first few units
    // (a 2014 month needs fewer searches than a 2025 one) don't swing the estimate.
    val prior =
        when (currentStage) {
          STAGE_DISCOVER -> estimate.discoverCalls / maxOf(1, estimate.discoverUnits)
          STAGE_ENRICH -> ENRICH_CALLS_PER_WAIVER
          else -> 1.0
        }
    val callsPerUnit = (prior * PRIOR_WEIGHT + callsThisStage) / (PRIOR_WEIGHT + unitsThisStage)
    val interval = effectiveIntervalMs() / MS_PER_SECOND.toDouble()
    val stageCalls = remainingUnits.coerceAtLeast(0) * callsPerUnit
    val laterCalls =
        if (progress.limit != null) 0.0
        else
            when (currentStage) {
              STAGE_DISCOVER -> estimate.enrichUnits * ENRICH_CALLS_PER_WAIVER + estimate.checkinPages
              STAGE_ENRICH -> estimate.checkinPages.toDouble()
              else -> 0.0
            }
    val until = secondsUntilMidnight()
    val stageEta = etaSeconds(stageCalls.toLong(), requestsToday, dailyBudget, interval, until)
    val overallEta = etaSeconds((stageCalls + laterCalls).toLong(), requestsToday, dailyBudget, interval, until)
    progress =
        progress.copy(
            stageDone = done,
            stageTotal = total,
            etaStageSeconds = stageEta,
            etaOverallSeconds = overallEta,
            failed = recentErrorCount())
    setState(RunState.RUNNING, null, null)
    persistRun()
    sleep(effectiveIntervalMs() * maxOf(1L, used))
  }

  private fun effectiveIntervalMs() = if (slowMode) intervalMs * SLOW_MODE_FACTOR else intervalMs

  private fun rollBudgetDay() {
    val day = today()
    if (budgetDay != day) {
      budgetDay = day
      requestsToday = 0
      slowMode = false
    }
  }

  private fun recordSample(totalRequests: Long) {
    val nowMs = clock.millis()
    samples.addLast(nowMs to totalRequests)
    while (samples.size > 1 && nowMs - samples.first().first > FIFTEEN_MINUTES_MS) samples.removeFirst()
  }

  private fun ratePerMinute(windowMs: Long): Double {
    val last = samples.lastOrNull() ?: return 0.0
    val first = samples.firstOrNull { last.first - it.first <= windowMs } ?: return 0.0
    val elapsed = last.first - first.first
    return if (elapsed <= 0) 0.0 else (last.second - first.second) * ONE_MINUTE_MS.toDouble() / elapsed
  }

  // ---- state, progress, errors ----

  private fun recordFailure(waiverId: String, e: Exception) {
    consecutiveFailures++
    val line = "${now()} $waiverId: ${e.message ?: e.javaClass.simpleName}"
    synchronized(lock) {
      recentErrors.addLast(line)
      while (recentErrors.size > MAX_RECENT_ERRORS) recentErrors.removeFirst()
    }
    log.warn("SmartWaiver backfill could not mirror $waiverId: ${e.message}")
    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
      throw IllegalStateException("$MAX_CONSECUTIVE_FAILURES waivers in a row failed; last: ${e.message}")
    }
  }

  private fun recentErrorCount() = synchronized(lock) { recentErrors.size }

  private fun logProgress() {
    val p = progress
    log.info(
        "SmartWaiver backfill ${p.stage}: ${p.stageDone}/${p.stageTotal ?: "?"}, ${p.mirrored} mirrored, " +
            "${p.photosStored} photos, ${p.failed} failed, calls today ${p.requestsToday}/${p.dailyBudget}, " +
            "%.1f/min, ETA ${p.etaStageSeconds?.let { "${it / 3600}h" } ?: "?"}".format(p.requestsPerMinute1))
  }

  private fun setState(state: RunState, waitingUntil: String?, error: String?) {
    if (state != progress.state) log.info("SmartWaiver backfill ${progress.state} -> $state${error?.let { ": $it" }.orEmpty()}")
    progress =
        progress.copy(
            state = state,
            waitingUntil = waitingUntil,
            waitingUntilEpoch = if (state == RunState.WAITING) progress.waitingUntilEpoch else null,
            lastError = error ?: progress.lastError,
            requestsToday = requestsToday,
            slowMode = slowMode,
            rateLimitHits = client.stats().rateLimitHits,
            requestsPerMinute1 = ratePerMinute(ONE_MINUTE_MS),
            requestsPerMinute15 = ratePerMinute(FIFTEEN_MINUTES_MS),
            updatedAt = now().toString())
    publish()
  }

  private fun publish() {
    updates.tryEmit(progress)
  }

  private suspend fun persistRun() {
    repository.saveState(
        BackfillState(
            RUN, progress.state.name, null, progress.mirrored, null, requestsToday, budgetDay, progress.lastError,
            startedAt, now(), null))
  }

  private suspend fun finish(state: RunState, error: String?) {
    setState(state, null, error)
    repository.saveState(
        BackfillState(
            RUN, state.name, null, progress.mirrored, null, requestsToday, budgetDay, error, startedAt, now(),
            if (state == RunState.DONE) now() else null))
  }

  private suspend fun saveStage(name: String, status: String, cursor: String?, done: Int, total: Int?, error: String? = null) {
    repository.saveState(
        BackfillState(name, status, cursor, done, total, 0, null, error, null, now(), if (status == "DONE") now() else null))
  }

  // ---- time ----

  private fun now(): LocalDateTime = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).withNano(0)

  private fun today(): LocalDate = ZonedDateTime.now(clock.withZone(CHICAGO)).toLocalDate()

  private fun nextChicagoMidnight(): ZonedDateTime = today().plusDays(1).atStartOfDay(CHICAGO)

  private fun secondsUntilMidnight() =
      (nextChicagoMidnight().toInstant().toEpochMilli() - clock.millis()) / MS_PER_SECOND

  private fun monthsBetween(from: LocalDate, to: LocalDate) =
      (to.year - from.year) * 12 + (to.monthValue - from.monthValue) + 1
}
