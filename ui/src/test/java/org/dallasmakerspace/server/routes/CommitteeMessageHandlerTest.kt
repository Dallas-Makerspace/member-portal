package org.dallasmakerspace.server.routes

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.RoutingContext
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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.CommitteeMessageCallResult
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.plugins.UserSession
import org.dallasmakerspace.testing.plugins.configureContentNegotiation
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** The chair-message POST: the acting member comes from the session, never from the body. */
class CommitteeMessageHandlerTest {

  private val laser = Committees.findByName("Laser")!!
  private val memberServiceClient = mock<MemberServiceClient>()

  private fun stubSend(result: CommitteeMessageCallResult) {
    runBlocking {
      whenever(memberServiceClient.sendCommitteeMessage(any(), any(), any(), any())).thenReturn(result)
    }
  }

  private fun withApi(block: suspend ApplicationTestBuilder.() -> Unit) {
    val userInfoProvider = mock<UserInfoProvider>()
    runBlocking {
      whenever(userInfoProvider.getUserInfo("tok-mia"))
          .thenReturn(mapOf("preferred_username" to "mia", "groups" to emptyList<String>()))
    }
    val handler = CommitteeMessageHandler(LoggerFactory(), userInfoProvider, memberServiceClient)
    testApplication {
      application {
        configureContentNegotiation()
        install(Sessions) { cookie<UserSession>("user_session") }
        routing { post("/committees/{committee_slug}/message") { asUser(handler) } }
      }
      block()
    }
  }

  private suspend fun RoutingContext.asUser(handler: IRouteHandler) {
    call.sessions.set(UserSession(accessToken = "tok-mia", sessionId = "sess-1", userId = "mia"))
    handler.handleBase(call)
  }

  private suspend fun ApplicationTestBuilder.send(
      body: String,
      slug: String = laser.slug,
      origin: String? = "http://localhost",
  ): HttpResponse =
      client.post("/committees/$slug/message") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Host, "localhost")
        if (origin != null) header(HttpHeaders.Origin, origin)
        setBody(body)
      }

  @Test
  fun `forwards only the message fields for the session user`() {
    stubSend(CommitteeMessageCallResult(200, "Message sent", mapOf("shortUrl" to "https://s.example.test/a")))
    withApi {
      val response =
          send(
              """{"message":"Meeting moved","talkUrl":"https://talk.example.test/t/x/1","createThread":false,"username":"mallory","committeeId":1}""")

      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue("https://s.example.test/a" in response.bodyAsText())
      val body = argumentCaptor<Map<String, Any?>>()
      verify(memberServiceClient).sendCommitteeMessage(eq(laser.id), eq("mia"), body.capture(), eq("sess-1"))
      assertEquals(
          mapOf<String, Any?>(
              "message" to "Meeting moved",
              "talkUrl" to "https://talk.example.test/t/x/1",
              "createThread" to false),
          body.firstValue)
    }
  }

  @Test
  fun `a request without an origin or from another site is refused`() {
    stubSend(CommitteeMessageCallResult(200, "Message sent", null))
    withApi {
      assertEquals(HttpStatusCode.Forbidden, send("""{"message":"x"}""", origin = null).status)
      assertEquals(
          HttpStatusCode.Forbidden,
          send("""{"message":"x"}""", origin = "https://evil.example.test").status)
    }
    runBlocking { verify(memberServiceClient, never()).sendCommitteeMessage(any(), any(), any(), any()) }
  }

  @Test
  fun `service errors keep their status and text`() {
    stubSend(
        CommitteeMessageCallResult(
            429, "Laser has reached its limit", mapOf("error" to "RATE_LIMITED", "nextAllowedAt" to "2026-03-11T09:30:00Z")))
    withApi {
      val response = send("""{"message":"Meeting moved","createThread":true}""")

      assertEquals(HttpStatusCode.TooManyRequests, response.status)
      val text = response.bodyAsText()
      assertTrue("Laser has reached its limit" in text, text)
      assertTrue("2026-03-11T09:30:00Z" in text, text)
    }
  }

  @Test
  fun `a body that is not JSON is a bad request`() {
    withApi { assertEquals(HttpStatusCode.BadRequest, send("not json").status) }
    runBlocking { verify(memberServiceClient, never()).sendCommitteeMessage(any(), any(), any(), any()) }
  }

  @Test
  fun `an unknown committee is a 404`() {
    withApi { assertEquals(HttpStatusCode.NotFound, send("""{"message":"x"}""", slug = "nope").status) }
  }
}
