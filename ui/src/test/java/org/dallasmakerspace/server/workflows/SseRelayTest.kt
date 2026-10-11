package org.dallasmakerspace.server.workflows

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeStringUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readString

class SseRelayTest {
  private suspend fun upstreamWrite(ch: ByteChannel, text: String) {
    ch.writeStringUtf8(text)
    ch.flush()
  }

  @Test
  fun `events pass through unchanged and the relay ends when upstream closes`() = runBlocking {
    withTimeout(5_000) {
      val upstream = ByteChannel(autoFlush = true)
      val out = ByteChannel(autoFlush = true)
      val job = async { SseRelay.relay(upstream, out, heartbeatMs = 10_000) }
      val payload = "id: 7\nevent: run\ndata: {\"type\":\"run\"}\n\n"
      upstreamWrite(upstream, payload)
      upstream.close()
      job.await()
      out.close()
      assertEquals(payload, out.readRemaining().readString())
    }
  }

  @Test
  fun `an idle stream gets heartbeat comments`() = runBlocking {
    withTimeout(5_000) {
      val upstream = ByteChannel(autoFlush = true)
      val out = ByteChannel(autoFlush = true)
      val job = async { SseRelay.relay(upstream, out, heartbeatMs = 20) }
      delay(120)
      upstream.close()
      job.await()
      out.close()
      assertTrue(out.readRemaining().readString().contains(": hb\n\n"))
    }
  }

  @Test
  fun `a heartbeat is never injected in the middle of an event`() = runBlocking {
    withTimeout(5_000) {
      val upstream = ByteChannel(autoFlush = true)
      val out = ByteChannel(autoFlush = true)
      val job = async { SseRelay.relay(upstream, out, heartbeatMs = 15) }
      upstreamWrite(upstream, "id: 1\nevent: run\n")
      delay(120)
      upstreamWrite(upstream, "data: {}\n\n")
      upstream.close()
      job.await()
      out.close()
      assertTrue(out.readRemaining().readString().contains("id: 1\nevent: run\ndata: {}\n\n"))
    }
  }

  @Test
  fun `the relay says bye and stops when the session is no longer allowed`() = runBlocking {
    withTimeout(5_000) {
      val upstream = ByteChannel(autoFlush = true)
      val out = ByteChannel(autoFlush = true)
      val job =
          async { SseRelay.relay(upstream, out, heartbeatMs = 20, stillAllowed = { false }) }
      job.await()
      out.close()
      val text = out.readRemaining().readString()
      assertTrue(text.startsWith("event: ${SseRelay.BYE_EVENT}\n"), text)
      assertFalse(text.contains(": hb"))
    }
  }
}
