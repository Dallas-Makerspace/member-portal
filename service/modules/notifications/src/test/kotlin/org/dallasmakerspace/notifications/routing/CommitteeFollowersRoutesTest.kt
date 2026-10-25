package org.dallasmakerspace.notifications.routing

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
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.notifications.CommitteeFollowersPage
import org.dallasmakerspace.notifications.CommitteeFollowersService
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CommitteeFollowersRoutesTest {

  private lateinit var mockService: CommitteeFollowersService

  @Before
  fun setUp() {
    mockService = mock()
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
      routing { committeeFollowersRoutes(mockService) }
    }
    client = createClient {
      install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
        gson { setPrettyPrinting() }
      }
    }
  }

  @Test
  fun `get followers returns the requested page`() = testApplication {
    configureApp()
    runBlocking {
      whenever(mockService.getPage(9, 2, 1))
          .thenReturn(CommitteeFollowersPage(5, listOf("asmith", "bsmith")))
    }

    val response =
        client.get("/committees/9/followers?limit=2&offset=1") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    val page = response.body<ApiResponse<CommitteeFollowersPage>>().data
    assertEquals(5, page?.total)
    assertEquals(listOf("asmith", "bsmith"), page?.usernames)
    Unit
  }

  @Test
  fun `get followers caps the limit`() = testApplication {
    configureApp()
    runBlocking {
      whenever(mockService.getPage(9, 100, 0)).thenReturn(CommitteeFollowersPage(0, emptyList()))
    }

    val response =
        client.get("/committees/9/followers?limit=5000") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.OK, response.status)
    Unit
  }

  @Test
  fun `get followers of an unknown committee is 404`() = testApplication {
    configureApp()

    val response =
        client.get("/committees/9999/followers") {
          header("X-Api-Key", "test-key")
          header("X-Api-Client", "test-client")
        }

    assertEquals(HttpStatusCode.NotFound, response.status)
    Unit
  }
}
