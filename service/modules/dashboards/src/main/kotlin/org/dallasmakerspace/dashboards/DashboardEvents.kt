package org.dallasmakerspace.dashboards

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

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
  private val flow =
      MutableSharedFlow<DashboardEvent>(
          extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  val events: SharedFlow<DashboardEvent> = flow.asSharedFlow()

  fun reload(committeeId: Int) {
    flow.tryEmit(DashboardEvent(DashboardEvent.RELOAD, committeeId))
  }

  fun targetsChanged(committeeId: Int) {
    flow.tryEmit(DashboardEvent(DashboardEvent.TARGETS_CHANGED, committeeId))
  }

  private companion object {
    const val BUFFER = 64
  }
}
