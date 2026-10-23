package org.dallasmakerspace.server.memberservice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.DMSHttpClient
import org.dallasmakerspace.server.common.HttpException
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.mockito.kotlin.whenever
import org.mockito.kotlin.mock

/**
 * P2-5 / QA F7: `watchErrorResult` recovers the service's status + member-facing message
 * from the JSON body embedded in an HttpException message — parsed as JSON, not regex —
 * and falls back cleanly when there is nothing parseable.
 */
class MemberServiceClientWatchErrorTest {

  private val config =
      mock<AppConfig>().also {
        whenever(it.requireStringProperty("app.member-service.url")).thenReturn("http://service.test")
        whenever(it.requireStringProperty("app.member-service.api-key")).thenReturn("test-key")
        whenever(it.requireStringProperty("app.member-service.api-client")).thenReturn("test-client")
      }
  private val client = MemberServiceClient(LoggerFactory(), config, mock<DMSHttpClient>())

  @Test
  fun `post-style failure yields the service status and message`() {
    val ex =
        HttpException(
            "Failed to post http://service.test/bambu/watches; Status: 409 Conflict " +
                "Response: {\"status\":\"ERROR\",\"message\":\"That print has already finished\",\"data\":null}")

    val result = client.watchErrorResult(ex)

    assertEquals(409, result.statusCode)
    assertEquals("That print has already finished", result.message)
    assertNull(result.data)
  }

  @Test
  fun `delete-style failure yields the service status and message`() {
    val ex =
        HttpException(
            "Failed to delete http://service.test/bambu/watches/9; Status: 404 Not Found " +
                "Response: {\"status\":\"ERROR\",\"message\":\"Watch not found\",\"data\":null}",
            httpStatus = 404)

    val result = client.watchErrorResult(ex)

    assertEquals(404, result.statusCode)
    assertEquals("Watch not found", result.message)
  }

  @Test
  fun `a message with escaped quotes survives parsing intact`() {
    val ex =
        HttpException(
            "Failed to post http://service.test/bambu/watches; Status: 422 Unprocessable Entity " +
                "Response: {\"status\":\"ERROR\",\"message\":\"We can't estimate \\\"yet\\\"\",\"data\":null}")

    val result = client.watchErrorResult(ex)

    assertEquals(422, result.statusCode)
    assertEquals("We can't estimate \"yet\"", result.message)
  }

  @Test
  fun `status falls back to the exception's httpStatus, then to 502`() {
    val withStatus = client.watchErrorResult(HttpException("boom", httpStatus = 503))
    assertEquals(503, withStatus.statusCode)
    assertEquals("Something went wrong. Please try again.", withStatus.message)

    val bare = client.watchErrorResult(HttpException("boom"))
    assertEquals(502, bare.statusCode)
    assertEquals("Something went wrong. Please try again.", bare.message)
  }

  @Test
  fun `an unparseable embedded body falls back to the generic message`() {
    val ex = HttpException("Failed to post x; Status: 400 Bad Request Response: {not json")

    val result = client.watchErrorResult(ex)

    assertEquals(400, result.statusCode)
    assertEquals("Something went wrong. Please try again.", result.message)
  }
}
