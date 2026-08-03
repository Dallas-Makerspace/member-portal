package org.dallasmakerspace.plugins

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get as httpGet
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlinx.datetime.Instant
import org.dallasmakerspace.models.DMSMember
import org.junit.Test

/**
 * Exercises the real [configureSerialization] plugin end-to-end (route tests elsewhere install
 * their own isolated ContentNegotiation, which doesn't catch bugs specific to this app's actual
 * config). Regression coverage for a bug where any response type that merely *references*
 * kotlinx.datetime.Instant/LocalDate/LocalDateTime anywhere in its object graph (e.g. DMSMember,
 * via memberSince, or transitively via AccountInfo/DMSGroup/GroupHistory) 500'd - Gson builds
 * adapters for a class's entire field graph eagerly, even for fields that are null at runtime, and
 * its default reflective adapter can't access the private `java.time.*` fields these types wrap.
 */
class SerializationTest {

  @Test
  fun `responding with a DMSMember (which references kotlinx-datetime types transitively) should not throw`() =
      testApplication {
        application {
          configureSerialization()
          routing {
            get("/member") {
              call.respond(
                  DMSMember(
                      id = 1,
                      username = "jdoe",
                      memberSince = Instant.parse("2022-09-10T16:36:20Z")))
            }
          }
        }
        client = createClient { install(ClientContentNegotiation) { gson() } }

        val response = client.httpGet("/member")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<Map<String, Any?>>()
        assertEquals("2022-09-10T16:36:20Z", body["memberSince"])
      }

  @Test
  fun `responding with an ApiResponse wrapping a generic list should not throw`() =
      testApplication {
        application {
          configureSerialization()
          routing {
            get("/list") { call.respond(ApiResponse(Status.SUCCESS, "ok", listOf("a", "b"))) }
          }
        }
        client = createClient { install(ClientContentNegotiation) { gson() } }

        val response = client.httpGet("/list")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ApiResponse<List<String>>>()
        assertEquals(listOf("a", "b"), body.data)
      }
}
