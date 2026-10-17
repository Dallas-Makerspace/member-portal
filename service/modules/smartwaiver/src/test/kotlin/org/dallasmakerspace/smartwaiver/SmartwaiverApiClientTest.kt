package org.dallasmakerspace.smartwaiver

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SmartwaiverApiClientTest {
  private val sleeps = mutableListOf<Long>()
  private var now = 0L
  private val requestedPaths = mutableListOf<String>()
  private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

  private val rateLimited = """{"type":"rate_limit","rate_limit":{"requests":107,"max":100,"retryAfter":2}}"""
  private val waiverBody =
      """{"version":4,"waiver":{"waiverId":"abc123","title":"Test Waiver","firstName":"Test","lastName":"User"}}"""

  private fun client(responses: List<Pair<HttpStatusCode, String>>): SmartwaiverApiClient {
    val appConfig = mock<AppConfig>()
    whenever(appConfig.requireStringProperty("app.smartwaiver.apiKey")).thenReturn("test-key")
    val queue = ArrayDeque(responses)
    val engine = MockEngine { request ->
      requestedPaths += request.url.encodedPath
      assertEquals("test-key", request.headers["sw-api-key"])
      val (status, body) = queue.removeFirst()
      respond(body, status, jsonHeaders)
    }
    val throttle =
        RequestThrottle(
            mapOf(RequestCategory.SEARCH to 0L, RequestCategory.OTHER to 0L),
            nowMs = { now },
            sleep = {
              sleeps += it
              now += it
            },
        )
    return SmartwaiverApiClient(appConfig, LoggerFactory(), HttpClient(engine), throttle)
  }

  @Test
  fun `retries after a 429 and pauses for retryAfter`() = runBlocking {
    val c = client(listOf(HttpStatusCode.TooManyRequests to rateLimited, HttpStatusCode.OK to waiverBody))

    val waiver = c.getWaiver("abc123")

    assertEquals("abc123", waiver.waiverId)
    assertEquals(listOf(2_000L), sleeps)
    assertEquals(2, requestedPaths.size)
  }

  @Test
  fun `gives up after repeated 429s`() {
    val c = client(List(5) { HttpStatusCode.TooManyRequests to rateLimited })

    assertFailsWith<SmartwaiverApiException> { runBlocking { c.getWaiver("abc123") } }
    assertEquals(5, requestedPaths.size)
  }

  @Test
  fun `non-429 errors are not retried`() {
    val c = client(listOf(HttpStatusCode.InternalServerError to "boom"))

    assertFailsWith<SmartwaiverApiException> { runBlocking { c.getWaiver("abc123") } }
    assertEquals(1, requestedPaths.size)
  }

  @Test
  fun `missing photos sub-resource means no photos`() = runBlocking {
    val c = client(listOf(HttpStatusCode.NotFound to "{}"))

    assertEquals(emptyList(), c.getWaiverPhotos("abc123"))
  }

  @Test
  fun `waiver ids that are not plain tokens never reach the network`() {
    val c = client(emptyList())

    listOf("../members", "a/b", "abc?x=1", "", "a b", "x".repeat(65)).forEach { id ->
      assertFailsWith<SmartwaiverApiException>(id) { runBlocking { c.getWaiver(id) } }
      assertFailsWith<SmartwaiverApiException>(id) { runBlocking { c.getWaiverPhotos(id) } }
    }
    assertTrue(requestedPaths.isEmpty())
  }
}
