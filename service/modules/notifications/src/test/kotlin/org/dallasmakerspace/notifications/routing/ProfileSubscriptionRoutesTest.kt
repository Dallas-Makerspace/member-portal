package org.dallasmakerspace.notifications.routing

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.apiKey
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.notifications.FollowSuggestionDebug
import org.dallasmakerspace.notifications.FollowSuggestionResult
import org.dallasmakerspace.notifications.FollowSuggestionService
import org.dallasmakerspace.notifications.ProfileSubscriptionService
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProfileSubscriptionRoutesTest {

  private lateinit var mockService: ProfileSubscriptionService
  private lateinit var mockFeatureFlagService: FeatureFlagService
  private lateinit var mockSuggestionService: FollowSuggestionService

  @Before
  fun setUp() {
    mockService = mock()
    mockSuggestionService = mock()
    mockFeatureFlagService = mock()
    runBlocking {
      whenever(mockFeatureFlagService.isEnabledForUsername(ConfigRegistry.FOLLOW_FEATURE_ENABLED, "jdoe"))
          .thenReturn(true)
    }
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
      routing {
        profileSubscriptionRoutes(mockService, mockFeatureFlagService, mockSuggestionService)
      }
    }
    client = createClient {
      install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
        gson { setPrettyPrinting() }
      }
    }
  }

  @Test
  fun `post follow should follow as the acting X-Username`() = testApplication {
    configureApp()

    val response =
        client.post("/members/jane/follow") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          header("X-Username", "jdoe")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    verify(mockService).follow("jdoe", "jane")
    Unit
  }

  @Test
  fun `post follow should reject self-follow`() = testApplication {
    configureApp()
    runBlocking {
      whenever(mockService.follow("jdoe", "jdoe"))
          .thenThrow(IllegalArgumentException("Cannot follow yourself"))
    }

    val response =
        client.post("/members/jdoe/follow") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          header("X-Username", "jdoe")
        }

    assertEquals(HttpStatusCode.BadRequest, response.status)
    val apiResponse = response.body<ApiResponse<String?>>()
    assertEquals("Cannot follow yourself", apiResponse.message)
    Unit
  }

  @Test
  fun `post follow should require X-Username header`() = testApplication {
    configureApp()

    val response =
        client.post("/members/jane/follow") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.BadRequest, response.status)
    Unit
  }

  @Test
  fun `post follow should reject when the follow feature is disabled for the actor`() =
      testApplication {
        configureApp()
        runBlocking {
          whenever(
                  mockFeatureFlagService.isEnabledForUsername(
                      ConfigRegistry.FOLLOW_FEATURE_ENABLED, "jdoe"))
              .thenReturn(false)
        }

        val response =
            client.post("/members/jane/follow") {
              header("X-Api-Key", "test-key")
              header("X-Api-Client", "test-client")
              header("X-Username", "jdoe")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        Unit
      }

  @Test
  fun `delete follow should reject when the follow feature is disabled for the actor`() =
      testApplication {
        configureApp()
        runBlocking {
          whenever(
                  mockFeatureFlagService.isEnabledForUsername(
                      ConfigRegistry.FOLLOW_FEATURE_ENABLED, "jdoe"))
              .thenReturn(false)
        }

        val response =
            client.delete("/members/jane/follow") {
              header("X-Api-Key", "test-key")
              header("X-Api-Client", "test-client")
              header("X-Username", "jdoe")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        Unit
      }

  @Test
  fun `post follow should require authentication`() = testApplication {
    configureApp()

    val response = client.post("/members/jane/follow") { header("X-Username", "jdoe") }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    Unit
  }

  @Test
  fun `delete follow should unfollow as the acting X-Username`() = testApplication {
    configureApp()

    val response =
        client.delete("/members/jane/follow") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
          header("X-Username", "jdoe")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    verify(mockService).unfollow("jdoe", "jane")
    Unit
  }

  @Test
  fun `get following should return the usernames the member follows`() = testApplication {
    configureApp()
    runBlocking { whenever(mockService.getFollowing("jdoe")).thenReturn(listOf("jane", "bob")) }

    val response =
        client.get("/members/jdoe/following") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    val apiResponse = response.body<ApiResponse<List<String>>>()
    assertEquals(listOf("jane", "bob"), apiResponse.data)
    Unit
  }

  @Test
  fun `get follow-suggestions should return suggestions when the feature is enabled`() =
      testApplication {
        configureApp()
        runBlocking {
          whenever(mockSuggestionService.suggest("jdoe", 6)).thenReturn(listOf("asmith", "bkim"))
        }

        val response =
            client.get("/members/jdoe/follow-suggestions") {
              header("X-Api-Key", "test-key")
              header("X-Api-Client", "test-client")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val apiResponse = response.body<ApiResponse<List<String>>>()
        assertEquals(listOf("asmith", "bkim"), apiResponse.data)
        Unit
      }

  @Test
  fun `get follow-suggestions should be empty when the feature is disabled`() = testApplication {
    configureApp()
    runBlocking {
      whenever(
              mockFeatureFlagService.isEnabledForUsername(
                  ConfigRegistry.FOLLOW_FEATURE_ENABLED, "jdoe"))
          .thenReturn(false)
    }

    val response =
        client.get("/members/jdoe/follow-suggestions") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    val apiResponse = response.body<ApiResponse<List<String>>>()
    assertEquals(emptyList<String>(), apiResponse.data)
    Unit
  }

  @Test
  fun `get follow-suggestions debug computes and reports the flag even when disabled`() =
      testApplication {
        configureApp()
        runBlocking {
          whenever(
                  mockFeatureFlagService.isEnabledForUsername(
                      ConfigRegistry.FOLLOW_FEATURE_ENABLED, "jdoe"))
              .thenReturn(false)
          whenever(mockSuggestionService.explain("jdoe", 6))
              .thenReturn(
                  FollowSuggestionResult(
                      listOf("asmith"),
                      FollowSuggestionDebug(
                          date = "2026-10-16",
                          cached = false,
                          classmateCount = 3,
                          tierOne = listOf("asmith"),
                          tierTwo = emptyList(),
                          candidates = listOf("asmith"),
                          following = 0)))
        }

        val response =
            client.get("/members/jdoe/follow-suggestions/debug") {
              header("X-Api-Key", "test-key")
              header("X-Api-Client", "test-client")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val data = response.body<ApiResponse<Map<String, Any?>>>().data!!
        assertEquals(listOf("asmith"), data["suggestions"])
        assertEquals(false, data["flagEnabled"])
        Unit
      }

  @Test
  fun `get followers should return the usernames following the member`() = testApplication {
    configureApp()
    runBlocking { whenever(mockService.getFollowers("jdoe")).thenReturn(listOf("alice")) }

    val response =
        client.get("/members/jdoe/followers") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    val apiResponse = response.body<ApiResponse<List<String>>>()
    assertEquals(listOf("alice"), apiResponse.data)
    Unit
  }
}
