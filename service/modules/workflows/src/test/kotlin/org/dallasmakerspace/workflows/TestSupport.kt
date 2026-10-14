package org.dallasmakerspace.workflows

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.workflows.*

class TestClock(start: Instant = Instant.parse("2030-01-01T00:00:00Z"), private val real: Boolean = false) :
    Clock() {
  @Volatile private var now = start

  override fun getZone(): ZoneId = ZoneOffset.UTC

  override fun withZone(zone: ZoneId?): Clock = this

  override fun instant(): Instant = if (real) Instant.now() else now

  fun advance(seconds: Long) {
    now = now.plusSeconds(seconds)
  }
}

class RecordingBus : WorkflowEventBus {
  val runs = CopyOnWriteArrayList<WorkflowRunDto>()
  val events = CopyOnWriteArrayList<WorkflowEventDto>()

  override fun publishRun(run: WorkflowRunDto) {
    runs.add(run)
  }

  override fun publishEvent(event: WorkflowEventDto) {
    events.add(event)
  }

  override fun subscribe(filter: StreamFilter): Flow<WorkflowStreamMessage> = emptyFlow()
}

@Serializable data class TestInput(val name: String)

/** Scripted step: every behaviour is a replaceable lambda and every call is appended to [log]. */
class TestStep(
    override val id: String,
    val log: MutableList<String>,
    override val reversibility: Reversibility = Reversibility.REVERSIBLE,
    override val skippable: Boolean = false,
    override val retry: RetryPolicy? = null,
) : Step<Unit, String> {
  override val outputSerializer = String.serializer()

  var checkFn: suspend (StepContext) -> StepCheck<String> = { StepCheck.WillRun() }
  var validateFn: suspend (StepContext) -> Validation = { Validation.Ok }
  var runFn: suspend (StepContext) -> String = { "out-$id" }
  var rollbackFn: suspend (StepContext, String) -> Unit = { _, _ -> }
  var artifactsFn: (String) -> List<ArtifactRef> = { emptyList() }

  /** Thrown, in order, by the next run() calls. */
  val failures = ArrayDeque<Throwable>()

  override suspend fun check(ctx: StepContext, args: Unit) = checkFn(ctx)

  override suspend fun validate(ctx: StepContext, args: Unit) = validateFn(ctx)

  override suspend fun run(ctx: StepContext, args: Unit): String {
    log += "run:$id"
    failures.removeFirstOrNull()?.let { throw it }
    return runFn(ctx)
  }

  override suspend fun rollback(ctx: StepContext, args: Unit, output: String) {
    log += "rollback:$id:$output"
    rollbackFn(ctx, output)
  }

  override fun summarize(output: String) = "summary of $id"

  override fun artifacts(args: Unit, output: String) = artifactsFn(output)
}

class StepSpec(val step: TestStep, val dependsOn: TestStep? = null)

class TestWorkflow(
    private val specs: List<StepSpec>,
    version: Int = 1,
    onFailure: FailurePolicy = FailurePolicy.PAUSE,
    retry: RetryPolicy = RetryPolicy(maxAttempts = 3, backoff = Backoff.Fixed(10.seconds), attemptTimeout = null),
    id: String = "test.flow",
) :
    Workflow<TestInput>(
        id = id,
        version = version,
        displayName = "Test flow",
        permission = "MANAGE_THINGS",
        inputSerializer = TestInput.serializer(),
        inputFields = listOf(WorkflowInputField("name", "Name")),
        onFailure = onFailure,
        retry = retry,
    ) {
  override fun subjects(input: TestInput) = listOf(Subject("thing", input.name))

  override fun steps() =
      specs.map { spec ->
        step(spec.step) {
          spec.dependsOn?.let { output(it) }
          Unit
        }
      }

  override fun validateInput(input: TestInput) =
      if (input.name.isBlank()) listOf("name is required") else emptyList()
}

fun input(name: String = "alpha") = JsonObject(mapOf("name" to JsonPrimitive(name)))

class Harness(
    val workflow: Workflow<*>,
    val clock: TestClock = TestClock(),
    val store: WorkflowStore = InMemoryWorkflowStore(clock),
    val bus: RecordingBus = RecordingBus(),
    instanceId: String = "inst-a",
) {
  val wake = WorkflowWakeSignal()
  val registry = WorkflowRegistry(setOf(workflow))
  val settings =
      EngineSettings().apply {
        this.clock = this@Harness.clock
        this.instanceId = instanceId
        leaseSeconds = 30
        sleep = {}
      }
  val service = DefaultWorkflowService(store, registry, bus, settings, wake, LoggerFactory())
  val executor = DefaultWorkflowExecutor(store, registry, bus, settings, wake, LoggerFactory())

  /** Another engine instance on the same store (own instance id, same clock). */
  fun otherInstance(id: String, wf: Workflow<*> = workflow): Harness =
      Harness(wf, clock, store, bus, id)

  suspend fun start(name: String = "alpha", token: String = "tok-$name"): WorkflowRunDto {
    val result = service.start(workflow.id, input(name), "jdoe", "TEST", token)
    return (result as StartResult.Started).run
  }

  suspend fun drain(max: Int = 50) {
    repeat(max) { if (!executor.runOnce()) return }
    error("executor did not settle")
  }

  suspend fun run(id: Long) = service.getRun(id)!!

  suspend fun operate(id: Long, action: WorkflowOperatorAction) = service.operate(id, action, "asmith")
}

val WorkflowRunDto.stepStatuses get() = steps.map { it.status }

fun blocking(block: suspend CoroutineScope.() -> Unit) {
  runBlocking(block = block)
}

/** Store whose finishStep(SUCCEEDED) throws once, simulating a process crash after run() returned. */
class CrashOnFinishStore(private val d: WorkflowStore) : WorkflowStore by d {
  @Volatile var crashNext = false

  override suspend fun finishStep(
      fence: Fence,
      stepIndex: Int,
      status: WorkflowStepStatus,
      outputJson: String?,
      outputSummary: String?,
      error: String?,
      events: List<NewEvent>,
  ) {
    if (crashNext && status == WorkflowStepStatus.SUCCEEDED) {
      crashNext = false
      throw IllegalStateException("simulated crash")
    }
    d.finishStep(fence, stepIndex, status, outputJson, outputSummary, error, events)
  }
}

/** Store whose heartbeats are rejected, as if another instance had taken the lease. */
class DeadHeartbeatStore(private val d: WorkflowStore) : WorkflowStore by d {
  override suspend fun heartbeat(fence: Fence, leaseSeconds: Int) = false
}

/** Counts calls per store method; [failOn] names methods that throw instead. */
class CallLog {
  val counts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
  @Volatile var failOn: Set<String> = emptySet()

  fun count(name: String) = counts[name]?.get() ?: 0

  val total get() = counts.values.sumOf { it.get() }
}

fun countingStore(delegate: WorkflowStore, calls: CallLog): WorkflowStore =
    java.lang.reflect.Proxy.newProxyInstance(
        WorkflowStore::class.java.classLoader, arrayOf(WorkflowStore::class.java)) { _, method, args ->
          calls.counts.getOrPut(method.name) { AtomicInteger() }.incrementAndGet()
          if (method.name in calls.failOn) throw IllegalStateException("store unavailable")
          try {
            method.invoke(delegate, *(args ?: emptyArray()))
          } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
          }
        } as WorkflowStore
