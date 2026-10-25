package org.dallasmakerspace.bambuddy.routing

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.bambuddy.notifications.webhook.BambuddyWebhookHandler
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BambuddyWebhookRouteTest {

  private lateinit var handler: BambuddyWebhookHandler

  @Before
  fun setUp() {
    handler = mock()
    runBlocking {
      whenever(handler.isRequestAuthorized("Bearer good")).thenReturn(true)
      whenever(handler.handle(eq("Bearer good"), any(), any())).thenReturn(HttpStatusCode.OK)
    }
  }

  private fun chunked(bytes: ByteArray) =
      object : OutgoingContent.ReadChannelContent() {
        override val contentLength: Long? = null

        override val contentType: ContentType = ContentType.Application.Json

        override fun readFrom(): ByteReadChannel = ByteReadChannel(bytes)
      }

  @Test
  fun `unauthorized requests are rejected before the body is read`() = testApplication {
    application { routing { bambuddyWebhookRoute { handler } } }

    val response =
        client.post("/webhook/bambuddy") {
          header(HttpHeaders.Authorization, "Bearer bad")
          setBody("""{"event":"print_complete"}""")
        }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    runBlocking { verify(handler, never()).handle(any(), any(), any()) }
  }

  @Test
  fun `a small body is handed to the handler`() = testApplication {
    application { routing { bambuddyWebhookRoute { handler } } }

    val response =
        client.post("/webhook/bambuddy") {
          header(HttpHeaders.Authorization, "Bearer good")
          setBody("""{"event":"print_complete"}""")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    runBlocking {
      verify(handler).handle(eq("Bearer good"), eq("""{"event":"print_complete"}"""), any())
    }
  }

  @Test
  fun `a declared Content-Length over the cap is refused`() = testApplication {
    application { routing { bambuddyWebhookRoute { handler } } }

    val response =
        client.post("/webhook/bambuddy") {
          header(HttpHeaders.Authorization, "Bearer good")
          setBody("x".repeat((BAMBU_WEBHOOK_MAX_BODY_BYTES + 1).toInt()))
        }

    assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    runBlocking { verify(handler, never()).handle(any(), any(), any()) }
  }

  @Test
  fun `a body over the cap with no Content-Length is refused while reading`() = testApplication {
    application { routing { bambuddyWebhookRoute { handler } } }

    val response =
        client.post("/webhook/bambuddy") {
          header(HttpHeaders.Authorization, "Bearer good")
          setBody(chunked(ByteArray((BAMBU_WEBHOOK_MAX_BODY_BYTES + 1).toInt()) { 'x'.code.toByte() }))
        }

    assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    runBlocking { verify(handler, never()).handle(any(), any(), any()) }
  }

  @Test
  fun `a chunked body exactly at the cap is accepted`() = testApplication {
    application { routing { bambuddyWebhookRoute { handler } } }

    val response =
        client.post("/webhook/bambuddy") {
          header(HttpHeaders.Authorization, "Bearer good")
          setBody(chunked(ByteArray(BAMBU_WEBHOOK_MAX_BODY_BYTES.toInt()) { 'x'.code.toByte() }))
        }

    assertEquals(HttpStatusCode.OK, response.status)
  }
}
