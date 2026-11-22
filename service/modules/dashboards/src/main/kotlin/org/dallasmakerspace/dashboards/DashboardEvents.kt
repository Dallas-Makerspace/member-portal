package org.dallasmakerspace.dashboards

import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Tells the cast agent what changed; it never has to poll for edits. */
@Serializable
data class DashboardEvent(val type: String, val committeeId: Int? = null) {
  companion object {
    const val RELOAD = "reload"
    const val TARGETS_CHANGED = "targets-changed"
  }
}

@Singleton
class DashboardEvents @Inject constructor() {
  private val shared =
      MutableSharedFlow<DashboardEvent>(
          extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  val events: SharedFlow<DashboardEvent> = shared.asSharedFlow()

  fun reload(committeeId: Int) {
    shared.tryEmit(DashboardEvent(DashboardEvent.RELOAD, committeeId))
  }

  fun targetsChanged(committeeId: Int) {
    shared.tryEmit(DashboardEvent(DashboardEvent.TARGETS_CHANGED, committeeId))
  }

  /**
   * What the agent reads: each event as a server-sent-event block, plus a comment every [heartbeat]. The comment
   * comes from the same loop that delivers events, so a reader that hears it knows events would arrive too, and
   * one that stops hearing it can drop the connection and start again.
   */
  fun stream(heartbeat: Duration = HEARTBEAT): Flow<String> =
      merge(
          events.map { "data: ${Json.encodeToString(it)}\n\n" },
          flow {
            while (true) {
              delay(heartbeat.toMillis())
              emit(": ping\n\n")
            }
          })

  companion object {
    val HEARTBEAT: Duration = Duration.ofSeconds(15)
    private const val BUFFER = 64
  }
}
