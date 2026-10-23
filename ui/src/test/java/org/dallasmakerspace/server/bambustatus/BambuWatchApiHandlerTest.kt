package org.dallasmakerspace.server.bambustatus

import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.BambuWatchCallResult
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.plugins.UserSession
import org.dallasmakerspace.server.routes.IRouteHandler
import org.dallasmakerspace.testing.plugins.configureContentNegotiation
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * QA F7 (UI half): the watch proxy derives the username from the session (never the body),
 * gates on BOTH Bambu feature flags, and rebuilds the forwarded body from only
 * scope/printerId/queueId.
 */
class BambuWatchApiHandlerTest {

  private val memberServiceClient = mock<MemberServiceClient>()

  private fun stubFlags(status: Boolean, notifications: Boolean) {
    runBlocking {
      whenever(memberServiceClient.isFeatureEnabled(eq("bambu-status.enabled"), any(), any()))
          .thenReturn(status)
      whenever(
              memberServiceClient.isFeatureEnabled(eq("bambu-notifications.enabled"), any(), any()))
          .thenReturn(notifications)
      whenever(memberServiceClient.createBambuWatch(any(), any(), any()))
          .thenReturn(BambuWatchCallResult(200, "Watch armed", mapOf("id" to 5)))
      whenever(memberServiceClient.deleteBambuWatch(any(), any(), any()))
          .thenReturn(BambuWatchCallResult(200, "Watch cancelled", mapOf("id" to 5)))
    }
  }

  private fun withApi(block: suspend ApplicationTestBuilder.() -> Unit) {
    val userInfoProvider = mock<UserInfoProvider>()
    runBlocking {
      whenever(userInfoProvider.getUserInfo("tok-jdoe"))
          .thenReturn(mapOf("preferred_username" to "jdoe", "groups" to emptyList<String>()))
    }
    val handler = BambuWatchApiHandler(LoggerFactory(), userInfoProvider, memberServiceClient)
    testApplication {
      application {
        configureContentNegotiation()
        install(Sessions) { cookie<UserSession>("user_session") }
        routing {
          post("/bambu-api/watches") { asUser(handler) }
          delete("/bambu-api/watches/{id}") { asUser(handler) }
        }
      }
      block()
    }
  }

  private suspend fun RoutingContext.asUser(handler: IRouteHandler) {
    call.sessions.set(UserSession(accessToken = "tok-jdoe", sessionId = "sess-1", userId = "jdoe"))
    handler.handleBase(call)
  }

  private suspend fun ApplicationTestBuilder.postWatch(body: String): HttpResponse =
      client.post("/bambu-api/watches") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(body)
      }

  @Test
  fun `username comes from the session and the forwarded body is rebuilt from known fields only`() {
    stubFlags(status = true, notifications = true)
    withApi {
      val response =
          postWatch(
              """{"scope":"OWN_JOB","printerId":1,"queueId":10,"username":"mallory","admin":true}""")
      assertEquals(HttpStatusCode.OK, response.status)

      val bodyCaptor = argumentCaptor<Map<String, Any?>>()
      verify(memberServiceClient)
          .createBambuWatch(eq("sess-1"), eq("jdoe"), bodyCaptor.capture())
      assertEquals(
          mapOf<String, Any?>("scope" to "OWN_JOB", "printerId" to 1, "queueId" to 10),
          bodyCaptor.firstValue)
    }
  }

  @Test
  fun `either flag off hides the endpoint with 404 and forwards nothing`() {
    stubFlags(status = true, notifications = false)
    withApi {
      assertEquals(HttpStatusCode.NotFound, postWatch("""{"scope":"OWN_JOB","printerId":1}""").status)
    }
    stubFlags(status = false, notifications = true)
    withApi {
      assertEquals(HttpStatusCode.NotFound, postWatch("""{"scope":"OWN_JOB","printerId":1}""").status)
    }
    runBlocking {
      verify(memberServiceClient, never()).createBambuWatch(any(), any(), any())
    }
  }

  @Test
  fun `delete forwards the session username and the path id`() {
    stubFlags(status = true, notifications = true)
    withApi {
      val response = client.delete("/bambu-api/watches/42")
      assertEquals(HttpStatusCode.OK, response.status)
      verify(memberServiceClient).deleteBambuWatch(eq("sess-1"), eq("jdoe"), eq("42"))
    }
  }
}
