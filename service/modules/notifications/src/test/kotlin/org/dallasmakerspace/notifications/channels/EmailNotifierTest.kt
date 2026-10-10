package org.dallasmakerspace.notifications.channels

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
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

  private val message =
      OutgoingMessage(subject = "Test subject", text = "Test text", html = "<p>Test body</p>")

  private fun notifier(engine: MockEngine, email: String? = "jdoe@example.com"): EmailNotifier {
    val activeDirectory = mock<IActiveDirectoryService>()
    whenever(activeDirectory.getMembersByUsernameList(listOf("jdoe")))
        .thenReturn(mapOf("jdoe" to email?.let(::adUser)))
    return EmailNotifier(mockAppConfig, mockLoggerFactory, HttpClient(engine), activeDirectory)
  }

  private fun adUser(email: String) =
      ADUser(
          sAMAccountName = "jdoe",
          mail = email,
          givenName = "Jane",
          sn = "Doe",
          telephoneNumber = null,
          employeeID = null,
          displayName = "Jane Doe",
          objectGuid = "guid",
          whenCreated = null,
          enabled = true,
          groups = emptyList())

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

    val result = notifier(engine).send("jdoe", message)

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

    notifier(engine).send("jdoe", message)

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

        notifier(engine).send("jdoe", message)

        val body = capturedRequest!!.body.toByteArray().decodeToString()
        assertTrue(body.contains("<p>Test body</p>"))
        assertTrue(body.contains("dallasmakerspace.org/wp-content/uploads/56_wide2.png"))
        assertTrue(body.contains("https://members.dallasmakerspace.org/profile-me"))
        assertTrue(!body.contains("/notifications/unsubscribe"))
      }

  @Test
  fun `send should keep the text part free of html`() = runBlocking {
    var capturedRequest: io.ktor.client.request.HttpRequestData? = null
    val engine = MockEngine { request ->
      capturedRequest = request
      respond(
          """{"results":{"total_rejected_recipients":0,"total_accepted_recipients":1,"id":"txn-1"}}""",
          HttpStatusCode.OK,
          headersOf(HttpHeaders.ContentType, "application/json"))
    }

    notifier(engine).send("jdoe", message)

    val body = capturedRequest!!.body.toByteArray().decodeToString()
    val text = Regex("\"text\":\"(.*?)\",\"html\"").find(body)!!.groupValues[1]
    assertTrue(text.startsWith("Test text"))
    assertTrue(!text.contains("<p>"))
  }

  @Test
  fun `send should return permanent failure when recipient email missing`() = runBlocking {
    val engine = MockEngine { error("request should not have been made") }

    val result = notifier(engine, email = null).send("jdoe", message)

    assertIs<SendResult.PermanentFailure>(result)
    assertTrue(result.reason.contains("No email address"))
  }

  @Test
  fun `send should return permanent failure on 400`() = runBlocking {
    val engine = MockEngine {
      respond("""{"errors":[{"message":"bad request"}]}""", HttpStatusCode.BadRequest)
    }

    val result = notifier(engine).send("jdoe", message)

    assertIs<SendResult.PermanentFailure>(result)
    assertTrue(result.reason.contains("400"))
  }

  @Test
  fun `send should return transient failure on 429`() = runBlocking {
    val engine = MockEngine {
      respond("""{"errors":[{"message":"rate limited"}]}""", HttpStatusCode.TooManyRequests)
    }

    val result = notifier(engine).send("jdoe", message)

    assertIs<SendResult.TransientFailure>(result)
    assertTrue(result.reason.contains("429"))
  }

  @Test
  fun `send should return transient failure on 500`() = runBlocking {
    val engine = MockEngine {
      respond("""{"errors":[{"message":"boom"}]}""", HttpStatusCode.InternalServerError)
    }

    val result = notifier(engine).send("jdoe", message)

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

    val result = notifier(engine).send("jdoe", message)

    assertIs<SendResult.PermanentFailure>(result)
    assertTrue(result.reason.contains("rejected"))
  }
}
