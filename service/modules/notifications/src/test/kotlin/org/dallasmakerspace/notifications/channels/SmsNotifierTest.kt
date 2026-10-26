package org.dallasmakerspace.notifications.channels

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class SmsNotifierTest {

  private lateinit var mockLoggerFactory: LoggerFactory
  private lateinit var mockAppConfig: AppConfig
  private lateinit var mockConfigOverride: ConfigOverrideService

  @Before
  fun setUp() {
    mockLoggerFactory = mock()
    mockAppConfig = mock()
    mockConfigOverride = mock()
    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    givenConfig(messagingServiceSid = "MG-test")
    givenSmsEnabled(true)
  }

  private fun givenConfig(
      accountSid: String = "AC-test",
      authToken: String = "test-token",
      fromNumber: String = "+12015550100",
      messagingServiceSid: String = "",
  ) {
    mapOf(
            "account-sid" to accountSid,
            "auth-token" to authToken,
            "from-number" to fromNumber,
            "messaging-service-sid" to messagingServiceSid)
        .forEach { (key, value) ->
          whenever(mockAppConfig.getStringProperty(eq("app.notifications.twilio.$key"), any()))
              .thenReturn(value)
        }
  }

  private fun givenSmsEnabled(enabled: Boolean) = runBlocking {
    whenever(
            mockConfigOverride.isFeatureEnabledFor(
                eq(ConfigRegistry.NOTIFICATIONS_SMS_ENABLED), eq("jdoe"), any()))
        .thenReturn(enabled)
  }

  private val message =
      OutgoingMessage(subject = "Your print finished", text = "Long text", html = "<p>Body</p>")

  private fun notifier(engine: MockEngine, phone: String? = "(201) 555-0123"): SmsNotifier {
    val activeDirectory = mock<IActiveDirectoryService>()
    whenever(activeDirectory.getMembersByUsernameList(listOf("jdoe")))
        .thenReturn(mapOf("jdoe" to adUser(phone)))
    return SmsNotifier(
        mockAppConfig, mockLoggerFactory, HttpClient(engine), activeDirectory, mockConfigOverride)
  }

  private fun adUser(phone: String?) =
      ADUser(
          sAMAccountName = "jdoe",
          mail = "jdoe@example.com",
          givenName = "Jane",
          sn = "Doe",
          telephoneNumber = phone,
          employeeID = null,
          displayName = "Jane Doe",
          objectGuid = "guid",
          whenCreated = null,
          enabled = true,
          groups = emptyList())

  private fun created(sid: String = "SM-1") = MockEngine {
    respond(
        """{"sid":"$sid","status":"accepted"}""",
        HttpStatusCode.Created,
        headersOf(HttpHeaders.ContentType, "application/json"))
  }

  private suspend fun form(request: HttpRequestData) =
      parseQueryString(request.body.toByteArray().decodeToString())

  @Test
  fun `send should post the message to twilio and return the message sid`() = runBlocking {
    var captured: HttpRequestData? = null
    val engine = MockEngine { request ->
      captured = request
      respond(
          """{"sid":"SM-1","status":"accepted"}""",
          HttpStatusCode.Created,
          headersOf(HttpHeaders.ContentType, "application/json"))
    }

    val result = notifier(engine).send("jdoe", message)

    assertIs<SendResult.Success>(result)
    assertEquals("SM-1", result.providerId)
    val request = captured!!
    assertEquals(
        "https://api.twilio.com/2010-04-01/Accounts/AC-test/Messages.json", request.url.toString())
    val credentials = Base64.getEncoder().encodeToString("AC-test:test-token".toByteArray())
    assertEquals("Basic $credentials", request.headers[HttpHeaders.Authorization])
    val form = form(request)
    assertEquals("+12015550123", form["To"])
    assertEquals("Dallas Makerspace: Your print finished", form["Body"])
    assertEquals("MG-test", form["MessagingServiceSid"])
    assertNull(form["From"])
  }

  @Test
  fun `send should use the from number when no messaging service is set`() = runBlocking {
    givenConfig(messagingServiceSid = "")
    var captured: HttpRequestData? = null
    val engine = MockEngine { request ->
      captured = request
      respond("""{"sid":"SM-2"}""", HttpStatusCode.Created)
    }

    notifier(engine).send("jdoe", message)

    val form = form(captured!!)
    assertEquals("+12015550100", form["From"])
    assertNull(form["MessagingServiceSid"])
  }

  @Test
  fun `send should fail permanently without calling twilio when sms is off for the member`() =
      runBlocking {
        givenSmsEnabled(false)
        val engine = MockEngine { error("request should not have been made") }

        val result = notifier(engine).send("jdoe", message)

        assertIs<SendResult.PermanentFailure>(result)
        assertTrue(result.reason.contains("not enabled"))
      }

  @Test
  fun `send should fail permanently without calling twilio when twilio is not configured`() =
      runBlocking {
        givenConfig(accountSid = "")
        val engine = MockEngine { error("request should not have been made") }

        val result = notifier(engine).send("jdoe", message)

        assertIs<SendResult.PermanentFailure>(result)
        assertTrue(result.reason.contains("not configured"))
      }

  @Test
  fun `send should fail permanently when the member has no usable phone number`() = runBlocking {
    val engine = MockEngine { error("request should not have been made") }

    for (phone in listOf(null, "", "null", "12345", "+44 20 7946 0958")) {
      val result = notifier(engine, phone).send("jdoe", message)

      assertIs<SendResult.PermanentFailure>(result, "phone=$phone")
      assertTrue(result.reason.contains("No valid US phone number"))
    }
  }

  @Test
  fun `send should fail permanently on a 400 and include the twilio error code`() = runBlocking {
    val engine = MockEngine {
      respond(
          """{"code":21610,"message":"Attempt to send to unsubscribed recipient","status":400}""",
          HttpStatusCode.BadRequest)
    }

    val result = notifier(engine).send("jdoe", message)

    assertIs<SendResult.PermanentFailure>(result)
    assertEquals("Twilio 400 (21610): Attempt to send to unsubscribed recipient", result.reason)
  }

  @Test
  fun `send should fail transiently on 429 and 500`() = runBlocking {
    for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError)) {
      val engine = MockEngine { respond("""{"code":20429,"message":"busy"}""", status) }

      val result = notifier(engine).send("jdoe", message)

      assertIs<SendResult.TransientFailure>(result, "status=$status")
      assertTrue(result.reason.contains(status.value.toString()))
    }
  }

  @Test
  fun `send should fail transiently when the request throws`() = runBlocking {
    val engine = MockEngine { throw java.io.IOException("connection reset") }

    val result = notifier(engine).send("jdoe", message)

    assertIs<SendResult.TransientFailure>(result)
    assertTrue(result.reason.contains("connection reset"))
  }

  @Test
  fun `sms does not batch into digests`() {
    assertEquals(false, notifier(created()).supportsDigest)
  }

  @Test
  fun `toE164 normalizes US numbers in common formats`() {
    assertEquals("+12015550123", toE164("201-555-0123"))
    assertEquals("+12015550123", toE164("(201) 555-0123"))
    assertEquals("+12015550123", toE164("+1 201 555 0123"))
    assertEquals("+12015550123", toE164("12015550123"))
  }

  @Test
  fun `smsBody caps long subjects`() {
    val body = smsBody(message.copy(subject = "x".repeat(500)))

    assertEquals(300, body.length)
    assertTrue(body.startsWith("Dallas Makerspace: x"))
    assertTrue(body.endsWith("..."))
  }
}
