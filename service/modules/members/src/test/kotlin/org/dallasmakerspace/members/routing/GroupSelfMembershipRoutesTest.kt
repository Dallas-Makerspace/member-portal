package org.dallasmakerspace.members.routing

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.apiKey
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.members.GroupSettingsRegistry
import org.dallasmakerspace.members.GroupSettingsService
import org.dallasmakerspace.members.MemberService
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GroupSelfMembershipRoutesTest {

  private lateinit var mockGroupSettingsService: GroupSettingsService
  private lateinit var mockMemberService: MemberService

  @Before
  fun setUp() {
    mockGroupSettingsService = mock()
    mockMemberService = mock()
  }

  private fun allowSelfService(allowed: Boolean) = runBlocking {
    whenever(
            mockGroupSettingsService.isEnabled(
                "Test Group", GroupSettingsRegistry.ALLOW_SELF_ADD_REMOVE))
        .thenReturn(allowed)
  }

  private fun directMember(isMember: Boolean) = runBlocking {
    whenever(mockMemberService.isDirectMemberOfGroup("jdoe", "Test+Group")).thenReturn(isMember)
  }

  private fun appConfig(): AppConfig {
    val mockAppConfig = mock<AppConfig>()
    whenever(mockAppConfig.requireStringProperty("app.api.fallback.name")).thenReturn("test-client")
    whenever(mockAppConfig.requireStringProperty("app.api.fallback.key")).thenReturn("test-key")
    whenever(mockAppConfig.requireStringProperty("app.api.fallback.role")).thenReturn("OFFICER")
    return mockAppConfig
  }

  private fun ApplicationTestBuilder.configureApp() {
    application {
      install(ContentNegotiation) { gson { setPrettyPrinting() } }
      install(Authentication) {
        apiKey(appConfig(), ApiKeyAuthProvider.X_API_KEY) { validate { null } }
      }
      routing { groupSelfMembershipRoutes(mockGroupSettingsService, mockMemberService) }
    }
  }

  private suspend fun ApplicationTestBuilder.send(
      method: String,
      actor: String? = "jdoe",
  ): HttpResponse {
    val url = "/groups/Test+Group/self"
    val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
      header("X-Api-Key", "test-key")
      header("X-Api-Client", "test-client")
      actor?.let { header("X-Actor-Username", it) }
    }
    return when (method) {
      "GET" -> client.get(url, build)
      "PUT" -> client.put(url, build)
      else -> client.delete(url, build)
    }
  }

  @Test
  fun `join adds only the actor when allowed`() = testApplication {
    configureApp()
    allowSelfService(true)
    directMember(false)

    val response = send("PUT")

    assertEquals(HttpStatusCode.OK, response.status)
    verify(mockMemberService).addMembersToGroup(listOf("jdoe"), "Test+Group", "jdoe")
    Unit
  }

  @Test
  fun `leave removes only the actor when allowed`() = testApplication {
    configureApp()
    allowSelfService(true)
    directMember(true)

    val response = send("DELETE")

    assertEquals(HttpStatusCode.OK, response.status)
    verify(mockMemberService).removeMembersToGroup(listOf("jdoe"), "Test+Group", "jdoe")
    Unit
  }

  @Test
  fun `join is forbidden when the setting is off`() = testApplication {
    configureApp()
    allowSelfService(false)

    val response = send("PUT")

    assertEquals(HttpStatusCode.Forbidden, response.status)
    verify(mockMemberService, never()).addMembersToGroup(any(), any(), anyOrNull())
    Unit
  }

  @Test
  fun `leave is forbidden when the setting is off`() = testApplication {
    configureApp()
    allowSelfService(false)

    val response = send("DELETE")

    assertEquals(HttpStatusCode.Forbidden, response.status)
    verify(mockMemberService, never()).removeMembersToGroup(any(), any(), anyOrNull())
    Unit
  }

  @Test
  fun `join conflicts when already a direct member`() = testApplication {
    configureApp()
    allowSelfService(true)
    directMember(true)

    val response = send("PUT")

    assertEquals(HttpStatusCode.Conflict, response.status)
    verify(mockMemberService, never()).addMembersToGroup(any(), any(), anyOrNull())
    Unit
  }

  @Test
  fun `leave conflicts when not a direct member`() = testApplication {
    configureApp()
    allowSelfService(true)
    directMember(false)

    val response = send("DELETE")

    assertEquals(HttpStatusCode.Conflict, response.status)
    verify(mockMemberService, never()).removeMembersToGroup(any(), any(), anyOrNull())
    Unit
  }

  @Test
  fun `missing actor is a bad request`() = testApplication {
    configureApp()
    allowSelfService(true)

    val response = send("PUT", actor = null)

    assertEquals(HttpStatusCode.BadRequest, response.status)
    verify(mockMemberService, never()).addMembersToGroup(any(), any(), anyOrNull())
    Unit
  }

  @Test
  fun `status skips the AD lookup when the setting is off`() = testApplication {
    configureApp()
    allowSelfService(false)

    val response = send("GET")

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsText().contains("\"allowed\": false"))
    verify(mockMemberService, never()).isDirectMemberOfGroup(any(), any())
    Unit
  }

  @Test
  fun `status reports membership when allowed`() = testApplication {
    configureApp()
    allowSelfService(true)
    directMember(true)

    val body = send("GET").bodyAsText()

    assertTrue(body.contains("\"allowed\": true"))
    assertTrue(body.contains("\"isMember\": true"))
  }

  @Test
  fun `status hides self-service when AD lookup fails`() = testApplication {
    configureApp()
    allowSelfService(true)
    runBlocking {
      whenever(mockMemberService.isDirectMemberOfGroup("jdoe", "Test+Group"))
          .thenThrow(RuntimeException("AD down"))
    }

    val body = send("GET").bodyAsText()

    assertTrue(body.contains("\"allowed\": false"))
  }
}
