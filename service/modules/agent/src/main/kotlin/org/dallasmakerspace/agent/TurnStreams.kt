package org.dallasmakerspace.agent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.dallasmakerspace.models.agent.AgentStreamEvent

/** A running (or just finished) turn's events; late subscribers get them all replayed. */
class ActiveTurn(val conversationId: String, val turnSeq: Int) {
  private val counter = AtomicInteger(0)
  private val flow = MutableSharedFlow<AgentStreamEvent>(replay = MAX_EVENTS)
  val events: SharedFlow<AgentStreamEvent> = flow
  @Volatile var job: Job? = null

  fun nextId(): String = "$turnSeq:${counter.incrementAndGet()}"

  suspend fun publish(event: AgentStreamEvent) = flow.emit(event)

  companion object {
    const val MAX_EVENTS = 5_000
  }
}

/** One active turn per conversation; finished turns linger briefly so reconnects can replay. */
@Singleton
class TurnStreams @Inject constructor() {
  private val active = ConcurrentHashMap<String, ActiveTurn>()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val finished = ConcurrentHashMap.newKeySet<ActiveTurn>()

  /** Null when the conversation already has a turn running. */
  fun start(conversationId: String, turnSeq: Int): ActiveTurn? {
    val turn = ActiveTurn(conversationId, turnSeq)
    val existing = active.putIfAbsent(conversationId, turn) ?: return turn
    if (existing !in finished) return null
    active[conversationId] = turn
    return turn
  }

  fun get(conversationId: String): ActiveTurn? = active[conversationId]

  fun isRunning(conversationId: String): Boolean =
      active[conversationId]?.let { it !in finished } == true

  fun finish(turn: ActiveTurn) {
    finished += turn
    scope.launch {
      delay(LINGER_MILLIS)
      active.remove(turn.conversationId, turn)
      finished -= turn
    }
  }

  companion object {
    private const val LINGER_MILLIS = 120_000L
  }
}
