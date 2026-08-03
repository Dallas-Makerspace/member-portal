package org.dallasmakerspace.notifications.channels

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.Aggregation
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.Notification
import org.dallasmakerspace.notifications.NotificationStatus
import org.dallasmakerspace.notifications.NotificationTopic
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class EmailNotifierTest {

  private lateinit var mockLoggerFactory: LoggerFactory
  private lateinit var mockLogger: Logger
  private lateinit var mockAppConfig: AppConfig

  @Before
  fun setUp() {
    mockLoggerFactory = mock()
    mockLogger = mock()
    mockAppConfig = mock()
    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mockLogger)
    whenever(mockAppConfig.requireStringProperty("app.notifications.sparkpost.api-key"))
        .thenReturn("test-api-key")
    whenever(mockAppConfig.requireStringProperty("app.notifications.sparkpost.from-email"))
        .thenReturn("no-reply@dallasmakerspace.org")
    whenever(mockAppConfig.requireStringProperty("app.notifications.sparkpost.from-name"))
        .thenReturn("DMS Notifications")
  }

  private fun notification() =
      Notification(
          id = 1L,
          memberUsername = "jdoe",
          topic = NotificationTopic.SYSTEM,
          channel = Channel.EMAIL,
          aggregation = Aggregation.IMMEDIATE,
          templateName = "system-test",
          payload = null,
          subject = "Test subject",
          body = "Test body",
          status = NotificationStatus.QUEUED,
          attempts = 0,
          nextAttemptAt = null,
          dedupeKey = null,
          providerId = null,
          lastError = null,
          sentAt = null,
          readAt = null,
          createdAt = LocalDateTime.now(ZoneId.of("UTC")))

  private fun notifier(engine: MockEngine): EmailNotifier =
      EmailNotifier(mockAppConfig, mockLoggerFactory, HttpClient(engine))

  @Test
  fun `send should post transmission and return success with provider id`() = runBlocking {
    var capturedRequest: io.ktor.client.request.HttpRequestData? = null
    val engine = MockEngine { request ->
      capturedRequest = request
      respond(
          """{"results":{"total_rejected_recipients":0,"total_accepted_recipients":1,"id":"txn-1"}}""",
          HttpStatusCode.OK,
          headersOf(HttpHeaders.ContentType, "application/json"))
    }

    val result = notifier(engine).send(notification(), "jdoe@example.com")

    assertIs<SendResult.Success>(result)
    assertEquals("txn-1", result.providerId)
    val request = capturedRequest!!
    assertEquals("https://api.sparkpost.com/api/v1/transmissions", request.url.toString())
    assertEquals("test-api-key", request.headers[HttpHeaders.Authorization])
    val body = request.body.toByteArray().decodeToString()
    assertTrue(body.contains("jdoe@example.com"))
    assertTrue(body.contains("no-reply@dallasmakerspace.org"))
    assertTrue(body.contains("Test subject"))
  }

  @Test
  fun `send should disable open and click tracking`() = runBlocking {
    var capturedRequest: io.ktor.client.request.HttpRequestData? = null
    val engine = MockEngine { request ->
      capturedRequest = request
      respond(
          """{"results":{"total_rejected_recipients":0,"total_accepted_recipients":1,"id":"txn-1"}}""",
          HttpStatusCode.OK,
          headersOf(HttpHeaders.ContentType, "application/json"))
    }

    notifier(engine).send(notification(), "jdoe@example.com")

    val body = capturedRequest!!.body.toByteArray().decodeToString()
    assertTrue(body.contains("\"open_tracking\":false"))
    assertTrue(body.contains("\"click_tracking\":false"))
  }

  @Test
  fun `send should wrap the html body in the branded email shell with a profile-management link, not a token unsubscribe link`() =
      runBlocking {
        var capturedRequest: io.ktor.client.request.HttpRequestData? = null
        val engine = MockEngine { request ->
          capturedRequest = request
          respond(
              """{"results":{"total_rejected_recipients":0,"total_accepted_recipients":1,"id":"txn-1"}}""",
              HttpStatusCode.OK,
              headersOf(HttpHeaders.ContentType, "application/json"))
        }

        notifier(engine).send(notification(), "jdoe@example.com")

        val body = capturedRequest!!.body.toByteArray().decodeToString()
        assertTrue(body.contains("Test body"))
        assertTrue(body.contains("dallasmakerspace.org/wp-content/uploads/56_wide2.png"))
        assertTrue(body.contains("https://members.dallasmakerspace.org/profile-me"))
        assertTrue(!body.contains("/notifications/unsubscribe"))
      }

  @Test
  fun `send should return permanent failure when recipient email missing`() = runBlocking {
    val engine = MockEngine { error("request should not have been made") }

    val result = notifier(engine).send(notification(), null)

    assertIs<SendResult.PermanentFailure>(result)
    assertTrue(result.reason.contains("No email address"))
  }

  @Test
  fun `send should return permanent failure on 400`() = runBlocking {
    val engine = MockEngine {
      respond("""{"errors":[{"message":"bad request"}]}""", HttpStatusCode.BadRequest)
    }

    val result = notifier(engine).send(notification(), "jdoe@example.com")

    assertIs<SendResult.PermanentFailure>(result)
    assertTrue(result.reason.contains("400"))
  }

  @Test
  fun `send should return transient failure on 429`() = runBlocking {
    val engine = MockEngine {
      respond("""{"errors":[{"message":"rate limited"}]}""", HttpStatusCode.TooManyRequests)
    }

    val result = notifier(engine).send(notification(), "jdoe@example.com")

    assertIs<SendResult.TransientFailure>(result)
    assertTrue(result.reason.contains("429"))
  }

  @Test
  fun `send should return transient failure on 500`() = runBlocking {
    val engine = MockEngine {
      respond("""{"errors":[{"message":"boom"}]}""", HttpStatusCode.InternalServerError)
    }

    val result = notifier(engine).send(notification(), "jdoe@example.com")

    assertIs<SendResult.TransientFailure>(result)
    assertTrue(result.reason.contains("500"))
  }

  @Test
  fun `send should return permanent failure when sparkpost rejects recipients`() = runBlocking {
    val engine = MockEngine {
      respond(
          """{"results":{"total_rejected_recipients":1,"total_accepted_recipients":0,"id":"txn-2"}}""",
          HttpStatusCode.OK,
          headersOf(HttpHeaders.ContentType, "application/json"))
    }

    val result = notifier(engine).send(notification(), "jdoe@example.com")

    assertIs<SendResult.PermanentFailure>(result)
    assertTrue(result.reason.contains("rejected"))
  }
}
