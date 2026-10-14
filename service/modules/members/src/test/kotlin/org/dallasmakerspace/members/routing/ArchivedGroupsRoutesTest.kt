package org.dallasmakerspace.members.routing

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get as serverGet
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.auth.ApiKeyAuthProvider
import org.dallasmakerspace.auth.apiKey
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.members.IGroupArchiveRepository
import org.dallasmakerspace.models.ArchivedGroup
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ArchivedGroupsRoutesTest {
  private val repository: IGroupArchiveRepository = mock()

  private val shop =
      ArchivedGroup(
          id = 7,
          name = "Shop Saws",
          archivedAt = "2026-10-13T15:30:00Z",
          archivedBy = "asmith",
          reason = "Group retired after the merge",
          runId = 42L,
          artifactStepIndex = 0,
          artifactName = "member-list")

  private fun appConfig(role: String): AppConfig {
    val config = mock<AppConfig>()
    whenever(config.requireStringProperty("app.api.fallback.name")).thenReturn("test-client")
    whenever(config.requireStringProperty("app.api.fallback.key")).thenReturn("test-key")
    whenever(config.requireStringProperty("app.api.fallback.role")).thenReturn(role)
    return config
  }

  private fun ApplicationTestBuilder.configureApp(role: String = "OFFICER") {
    application {
      install(ContentNegotiation) { gson { setPrettyPrinting() } }
      install(Authentication) {
        apiKey(appConfig(role), ApiKeyAuthProvider.X_API_KEY) { validate { null } }
      }
      routing {
        serverGet("/groups/{groupslug}") { call.respondText("single group") }
        archivedGroupsRoutes(repository)
      }
    }
  }

  private suspend fun ApplicationTestBuilder.fetch(query: String = ""): HttpResponse =
      client.get("/groups/archived$query") {
        header("X-Api-Key", "test-key")
        header("X-Api-Client", "test-client")
      }

  @Test
  fun `returns the archived groups and wins over the group slug route`() = testApplication {
    configureApp()
    runBlocking { whenever(repository.listArchived(any())).thenReturn(listOf(shop)) }

    val response = fetch()

    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("\"name\": \"Shop Saws\""))
    assertTrue(body.contains("\"archivedAt\": \"2026-10-13T15:30:00Z\""))
    assertTrue(body.contains("\"artifactName\": \"member-list\""))
    assertTrue(body.contains("\"status\": \"SUCCESS\""))
  }

  @Test
  fun `passes every repeated prefix to the repository`() = testApplication {
    configureApp()
    runBlocking { whenever(repository.listArchived(any())).thenReturn(emptyList()) }

    val response = fetch("?prefix=Shop&prefix=lab")

    assertEquals(HttpStatusCode.OK, response.status)
    runBlocking { verify(repository).listArchived(listOf("Shop", "lab")) }
  }

  @Test
  fun `no prefix asks for all archived groups`() = testApplication {
    configureApp()
    runBlocking { whenever(repository.listArchived(any())).thenReturn(emptyList()) }

    fetch()

    runBlocking { verify(repository).listArchived(emptyList()) }
  }

  @Test
  fun `a client without the manage groups permission is forbidden`() = testApplication {
    configureApp(role = "MEMBER_READER")

    val response = fetch()

    assertEquals(HttpStatusCode.Forbidden, response.status)
  }

  @Test
  fun `a request without credentials is unauthorized`() = testApplication {
    configureApp()

    val response = client.get("/groups/archived")

    assertEquals(HttpStatusCode.Unauthorized, response.status)
  }
}
