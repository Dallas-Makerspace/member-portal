package org.dallasmakerspace.webhook

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.discourse.FeaturedProjectsService
import org.dallasmakerspace.members.IMemberRepository
import org.dallasmakerspace.models.DMSMember
import org.dallasmakerspace.notifications.FanOutResult
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.ProfileSubscriptionService
import org.dallasmakerspace.notifications.topics.FollowingTopic
import org.dallasmakerspace.webhook.handlers.DiscoursePostWebhook
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DiscoursePostWebhookTest {

  private val memberRepository = mock<IMemberRepository>()
  private val subscriptions = mock<ProfileSubscriptionService>()
  private val notifications = mock<NotificationService>()
  private val featuredProjects = mock<FeaturedProjectsService>()
  private val activeDirectory = mock<IActiveDirectoryService>()
  private val handler =
      DiscoursePostWebhook(
          memberRepository, subscriptions, notifications, featuredProjects, activeDirectory)

  private val image = """<p>Look!</p><img src="/uploads/big.png" data-large-src="/uploads/big.png" width="800">"""

  @Before
  fun setUp() {
    runBlocking {
      whenever(memberRepository.getMembersByDiscourseUsernames(listOf("jd_talk")))
          .thenReturn(mapOf("jd_talk" to member()))
      whenever(subscriptions.getFollowers("jdoe")).thenReturn(listOf("alice", "bob"))
      whenever(activeDirectory.getMembersByUsernameList(listOf("jdoe")))
          .thenReturn(mapOf("jdoe" to adUser("Jane Doe")))
      whenever(notifications.createForMembers(any(), any(), any(), anyOrNull(), any(), any(), any()))
          .thenReturn(FanOutResult(created = 2, duplicates = 0, failed = 0))
    }
  }

  private fun adUser(displayName: String?) =
      ADUser(
          sAMAccountName = "jdoe",
          mail = "jdoe@example.com",
          givenName = null,
          sn = null,
          telephoneNumber = null,
          employeeID = null,
          displayName = displayName,
          objectGuid = "guid",
          whenCreated = null,
          enabled = true,
          groups = emptyList())

  private fun member() =
      DMSMember(id = 1, username = "jdoe", displayName = "Jane Doe", discourseUsername = "jd_talk")

  private fun envelope(
      event: String = "post_created",
      categoryId: Int? = 49,
      topicTitle: String = "Show and Tell September",
      postNumber: Int = 5,
      postType: Int = 1,
      cooked: String = image,
      createdAt: String = Instant.now().toString(),
      hidden: Boolean = false,
      deletedAt: String? = null,
      username: String = "jd_talk",
  ): String {
    val post = buildJsonObject {
      put("id", JsonPrimitive(900))
      put("username", JsonPrimitive(username))
      put("topic_id", JsonPrimitive(77))
      put("topic_title", JsonPrimitive(topicTitle))
      put("topic_slug", JsonPrimitive("show-and-tell-september"))
      put("post_number", JsonPrimitive(postNumber))
      put("post_type", JsonPrimitive(postType))
      categoryId?.let { put("category_id", JsonPrimitive(it)) }
      put("cooked", JsonPrimitive(cooked))
      put("created_at", JsonPrimitive(createdAt))
      put("hidden", JsonPrimitive(hidden))
      deletedAt?.let { put("deleted_at", JsonPrimitive(it)) }
    }
    val payload = JsonObject(mapOf("post" to post)).toString()
    return JsonObject(mapOf("event" to JsonPrimitive(event), "payload" to JsonPrimitive(payload)))
        .toString()
  }

  private suspend fun assertIgnored(body: String, fragment: String) {
    val result = handler.handleWebhook(body)
    assertTrue(result.success, result.message)
    assertTrue(result.message.contains(fragment), result.message)
    verify(notifications, never()).createForMembers(any(), any(), any(), anyOrNull(), any(), any(), any())
  }

  @Test
  fun `an eligible post notifies every follower with a per-post dedupe key`() = runBlocking {
    val result = handler.handleWebhook(envelope())

    assertTrue(result.success)
    assertTrue(result.message.contains("Notified 2 of 2"))
    val payload = argumentCaptor<String>()
    val dedupe = argumentCaptor<(String) -> String?>()
    verify(notifications)
        .createForMembers(
            eq(listOf("alice", "bob")),
            eq(FollowingTopic),
            eq("project-post"),
            payload.capture(),
            any(),
            dedupe.capture(),
            any())
    assertTrue(payload.lastValue.contains("\"memberName\":\"Jane Doe\""))
    assertTrue(payload.lastValue.contains("https://talk.dallasmakerspace.org/uploads/big.png"))
    assertEquals("project-post-900-alice", dedupe.lastValue("alice"))
    verify(featuredProjects).invalidateMember("jdoe")
  }

  @Test
  fun `the poster's name falls back to the username when AD has none`() = runBlocking {
    whenever(activeDirectory.getMembersByUsernameList(listOf("jdoe"))).thenReturn(mapOf("jdoe" to null))

    handler.handleWebhook(envelope())

    val payload = argumentCaptor<String>()
    verify(notifications)
        .createForMembers(any(), any(), any(), payload.capture(), any(), any(), any())
    assertTrue(payload.lastValue.contains("\"memberName\":\"jdoe\""))
  }

  @Test
  fun `an edit within a week of creation notifies`() = runBlocking {
    val result = handler.handleWebhook(envelope(event = "post_edited"))

    assertTrue(result.message.contains("Notified 2 of 2"))
  }

  @Test
  fun `an edit to an older post is ignored`() = runBlocking {
    val old = Instant.now().minus(30, ChronoUnit.DAYS).toString()

    assertIgnored(envelope(event = "post_edited", createdAt = old), "edit of an older post")
  }

  @Test
  fun `ineligible posts are ignored with a success result`() = runBlocking {
    assertIgnored(envelope(categoryId = 12), "Show & Tell category")
    assertIgnored(envelope(categoryId = null), "Show & Tell category")
    assertIgnored(envelope(topicTitle = "Welcome"), "monthly Show and Tell")
    assertIgnored(envelope(postNumber = 1), "intro post")
    assertIgnored(envelope(postType = 4), "not a regular post")
    assertIgnored(envelope(hidden = true), "hidden or deleted")
    assertIgnored(envelope(deletedAt = "2026-09-01T00:00:00Z"), "hidden or deleted")
    assertIgnored(envelope(cooked = "<p>no photo</p>"), "no qualifying image")
    assertIgnored(
        envelope(cooked = """<img src="/x.png" width="50">"""), "no qualifying image")
  }

  @Test
  fun `unhandled event types are ignored`() = runBlocking {
    assertIgnored(envelope(event = "post_destroyed"), "Ignoring event post_destroyed")
  }

  @Test
  fun `a poster who is not a member is ignored`() = runBlocking {
    whenever(memberRepository.getMembersByDiscourseUsernames(listOf("stranger")))
        .thenReturn(emptyMap())

    assertIgnored(envelope(username = "stranger"), "not a DMS member")
    verify(featuredProjects, never()).invalidateMember(any())
    Unit
  }

  @Test
  fun `a poster with no followers sends nothing but still refreshes the profile list`() =
      runBlocking {
        whenever(subscriptions.getFollowers("jdoe")).thenReturn(emptyList())

        assertIgnored(envelope(), "No followers")
        verify(featuredProjects).invalidateMember("jdoe")
        Unit
      }

  @Test
  fun `malformed payloads are errors`() = runBlocking {
    assertFalse(handler.handleWebhook("not json").success)
    val badInner =
        JsonObject(
                mapOf(
                    "event" to JsonPrimitive("post_created"), "payload" to JsonPrimitive("{}")))
            .toString()
    assertFalse(handler.handleWebhook(badInner).success)
  }

  @Test
  fun `getName should be discourse-post`() {
    assertEquals("discourse-post", handler.getName())
  }
}
