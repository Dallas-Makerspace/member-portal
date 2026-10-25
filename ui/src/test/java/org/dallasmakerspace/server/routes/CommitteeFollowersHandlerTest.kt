package org.dallasmakerspace.server.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.testApplication
import io.ktor.server.thymeleaf.Thymeleaf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.SubscriptionCopy
import org.dallasmakerspace.models.SubscriptionStatus
import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.CommitteeFollowers
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.plugins.UserSession
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

/** Renders the real committee followers page. */
class CommitteeFollowersHandlerTest {

  private val laser = Committees.findByName("Laser")!!

  private val status =
      SubscriptionStatus(
          topic = TopicKeys.COMMITTEE_UPDATES,
          args = mapOf("committee" to laser.id.toString()),
          subscribed = false,
          copy =
              SubscriptionCopy(
                  subscribeLabel = "Follow",
                  subscribedLabel = "Following",
                  unsubscribeLabel = "Unfollow",
                  subscribeIcon = "notifications",
                  subscribedIcon = "notifications_active",
                  unsubscribeIcon = "notifications_off",
                  subscribeBusy = "Following…",
                  unsubscribeBusy = "Unfollowing…",
                  subscribedToast = "You're following Laser.",
                  unsubscribedToast = "You unfollowed Laser.",
                  hint = "Get notified when this committee adds a class",
                  subscribedHint = "You'll be notified when this committee adds a class"))

  private fun service(subscription: SubscriptionStatus?, followers: CommitteeFollowers): MemberService {
    val service = mock<MemberService>()
    runBlocking {
      whenever(service.getSubscriptionStatus(any(), any(), any(), anyOrNull()))
          .thenReturn(subscription)
      whenever(service.getCommitteeFollowers(any(), any(), any(), anyOrNull()))
          .thenReturn(followers)
    }
    return service
  }

  private fun page(
      service: MemberService,
      path: String,
      block: suspend (HttpResponse) -> Unit,
  ) {
    testApplication {
      application {
        install(Sessions) { cookie<UserSession>("user_session") }
        install(Thymeleaf) {
          setTemplateResolver(
              ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".html"
                characterEncoding = "utf-8"
              })
        }
        routing {
          val handler = CommitteeFollowersHandler(LoggerFactory(), userInfoProvider(), service)
          get("/committees/{committee_slug}/followers") { asUser(handler) }
        }
      }
      block(client.get(path) { header("X-Test-User", "mia") })
    }
  }

  private fun userInfoProvider(): UserInfoProvider {
    val provider = mock<UserInfoProvider>()
    runBlocking {
      whenever(provider.getUserInfo("tok-mia"))
          .thenReturn(mapOf("preferred_username" to "mia", "groups" to listOf<String>()))
    }
    return provider
  }

  private suspend fun RoutingContext.asUser(handler: IRouteHandler) {
    call.sessions.set(UserSession(accessToken = "tok-mia", sessionId = "sess", userId = "mia"))
    handler.handleBase(call)
  }

  @Test
  fun `lists the followers of a followable committee`() {
    val service = service(status, CommitteeFollowers(2, listOf("jdoe", "asmith")))
    page(service, "/committees/${laser.slug}/followers") { response ->
      assertEquals(HttpStatusCode.OK, response.status)
      val html = response.bodyAsText()
      assertTrue("jdoe" in html, html)
      assertTrue("asmith" in html, html)
      assertFalse("Page 1 of" in html, "a single page shows no pager")
    }
    runBlocking { verify(service).getCommitteeFollowers(eq(laser.id), eq(48), eq(0), anyOrNull()) }
  }

  @Test
  fun `a later page asks for the matching offset and shows the pager`() {
    val service = service(status, CommitteeFollowers(100, listOf("jdoe")))
    page(service, "/committees/${laser.slug}/followers?page=2") { response ->
      assertEquals(HttpStatusCode.OK, response.status)
      val html = response.bodyAsText().replace(Regex("\\s+"), " ")
      assertTrue("Page 2 of 3" in html, html)
    }
    runBlocking { verify(service).getCommitteeFollowers(eq(laser.id), eq(48), eq(48), anyOrNull()) }
  }

  @Test
  fun `404 when the follow feature is off for the viewer`() {
    val service = service(null, CommitteeFollowers(1, listOf("jdoe")))
    page(service, "/committees/${laser.slug}/followers") { response ->
      assertEquals(HttpStatusCode.NotFound, response.status)
    }
    runBlocking { verify(service, never()).getCommitteeFollowers(any(), any(), any(), anyOrNull()) }
  }

  @Test
  fun `404 for a committee that cannot be followed`() {
    val vector = Committees.findByName("VECTOR")!!
    page(service(status, CommitteeFollowers(0, emptyList())), "/committees/${vector.slug}/followers") {
        response ->
      assertEquals(HttpStatusCode.NotFound, response.status)
    }
  }
}
