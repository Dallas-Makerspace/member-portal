package org.dallasmakerspace.bambuddy.routing

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.gson.gson
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.bambuddy.notifications.BambuWatchDTO
import org.dallasmakerspace.bambuddy.notifications.BambuWatchException
import org.dallasmakerspace.bambuddy.notifications.EphemeralNotificationService
import org.dallasmakerspace.config.FeatureFlagService
import org.dallasmakerspace.core.api.ApiResponse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * QA F7 (service half): the watch routes' gate and error mapping — per-user flag → 403,
 * missing X-Username → 400, malformed body → 400, BambuWatchException status passthrough,
 * and DELETE of an unknown / non-owned watch → 404 with no existence leak.
 */
class BambuWatchRoutesTest {

  private lateinit var watchService: EphemeralNotificationService
  private lateinit var featureFlagService: FeatureFlagService

  @Before
  fun setUp() {
    watchService = mock()
    featureFlagService = mock()
    runBlocking {
      whenever(featureFlagService.isEnabledForUsername(any(), eq("jdoe"))).thenReturn(true)
      whenever(featureFlagService.isEnabledForUsername(any(), eq("pilotless"))).thenReturn(false)
    }
  }

  private fun ApplicationTestBuilder.configureApp() {
    application {
      install(ContentNegotiation) { gson { setPrettyPrinting() } }
      routing { bambuWatchRoutes(watchService, featureFlagService) }
    }
    client = createClient { install(ClientContentNegotiation) { gson { setPrettyPrinting() } } }
  }

  private fun dto(id: Long = 5, status: String = "ARMED") =
      BambuWatchDTO(
          id = id,
          scope = "OWN_JOB",
          printerId = 1,
          printerName = "Printer 1",
          queueId = 10,
          jobName = "Job.3mf",
          status = status,
          createdAt = "2026-10-22T10:00:00",
          expiresAt = "2026-10-22T12:00:00",
          rearmCount = 0)

  @Test
  fun `missing X-Username header is 400`() = testApplication {
    configureApp()
    val response = client.get("/bambu/watches")
    assertEquals(HttpStatusCode.BadRequest, response.status)
    Unit
  }

  @Test
  fun `flag off for the actor is 403`() = testApplication {
    configureApp()
    val response =
        client.post("/bambu/watches") {
          header("X-Username", "pilotless")
          contentType(ContentType.Application.Json)
          setBody("""{"scope":"OWN_JOB","printerId":1,"queueId":10}""")
        }
    assertEquals(HttpStatusCode.Forbidden, response.status)
    Unit
  }

  @Test
  fun `invalid body is 400`() = testApplication {
    configureApp()
    val response =
        client.post("/bambu/watches") {
          header("X-Username", "jdoe")
          contentType(ContentType.Application.Json)
          setBody("""{"scope":""")
        }
    assertEquals(HttpStatusCode.BadRequest, response.status)
    Unit
  }

  @Test
  fun `arm success returns the watch DTO`() = testApplication {
    configureApp()
    runBlocking { whenever(watchService.arm(eq("jdoe"), any())).thenReturn(dto()) }
    val response =
        client.post("/bambu/watches") {
          header("X-Username", "jdoe")
          contentType(ContentType.Application.Json)
          setBody("""{"scope":"OWN_JOB","printerId":1,"queueId":10}""")
        }
    assertEquals(HttpStatusCode.OK, response.status)
    val apiResponse = response.body<ApiResponse<BambuWatchDTO>>()
    assertEquals(5L, apiResponse.data?.id)
    Unit
  }

  @Test
  fun `BambuWatchException statuses map through - 403 404 409 422`() = testApplication {
    configureApp()
    for ((code, message) in
        listOf(
            403 to "You can only watch your own prints",
            404 to "Printer not found",
            409 to "That print has already finished",
            422 to "We can't estimate how long this print will run yet")) {
      runBlocking {
        whenever(watchService.arm(eq("jdoe"), any())).thenAnswer { throw BambuWatchException(code, message) }
      }
      val response =
          client.post("/bambu/watches") {
            header("X-Username", "jdoe")
            contentType(ContentType.Application.Json)
            setBody("""{"scope":"OWN_JOB","printerId":1,"queueId":10}""")
          }
      assertEquals(HttpStatusCode.fromValue(code), response.status, "code=$code")
      val apiResponse = response.body<ApiResponse<String?>>()
      assertEquals(message, apiResponse.message)
    }
    Unit
  }

  @Test
  fun `delete of an unknown or non-owned watch is 404`() = testApplication {
    configureApp()
    // cancel() returns null for both unknown ids and other members' watches by design.
    runBlocking { whenever(watchService.cancel(eq("jdoe"), eq(99L))).thenReturn(null) }
    val response = client.delete("/bambu/watches/99") { header("X-Username", "jdoe") }
    assertEquals(HttpStatusCode.NotFound, response.status)
    val apiResponse = response.body<ApiResponse<String?>>()
    assertEquals("Watch not found", apiResponse.message)
    Unit
  }

  @Test
  fun `delete of an owned watch returns its status`() = testApplication {
    configureApp()
    runBlocking {
      whenever(watchService.cancel(eq("jdoe"), eq(5L))).thenReturn(dto(status = "CANCELLED"))
    }
    val response = client.delete("/bambu/watches/5") { header("X-Username", "jdoe") }
    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.body<ApiResponse<BambuWatchDTO>>().data?.status == "CANCELLED")
    Unit
  }

  @Test
  fun `list returns only the actor's armed watches`() = testApplication {
    configureApp()
    runBlocking { whenever(watchService.listArmed(eq("jdoe"))).thenReturn(listOf(dto())) }
    val response = client.get("/bambu/watches") { header("X-Username", "jdoe") }
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(1, response.body<ApiResponse<List<BambuWatchDTO>>>().data?.size)
    Unit
  }
}
