package org.dallasmakerspace.config.routing

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import org.dallasmakerspace.plugins.ApiResponse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class FeatureFlagRoutesTest {

  private lateinit var mockFeatureFlagService: FeatureFlagService

  @Before
  fun setUp() {
    mockFeatureFlagService = mock()
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
      routing { featureFlagRoutes(mockFeatureFlagService) }
    }
    client = createClient {
      install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
        gson { setPrettyPrinting() }
      }
    }
  }

  @Test
  fun `returns enabled true when the flag is on for the user`() = testApplication {
    configureApp()
    runBlocking {
      whenever(
              mockFeatureFlagService.isEnabledForUsername(
                  ConfigRegistry.FOLLOW_FEATURE_ENABLED, "jdoe"))
          .thenReturn(true)
    }

    val response =
        client.get("/feature-flags/${ConfigRegistry.FOLLOW_FEATURE_ENABLED.key}?username=jdoe") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    val apiResponse = response.body<ApiResponse<FeatureFlagStatus>>()
    assertEquals(true, apiResponse.data?.enabled)
    Unit
  }

  @Test
  fun `returns enabled false when the flag is off for the user`() = testApplication {
    configureApp()
    runBlocking {
      whenever(
              mockFeatureFlagService.isEnabledForUsername(
                  ConfigRegistry.FOLLOW_FEATURE_ENABLED, "jdoe"))
          .thenReturn(false)
    }

    val response =
        client.get("/feature-flags/${ConfigRegistry.FOLLOW_FEATURE_ENABLED.key}?username=jdoe") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    val apiResponse = response.body<ApiResponse<FeatureFlagStatus>>()
    assertEquals(false, apiResponse.data?.enabled)
    Unit
  }

  @Test
  fun `returns 404 for an unknown key`() = testApplication {
    configureApp()

    val response =
        client.get("/feature-flags/not-a-real-key?username=jdoe") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.NotFound, response.status)
    Unit
  }

  @Test
  fun `returns 400 when username is missing`() = testApplication {
    configureApp()

    val response =
        client.get("/feature-flags/${ConfigRegistry.FOLLOW_FEATURE_ENABLED.key}") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.BadRequest, response.status)
    Unit
  }

  @Test
  fun `requires authentication`() = testApplication {
    configureApp()

    val response =
        client.get("/feature-flags/${ConfigRegistry.FOLLOW_FEATURE_ENABLED.key}?username=jdoe")

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    Unit
  }
}
