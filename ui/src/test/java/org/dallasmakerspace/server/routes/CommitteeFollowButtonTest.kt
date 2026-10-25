package org.dallasmakerspace.server.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.thymeleaf.Thymeleaf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.DMSGroup
import org.dallasmakerspace.models.DMSMember
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

/** Renders the real committee page to check the follow button's wiring. */
class CommitteeFollowButtonTest {

  private val laser = Committees.findByName("Laser")!!

  private fun status(subscribed: Boolean) =
      SubscriptionStatus(
          topic = TopicKeys.COMMITTEE_UPDATES,
          args = mapOf("committee" to laser.id.toString()),
          subscribed = subscribed,
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

  private fun userInfoProvider(): UserInfoProvider {
    val provider = mock<UserInfoProvider>()
    runBlocking {
      whenever(provider.getUserInfo("tok-mia"))
          .thenReturn(mapOf("preferred_username" to "mia", "groups" to listOf<String>()))
    }
    return provider
  }

  private fun service(
      subscription: SubscriptionStatus?,
      followers: CommitteeFollowers = CommitteeFollowers(0, emptyList()),
  ): MemberService {
    val service = mock<MemberService>()
    runBlocking {
      whenever(service.getAllGroups(anyOrNull())).thenReturn(emptyList<DMSGroup>())
      whenever(service.getMultipleGroups(any(), anyOrNull())).thenReturn(emptyList())
      whenever(service.getArchivedGroups(any(), anyOrNull())).thenReturn(emptyList())
      whenever(service.getSubscriptionStatus(any(), any(), any(), anyOrNull()))
          .thenReturn(subscription)
      whenever(service.getCommitteeFollowers(any(), any(), any(), anyOrNull())).thenReturn(followers)
      whenever(service.getMembersByUsernames(any(), anyOrNull()))
          .thenReturn(emptyMap<String, DMSMember>())
    }
    return service
  }

  private fun committeePage(service: MemberService, slug: String, block: suspend (String) -> Unit) {
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
          val handler =
              CommitteeDetailHandler(LoggerFactory(), userInfoProvider(), service, emptySet())
          get("/committees/{committee_slug}") { asUser(handler) }
        }
      }
      val response = client.get("/committees/$slug") { header("X-Test-User", "mia") }
      assertEquals(HttpStatusCode.OK, response.status)
      block(response.bodyAsText())
    }
  }

  private suspend fun RoutingContext.asUser(handler: IRouteHandler) {
    call.sessions.set(UserSession(accessToken = "tok-mia", sessionId = "sess", userId = "mia"))
    handler.handleBase(call)
  }

  @Test
  fun `a followable committee shows the follow button for the viewer`() {
    val service = service(status(subscribed = false))
    committeePage(service, laser.slug) { html ->
      assertTrue("data-dms-subscribe" in html, html)
      assertTrue("data-topic=\"COMMITTEE_UPDATES\"" in html, html)
      assertTrue("data-args=\"{&quot;committee&quot;:&quot;${laser.id}&quot;}\"" in html, html)
      assertTrue("/static/js/dms-subscribe-button.js" in html)
      assertTrue("id=\"dmsToast\"" in html, "the toast host is needed for toasts and Undo")
      assertTrue(">Follow<" in html.replace(Regex("\\s+"), ""), html)
    }
    runBlocking {
      verify(service)
          .getSubscriptionStatus(
              eq(TopicKeys.COMMITTEE_UPDATES),
              eq(mapOf("committee" to laser.id.toString())),
              eq("mia"),
              anyOrNull())
    }
  }

  @Test
  fun `a followed committee shows Following`() {
    committeePage(service(status(subscribed = true)), laser.slug) { html ->
      val compact = html.replace(Regex("\\s+"), "")
      assertTrue(">Following<" in compact, html)
      assertTrue("data-subscribed=\"true\"" in html, html)
    }
  }

  @Test
  fun `no button or script when the follow feature is off for the viewer`() {
    committeePage(service(null), laser.slug) { html ->
      assertFalse("data-dms-subscribe" in html)
      assertFalse("dms-subscribe-button.js" in html)
    }
  }

  @Test
  fun `a committee without group prefixes is never offered and the service is not asked`() {
    val vector = Committees.findByName("VECTOR")!!
    assertTrue(vector.groupPrefixes.isEmpty())
    val service = service(status(subscribed = false))
    committeePage(service, vector.slug) { html -> assertFalse("data-dms-subscribe" in html) }
    runBlocking {
      verify(service, never()).getSubscriptionStatus(any(), any(), any(), anyOrNull())
    }
  }

  @Test
  fun `followers are shown as a compact facepile with an overflow link`() {
    val followers = CommitteeFollowers(10, listOf("jdoe", "asmith", "bsmith"))
    committeePage(service(status(subscribed = false), followers), laser.slug) { html ->
      assertTrue("id=\"committeeFollowers\"" in html, html)
      assertTrue("dms-facepile dms-facepile-sm" in html, html)
      assertTrue("href=\"/profile/@jdoe\"" in html, html)
      assertTrue(">+7<" in html.replace(Regex("\\s+"), ""), "10 followers, 3 shown, so +7")
      assertTrue("/committees/${laser.slug}/followers" in html, html)
    }
  }

  @Test
  fun `a committee with no followers says so without a facepile`() {
    committeePage(service(status(subscribed = false)), laser.slug) { html ->
      assertTrue("id=\"committeeFollowers\"" in html, html)
      assertFalse("dms-facepile-sm" in html.substringAfter("committeeFollowers"), html)
    }
  }

  @Test
  fun `no follower list when the follow feature is off for the viewer`() {
    val service = service(null, CommitteeFollowers(3, listOf("jdoe")))
    committeePage(service, laser.slug) { html -> assertFalse("committeeFollowers" in html) }
    runBlocking { verify(service, never()).getCommitteeFollowers(any(), any(), any(), anyOrNull()) }
  }
}
