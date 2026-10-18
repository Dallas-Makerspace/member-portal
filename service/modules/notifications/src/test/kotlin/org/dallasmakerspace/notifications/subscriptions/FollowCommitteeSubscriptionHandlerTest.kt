package org.dallasmakerspace.notifications.subscriptions

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.models.Committees
import org.dallasmakerspace.models.TopicKeys
import org.dallasmakerspace.notifications.db.CommitteeSubscriptionsRepository
import org.dallasmakerspace.notifications.topics.CommitteeUpdatesTopic
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FollowCommitteeSubscriptionHandlerTest {

  private val repository = mock<CommitteeSubscriptionsRepository>()
  private val handler = FollowCommitteeSubscriptionHandler(repository)
  private val laser = Committees.findByName("Laser")!!
  private val args = mapOf("committee" to laser.id.toString())

  @Test
  fun `binds the committee topic and its own feature flag`() {
    assertEquals(CommitteeUpdatesTopic, handler.topic)
    assertEquals(TopicKeys.COMMITTEE_UPDATES, CommitteeUpdatesTopic.key)
    assertEquals(ConfigRegistry.COMMITTEE_FOLLOW_ENABLED, handler.featureFlag)
    assertEquals(setOf("committee"), handler.argNames)
  }

  @Test
  fun `delegates to the repository with the committee id`() = runBlocking {
    whenever(repository.isSubscribed("jdoe", laser.id)).thenReturn(true)

    handler.subscribe("jdoe", args)
    handler.unsubscribe("jdoe", args)

    verify(repository).subscribe("jdoe", laser.id)
    verify(repository).unsubscribe("jdoe", laser.id)
    assertTrue(handler.isSubscribed("jdoe", args))
  }

  @Test
  fun `copy names the committee, confirms follow and not unfollow, and avoids the word email`() {
    val copy = handler.copy(args)

    assertEquals("Follow Laser?", assertNotNull(copy.subscribeConfirm).title)
    assertNull(copy.unsubscribeConfirm)
    assertEquals("notifications_active", copy.subscribedIcon)
    assertEquals("Following", copy.subscribedLabel)
    val allText =
        listOfNotNull(
            copy.subscribedToast,
            copy.unsubscribedToast,
            copy.hint,
            copy.subscribedHint,
            copy.subscribeConfirm?.message)
    assertTrue(allText.none { it.contains("email", ignoreCase = true) })
  }

  @Test
  fun `rejects unknown, non-numeric, inactive and prefix-less committees`() {
    val noPrefixes = Committees.findByName("VECTOR")!!
    listOf("999", "abc", "", noPrefixes.id.toString()).forEach { value ->
      assertFailsWith<IllegalArgumentException>(value) { handler.copy(mapOf("committee" to value)) }
    }
  }
}
