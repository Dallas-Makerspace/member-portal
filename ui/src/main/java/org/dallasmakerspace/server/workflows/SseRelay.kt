package org.dallasmakerspace.server.workflows

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Copies an upstream SSE body to the browser line by line (no buffering beyond one line), adds a
 * heartbeat comment whenever the stream is idle at an event boundary, and stops when [stillAllowed]
 * turns false (checked on every heartbeat tick).
 */
object SseRelay {
  const val HEARTBEAT_MS = 15_000L
  const val HEARTBEAT_LINE = ": hb"
  const val BYE_EVENT = "bye"
  const val ERROR_EVENT = "stream-error"

  suspend fun relay(
      upstream: ByteReadChannel,
      out: ByteWriteChannel,
      heartbeatMs: Long = HEARTBEAT_MS,
      stillAllowed: suspend () -> Boolean = { true },
  ) = coroutineScope {
    val mutex = Mutex()
    var atBoundary = true
    val reader = launch {
      while (true) {
        val line = upstream.readUTF8Line() ?: break
        mutex.withLock {
          out.writeStringUtf8(line + "\n")
          out.flush()
          if (line.isEmpty()) atBoundary = true else if (!line.startsWith(":")) atBoundary = false
        }
      }
    }
    val heartbeat = launch {
      while (isActive) {
        delay(heartbeatMs)
        if (!stillAllowed()) {
          mutex.withLock { writeEvent(out, BYE_EVENT, """{"reason":"unauthorized"}""") }
          reader.cancel()
          return@launch
        }
        mutex.withLock {
          if (atBoundary) {
            out.writeStringUtf8("$HEARTBEAT_LINE\n\n")
            out.flush()
          }
        }
      }
    }
    reader.join()
    heartbeat.cancel()
  }

  suspend fun writeEvent(out: ByteWriteChannel, name: String, data: String) {
    out.writeStringUtf8("event: $name\ndata: $data\n\n")
    out.flush()
  }
}
