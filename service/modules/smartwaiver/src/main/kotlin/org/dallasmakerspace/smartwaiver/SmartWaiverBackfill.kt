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
private const val MAX_CONSECUTIVE_FAILURES = 10
private const val MAX_RECENT_ERRORS = 200
private const val CALLS_PER_WAIVER_GUESS = 2.0
private const val SECONDS_PER_DAY = 86_400L
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

/** One snapshot of the backfill, sent over the status and stream routes. */
@Serializable
data class BackfillProgress(
    val state: RunState = RunState.IDLE,
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

  /** Starts the run, or continues from the saved cursors; [restart] clears them and begins again. */
  fun start(restart: Boolean = false): StartResult {
    synchronized(lock) {
      if (job?.isActive == true) return StartResult.ALREADY_RUNNING
      paused.value = false
      job = scope.launch { runAll(restart) }
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
  private suspend fun runAll(restart: Boolean) {
    try {
      begin(restart)
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

  private suspend fun begin(restart: Boolean) {
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
    progress = BackfillProgress(dailyBudget = dailyBudget, startedAt = startedAt.toString())
    setState(RunState.RUNNING, null, null)
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
      val before = client.stats().requests
      val summaries = client.getWaiverDetails(start, end)
      val added = repository.insertMissingSummaries(summaries)
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
  private suspend fun enrich() {
    val saved = repository.getState(STAGE_ENRICH)
    var cursor = saved?.cursorValue?.toLongOrNull() ?: 0L
    var done = saved?.doneCount ?: 0
    val total = done + repository.countNeedingMirror()
    beginStage(STAGE_ENRICH, done, total)
    var failedThisPass = 0
    while (true) {
      val batch = repository.waiversNeedingMirror(cursor, ENRICH_BATCH)
      if (batch.isEmpty()) break
      for (ref in batch) {
        gateBeforeUnit()
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
        unitDone(done, total, client.stats().requests - before, remainingUnits = total - done)
      }
    }
    val note = if (failedThisPass > 0) "$failedThisPass waivers could not be mirrored; see the errors list" else null
    saveStage(STAGE_ENRICH, RunState.DONE.name, cursor.toString(), done, total, note)
    log.info("SmartWaiver enrich finished: ${progress.mirrored} mirrored, $failedThisPass failed")
  }

  private suspend fun checkins() {
    val saved = repository.getState(STAGE_CHECKINS)
    val from = saved?.cursorValue?.let { LocalDateTime.parse(it) } ?: FIRST_WAIVER_DAY.atStartOfDay()
    beginStage(STAGE_CHECKINS, saved?.doneCount ?: 0, null)
    var total = saved?.doneCount ?: 0
    val now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).withNano(0)
    checkinSync.syncRange(from, now) { added, cursor ->
      total += added
      progress = progress.copy(checkinsAdded = progress.checkinsAdded + added)
      saveStage(STAGE_CHECKINS, "RUNNING", cursor.toString(), total, null)
      unitDone(total, null, 1, remainingUnits = 0)
      gateBeforeUnit()
    }
    saveStage(STAGE_CHECKINS, RunState.DONE.name, now.toString(), total, null)
  }

  // ---- pacing, budget, pause ----

  private fun beginStage(name: String, done: Int, total: Int?) {
    unitsThisStage = 0
    callsThisStage = 0
    stageStartedMs = clock.millis()
    progress = progress.copy(stage = name, stageDone = done, stageTotal = total)
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
      slowMode = true
      lastRateLimitHits = stats.rateLimitHits
    }
    unitsThisStage++
    callsThisStage += calls
    rollBudgetDay()
    recordSample(stats.requests)
    val callsPerUnit = if (unitsThisStage > 0) callsThisStage.toDouble() / unitsThisStage else CALLS_PER_WAIVER_GUESS
    val interval = effectiveIntervalMs() / MS_PER_SECOND.toDouble()
    val stageEta =
        if (total != null) etaSeconds((remainingUnits * callsPerUnit).toLong(), requestsToday, dailyBudget, interval, secondsUntilMidnight())
        else null
    progress =
        progress.copy(
            stageDone = done,
            stageTotal = total,
            etaStageSeconds = stageEta,
            etaOverallSeconds = stageEta,
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

  private fun setState(state: RunState, waitingUntil: String?, error: String?) {
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
