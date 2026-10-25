package org.dallasmakerspace.notifications.subscriptions

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.notifications.ProfileSubscriptionService
import org.dallasmakerspace.notifications.topics.FollowingTopic
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FollowMemberSubscriptionHandlerTest {

  private val service = mock<ProfileSubscriptionService>()
  private val handler = FollowMemberSubscriptionHandler(service)
  private val args = mapOf("username" to "asmith")

  @Test
  fun `binds the following topic and its feature flag`() {
    assertEquals(FollowingTopic, handler.topic)
    assertEquals(TopicKeys.FOLLOWING, FollowingTopic.key)
    assertEquals(ConfigRegistry.FOLLOW_FEATURE_ENABLED, handler.featureFlag)
    assertEquals(setOf("username"), handler.argNames)
  }

  @Test
  fun `delegates to the follow service as actor and target`() = runBlocking {
    whenever(service.isFollowing("jdoe", "asmith")).thenReturn(true)

    handler.subscribe("jdoe", args)
    handler.unsubscribe("jdoe", args)

    verify(service).follow("jdoe", "asmith")
    verify(service).unfollow("jdoe", "asmith")
    assertEquals(true, handler.isSubscribed("jdoe", args))
  }

  @Test
  fun `confirms follow but not unfollow`() = runBlocking<Unit> {
    val copy = handler.copy(args)

    assertEquals("Follow @asmith?", assertNotNull(copy.subscribeConfirm).title)
    assertNull(copy.unsubscribeConfirm)
    assertEquals("person_add", copy.subscribeIcon)
    assertEquals("check", copy.subscribedIcon)
    assertEquals("person_remove", copy.unsubscribeIcon)
    assertEquals("Following", copy.subscribedLabel)
    assertNotNull(copy.subscribedHint)
  }
}
