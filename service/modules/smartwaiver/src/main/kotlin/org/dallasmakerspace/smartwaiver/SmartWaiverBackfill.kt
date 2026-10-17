package org.dallasmakerspace.smartwaiver

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
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
import org.dallasmakerspace.smartwaiver.SmartWaiverMirrorRepository.Companion.RUN_STATE_NAME

private val CHICAGO: ZoneId = ZoneId.of("America/Chicago")
private val FIRST_WAIVER_DAY: LocalDate = LocalDate.of(2014, 1, 1)
private const val STAGE_DISCOVER = "discover"
private const val STAGE_ENRICH = "enrich"
private const val STAGE_CHECKINS = "checkins"
private const val ENRICH_BATCH = 50
private const val LOG_EVERY_UNITS = 50
private const val MAX_CONSECUTIVE_FAILURES = 10
private const val MAX_RECENT_ERRORS = 200
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
private const val ENRICH_PASSES = 2 // one retry pass for waivers that failed
private const val MAX_ETA_DAYS = 400
private const val STARTUP_CHECK_TRIES = 10
private const val MAX_RETRIES = 150 // five growing waits, then 10 minutes each: about a day
private val RETRY_BACKOFF_MS = listOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L)

// Time a unit takes besides the API calls and their pacing: database round trips over the VPN, S3.
private const val DB_OVERHEAD_DISCOVER_S = 2.0
private const val DB_OVERHEAD_ENRICH_S = 3.0
private const val DB_OVERHEAD_CHECKIN_S = 1.5

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
    /** Waivers SmartWaiver no longer has (it answered 404). */
    val gone: Int = 0,
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

/** Another copy of the job (a second container during a deploy) took over, or the run was stopped. */
private class SupersededException : CancellationException("another copy of the backfill took over")

/**
 * Seconds until [remainingCalls] API calls are done at one call per [intervalSeconds], with at most
 * [budget] calls per day: today's leftover first, then a wait until midnight, then whole days. A day
 * holds at most 24 h of calls, which matters when the pace is slow.
 */
internal fun etaSeconds(
    remainingCalls: Long,
    requestsToday: Int,
    budget: Int,
    intervalSeconds: Double,
    secondsUntilMidnight: Long,
): Long {
  if (remainingCalls <= 0) return 0
  val interval = maxOf(intervalSeconds, 0.001)
  var left = remainingCalls
  var usedToday = requestsToday.toLong()
  var untilMidnight = secondsUntilMidnight.toDouble()
  var elapsed = 0.0
  // One pass per day: a day ends when the budget is spent or midnight comes, whichever is first.
  repeat(MAX_ETA_DAYS) {
    val today = minOf(left, maxOf(0L, budget - usedToday), (untilMidnight / interval).toLong())
    elapsed += today * interval
    left -= today
    if (left <= 0) return elapsed.toLong()
    elapsed += maxOf(0.0, untilMidnight - today * interval) // wait out the rest of the day
    usedToday = 0
    untilMidnight = SECONDS_PER_DAY.toDouble()
  }
  return elapsed.toLong()
}

/**
 * Mirrors the whole SmartWaiver history in the background: find every waiver, copy each one in
 * full with its photos, then the check-ins. It runs inside the service and is deliberately slow: a
 * daily request budget and a fixed pace keep it a polite API client. When the day's budget is spent
 * it sleeps on one timer until midnight Chicago time; nothing polls.
 *
 * Staying safe over several days:
 * - Progress is saved after every unit (one transaction), so a restart or deploy resumes where it
 *   was; a run that was RUNNING, WAITING or PAUSED is picked up again at startup. A trial is saved
 *   as TRIAL, which is never resumed.
 * - Only one copy runs. Starting a copy makes it the owner in the run's saved state; a copy that is
 *   no longer the owner (a deploy overlap, or `stop`) notices at its next checkpoint and ends.
 * - A failed step is retried with growing waits, then every 10 minutes for about a day; only a bad
 *   key, bad configuration, or running out of that day ends the run as FAILED.
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
    private val owner: String = UUID.randomUUID().toString(),
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

  // Run-wide state, touched only from the single run coroutine.
  private var requestsToday = 0
  private var budgetDay: LocalDate? = null
  private var lastRequestTotal = 0L
  private var lastRateLimitHits = 0L
  private var slowMode = false
  private var startedAt: LocalDateTime? = null
  private var consecutiveFailures = 0
  private var consecutiveRetries = 0
  private var unitsThisStage = 0
  private var stageSeconds = 0.0
  private var lastUnitMs = 0L
  private var excludedMs = 0L // time spent paused, waiting or retrying since the last unit
  private var estimate = Estimate()
  private var currentStage: String? = null
  private var enrichSecondsPerUnit: Double? = null

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
   * Releases a pause. If no job is running here (a deploy replaced the container while it was
   * paused), the saved PAUSED run is started again instead.
   */
  suspend fun resumeOrStart(): String {
    paused.value = false
    if (synchronized(lock) { job?.isActive == true }) return "resuming"
    return if (repository.getState(RUN_STATE_NAME)?.status == RunState.PAUSED.name) {
      start()
      "started again from the saved pause"
    } else {
      "nothing is paused"
    }
  }

  /**
   * Starts the run, or continues from the saved positions.
   * - [restart] clears every stage's position and begins again (the day's call count is kept).
   * - [retryFailed] re-runs only the waiver-copying step from the start; waivers already copied are
   *   skipped, so only the ones that failed before are tried again.
   * - [limit] copies only that many already-stored waivers in full (no discovery, no check-ins) and
   *   leaves the stages unfinished, so a later full run carries on from there.
   */
  fun start(restart: Boolean = false, limit: Int? = null, retryFailed: Boolean = false, startPaused: Boolean = false): StartResult {
    synchronized(lock) {
      if (job?.isActive == true) return StartResult.ALREADY_RUNNING
      log.info(
          "SmartWaiver backfill starting (restart=$restart, retryFailed=$retryFailed, limit=$limit, " +
              "paused=$startPaused, budget $dailyBudget calls/day, one call per ${intervalMs}ms)")
      paused.value = startPaused
      // Replace the previous run's snapshot now, so a client that attaches right after start()
      // doesn't see that run's DONE and leave.
      progress =
          BackfillProgress(
              dailyBudget = dailyBudget,
              state = if (startPaused) RunState.PAUSED else RunState.RUNNING,
              limit = limit,
              activity = "Starting")
      publish()
      job = scope.launch { runAll(restart, limit, retryFailed) }
    }
    return StartResult.STARTED
  }

  /**
   * Ends the run now, keeping the positions. The run is saved as IDLE with no owner, so a restart
   * of the service doesn't resume it and a copy still running elsewhere stops at its next
   * checkpoint. Call [start] to continue from where it stopped.
   */
  suspend fun stop() {
    val running = synchronized(lock) { job.also { job = null } }
    running?.cancelAndJoin()
    paused.value = false
    repository.markRunIdle(now())
    progress = progress.copy(state = RunState.IDLE, waitingUntil = null, waitingUntilEpoch = null, activity = null)
    publish()
  }

  /** After a restart of the service: carry on if the previous run was cut off (a trial is not resumed). */
  @Suppress("TooGenericExceptionCaught")
  suspend fun resumeIfInterrupted() {
    // The database may be unreachable for a moment right after a deploy, so try again with waits;
    // giving up after one failure would leave the run saved as running with nothing running it.
    for (attempt in 1..STARTUP_CHECK_TRIES) {
      try {
        when (repository.getState(RUN_STATE_NAME)?.status) {
          RunState.RUNNING.name,
          RunState.WAITING.name -> {
            log.info("SmartWaiver backfill was running when the service stopped; resuming")
            start()
          }
          RunState.PAUSED.name -> {
            log.info("SmartWaiver backfill was paused when the service stopped; resuming it paused")
            start(startPaused = true)
          }
        }
        return
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.warn("Could not check for an interrupted SmartWaiver backfill (try $attempt of $STARTUP_CHECK_TRIES): ${e.safeMessage()}")
        if (attempt < STARTUP_CHECK_TRIES) sleep(RETRY_BACKOFF_MS[minOf(attempt - 1, RETRY_BACKOFF_MS.size - 1)])
      }
    }
  }

  // ---- the run ----

  @Suppress("TooGenericExceptionCaught")
  private suspend fun runAll(restart: Boolean, limit: Int?, retryFailed: Boolean) {
    try {
      retrying("starting") { begin(restart, limit, retryFailed) }
      retrying("planning") { makePlan(limit) }
      if (limit != null) {
        retrying("copying waivers") { enrich(limit) }
        progress = progress.copy(message = "Trial of up to $limit waivers finished; start again without a limit for the rest")
        finish(RunState.DONE, null)
        return
      }
      if (!retrying("checking progress") { stageDone(STAGE_DISCOVER) }) retrying("finding waivers") { discover() }
      if (!retrying("checking progress") { stageDone(STAGE_ENRICH) }) retrying("copying waivers") { enrich() }
      if (!retrying("checking progress") { stageDone(STAGE_CHECKINS) }) retrying("copying check-ins") { checkins() }
      finish(RunState.DONE, null)
    } catch (e: SupersededException) {
      log.info("SmartWaiver backfill: another copy took over or the run was stopped; this copy ends")
      progress = progress.copy(state = RunState.IDLE, activity = null, message = "Another copy of the backfill took over")
      publish()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.error("SmartWaiver backfill stopped: ${e.safeMessage()}")
      finish(RunState.FAILED, e.safeMessage())
    }
  }

  /**
   * Runs [block], and when it fails tries again after a growing wait, picking up from the saved
   * positions. Only a bad API key or configuration, or too many attempts in a row without progress,
   * lets the error through. [consecutiveRetries] starts over only when a unit of work really
   * succeeds (a waiver copied, a search window or check-in page read), not when one merely fails.
   */
  @Suppress("TooGenericExceptionCaught")
  private suspend fun <T> retrying(what: String, block: suspend () -> T): T {
    while (true) {
      try {
        return block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (isFatal(e) || ++consecutiveRetries > MAX_RETRIES) throw e
        val wait = RETRY_BACKOFF_MS[minOf(consecutiveRetries - 1, RETRY_BACKOFF_MS.size - 1)]
        val why = e.safeMessage()
        log.warn(
            "SmartWaiver backfill: $what failed ($why); trying again in ${wait / MS_PER_SECOND}s " +
                "(attempt $consecutiveRetries of $MAX_RETRIES)")
        consecutiveFailures = 0
        waitToRetry(wait, why)
      }
    }
  }

  private fun isFatal(e: Exception) =
      e is IllegalArgumentException || (e is SmartwaiverApiException && e.message.orEmpty().startsWith("Unauthorized"))

  @Suppress("TooGenericExceptionCaught")
  private suspend fun waitToRetry(ms: Long, why: String) {
    setState(RunState.WAITING, "retrying after an error", why)
    progress =
        progress.copy(
            waitingUntilEpoch = (clock.millis() + ms) / MS_PER_SECOND,
            activity = "Hit an error ($why); trying again in ${ms / MS_PER_SECOND}s")
    publish()
    try {
      renewLease()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // The database is the likely cause; the state is saved by the next write that works.
    }
    sleep(ms)
    excludedMs += ms
    setState(RunState.RUNNING, null, null)
  }

  private suspend fun begin(restart: Boolean, limit: Int?, retryFailed: Boolean) {
    val saved = repository.getState(RUN_STATE_NAME)
    startedAt = if (restart || saved?.startedAt == null) now() else saved.startedAt
    budgetDay = saved?.budgetDay
    requestsToday = saved?.requestsToday ?: 0 // the day's count survives a restart: it is a courtesy limit
    rollBudgetDay()
    val stats = client.stats()
    lastRequestTotal = stats.requests
    lastRateLimitHits = stats.rateLimitHits
    slowMode = false
    consecutiveFailures = 0
    consecutiveRetries = 0
    progress =
        BackfillProgress(
            dailyBudget = dailyBudget,
            paceCallsPerMinute = ONE_MINUTE_MS.toDouble() / intervalMs,
            startedAt = startedAt.toString(),
            limit = limit,
            activity = "Planning the run")
    repository.claimRun(owner, runState(runStatus(RunState.RUNNING)))
    if (restart) {
      listOf(STAGE_DISCOVER, STAGE_ENRICH, STAGE_CHECKINS).forEach { repository.saveState(stageState(it, "IDLE", null, 0, null)) }
    } else if (retryFailed) {
      repository.saveState(stageState(STAGE_ENRICH, "IDLE", null, 0, null))
    }
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
    val overall = { seconds: Double, calls: Double ->
      maxOf(seconds.toLong(), etaSeconds(calls.toLong(), requestsToday, dailyBudget, interval, secondsUntilMidnight()))
    }
    if (limit != null) {
      val units = minOf(limit, repository.countNeedingMirror())
      estimate = Estimate(enrichUnits = units)
      val seconds = units * priorSeconds(STAGE_ENRICH)
      progress =
          progress.copy(
              stageCount = 1,
              plan = listOf(PlanStep("Copy up to $limit waivers with their photos (trial)", units, "waivers", seconds.toLong())),
              etaOverallSeconds = overall(seconds, units * ENRICH_CALLS_PER_WAIVER))
      publish()
      return
    }
    // The counting searches are API calls too, so wait out a pause or an exhausted day first.
    gateBeforeUnit()
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
          log.warn("Could not count waivers on SmartWaiver for the plan: ${e.safeMessage()}")
          null
        }
    val stored = repository.countWaivers()
    val discoverState = repository.getState(STAGE_DISCOVER)
    val months = monthsBetween(FIRST_WAIVER_DAY, today())
    val discoverLeft =
        if (discoverState?.status == RunState.DONE.name) 0
        else months - (discoverState?.doneCount ?: 0).coerceAtMost(months)
    val discoverCalls =
        discoverLeft + Math.ceil((expected ?: 0) * discoverLeft.toDouble() / months / SEARCH_RESULTS_PER_PAGE)
    val toEnrich =
        repository.countNeedingMirror() + if (discoverLeft > 0 && expected != null) maxOf(0, expected - stored) else 0
    val enrichDone = repository.getState(STAGE_ENRICH)?.status == RunState.DONE.name
    val checkinsDone = repository.getState(STAGE_CHECKINS)?.status == RunState.DONE.name
    val pages = if (checkinsDone) 0 else Math.ceil((expected ?: stored) / CHECKINS_PER_PAGE.toDouble()).toInt()
    estimate =
        Estimate(
            discoverUnits = discoverLeft,
            discoverCalls = discoverCalls,
            enrichUnits = if (enrichDone) 0 else toEnrich,
            checkinPages = pages,
            expectedWaivers = expected)
    val s1 = estimate.discoverUnits * priorSeconds(STAGE_DISCOVER)
    val s2 = estimate.enrichUnits * priorSeconds(STAGE_ENRICH)
    val s3 = estimate.checkinPages * priorSeconds(STAGE_CHECKINS)
    val calls = estimate.discoverCalls + estimate.enrichUnits * ENRICH_CALLS_PER_WAIVER + estimate.checkinPages
    val total = overall(s1 + s2 + s3, calls)
    val steps =
        listOf(
            PlanStep("Find every waiver on SmartWaiver", estimate.discoverUnits, "monthly searches", s1.toLong()),
            PlanStep("Copy each waiver's details and photos", estimate.enrichUnits, "waivers", s2.toLong(), approximate = true),
            PlanStep("Copy the check-in history", estimate.checkinPages, "pages of $CHECKINS_PER_PAGE", s3.toLong(), approximate = true),
        )
    progress = progress.copy(stageCount = steps.size, plan = steps, etaOverallSeconds = total, activity = null)
    publish()
    log.info(
        "SmartWaiver backfill plan: ${estimate.discoverUnits} searches, ${estimate.enrichUnits} waivers, " +
            "about ${estimate.checkinPages} check-in pages; roughly ${total / SECONDS_PER_HOUR} hours")
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
      consecutiveRetries = 0
      log.info("Discover $start to $end: ${summaries.size} on SmartWaiver, $added new (window ${done + 1}/$months)")
      start = end.plusDays(1)
      done++
      progress = progress.copy(discovered = progress.discovered + added)
      val next = start.toString()
      unitDone(done, months, client.stats().requests - before, remainingUnits = months - done) {
        checkpoint(STAGE_DISCOVER, "RUNNING", next, done, months)
      }
    }
    checkpoint(STAGE_DISCOVER, RunState.DONE.name, start.toString(), months, months)
    log.info("SmartWaiver discover finished: ${progress.discovered} new waivers")
  }

  @Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
  private suspend fun enrich(maxUnits: Int? = null) {
    val saved = repository.getState(STAGE_ENRICH)
    var cursor = saved?.cursorValue?.toLongOrNull() ?: 0L
    var done = saved?.doneCount ?: 0
    val total = done + repository.countNeedingMirror()
    // A trial counts only its own waivers; the saved counters keep the run-wide totals.
    val shownTotal = if (maxUnits != null) minOf(maxUnits, total - done) else total
    beginStage(STAGE_ENRICH, if (maxUnits != null) 0 else done, shownTotal)
    var processed = 0
    var capped = false
    var pass = 0
    var failedThisPass: Int
    var stillMissing = 0
    while (true) {
      pass++
      failedThisPass = 0
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
            consecutiveRetries = 0
            progress = progress.copy(mirrored = progress.mirrored + 1, photosStored = progress.photosStored + outcome.photos)
          } catch (e: CancellationException) {
            throw e
          } catch (e: SmartwaiverNotFoundException) {
            // SmartWaiver no longer has it: remember that so it stops counting as work to do.
            repository.markGone(ref.waiverId)
            consecutiveRetries = 0
            progress = progress.copy(gone = progress.gone + 1)
            log.info("Waiver ${ref.waiverId} no longer exists on SmartWaiver; marked as gone")
          } catch (e: Exception) {
            failedThisPass++
            recordFailure(ref.waiverId, e)
          }
          cursor = ref.rowId
          done++
          val after = if (maxUnits != null) processed else done
          val savedCursor = cursor.toString()
          val savedDone = done
          unitDone(after, shownTotal, client.stats().requests - before, remainingUnits = shownTotal - after) {
            checkpoint(STAGE_ENRICH, "RUNNING", savedCursor, savedDone, total)
          }
          if (done % LOG_EVERY_UNITS == 0) logProgress()
        }
      }
      // Waivers that failed (a hiccup at the time) get one more pass before the step is called done.
      // What is still missing is read from the database, not remembered, so a restart in the middle
      // of the first pass still ends up giving the failures their second try.
      if (capped || maxUnits != null) break
      stillMissing = repository.countNeedingMirror()
      if (stillMissing == 0 || pass >= ENRICH_PASSES) break
      log.info("SmartWaiver enrich: $stillMissing waivers are still missing; trying them once more")
      cursor = 0
      // Save the restart now: otherwise a restart during this pass resumes at the end of the last
      // one, finds nothing left, and calls the step done with the failures still outstanding.
      checkpoint(STAGE_ENRICH, "RUNNING", "0", done, total)
    }
    if (capped) {
      log.info("SmartWaiver enrich trial stopped after $processed waivers (${progress.mirrored} mirrored)")
      return // stage stays RUNNING with its cursor, so a full run continues from here
    }
    // A trial never marks the step done, even if it ran out of waivers: discovery may find more.
    if (maxUnits != null) return
    val note = if (stillMissing > 0) "$stillMissing waivers could not be mirrored; see the errors list" else null
    checkpoint(STAGE_ENRICH, RunState.DONE.name, cursor.toString(), done, total, note)
    log.info("SmartWaiver enrich finished: ${progress.mirrored} mirrored, ${progress.gone} gone, $stillMissing still missing")
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
      consecutiveRetries = 0
      if (pages >= shownTotal) shownTotal = pages + 1
      progress =
          progress.copy(
              checkinsAdded = progress.checkinsAdded + added,
              activity = "Reading check-ins from ${cursor.toLocalDate()}")
      val savedPages = pages
      val savedTotal = shownTotal
      unitDone(savedPages, savedTotal, 1, remainingUnits = savedTotal - savedPages) {
        checkpoint(STAGE_CHECKINS, "RUNNING", cursor.toString(), savedPages, null)
      }
      gateBeforeUnit()
    }
    checkpoint(STAGE_CHECKINS, RunState.DONE.name, now.toString(), pages, null)
    log.info("SmartWaiver check-ins finished: ${progress.checkinsAdded} new")
  }

  // ---- pacing, budget, pause ----

  private fun beginStage(name: String, done: Int, total: Int?) {
    unitsThisStage = 0
    stageSeconds = 0.0
    excludedMs = 0
    lastUnitMs = clock.millis()
    currentStage = name
    val trial = progress.limit != null
    val (index, title, unit) =
        when (name) {
          STAGE_DISCOVER -> Triple(1, "Finding every waiver on SmartWaiver", "monthly searches")
          STAGE_ENRICH -> Triple(if (trial) 1 else 2, "Copying each waiver's details and photos", "waivers")
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
      val since = clock.millis()
      setState(RunState.PAUSED, null, null)
      renewLease()
      paused.first { !it }
      excludedMs += clock.millis() - since
      setState(RunState.RUNNING, null, null)
    }
    rollBudgetDay()
    if (requestsToday >= dailyBudget) {
      val wake = nextChicagoMidnight()
      log.info("SmartWaiver backfill used today's budget of $dailyBudget calls; sleeping until $wake")
      setState(RunState.WAITING, wake.toLocalDateTime().toString() + " America/Chicago", null)
      progress = progress.copy(waitingUntilEpoch = wake.toEpochSecond())
      publish()
      renewLease()
      val ms = maxOf(0L, wake.toInstant().toEpochMilli() - clock.millis())
      sleep(ms)
      excludedMs += ms
      rollBudgetDay()
      setState(RunState.RUNNING, null, null)
    }
  }

  /**
   * Books a finished unit's calls against the budget, saves the checkpoint via [save], then paces:
   * one call per interval, doubled after a 429. Also refreshes the estimates.
   */
  private suspend fun unitDone(done: Int, total: Int?, calls: Long, remainingUnits: Int, save: suspend () -> Unit) {
    val stats = client.stats()
    val used = (stats.requests - lastRequestTotal).coerceAtLeast(0)
    lastRequestTotal = stats.requests
    requestsToday += used.toInt()
    if (stats.rateLimitHits > lastRateLimitHits) {
      if (!slowMode) log.warn("SmartWaiver rate limit hit; backfill pace halves until the next Chicago day")
      slowMode = true
      lastRateLimitHits = stats.rateLimitHits
    }
    val nowMs = clock.millis()
    stageSeconds += maxOf(0L, nowMs - lastUnitMs - excludedMs) / MS_PER_SECOND.toDouble()
    lastUnitMs = nowMs
    excludedMs = 0
    unitsThisStage++
    rollBudgetDay()
    recordSample(stats.requests)

    // Blend the planned time per unit with what has been measured, so the first few units
    // (a 2014 month needs fewer searches than a 2025 one) don't swing the estimate.
    val secondsPerUnit = (priorSeconds(currentStage) * PRIOR_WEIGHT + stageSeconds) / (PRIOR_WEIGHT + unitsThisStage)
    if (currentStage == STAGE_ENRICH && unitsThisStage >= PRIOR_WEIGHT) enrichSecondsPerUnit = secondsPerUnit
    val interval = effectiveIntervalMs() / MS_PER_SECOND.toDouble()
    val callsPerUnit = if (currentStage == STAGE_ENRICH) ENRICH_CALLS_PER_WAIVER else (calls.coerceAtLeast(1)).toDouble()
    val stageRemaining = remainingUnits.coerceAtLeast(0)
    val laterSeconds: Double
    val laterCalls: Double
    if (progress.limit != null) {
      laterSeconds = 0.0
      laterCalls = 0.0
    } else {
      val enrich = if (currentStage == STAGE_DISCOVER) estimate.enrichUnits else 0
      val checkins = if (currentStage == STAGE_CHECKINS) 0 else estimate.checkinPages
      laterSeconds = enrich * (enrichSecondsPerUnit ?: priorSeconds(STAGE_ENRICH)) + checkins * priorSeconds(STAGE_CHECKINS)
      laterCalls = enrich * ENRICH_CALLS_PER_WAIVER + checkins
    }
    val until = secondsUntilMidnight()
    val stageCalls = stageRemaining * callsPerUnit
    // Time-bound (each unit's real duration) or budget-bound (calls per day), whichever is longer.
    val stageEta =
        maxOf((stageRemaining * secondsPerUnit).toLong(), etaSeconds(stageCalls.toLong(), requestsToday, dailyBudget, interval, until))
    val overallEta =
        maxOf(
            (stageRemaining * secondsPerUnit + laterSeconds).toLong(),
            etaSeconds((stageCalls + laterCalls).toLong(), requestsToday, dailyBudget, interval, until))
    progress =
        progress.copy(
            stageDone = done,
            stageTotal = total,
            etaStageSeconds = stageEta,
            etaOverallSeconds = overallEta,
            failed = recentErrorCount())
    setState(RunState.RUNNING, null, null)
    save()
    sleep(effectiveIntervalMs() * maxOf(1L, used))
  }

  /** What a unit of [stage] is expected to take before any has been measured. */
  private fun priorSeconds(stage: String?): Double {
    val interval = effectiveIntervalMs() / MS_PER_SECOND.toDouble()
    return when (stage) {
      STAGE_DISCOVER -> estimate.discoverCalls / maxOf(1, estimate.discoverUnits) * interval + DB_OVERHEAD_DISCOVER_S
      STAGE_ENRICH -> ENRICH_CALLS_PER_WAIVER * interval + DB_OVERHEAD_ENRICH_S
      else -> interval + DB_OVERHEAD_CHECKIN_S
    }
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
    val why = e.safeMessage()
    synchronized(lock) {
      recentErrors.addLast("${now()} $waiverId: $why")
      while (recentErrors.size > MAX_RECENT_ERRORS) recentErrors.removeFirst()
    }
    log.warn("SmartWaiver backfill could not mirror $waiverId: $why")
    // A long unbroken run of failures means the problem is not the waivers (SmartWaiver, S3 or the
    // database is down); step out so the retry wait gives it time to recover.
    check(consecutiveFailures < MAX_CONSECUTIVE_FAILURES) { "$MAX_CONSECUTIVE_FAILURES waivers in a row failed; last: $why" }
  }

  private fun recentErrorCount() = synchronized(lock) { recentErrors.size }

  private fun logProgress() {
    val p = progress
    log.info(
        "SmartWaiver backfill ${p.stage}: ${p.stageDone}/${p.stageTotal ?: "?"}, ${p.mirrored} mirrored, " +
            "${p.photosStored} photos, ${p.failed} failed, calls today ${p.requestsToday}/${p.dailyBudget}, " +
            "%.1f/min, ETA ${p.etaStageSeconds?.let { "${it / SECONDS_PER_HOUR}h" } ?: "?"}".format(p.requestsPerMinute1))
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

  /** How the run is saved: a trial is stored as TRIAL while active so a restart never resumes it. */
  private fun runStatus(state: RunState) =
      if (progress.limit != null && (state == RunState.RUNNING || state == RunState.WAITING || state == RunState.PAUSED)) "TRIAL"
      else state.name

  private fun runState(status: String, error: String? = null, finished: LocalDateTime? = null) =
      BackfillState(
          RUN_STATE_NAME, status, owner, progress.mirrored, null, requestsToday, budgetDay, error ?: progress.lastError,
          startedAt, now(), finished)

  private fun stageState(name: String, status: String, cursor: String?, done: Int, total: Int?, note: String? = null) =
      BackfillState(name, status, cursor, done, total, 0, null, note, null, now(), if (status == "DONE") now() else null)

  /** Saves a unit's progress with the run row in one transaction; ends this copy if it lost the run. */
  private suspend fun checkpoint(name: String, status: String, cursor: String?, done: Int, total: Int?, note: String? = null) {
    if (!repository.saveUnit(stageState(name, status, cursor, done, total, note), owner, runState(runStatus(progress.state)))) {
      throw SupersededException()
    }
  }

  private suspend fun renewLease() {
    if (!repository.renewRun(owner, runState(runStatus(progress.state)))) throw SupersededException()
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun finish(state: RunState, error: String?) {
    try {
      val finished = if (state == RunState.DONE) now() else null
      repository.renewRun(owner, runState(state.name, error, finished))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.warn("Could not save the final state of the SmartWaiver backfill: ${e.safeMessage()}")
    }
    // Published last, so anyone who sees DONE or FAILED can rely on it being saved.
    setState(state, null, error)
  }

  /** Waits for the current job to end; for tests, which start a new run right after one finishes. */
  internal suspend fun awaitJob() {
    synchronized(lock) { job }?.join()
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
