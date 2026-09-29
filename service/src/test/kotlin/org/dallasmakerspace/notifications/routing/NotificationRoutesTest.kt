package org.dallasmakerspace.notifications.routing

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.apiKey
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.notifications.Aggregation
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.Notification
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.NotificationStatus
import org.dallasmakerspace.notifications.topics.FollowingTopic
import org.dallasmakerspace.notifications.topics.SystemTopic
import org.dallasmakerspace.plugins.ApiResponse
import org.dallasmakerspace.plugins.Status
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NotificationRoutesTest {

  private lateinit var mockService: NotificationService

  @Before
  fun setUp() {
    mockService = mock()
    // The emit/PUT-preferences routes both resolve a topic string via
    // notificationService.findTopic(...) (mockService is a bare mock, so this must be stubbed for
    // any test that expects a route to get past topic resolution).
    whenever(mockService.findTopic("EVENT_ANNOUNCEMENT")).thenReturn(FollowingTopic)
    whenever(mockService.findTopic("SYSTEM")).thenReturn(SystemTopic)
  }

  private fun appConfig(): AppConfig {
    val mockAppConfig = mock<AppConfig>()
    whenever(mockAppConfig.requireStringProperty("app.api.fallback.name")).thenReturn("test-client")
    whenever(mockAppConfig.requireStringProperty("app.api.fallback.key")).thenReturn("test-key")
    whenever(mockAppConfig.requireStringProperty("app.api.fallback.role"))
        .thenReturn("MEMBER_READER")
    return mockAppConfig
  }

  private fun ApplicationTestBuilder.configureApp() {
    application {
      install(ContentNegotiation) { gson { setPrettyPrinting() } }
      install(Authentication) {
        apiKey(appConfig(), ApiKeyAuthProvider.X_API_KEY) { validate { null } }
      }
      routing { notificationRoutes(mockService) }
    }
    client = createClient {
      install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
        gson { setPrettyPrinting() }
      }
    }
  }

  private fun notification(id: Long) =
      Notification(
          id = id,
          memberUsername = "jdoe",
          topic = SystemTopic,
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
          createdAt = LocalDateTime.of(2026, 8, 2, 12, 0))

  @Test
  fun `post notifications should emit and return the created notification`() = testApplication {
    configureApp()
    val created = notification(7L)
    runBlocking { whenever(mockService.create(any())).thenReturn(created) }

    val response =
        client.post("/notifications") {
          contentType(ContentType.Application.Json)
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          setBody(
              """{"memberUsername":"jdoe","topic":"SYSTEM","templateName":"system-test",
                      "channel":"EMAIL","dedupeKey":"k1"}""")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    val apiResponse = response.body<ApiResponse<NotificationView>>()
    assertEquals(Status.SUCCESS, apiResponse.status)
    assertEquals(7L, apiResponse.data!!.id)
    assertEquals("QUEUED", apiResponse.data!!.status)
    Unit
  }

  @Test
  fun `get notifications should return the outbox listing`() = testApplication {
    configureApp()
    runBlocking {
      whenever(mockService.list(eq(50), eq("jdoe"), eq(NotificationStatus.QUEUED)))
          .thenReturn(listOf(notification(7L)))
    }

    val response =
        client.get("/notifications?username=jdoe&status=QUEUED") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    val apiResponse = response.body<ApiResponse<List<NotificationView>>>()
    assertEquals(1, apiResponse.data!!.size)
    assertEquals("jdoe", apiResponse.data!![0].memberUsername)
    Unit
  }

  @Test
  fun `get notifications should reject an invalid status filter`() = testApplication {
    configureApp()

    val response =
        client.get("/notifications?status=BOGUS") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.BadRequest, response.status)
    Unit
  }

  @Test
  fun `get notifications should require authentication`() = testApplication {
    configureApp()

    val response = client.get("/notifications")

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    Unit
  }

  @Test
  fun `get preferences should return the effective views`() = testApplication {
    configureApp()
    runBlocking { whenever(mockService.getPreferences("jdoe")).thenReturn(emptyList()) }

    val response =
        client.get("/notifications/preferences/jdoe") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    val apiResponse = response.body<ApiResponse<List<PreferenceView>>>()
    assertEquals(0, apiResponse.data!!.size)
    Unit
  }

  @Test
  fun `put preferences should update a member preference`() = testApplication {
    configureApp()
    runBlocking {
      whenever(
              mockService.setPreference(
                  eq("jdoe"),
                  eq(FollowingTopic),
                  eq(Channel.EMAIL),
                  eq(false),
                  eq(null)))
          .thenReturn(
              org.dallasmakerspace.notifications.MemberPreferenceView(
                  memberUsername = "jdoe",
                  topic = FollowingTopic,
                  channel = Channel.EMAIL,
                  enabled = false,
                  aggregationOverride = null,
                  effectiveAggregation = Channel.EMAIL.defaultAggregation,
                  digestSupported = true))
    }

    val response =
        client.put("/notifications/preferences/jdoe") {
          contentType(ContentType.Application.Json)
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          setBody("""{"topic":"EVENT_ANNOUNCEMENT","channel":"EMAIL","enabled":false}""")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    verify(mockService)
        .setPreference(
            eq("jdoe"),
            eq(FollowingTopic),
            eq(Channel.EMAIL),
            eq(false),
            eq(null))
    Unit
  }

  @Test
  fun `put preferences should reject an invalid aggregation`() = testApplication {
    configureApp()

    val response =
        client.put("/notifications/preferences/jdoe") {
          contentType(ContentType.Application.Json)
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          setBody("""{"topic":"EVENT_ANNOUNCEMENT","channel":"EMAIL","aggregation":"HOURLY"}""")
        }

    assertEquals(HttpStatusCode.BadRequest, response.status)
    Unit
  }
}
